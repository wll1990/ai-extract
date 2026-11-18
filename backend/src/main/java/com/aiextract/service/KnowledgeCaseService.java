package com.aiextract.service;

import com.aiextract.common.SourceText;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.IndustryCaseRequest;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.GrainEditHistory;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.GrainEditHistoryRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeCaseRetrieveLogRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库 + 案例库 CRUD 服务 —— 纯业务逻辑，事务边界 + 向量化 + 审计 + 版本历史。
 *
 * <p>公司维度归属校验不在此层：Controller 层先经
 * {@code CompanyScopeService.assertKnowledgeOwnership / assertCaseOwnership} 校验，
 * 再调用本服务。create/copy 的目标企业 {@code companyId}（null=公共）由 Controller
 * 按角色解析后传入，本层不重复读权限。</p>
 *
 * <p>知识修改采用「版本化 + AI 整理 + 人工确认」：{@link #suggestRevision} 走自注入代理，
 * AI 调用在事务外执行，{@link #persistRevision} 短事务落库（旧版 deprecated + 新版 draft）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCaseService {

    /** 自注入代理：AI 调用在事务外，落库走 self 短事务（照 MaterialCleaningService） */
    @Autowired
    @Lazy
    private KnowledgeCaseService self;

    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final CompanyScopeService companyScopeService;
    private final KnowledgeCaseRetrieveLogRepository retrieveLogRepository;
    private final DashScopeEmbeddingService embeddingService;
    private final AdminAuditLogRepository auditLogRepository;
    private final GrainEditHistoryRepository editHistoryRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    // ==================== 知识库 ====================

    /**
     * 创建知识条目。companyId 为 null 表示公共知识（super_admin），否则为租户私有。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry createKnowledge(KnowledgeEntryRequest req, UUID companyId) {
        // 企业私有内容标准脱敏；公共知识（companyId 为空）对外可见，直接强脱敏
        maskKnowledgeRequest(req, companyId == null);
        validateCategory(req.getCategory());
        validateSourceType(req.getSourceType());
        ContentQualityGate.QualityReport report = ContentQualityGate.checkKnowledge(
                req.getTitle(), req.getContent(), req.getCategory(), req.getSource(), false);
        if (!report.pass()) {
            throw new BusinessException(400, "内容未达准入标准：" + report.issueText());
        }
        KnowledgeEntry k = KnowledgeEntry.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .sourceType(req.getSourceType())
                .category(req.getCategory())
                .title(req.getTitle())
                .content(req.getContent())
                .version(1)
                .effectiveAt(req.getEffectiveAt())
                .expireAt(req.getExpireAt())
                .status(resolveCreateStatus(req.getStatus()))
                .source(req.getSource())
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        knowledgeRepository.save(k);
        scheduleReEmbed(k);
        writeAudit("create_knowledge", "knowledge", k.getId(), Map.of("action", "create"));
        return k;
    }

    /**
     * 企业内容提交到公共库：生成一条 company_id=null、status=pending 的候选，
     * source_company_id 记录来源企业（只做溯源展示）。超管在公共 Tab 通过后成为公共知识。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry submitKnowledgeToPublic(UUID id) {
        KnowledgeEntry src = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        if (src.getCompanyId() == null) {
            throw new BusinessException(400, "公共内容无需重复提交");
        }
        if (!KnowledgeEntry.STATUS_ACTIVE.equals(src.getStatus())) {
            throw new BusinessException(409, "仅已生效的知识可以提交到公共库");
        }
        LocalDateTime now = LocalDateTime.now();
        // 进入公共库=对外共享：公司名/项目名/具体金额一并强脱敏
        KnowledgeEntry k = KnowledgeEntry.builder()
                .id(UUID.randomUUID())
                .companyId(null)
                .sourceCompanyId(src.getCompanyId())
                .origin(src.getOrigin())
                .bookMode(src.isBookMode())
                .sourceType(src.getSourceType())
                .category(src.getCategory())
                .title(PiiMasker.maskStrict(src.getTitle()))
                .content(PiiMasker.maskStrict(src.getContent()))
                .version(1)
                .effectiveAt(src.getEffectiveAt())
                .expireAt(src.getExpireAt())
                .status(KnowledgeEntry.STATUS_PENDING)
                // 公共库跨租户：来源文本与文档名同样要强脱敏
                .source(PiiMasker.maskStrict(src.getSource()))
                .sourceDocument(SourceText.truncateDocument(src.getSourceDocument() == null
                        ? null : PiiMasker.maskStrict(src.getSourceDocument())))
                .createdBy(currentUserId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        knowledgeRepository.save(k);
        writeAudit("submit_public_knowledge", "knowledge", k.getId(),
                Map.of("sourceId", id.toString(), "sourceCompanyId", src.getCompanyId().toString()));
        log.info("知识已提交到公共库 id={} sourceId={} companyId={}", k.getId(), id, src.getCompanyId());
        return k;
    }

    /** 同 {@link #submitKnowledgeToPublic(UUID)}，案例版本。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase submitCaseToPublic(UUID id) {
        IndustryCase src = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        if (src.getCompanyId() == null) {
            throw new BusinessException(400, "公共案例无需重复提交");
        }
        if (!IndustryCase.STATUS_ACTIVE.equals(src.getStatus())) {
            throw new BusinessException(409, "仅已生效的案例可以提交到公共库");
        }
        LocalDateTime now = LocalDateTime.now();
        IndustryCase c = IndustryCase.builder()
                .id(UUID.randomUUID())
                .companyId(null)
                .sourceCompanyId(src.getCompanyId())
                .origin(src.getOrigin())
                // 进入公共库=对外共享：公司名/项目名/具体金额一并强脱敏
                .title(PiiMasker.maskStrict(src.getTitle()))
                .customerProfile(PiiMasker.maskStrict(src.getCustomerProfile()))
                .funnelStage(src.getFunnelStage())
                .sceneTag(src.getSceneTag())
                .dialogueQuote(PiiMasker.maskStrict(src.getDialogueQuote()))
                .objection(PiiMasker.maskStrict(src.getObjection()))
                .emotionChange(PiiMasker.maskStrict(src.getEmotionChange()))
                .keyActions(PiiMasker.maskStrict(src.getKeyActions()))
                .scripts(PiiMasker.maskStrict(src.getScripts()))
                .advanceNodes(PiiMasker.maskStrict(src.getAdvanceNodes()))
                .result(src.getResult())
                .reusablePoints(PiiMasker.maskStrict(src.getReusablePoints()))
                .failurePoints(PiiMasker.maskStrict(src.getFailurePoints()))
                .source(PiiMasker.maskStrict(src.getSource()))
                .sourceDocument(SourceText.truncateDocument(src.getSourceDocument() == null
                        ? null : PiiMasker.maskStrict(src.getSourceDocument())))
                .contributorId(currentUserId())
                .confidence(src.getConfidence())
                .version(1)
                .effectiveAt(src.getEffectiveAt())
                .expireAt(src.getExpireAt())
                .status(IndustryCase.STATUS_PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        caseRepository.save(c);
        writeAudit("submit_public_case", "case", c.getId(),
                Map.of("sourceId", id.toString(), "sourceCompanyId", src.getCompanyId().toString()));
        log.info("案例已提交到公共库 id={} sourceId={} companyId={}", c.getId(), id, src.getCompanyId());
        return c;
    }

    /**
     * 直接人工编辑知识条目（不改变状态/版本）。内容字段全量替换，source/生效失效时间随请求覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry updateKnowledge(UUID id, KnowledgeEntryRequest req) {
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        assertEditable(k.getStatus(), "知识");
        maskKnowledgeRequest(req, k.getCompanyId() == null);
        validateCategory(req.getCategory());
        validateSourceType(req.getSourceType());
        ContentQualityGate.QualityReport report = ContentQualityGate.checkKnowledge(
                req.getTitle(), req.getContent(), req.getCategory(), req.getSource(), k.isBookMode());
        if (!report.pass()) {
            throw new BusinessException(400, "内容未达准入标准：" + report.issueText());
        }
        saveEditHistory(id, "title", k.getTitle(), req.getTitle(), null);
        saveEditHistory(id, "content", k.getContent(), req.getContent(), null);
        saveEditHistory(id, "category", k.getCategory(), req.getCategory(), null);
        saveEditHistory(id, "sourceType", k.getSourceType(), req.getSourceType(), null);
        k.setTitle(req.getTitle());
        k.setContent(req.getContent());
        k.setCategory(req.getCategory());
        k.setSourceType(req.getSourceType());
        k.setSource(req.getSource());
        k.setEffectiveAt(req.getEffectiveAt());
        k.setExpireAt(req.getExpireAt());
        knowledgeRepository.save(k);
        scheduleReEmbed(k);
        writeAudit("update_knowledge", "knowledge", id, Map.of("action", "update"));
        return k;
    }

    /** KN-08 批量改分类：只改分类，不覆盖标题/正文等其他字段 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry updateKnowledgeCategory(UUID id, String category) {
        validateCategory(category);
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        saveEditHistory(id, "category", k.getCategory(), category, null);
        k.setCategory(category);
        k.setUpdatedAt(LocalDateTime.now());
        knowledgeRepository.save(k);
        writeAudit("update_category", "knowledge", id, Map.of("action", "update_category", "category", category));
        return k;
    }

    /** 废弃知识条目 */
    @Transactional(rollbackFor = Exception.class)
    public void deprecateKnowledge(UUID id) {
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        if (!KnowledgeEntry.STATUS_DEPRECATED.equals(k.getStatus())) {
            // 记住来源状态：草稿被“放弃”后恢复必须回草稿，不能绕过审核直接生效
            k.setPreviousStatus(k.getStatus());
        }
        k.setStatus(KnowledgeEntry.STATUS_DEPRECATED);
        if (k.getGenerationJobId() != null && k.getReviewOutcome() == null) {
            // 未审核直接丢弃 → 记为 rejected；已审核通过后被取代/下架不覆盖原结论
            k.setReviewOutcome("rejected");
            if (k.getReviewedAt() == null) {
                k.setReviewedBy(TokenContext.get());
                k.setReviewedAt(LocalDateTime.now());
            }
        }
        k.setUpdatedAt(LocalDateTime.now());
        knowledgeRepository.save(k);
        writeAudit("deprecate_knowledge", "knowledge", id, Map.of("action", "deprecate"));
    }

    /** 恢复已废弃知识：30 天内，恢复后重新索引 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry restoreKnowledge(UUID id) {
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        if (!KnowledgeEntry.STATUS_DEPRECATED.equals(k.getStatus())) {
            throw new BusinessException(400, "只有已废弃的知识可以恢复");
        }
        if (k.getUpdatedAt() != null && k.getUpdatedAt().isBefore(LocalDateTime.now().minusDays(30))) {
            throw new BusinessException(409, "已超过 30 天恢复窗口，无法恢复");
        }
        // 回到废弃前的状态；被取代后恢复不能直接回 active，否则同一 root 出现两个生效版本
        String restored = resolveRestoredStatus(k.getRootId(), k.getId(), k.getPreviousStatus(),
                KnowledgeEntry.STATUS_DRAFT, KnowledgeEntry.STATUS_PENDING,
                KnowledgeEntry.STATUS_ACTIVE,
                () -> knowledgeRepository.existsByRootIdAndStatusAndIdNot(
                        k.getRootId(), KnowledgeEntry.STATUS_ACTIVE, k.getId()));
        k.setStatus(restored);
        k.setPreviousStatus(null);
        k.setUpdatedAt(LocalDateTime.now());
        knowledgeRepository.save(k);
        if (KnowledgeEntry.STATUS_ACTIVE.equals(restored)) {
            scheduleReEmbed(k);
        }
        writeAudit("restore_knowledge", "knowledge", id,
                Map.of("action", "restore", "restoredStatus", restored));
        return k;
    }

    /**
     * 审核通过知识条目（pending/draft → active）。失效时间必填（三次评审#3）：
     * 优先用入参，入参为空时回退已有失效时间，两者皆空则拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry approveKnowledge(UUID id, LocalDateTime expireAt) {
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        if (KnowledgeEntry.STATUS_DEPRECATED.equals(k.getStatus())) {
            throw new BusinessException(400, "已废弃的知识不能审核通过");
        }
        if (KnowledgeEntry.STATUS_ACTIVE.equals(k.getStatus())) {
            throw new BusinessException(400, "该知识已生效，无需重复通过");
        }
        List<String> blockers = ContentQualityGate.approvalBlockersKnowledge(
                k.getTitle(), k.getContent(), k.getCategory(), k.isBookMode());
        if (!blockers.isEmpty()) {
            throw new BusinessException(400, "内容未达生效标准：" + String.join("；", blockers));
        }
        LocalDateTime effectiveExpire = expireAt != null ? expireAt : k.getExpireAt();
        if (effectiveExpire == null) {
            throw new BusinessException(400, "审核通过必须指定失效时间");
        }
        k.setStatus(KnowledgeEntry.STATUS_ACTIVE);
        k.setExpireAt(effectiveExpire);
        if (k.getGenerationJobId() != null) {
            // 采纳指标：审核结论不会被后续「被新版取代」改写
            k.setReviewOutcome("approved");
            if (k.getReviewedAt() == null) {
                k.setReviewedBy(TokenContext.get());
                k.setReviewedAt(LocalDateTime.now());
            }
        }
        knowledgeRepository.save(k);
        // 两阶段：先激活新版，向量化成功后再由 finalizeKnowledgeIndex 下线同链旧版；
        // 向量化失败时旧版继续生效，避免「新版 active 但检索不到、旧版已下线」的真空。
        scheduleReEmbed(k);
        writeAudit("approve_knowledge", "knowledge", id, Map.of("action", "approve"));
        return k;
    }

    /**
     * 复制公共知识到本企业（模板化授权）。复制后为 pending，待企业确认后 approve。
     * 向量重新嵌入（三次评审#4），不用 native SQL 复制 embedding。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry copyPublicKnowledgeToCompany(UUID sourceId, UUID targetCompanyId) {
        KnowledgeEntry source = knowledgeRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + sourceId));
        if (source.getCompanyId() != null) {
            throw new BusinessException(400, "只能复制公共知识");
        }
        // 未审核的公共候选不对租户开放，避免“复制后自行审核”绕过平台审核
        if (!KnowledgeEntry.STATUS_ACTIVE.equals(source.getStatus())) {
            throw new BusinessException(400, "只能复制已生效的公共知识");
        }
        if (targetCompanyId == null) {
            throw new BusinessException(400, "复制目标企业不能为空");
        }
        // 幂等：连点/重复复制不产生多个同名 pending 副本
        String copyTitle = PiiMasker.mask(source.getTitle());
        KnowledgeEntry duplicate = knowledgeRepository
                .findFirstByCompanyIdAndTitleAndStatusOrderByCreatedAtDesc(
                        targetCompanyId, copyTitle, KnowledgeEntry.STATUS_PENDING)
                .orElse(null);
        if (duplicate != null) {
            return duplicate;
        }
        KnowledgeEntry copy = KnowledgeEntry.builder()
                .id(UUID.randomUUID())
                .companyId(targetCompanyId)
                .origin(source.getOrigin())
                .bookMode(source.isBookMode())
                .sourceType(source.getSourceType())
                .category(source.getCategory())
                .title(copyTitle)
                .content(PiiMasker.mask(source.getContent()))
                .version(1)
                .effectiveAt(source.getEffectiveAt())
                .expireAt(source.getExpireAt())
                .status(KnowledgeEntry.STATUS_PENDING)
                .source(source.getSource())
                // 公共内容复制到企业：只带文档名，不带原企业上传任务（跨租户文件不可访问）
                .sourceDocument(source.getSourceDocument())
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        knowledgeRepository.save(copy);
        scheduleReEmbed(copy);
        writeAudit("copy_knowledge", "knowledge", copy.getId(),
                Map.of("action", "copy", "source", sourceId.toString()));
        return copy;
    }

    /**
     * AI 整理知识（版本化）。无事务：先读当前版 → AI 改写（事务外）→ self 短事务落库。
     *
     * @param id          知识条目 ID
     * @param instruction 人工修改指令
     * @return 新版本（draft）
     */
    public KnowledgeEntry suggestRevision(UUID id, String instruction) {
        KnowledgeEntry current = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        if (instruction == null || instruction.isBlank()) {
            throw new BusinessException(400, "修改指令不能为空");
        }
        String prompt = promptLoader.format("material_knowledge_revise.md", Map.of(
                "instruction", instruction,
                "title", current.getTitle(),
                "content", current.getContent(),
                "category", current.getCategory()));
        RevisionResult r = chatJsonWithRetry(prompt, this::parseRevisionResult);
        // AI 可能返回非法分类，落库前校验，避免命中 DB CHECK 抛 500
        if (r.category() != null) {
            validateCategory(r.category());
        }
        return self.persistRevision(id, r.title(), r.content(), r.category(), instruction);
    }

    /**
     * 落库修订结果（经 self 自注入代理调用，短事务）：旧版 deprecated + 新版 draft(version+1)。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry persistRevision(UUID id, String newTitle, String newContent,
                                          String newCategory, String instruction) {
        KnowledgeEntry old = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));

        // 草稿/待审还没生效：AI 改写直接覆盖当前条目，避免生成同内容的子版本草稿
        if (KnowledgeEntry.STATUS_DRAFT.equals(old.getStatus())
                || KnowledgeEntry.STATUS_PENDING.equals(old.getStatus())) {
            String maskedTitle = PiiMasker.mask(newTitle != null ? newTitle : old.getTitle());
            String maskedContent = PiiMasker.mask(newContent);
            List<String> blockers = ContentQualityGate.approvalBlockersKnowledge(
                    maskedTitle, maskedContent, newCategory != null ? newCategory : old.getCategory());
            if (!blockers.isEmpty()) {
                throw new BusinessException(400, "AI 改写结果不可用：" + String.join("；", blockers));
            }
            saveEditHistory(id, "content", old.getContent(), maskedContent, instruction);
            old.setTitle(maskedTitle);
            old.setContent(maskedContent);
            if (newCategory != null) {
                old.setCategory(newCategory);
            }
            old.setUpdatedAt(LocalDateTime.now());
            knowledgeRepository.save(old);
            writeAudit("suggest_revision", "knowledge", id, Map.of("inPlace", true, "instruction", instruction));
            return old;
        }

        int newVersion = (old.getVersion() == null ? 1 : old.getVersion()) + 1;

        KnowledgeEntry draft = KnowledgeEntry.builder()
                .id(UUID.randomUUID())
                .companyId(old.getCompanyId())
                .supersedesId(old.getId())
                .rootId(old.getRootId())
                .origin(old.getOrigin())
                .bookMode(old.isBookMode())
                .sourceType(old.getSourceType())
                .category(newCategory != null ? newCategory : old.getCategory())
                .title(PiiMasker.mask(newTitle != null ? newTitle : old.getTitle()))
                .content(PiiMasker.mask(newContent))
                .version(newVersion)
                .effectiveAt(old.getEffectiveAt())
                .expireAt(old.getExpireAt())
                .status(KnowledgeEntry.STATUS_DRAFT)
                .source(old.getSource())
                .sourceTaskId(old.getSourceTaskId())
                .sourceDocument(old.getSourceDocument())
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        knowledgeRepository.save(draft);
        saveEditHistory(id, "version", String.valueOf(old.getVersion()), String.valueOf(newVersion), instruction);
        writeAudit("suggest_revision", "knowledge", draft.getId(),
                Map.of("action", "suggest_revision", "instruction", instruction));
        return draft;
    }

    // ==================== 案例库 ====================

    /** 创建案例条目。companyId 为 null 表示公共案例，否则为租户私有。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase createCase(IndustryCaseRequest req, UUID companyId) {
        maskCaseRequest(req, companyId == null);
        validateResult(req.getResult());
        validateJsonFields(req.getCustomerProfile(), req.getDialogueQuote());
        ContentQualityGate.QualityReport report = ContentQualityGate.checkCase(
                req.getTitle(), req.getResult(), req.getObjection(), req.getCustomerProfile(),
                req.getKeyActions(), req.getScripts(), req.getDialogueQuote(), req.getSource(), false);
        String createStatus = resolveCreateStatus(req.getStatus());
        if (IndustryCase.STATUS_ACTIVE.equals(createStatus) && !report.pass()) {
            throw new BusinessException(400, "内容未达生效标准：" + report.issueText());
        }
        IndustryCase c = IndustryCase.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .title(req.getTitle())
                .customerProfile(req.getCustomerProfile())
                .funnelStage(req.getFunnelStage())
                .sceneTag(req.getSceneTag())
                .dialogueQuote(req.getDialogueQuote())
                .objection(req.getObjection())
                .emotionChange(req.getEmotionChange())
                .keyActions(req.getKeyActions())
                .scripts(req.getScripts())
                .advanceNodes(req.getAdvanceNodes())
                .result(req.getResult())
                .reusablePoints(req.getReusablePoints())
                .failurePoints(req.getFailurePoints())
                .source(req.getSource())
                .origin(IndustryCase.ORIGIN_INTERNAL)
                .qualityIssues(report.issues().isEmpty() ? null : report.issueText())
                .qualityWarnings(report.warnings().isEmpty() ? null : report.warningText())
                .contributorId(currentUserId())
                .version(1)
                .effectiveAt(req.getEffectiveAt())
                .expireAt(req.getExpireAt())
                .status(createStatus)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        caseRepository.save(c);
        scheduleReEmbedCase(c);
        writeAudit("create_case", "case", c.getId(), Map.of("action", "create"));
        return c;
    }

    /** 直接人工编辑案例（不改变状态/版本），内容字段全量替换。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase updateCase(UUID id, IndustryCaseRequest req) {
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        assertEditable(c.getStatus(), "案例");
        maskCaseRequest(req, c.getCompanyId() == null);
        validateResult(req.getResult());
        validateJsonFields(req.getCustomerProfile(), req.getDialogueQuote());
        // 编辑后重算准入问题：补齐了哪些、还缺哪些，审核时以此为准
        ContentQualityGate.QualityReport report = ContentQualityGate.checkCase(
                req.getTitle(), req.getResult(), req.getObjection(), req.getCustomerProfile(),
                req.getKeyActions(), req.getScripts(), req.getDialogueQuote(), req.getSource(),
                IndustryCase.ORIGIN_BOOK.equals(c.getOrigin()));
        c.setQualityIssues(report.issues().isEmpty() ? null : report.issueText());
        c.setQualityWarnings(report.warnings().isEmpty() ? null : report.warningText());
        c.setTitle(req.getTitle());
        c.setCustomerProfile(req.getCustomerProfile());
        // 编辑页不展示漏斗阶段/生效时间：请求没带就不要清空已有值
        if (req.getFunnelStage() != null) {
            c.setFunnelStage(req.getFunnelStage());
        }
        c.setSceneTag(req.getSceneTag());
        c.setDialogueQuote(req.getDialogueQuote());
        c.setObjection(req.getObjection());
        c.setEmotionChange(req.getEmotionChange());
        c.setKeyActions(req.getKeyActions());
        c.setScripts(req.getScripts());
        c.setAdvanceNodes(req.getAdvanceNodes());
        c.setResult(req.getResult());
        c.setReusablePoints(req.getReusablePoints());
        c.setFailurePoints(req.getFailurePoints());
        c.setSource(req.getSource());
        if (req.getEffectiveAt() != null) {
            c.setEffectiveAt(req.getEffectiveAt());
        }
        if (req.getExpireAt() != null) {
            c.setExpireAt(req.getExpireAt());
        }
        caseRepository.save(c);
        scheduleReEmbedCase(c);
        writeAudit("update_case", "case", id, Map.of("action", "update"));
        return c;
    }

    /** 废弃案例 */
    @Transactional(rollbackFor = Exception.class)
    public void deprecateCase(UUID id) {
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        if (!IndustryCase.STATUS_DEPRECATED.equals(c.getStatus())) {
            c.setPreviousStatus(c.getStatus());
        }
        c.setStatus(IndustryCase.STATUS_DEPRECATED);
        if (c.getGenerationJobId() != null && c.getReviewOutcome() == null) {
            c.setReviewOutcome("rejected");
            if (c.getReviewedAt() == null) {
                c.setReviewedBy(TokenContext.get());
                c.setReviewedAt(LocalDateTime.now());
            }
        }
        c.setUpdatedAt(LocalDateTime.now());
        caseRepository.save(c);
        writeAudit("deprecate_case", "case", id, Map.of("action", "deprecate"));
    }

    /** 恢复已废弃案例：30 天内，恢复后重新索引 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase restoreCase(UUID id) {
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        if (!IndustryCase.STATUS_DEPRECATED.equals(c.getStatus())) {
            throw new BusinessException(400, "只有已废弃的案例可以恢复");
        }
        if (c.getUpdatedAt() != null && c.getUpdatedAt().isBefore(LocalDateTime.now().minusDays(30))) {
            throw new BusinessException(409, "已超过 30 天恢复窗口，无法恢复");
        }
        String restored = resolveRestoredStatus(c.getRootId(), c.getId(), c.getPreviousStatus(),
                IndustryCase.STATUS_DRAFT, IndustryCase.STATUS_PENDING,
                IndustryCase.STATUS_ACTIVE,
                () -> caseRepository.existsByRootIdAndStatusAndIdNot(
                        c.getRootId(), IndustryCase.STATUS_ACTIVE, c.getId()));
        c.setStatus(restored);
        c.setPreviousStatus(null);
        c.setUpdatedAt(LocalDateTime.now());
        caseRepository.save(c);
        if (IndustryCase.STATUS_ACTIVE.equals(restored)) {
            scheduleReEmbedCase(c);
        }
        writeAudit("restore_case", "case", id,
                Map.of("action", "restore", "restoredStatus", restored));
        return c;
    }

    /** 审核通过案例（pending/draft → active）。失效时间可选（案例可长期有效）。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase approveCase(UUID id, LocalDateTime expireAt) {
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        if (IndustryCase.STATUS_DEPRECATED.equals(c.getStatus())) {
            throw new BusinessException(400, "已废弃的案例不能审核通过");
        }
        if (IndustryCase.STATUS_ACTIVE.equals(c.getStatus())) {
            throw new BusinessException(400, "该案例已生效，无需重复通过");
        }
        List<String> blockers = ContentQualityGate.approvalBlockersCase(
                c.getTitle(), c.getResult(), c.getQualityIssues());
        if (!blockers.isEmpty()) {
            throw new BusinessException(400, "案例未达生效标准，请先补齐：" + String.join("；", blockers));
        }
        c.setStatus(IndustryCase.STATUS_ACTIVE);
        if (expireAt != null) {
            c.setExpireAt(expireAt);
        }
        if (c.getGenerationJobId() != null) {
            c.setReviewOutcome("approved");
            if (c.getReviewedAt() == null) {
                c.setReviewedBy(TokenContext.get());
                c.setReviewedAt(LocalDateTime.now());
            }
        }
        caseRepository.save(c);
        // 两阶段：向量化成功后再由 finalizeCaseIndex 下线同链旧版
        scheduleReEmbedCase(c);
        writeAudit("approve_case", "case", id, Map.of("action", "approve"));
        return c;
    }

    /** 复制公共案例到本企业，复制后 pending 待确认。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase copyPublicCaseToCompany(UUID sourceId, UUID targetCompanyId) {
        IndustryCase source = caseRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + sourceId));
        if (source.getCompanyId() != null) {
            throw new BusinessException(400, "只能复制公共案例");
        }
        if (!IndustryCase.STATUS_ACTIVE.equals(source.getStatus())) {
            throw new BusinessException(400, "只能复制已生效的公共案例");
        }
        if (targetCompanyId == null) {
            throw new BusinessException(400, "复制目标企业不能为空");
        }
        // 幂等：连点/重复复制不产生多个同名 pending 副本
        String copyTitle = PiiMasker.mask(source.getTitle());
        IndustryCase duplicate = caseRepository
                .findFirstByCompanyIdAndTitleAndStatusOrderByCreatedAtDesc(
                        targetCompanyId, copyTitle, IndustryCase.STATUS_PENDING)
                .orElse(null);
        if (duplicate != null) {
            return duplicate;
        }
        IndustryCase copy = IndustryCase.builder()
                .id(UUID.randomUUID())
                .companyId(targetCompanyId)
                .origin(source.getOrigin())
                .title(copyTitle)
                .customerProfile(PiiMasker.mask(source.getCustomerProfile()))
                .funnelStage(source.getFunnelStage())
                .sceneTag(source.getSceneTag())
                .dialogueQuote(PiiMasker.mask(source.getDialogueQuote()))
                .objection(PiiMasker.mask(source.getObjection()))
                .emotionChange(PiiMasker.mask(source.getEmotionChange()))
                .keyActions(PiiMasker.mask(source.getKeyActions()))
                .scripts(PiiMasker.mask(source.getScripts()))
                .advanceNodes(PiiMasker.mask(source.getAdvanceNodes()))
                .result(source.getResult())
                .reusablePoints(PiiMasker.mask(source.getReusablePoints()))
                .failurePoints(PiiMasker.mask(source.getFailurePoints()))
                .source(PiiMasker.mask(source.getSource()))
                .sourceDocument(source.getSourceDocument())
                .contributorId(currentUserId())
                .version(1)
                .effectiveAt(source.getEffectiveAt())
                .expireAt(source.getExpireAt())
                .status(IndustryCase.STATUS_PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        caseRepository.save(copy);
        scheduleReEmbedCase(copy);
        writeAudit("copy_case", "case", copy.getId(),
                Map.of("action", "copy", "source", sourceId.toString()));
        return copy;
    }

    /**
     * AI 整理案例（版本化）。无事务：先读当前版 → AI 改写（事务外）→ self 短事务落库。
     *
     * <p>只改写文本字段；漏斗阶段、场景标签、客户原话、结果等结构化字段沿用原值，
     * 避免 AI 返回的客户原话破坏 JSONB 结构。</p>
     *
     * @param id          案例 ID
     * @param instruction 人工修改指令
     * @return 新版本（draft）
     */
    public IndustryCase suggestCaseRevision(UUID id, String instruction) {
        IndustryCase current = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        if (instruction == null || instruction.isBlank()) {
            throw new BusinessException(400, "修改指令不能为空");
        }
        String prompt = promptLoader.format("material_case_revise.md", Map.of(
                "instruction", instruction,
                "title", nvl(current.getTitle()),
                "customerProfile", nvl(current.getCustomerProfile()),
                "objection", nvl(current.getObjection()),
                "emotionChange", nvl(current.getEmotionChange()),
                "keyActions", nvl(current.getKeyActions()),
                "scripts", nvl(current.getScripts()),
                "advanceNodes", nvl(current.getAdvanceNodes()),
                "reusablePoints", nvl(current.getReusablePoints()),
                "failurePoints", nvl(current.getFailurePoints())));
        CaseRevisionResult r = chatJsonWithRetry(prompt, this::parseCaseRevisionResult);
        return self.persistCaseRevision(id, r, instruction);
    }

    /**
     * AI 针对当前知识给出 3 条修改建议，供「AI 整理」弹窗展示。
     *
     * <p>只读、不落库；AI 调用在事务外。</p>
     */
    public List<Map<String, String>> suggestKnowledgeImprovements(UUID id) {
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        String prompt = promptLoader.format("material_knowledge_improve.md", Map.of(
                "title", nvl(k.getTitle()),
                "content", nvl(k.getContent()),
                "category", nvl(k.getCategory())));
        return parseSuggestions(chatStreamAdapter.chat(prompt));
    }

    /** AI 针对当前案例给出 3 条修改建议。 */
    public List<Map<String, String>> suggestCaseImprovements(UUID id) {
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        String prompt = promptLoader.format("material_case_improve.md", Map.of(
                "title", nvl(c.getTitle()),
                "content", caseRevisionText(c)));
        return parseSuggestions(chatStreamAdapter.chat(prompt));
    }

    /**
     * 落库案例修订结果（经 self 自注入代理调用，短事务）：旧版 deprecated + 新版 draft(version+1)。
     */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase persistCaseRevision(UUID id, CaseRevisionResult r, String instruction) {
        IndustryCase old = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        // AI 修订结果同样脱敏，避免模型从上下文里把标识重新带回
        String newTitle = PiiMasker.mask(firstNonBlank(r.title(), old.getTitle()));
        String newProfile = normalizeJsonObject(PiiMasker.mask(
                firstNonBlank(r.customerProfile(), old.getCustomerProfile())));
        String newObjection = PiiMasker.mask(firstNonBlank(r.objection(), old.getObjection()));
        String newKeyActions = PiiMasker.mask(firstNonBlank(r.keyActions(), old.getKeyActions()));
        String newScripts = PiiMasker.mask(firstNonBlank(r.scripts(), old.getScripts()));
        String newEmotion = PiiMasker.mask(firstNonBlank(r.emotionChange(), old.getEmotionChange()));
        String newAdvance = PiiMasker.mask(firstNonBlank(r.advanceNodes(), old.getAdvanceNodes()));
        String newReusable = PiiMasker.mask(firstNonBlank(r.reusablePoints(), old.getReusablePoints()));
        String newFailure = PiiMasker.mask(firstNonBlank(r.failurePoints(), old.getFailurePoints()));
        ContentQualityGate.QualityReport report = ContentQualityGate.checkCase(
                newTitle, old.getResult(), newObjection, newProfile, newKeyActions, newScripts,
                old.getDialogueQuote(), old.getSource(), IndustryCase.ORIGIN_BOOK.equals(old.getOrigin()));

        // 草稿/待审还没生效：AI 改写直接覆盖，避免生成同内容的子版本草稿
        if (IndustryCase.STATUS_DRAFT.equals(old.getStatus())
                || IndustryCase.STATUS_PENDING.equals(old.getStatus())) {
            old.setTitle(newTitle);
            old.setCustomerProfile(newProfile);
            old.setObjection(newObjection);
            old.setKeyActions(newKeyActions);
            old.setScripts(newScripts);
            old.setEmotionChange(newEmotion);
            old.setAdvanceNodes(newAdvance);
            old.setReusablePoints(newReusable);
            old.setFailurePoints(newFailure);
            old.setQualityIssues(report.issues().isEmpty() ? null : report.issueText());
            old.setQualityWarnings(report.warnings().isEmpty() ? null : report.warningText());
            old.setUpdatedAt(LocalDateTime.now());
            caseRepository.save(old);
            saveEditHistory(id, "content", null, newTitle, instruction);
            writeAudit("suggest_case_revision", "case", id, Map.of("inPlace", true, "instruction", instruction));
            return old;
        }

        int newVersion = (old.getVersion() == null ? 1 : old.getVersion()) + 1;

        IndustryCase draft = IndustryCase.builder()
                .id(UUID.randomUUID())
                .companyId(old.getCompanyId())
                .supersedesId(old.getId())
                .rootId(old.getRootId())
                .origin(old.getOrigin())
                .title(newTitle)
                .customerProfile(newProfile)
                .funnelStage(old.getFunnelStage())
                .sceneTag(old.getSceneTag())
                .dialogueQuote(old.getDialogueQuote())
                .objection(newObjection)
                .emotionChange(newEmotion)
                .keyActions(newKeyActions)
                .scripts(newScripts)
                .advanceNodes(newAdvance)
                .result(old.getResult())
                .reusablePoints(newReusable)
                .failurePoints(newFailure)
                .source(old.getSource())
                .sourceTaskId(old.getSourceTaskId())
                .sourceDocument(old.getSourceDocument())
                .origin(old.getOrigin())
                .qualityIssues(report.issues().isEmpty() ? null : report.issueText())
                .qualityWarnings(report.warnings().isEmpty() ? null : report.warningText())
                .contributorId(currentUserId())
                .confidence(old.getConfidence())
                .version(newVersion)
                .effectiveAt(old.getEffectiveAt())
                .expireAt(old.getExpireAt())
                .status(IndustryCase.STATUS_DRAFT)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        caseRepository.save(draft);
        saveEditHistory(id, "version", String.valueOf(old.getVersion()), String.valueOf(newVersion), instruction);
        writeAudit("suggest_case_revision", "case", draft.getId(),
                Map.of("action", "suggest_revision", "instruction", instruction));
        return draft;
    }

    // ==================== 版本链与引用 ====================

    /**
     * 知识完整版本链（v1 → 当前），按版本号升序。
     *
     * <p>先沿 supersedes_id 向上追溯根版本，再向下补齐同一链上的分支
     * （AI 整理可能从一个版本派生多个草稿），最后按版本号排序。</p>
     */
    public List<KnowledgeEntry> knowledgeVersions(UUID id) {
        KnowledgeEntry current = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + id));
        List<KnowledgeEntry> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        for (KnowledgeEntry node = current; node != null && visited.add(node.getId()); ) {
            chain.add(node);
            node = node.getSupersedesId() == null ? null
                    : knowledgeRepository.findById(node.getSupersedesId()).orElse(null);
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            List<UUID> ids = chain.stream().map(KnowledgeEntry::getId).toList();
            for (KnowledgeEntry child : knowledgeRepository.findBySupersedesIdIn(ids)) {
                if (visited.add(child.getId())) {
                    chain.add(child);
                    grew = true;
                }
            }
        }
        chain.sort(Comparator
                .comparing(KnowledgeEntry::getVersion, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(KnowledgeEntry::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)));
        return chain;
    }

    /**
     * 以历史版本内容生成新草稿（回滚）：不直接改历史版本，
     * 而是基于目标版本内容创建 version=max+1、supersedesId=当前条目的 draft，经通过后生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry rollbackKnowledge(UUID currentId, UUID versionId) {
        if (currentId.equals(versionId)) {
            throw new BusinessException(400, "当前版本无需回滚");
        }
        KnowledgeEntry current = knowledgeRepository.findById(currentId)
                .orElseThrow(() -> new BusinessException(404, "知识不存在: " + currentId));
        List<KnowledgeEntry> chain = knowledgeVersions(currentId);
        KnowledgeEntry target = chain.stream()
                .filter(v -> v.getId().equals(versionId)).findFirst()
                .orElseThrow(() -> new BusinessException(400, "目标版本不属于该知识的版本链"));
        int maxVersion = chain.stream()
                .map(KnowledgeEntry::getVersion)
                .filter(v -> v != null)
                .max(Integer::compareTo).orElse(1);
        LocalDateTime now = LocalDateTime.now();
        KnowledgeEntry draft = KnowledgeEntry.builder()
                .id(UUID.randomUUID())
                .companyId(current.getCompanyId())
                .supersedesId(current.getId())
                .rootId(current.getRootId())
                .origin(target.getOrigin())
                .bookMode(target.isBookMode())
                .sourceType(target.getSourceType())
                .category(target.getCategory())
                .title(PiiMasker.mask(target.getTitle()))
                .content(PiiMasker.mask(target.getContent()))
                .version(maxVersion + 1)
                // 回滚继承目标版本的有效期，避免分叉后丢失生效/失效时间
                .effectiveAt(target.getEffectiveAt())
                .expireAt(target.getExpireAt())
                .status(KnowledgeEntry.STATUS_DRAFT)
                .source(target.getSource())
                .sourceTaskId(target.getSourceTaskId())
                .sourceDocument(target.getSourceDocument())
                .createdBy(currentUserId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        knowledgeRepository.save(draft);
        writeAudit("rollback_knowledge", "knowledge", draft.getId(),
                Map.of("fromVersion", String.valueOf(target.getVersion()),
                        "baseId", currentId.toString()));
        return draft;
    }

    /** 知识「被引用」：最近 N 天命中的分身、次数、最近时间。 */
    public List<Map<String, Object>> knowledgeReferences(UUID id, int days) {
        return retrieveLogRepository.aggregateKnowledgeReferences(
                        id, Math.max(1, days), referenceCompanyFilter()).stream()
                .map(this::toReferenceMap).toList();
    }

    /**
     * 引用面板企业过滤直接下推到 SQL：租户只查本企业日志，超管传 null 看全部。
     * 不先查全量再在内存过滤，避免跨企业数据被读出来。
     */
    private UUID referenceCompanyFilter() {
        return companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
    }

    /** 案例完整版本链（v1 → 当前），按版本号升序。 */
    public List<IndustryCase> caseVersions(UUID id) {
        IndustryCase current = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + id));
        List<IndustryCase> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        for (IndustryCase node = current; node != null && visited.add(node.getId()); ) {
            chain.add(node);
            node = node.getSupersedesId() == null ? null
                    : caseRepository.findById(node.getSupersedesId()).orElse(null);
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            List<UUID> ids = chain.stream().map(IndustryCase::getId).toList();
            for (IndustryCase child : caseRepository.findBySupersedesIdIn(ids)) {
                if (visited.add(child.getId())) {
                    chain.add(child);
                    grew = true;
                }
            }
        }
        chain.sort(Comparator
                .comparing(IndustryCase::getVersion, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(IndustryCase::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)));
        return chain;
    }

    /** 以历史案例版本内容生成新草稿（回滚）。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCase rollbackCase(UUID currentId, UUID versionId) {
        if (currentId.equals(versionId)) {
            throw new BusinessException(400, "当前版本无需回滚");
        }
        IndustryCase current = caseRepository.findById(currentId)
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + currentId));
        List<IndustryCase> chain = caseVersions(currentId);
        IndustryCase target = chain.stream()
                .filter(v -> v.getId().equals(versionId)).findFirst()
                .orElseThrow(() -> new BusinessException(400, "目标版本不属于该案例的版本链"));
        int maxVersion = chain.stream()
                .map(IndustryCase::getVersion)
                .filter(v -> v != null)
                .max(Integer::compareTo).orElse(1);
        LocalDateTime now = LocalDateTime.now();
        IndustryCase draft = IndustryCase.builder()
                .id(UUID.randomUUID())
                .companyId(current.getCompanyId())
                .supersedesId(current.getId())
                .rootId(current.getRootId())
                .origin(target.getOrigin())
                .title(PiiMasker.mask(target.getTitle()))
                .customerProfile(PiiMasker.mask(target.getCustomerProfile()))
                .funnelStage(target.getFunnelStage())
                .sceneTag(target.getSceneTag())
                .dialogueQuote(PiiMasker.mask(target.getDialogueQuote()))
                .objection(PiiMasker.mask(target.getObjection()))
                .emotionChange(PiiMasker.mask(target.getEmotionChange()))
                .keyActions(PiiMasker.mask(target.getKeyActions()))
                .scripts(PiiMasker.mask(target.getScripts()))
                .advanceNodes(PiiMasker.mask(target.getAdvanceNodes()))
                .result(target.getResult())
                .reusablePoints(PiiMasker.mask(target.getReusablePoints()))
                .failurePoints(PiiMasker.mask(target.getFailurePoints()))
                .source(PiiMasker.mask(target.getSource()))
                .sourceTaskId(target.getSourceTaskId())
                .sourceDocument(target.getSourceDocument())
                .contributorId(currentUserId())
                .confidence(target.getConfidence())
                .version(maxVersion + 1)
                // 回滚继承目标版本的有效期，避免分叉后丢失生效/失效时间
                .effectiveAt(target.getEffectiveAt())
                .expireAt(target.getExpireAt())
                .status(IndustryCase.STATUS_DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .build();
        caseRepository.save(draft);
        writeAudit("rollback_case", "case", draft.getId(),
                Map.of("fromVersion", String.valueOf(target.getVersion()),
                        "baseId", currentId.toString()));
        return draft;
    }

    /** 案例「被引用」：最近 N 天命中的分身、次数、最近时间。 */
    public List<Map<String, Object>> caseReferences(UUID id, int days) {
        return retrieveLogRepository.aggregateCaseReferences(
                        id, Math.max(1, days), referenceCompanyFilter()).stream()
                .map(this::toReferenceMap).toList();
    }

    private Map<String, Object> toReferenceMap(Object[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", row[0] != null ? row[0].toString() : null);
        m.put("skillName", row[1] != null ? row[1].toString() : "未知分身");
        m.put("refCount", row[2] instanceof Number n ? n.longValue() : 0L);
        m.put("lastReferencedAt", row[3] instanceof java.sql.Timestamp ts
                ? ts.toInstant().toString()
                : (row[3] != null ? row[3].toString() : null));
        return m;
    }

    // ==================== 私有辅助 ====================

    /** 创建时状态：默认 pending，显式 draft/pending/active 才放行，其余非法。 */
    private String resolveCreateStatus(String requested) {
        if (requested == null || requested.isBlank()) {
            return KnowledgeEntry.STATUS_PENDING;
        }
        if (KnowledgeEntry.STATUS_DRAFT.equals(requested)
                || KnowledgeEntry.STATUS_PENDING.equals(requested)
                || KnowledgeEntry.STATUS_ACTIVE.equals(requested)) {
            return requested;
        }
        throw new BusinessException(400, "非法状态: " + requested);
    }

    private void validateCategory(String category) {
        if (!KnowledgeEntry.isValidCategory(category)) {
            throw new BusinessException(400, "非法内容分类: " + category);
        }
    }

    /** 编辑只允许在通过前：已生效走「基于此版本修改」，已废弃不可编辑 */
    private void assertEditable(String status, String label) {
        if (KnowledgeEntry.STATUS_ACTIVE.equals(status)) {
            throw new BusinessException(400, label + "已生效，不能直接编辑；请用「基于此版本修改」生成新草稿");
        }
        if (KnowledgeEntry.STATUS_DEPRECATED.equals(status)) {
            throw new BusinessException(400, label + "已废弃，不能编辑；请先恢复");
        }
    }

    /**
     * 恢复被废弃条目时的目标状态。
     *
     * <p>草稿/待审原样恢复；原先生效的，如果同 root 里已经有其他生效版本，
     * 只能回到 pending 等人工重新审核，避免版本链出现两个 active。历史数据为空时按 pending 处理。</p>
     */
    private String resolveRestoredStatus(UUID rootId, UUID id, String previousStatus,
                                         String draftStatus, String pendingStatus, String activeStatus,
                                         java.util.function.BooleanSupplier hasOtherActive) {
        if (draftStatus.equals(previousStatus) || pendingStatus.equals(previousStatus)) {
            return previousStatus;
        }
        if (activeStatus.equals(previousStatus)) {
            return hasOtherActive.getAsBoolean() ? pendingStatus : activeStatus;
        }
        return pendingStatus;
    }

    private void validateSourceType(String sourceType) {
        if (!KnowledgeEntry.isValidSourceType(sourceType)) {
            throw new BusinessException(400, "非法来源类型: " + sourceType);
        }
    }

    private void validateResult(String result) {
        if (!IndustryCase.isValidResult(result)) {
            throw new BusinessException(400, "非法结果类型: " + result + "（仅 win/loss/delay）");
        }
    }

    /**
     * 案例的两个 JSONB 列（customer_profile / dialogue_quote）入参必须是合法 JSON。
     *
     * <p>这两列在库里是 JSONB，传纯文本会在事务提交阶段报 "invalid input syntax for type json"，
     * 对调用方表现为 500。这里提前校验，统一转成 400 并给出可操作的提示。</p>
     */
    /** 人工录入/编辑的知识脱敏；公共知识（strict=true）连公司名/项目名/金额一起隐去 */
    private void maskKnowledgeRequest(KnowledgeEntryRequest req, boolean strict) {
        java.util.function.Function<String, String> masker = strict ? PiiMasker::maskStrict : PiiMasker::mask;
        req.setTitle(masker.apply(req.getTitle()));
        req.setContent(masker.apply(req.getContent()));
        req.setSource(masker.apply(req.getSource()));
    }

    /** 人工录入/编辑的案例脱敏（含客户画像、客户原话等文本字段） */
    private void maskCaseRequest(IndustryCaseRequest req, boolean strict) {
        java.util.function.Function<String, String> masker = strict ? PiiMasker::maskStrict : PiiMasker::mask;
        req.setTitle(masker.apply(req.getTitle()));
        req.setCustomerProfile(masker.apply(req.getCustomerProfile()));
        req.setDialogueQuote(masker.apply(req.getDialogueQuote()));
        req.setObjection(masker.apply(req.getObjection()));
        req.setEmotionChange(masker.apply(req.getEmotionChange()));
        req.setKeyActions(masker.apply(req.getKeyActions()));
        req.setScripts(masker.apply(req.getScripts()));
        req.setAdvanceNodes(masker.apply(req.getAdvanceNodes()));
        req.setReusablePoints(masker.apply(req.getReusablePoints()));
        req.setFailurePoints(masker.apply(req.getFailurePoints()));
        req.setSource(masker.apply(req.getSource()));
    }

    private void validateJsonFields(String customerProfile, String dialogueQuote) {
        requireJsonIfPresent(customerProfile, "客户画像",
                "{\"industry\":\"制造业\",\"scale\":\"500人\",\"role\":\"采购总监\"}");
        requireJsonIfPresent(dialogueQuote, "客户原话",
                "[\"你们比竞品贵 20%\",\"我们再考虑一下\"]");
    }

    private void requireJsonIfPresent(String value, String label, String example) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException(400, label + "必须是合法 JSON，例如 " + example);
        }
    }

    /** 模型/历史数据可能给普通文本：合法 JSON 原样保留，否则包装成 {"text": ...}，避免事务提交时 500 */
    private String normalizeJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of("text", value));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * 向量化：embed HTTP 在事务外执行，updateEmbedding 走 self 短事务。
     * 由 scheduleReEmbed 在事务提交后触发，避免 HTTP 调用持事务连接。
     */
    public void reEmbedKnowledge(KnowledgeEntry k) {
        markKnowledgeIndex(k.getId(), "indexing", null);
        try {
            String text = knowledgeEmbedText(k);
            if (text.isBlank()) {
                markKnowledgeIndex(k.getId(), "failed", "内容为空，无法向量化");
                return;
            }
            float[] vec = embeddingService.embed(text);
            self.updateKnowledgeEmbedding(k.getId(), vec);
            markKnowledgeIndex(k.getId(), "indexed", null);
            // 向量化成功后才下线同链旧版，避免新版检索不到、旧版已下线的真空
            self.finalizeKnowledgeIndex(k.getId());
        } catch (Exception e) {
            log.error("知识向量化失败 id={}: {}", k.getId(), e.getMessage());
            markKnowledgeIndex(k.getId(), "failed", e.getMessage());
        }
    }

    /** 索引状态写回：成功时记录 indexed_at，失败保留原因（截断 500 字） */
    private void markKnowledgeIndex(UUID id, String status, String error) {
        try {
            if ("indexed".equals(status)) {
                knowledgeRepository.markIndexed(id);
            } else if ("failed".equals(status)) {
                knowledgeRepository.markIndexFailed(id, truncateError(error));
            } else {
                knowledgeRepository.markIndexStatus(id, status, truncateError(error));
            }
        } catch (Exception e) {
            log.warn("写入知识索引状态失败 id={}: {}", id, e.getMessage());
        }
    }

    private void markCaseIndex(UUID id, String status, String error) {
        try {
            if ("indexed".equals(status)) {
                caseRepository.markIndexed(id);
            } else if ("failed".equals(status)) {
                caseRepository.markIndexFailed(id, truncateError(error));
            } else {
                caseRepository.markIndexStatus(id, status, truncateError(error));
            }
        } catch (Exception e) {
            log.warn("写入案例索引状态失败 id={}: {}", id, e.getMessage());
        }
    }

    /** 向量化成功后的收尾：同 root 只保留当前 active，旧版本下线并记录 previous_status */
    @Transactional(rollbackFor = Exception.class)
    public void finalizeKnowledgeIndex(UUID id) {
        KnowledgeEntry current = knowledgeRepository.findById(id).orElse(null);
        if (current == null || !KnowledgeEntry.STATUS_ACTIVE.equals(current.getStatus())
                || !"indexed".equals(current.getEmbeddingStatus())) {
            return;
        }
        UUID root = current.getRootId() != null ? current.getRootId() : current.getId();
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeEntry other : knowledgeRepository.findByRootIdAndStatus(
                root, KnowledgeEntry.STATUS_ACTIVE)) {
            if (!other.getId().equals(id) && !isNewerVersion(other.getVersion(), current.getVersion())) {
                other.setPreviousStatus(KnowledgeEntry.STATUS_ACTIVE);
                other.setStatus(KnowledgeEntry.STATUS_DEPRECATED);
                other.setUpdatedAt(now);
                knowledgeRepository.save(other);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeCaseIndex(UUID id) {
        IndustryCase current = caseRepository.findById(id).orElse(null);
        if (current == null || !IndustryCase.STATUS_ACTIVE.equals(current.getStatus())
                || !"indexed".equals(current.getEmbeddingStatus())) {
            return;
        }
        UUID root = current.getRootId() != null ? current.getRootId() : current.getId();
        LocalDateTime now = LocalDateTime.now();
        for (IndustryCase other : caseRepository.findByRootIdAndStatus(
                root, IndustryCase.STATUS_ACTIVE)) {
            if (!other.getId().equals(id) && !isNewerVersion(other.getVersion(), current.getVersion())) {
                other.setPreviousStatus(IndustryCase.STATUS_ACTIVE);
                other.setStatus(IndustryCase.STATUS_DEPRECATED);
                other.setUpdatedAt(now);
                caseRepository.save(other);
            }
        }
    }

    /** 调度重试用：按 ID 重新向量化 */
    public void reEmbedKnowledgeById(UUID id) {
        knowledgeRepository.findById(id).ifPresent(this::reEmbedKnowledge);
    }

    public void reEmbedCaseById(UUID id) {
        caseRepository.findById(id).ifPresent(this::reEmbedCase);
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    /** 管理端「重建索引」：校验后异步重跑，避免在 HTTP 线程调用 embedding */
    public void requestKnowledgeReindex(UUID id) {
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在"));
        if (!KnowledgeEntry.STATUS_ACTIVE.equals(k.getStatus())) {
            throw new BusinessException(409, "仅已生效的知识需要重建索引");
        }
        self.reindexKnowledgeAsync(id);
    }

    public void requestCaseReindex(UUID id) {
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在"));
        if (!IndustryCase.STATUS_ACTIVE.equals(c.getStatus())) {
            throw new BusinessException(409, "仅已生效的案例需要重建索引");
        }
        self.reindexCaseAsync(id);
    }

    @Async("embeddingExecutor")
    public void reindexKnowledgeAsync(UUID id) {
        knowledgeRepository.findById(id).ifPresent(this::reEmbedKnowledge);
    }

    @Async("embeddingExecutor")
    public void reindexCaseAsync(UUID id) {
        caseRepository.findById(id).ifPresent(this::reEmbedCase);
    }

    /**
     * 写向量：jdbcTemplate 直写（照 saveEmbeddings），自提交、不依赖 @Modifying/@Transactional，
     * 避免 @Modifying 在 self 注入事务里「Executing an update/delete query」。
     */
    public void updateKnowledgeEmbedding(UUID id, float[] vec) {
        jdbcTemplate.update("UPDATE knowledge_entry SET embedding = ? WHERE id = ?", new PGvector(vec), id);
    }

    public void reEmbedCase(IndustryCase c) {
        markCaseIndex(c.getId(), "indexing", null);
        try {
            String text = caseEmbedText(c);
            if (text.isBlank()) {
                markCaseIndex(c.getId(), "failed", "内容为空，无法向量化");
                return;
            }
            float[] vec = embeddingService.embed(text);
            self.updateCaseEmbedding(c.getId(), vec);
            markCaseIndex(c.getId(), "indexed", null);
            self.finalizeCaseIndex(c.getId());
        } catch (Exception e) {
            log.error("案例向量化失败 id={}: {}", c.getId(), e.getMessage());
            markCaseIndex(c.getId(), "failed", e.getMessage());
        }
    }

    /** 写案例向量：jdbcTemplate 直写（自提交），理由同 updateKnowledgeEmbedding */
    public void updateCaseEmbedding(UUID id, float[] vec) {
        jdbcTemplate.update("UPDATE industry_case SET embedding = ? WHERE id = ?", new PGvector(vec), id);
    }

    /**
     * 事务提交后触发向量化；无事务上下文（单测）则同步执行。
     * 仅 active 状态写向量：pending/draft/deprecated 通过前不参与检索，无需向量，
     * 避免 create(默认 pending) 与 approve 重复调用 embedding（API 成本 ×2）。
     */
    private void scheduleReEmbed(KnowledgeEntry k) {
        if (!KnowledgeEntry.STATUS_ACTIVE.equals(k.getStatus())) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.reEmbedKnowledge(k);
                }
            });
        } else {
            self.reEmbedKnowledge(k);
        }
    }

    /** 同 {@link #scheduleReEmbed(KnowledgeEntry)}，仅 active 案例写向量。 */
    private void scheduleReEmbedCase(IndustryCase c) {
        if (!IndustryCase.STATUS_ACTIVE.equals(c.getStatus())) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.reEmbedCase(c);
                }
            });
        } else {
            self.reEmbedCase(c);
        }
    }

    private String knowledgeEmbedText(KnowledgeEntry k) {
        return (k.getTitle() == null ? "" : k.getTitle()) + "\n"
                + (k.getContent() == null ? "" : k.getContent());
    }

    /**
     * 案例 embedding 文本：只保留高信息量字段（title 加权 + 客户画像 + 异议 + 话术 + 结果 + 可复用/失败点），
     * 剔除 funnelStage/keyActions/advanceNodes 这类「泛销售话术」，避免把向量拉成「泛销售故事」，
     * 导致对无关销售查询产生 35-40% 基底相似度（召回噪音）。
     */
    private String caseEmbedText(IndustryCase c) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, c.getTitle());
        addIfPresent(parts, c.getTitle()); // 标题重复一次加权：最短、辨识度最高的标识
        addIfPresent(parts, c.getSceneTag());
        addIfPresent(parts, extractCustomerProfileText(c.getCustomerProfile()));
        addIfPresent(parts, c.getObjection());
        addIfPresent(parts, c.getScripts());
        addIfPresent(parts, c.getResult());
        addIfPresent(parts, c.getReusablePoints());
        addIfPresent(parts, c.getFailurePoints());
        return String.join(" ", parts);
    }

    /** 从 customerProfile JSON（{"行业":"精密制造","规模":"年产值20亿",...}）提取各 value 拼成文本 */
    private String extractCustomerProfileText(String customerProfileJson) {
        if (customerProfileJson == null || customerProfileJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(customerProfileJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            List<String> values = new ArrayList<>();
            for (Object v : map.values()) {
                if (v != null && !v.toString().isBlank()) {
                    values.add(v.toString().trim());
                }
            }
            return values.isEmpty() ? null : String.join(" ", values);
        } catch (Exception e) {
            log.warn("customerProfile 解析失败，跳过该字段: {}", e.getMessage());
            return null;
        }
    }

    private void addIfPresent(List<String> parts, String s) {
        if (s != null && !s.isBlank()) {
            parts.add(s.trim());
        }
    }

    /** 记录字段级编辑历史。复用 grain_edit_history 表，grain_id 存知识/案例条目 ID。 */
    private void saveEditHistory(UUID entryId, String field, String oldVal, String newVal, String note) {
        if (newVal == null || newVal.equals(oldVal)) {
            return;
        }
        editHistoryRepository.save(GrainEditHistory.builder()
                .id(UUID.randomUUID())
                .grainId(entryId)
                .fieldName(field)
                .oldValue(truncate(oldVal))
                .newValue(truncate(newVal))
                .editedBy(currentOperator())
                .editNote(note)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** 写操作审计。操作人取 TokenContext，取不到时回退零 UUID（照 AdminGrainService）。 */
    private void writeAudit(String action, String targetType, UUID targetId, Map<String, ?> detail) {
        try {
            UUID adminId = currentUserId();
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(adminId != null ? adminId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(toJson(detail))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.debug("审计日志写入失败: {}", e.getMessage());
        }
    }

    /**
     * 模型偶发返回非法 JSON（截断/多余文本）：同一提示词重试一次再报 502，
     * 避免用户看到一次失败就必须手动重试。
     */
    private <T> T chatJsonWithRetry(String prompt, java.util.function.Function<String, T> parser) {
        String json = chatStreamAdapter.chat(prompt);
        try {
            return parser.apply(json);
        } catch (BusinessException e) {
            if (e.getErrorCode() != 502) {
                throw e;
            }
            log.warn("AI 返回结果解析失败，重试一次: {}", e.getMessage());
            return parser.apply(chatStreamAdapter.chat(prompt));
        }
    }

    private RevisionResult parseRevisionResult(String json) {
        if (json == null) {
            throw new BusinessException(502, "AI 整理失败：无响应");
        }
        String clean = json.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(clean, Map.class);
            String title = (String) map.get("title");
            String content = (String) map.get("content");
            String category = (String) map.get("category");
            if (content == null || content.isBlank()) {
                throw new BusinessException(502, "AI 整理失败：缺少 content");
            }
            return new RevisionResult(title, content, category);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI 整理结果解析失败: " + e.getMessage(), e);
        }
    }

    /** 解析 AI 建议：只接受有 title + instruction 的条目，非法项丢弃；全空则报错。 */
    private List<Map<String, String>> parseSuggestions(String json) {
        if (json == null) {
            throw new BusinessException(502, "AI 建议失败：无响应");
        }
        String clean = json.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(clean, new TypeReference<>() {});
            List<Map<String, String>> result = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                String title = str(item.get("title"));
                String instruction = str(item.get("instruction"));
                if (title != null && !title.isBlank() && instruction != null && !instruction.isBlank()) {
                    result.add(Map.of("title", title.trim(), "instruction", instruction.trim()));
                }
            }
            if (result.isEmpty()) {
                throw new BusinessException(502, "AI 建议为空，请稍后重试");
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI 建议解析失败: " + e.getMessage(), e);
        }
    }

    /** 把案例字段拼成带标签的文本，供 AI 提建议时理解上下文。 */
    private String caseRevisionText(IndustryCase c) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "标题", c.getTitle());
        appendLine(sb, "客户画像", c.getCustomerProfile());
        appendLine(sb, "漏斗阶段", c.getFunnelStage());
        appendLine(sb, "场景标签", c.getSceneTag());
        appendLine(sb, "核心异议", c.getObjection());
        appendLine(sb, "情绪变化", c.getEmotionChange());
        appendLine(sb, "关键动作", c.getKeyActions());
        appendLine(sb, "关键话术", c.getScripts());
        appendLine(sb, "推进节点", c.getAdvanceNodes());
        appendLine(sb, "结果", c.getResult());
        appendLine(sb, "可复用点", c.getReusablePoints());
        appendLine(sb, "失败点", c.getFailurePoints());
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(value).append('\n');
        }
    }

    private CaseRevisionResult parseCaseRevisionResult(String json) {
        if (json == null) {
            throw new BusinessException(502, "AI 整理失败：无响应");
        }
        String clean = json.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(clean, Map.class);
            String title = str(map.get("title"));
            if (title == null || title.isBlank()) {
                throw new BusinessException(502, "AI 整理失败：缺少 title");
            }
            return new CaseRevisionResult(
                    title,
                    profileText(map.get("customerProfile")),
                    str(map.get("objection")),
                    str(map.get("emotionChange")),
                    str(map.get("keyActions")),
                    str(map.get("scripts")),
                    str(map.get("advanceNodes")),
                    str(map.get("reusablePoints")),
                    str(map.get("failurePoints")));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI 整理结果解析失败: " + e.getMessage(), e);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 异步向量化完成顺序不保证：旧版收尾时如果同链已有更高版本的 active，
     * 不能把新版下线；让新版自己的收尾去下线旧版。
     */
    private static boolean isNewerVersion(Integer otherVersion, Integer currentVersion) {
        return otherVersion != null && currentVersion != null && otherVersion > currentVersion;
    }

    /**
     * 客户画像是 JSONB：模型可能返回对象，也可能返回 JSON 字符串。
     *
     * <p>生产事故：模型把输入里的原始 JSON 直接塞进字符串且未转义，整段 JSON 解析失败（502）。
     * 现在两种形态都接受，统一序列化成 JSON 文本再交给 {@link #normalizeJsonObject}。</p>
     */
    private String profileText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                return null;
            }
        }
        return String.valueOf(value);
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private UUID currentUserId() {
        UUID uid = TokenContext.get();
        if (uid == null) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UUID u) {
                uid = u;
            }
        }
        return uid;
    }

    private String currentOperator() {
        UUID uid = currentUserId();
        return uid != null ? uid.toString() : "admin";
    }

    private String truncate(String s) {
        return s != null && s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    /** AI 改写结果 */
    private record RevisionResult(String title, String content, String category) {
    }

    /** AI 整理后的案例文本字段（结构化字段沿用原值，不在此记录）。 */
    public record CaseRevisionResult(String title, String customerProfile, String objection,
                                     String emotionChange, String keyActions, String scripts,
                                     String advanceNodes, String reusablePoints, String failurePoints) {
    }
}
