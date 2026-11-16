package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.EvaluationDatasetRequest;
import com.aiextract.dto.EvaluationItemRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationDatasetVersion;
import com.aiextract.model.EvaluationItem;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationDatasetVersionRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 评测中心题集服务 —— 题集/版本/题目的 CRUD + 审核状态机。
 *
 * <p>状态机：draft → in_review → approved → published；退回回 draft；已发布版本冻结，
 * 编辑复制新 draft 版本。审核期（in_review）与已发布（published）禁止编辑。</p>
 *
 * <p>companyId 由 Controller 按角色解析后传入（super_admin=NULL 表示平台公共模板），
 * 本服务不重复读权限。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationDatasetVersionRepository versionRepository;
    private final EvaluationItemRepository itemRepository;
    private final EvaluationRunItemRepository runItemRepository;

    // ==================== 题集 ====================

    /** 创建题集草稿（status=draft，版本 1） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationDataset createDataset(EvaluationDatasetRequest req, UUID companyId) {
        validatePurpose(req.getPurpose());
        UUID id = UUID.randomUUID();
        EvaluationDataset ds = EvaluationDataset.builder()
                .id(id)
                .companyId(companyId)
                .name(req.getName())
                .description(req.getDescription())
                .purpose(req.getPurpose())
                .status(EvaluationDataset.STATUS_DRAFT)
                .currentVersion(1)
                .itemCount(0)
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        datasetRepository.save(ds);

        EvaluationDatasetVersion version = EvaluationDatasetVersion.builder()
                .id(UUID.randomUUID())
                .datasetId(id)
                .version(1)
                .status(EvaluationDatasetVersion.STATUS_DRAFT)
                .itemCount(0)
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .build();
        versionRepository.save(version);
        return ds;
    }

    /** 编辑题集（仅 draft，乐观锁校验 version） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationDataset updateDataset(UUID id, EvaluationDatasetRequest req) {
        EvaluationDataset ds = findDataset(id);
        if (!EvaluationDataset.STATUS_DRAFT.equals(ds.getStatus())) {
            throw new BusinessException(400, "审核中或已发布的题集不可编辑");
        }
        if (req.getVersion() != null && !req.getVersion().equals(ds.getCurrentVersion())) {
            throw new BusinessException(409, "题集已被修改，请刷新后重试");
        }
        validatePurpose(req.getPurpose());
        ds.setName(req.getName());
        ds.setDescription(req.getDescription());
        ds.setPurpose(req.getPurpose());
        return datasetRepository.save(ds);
    }

    /** 提交审核（校验完整性 + 用途一致性） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationDataset submitForReview(UUID id) {
        EvaluationDataset ds = findDataset(id);
        if (!EvaluationDataset.STATUS_DRAFT.equals(ds.getStatus())) {
            throw new BusinessException(400, "仅草稿可提交审核");
        }
        long drafts = itemRepository.countByDatasetIdAndVersionAndStatus(
                id, ds.getCurrentVersion(), EvaluationItem.STATUS_DRAFT);
        if (drafts > 0) {
            // 先提示未审草稿：否则 active=0 时只会看到「无题目」，用户不知道草稿挡着
            throw new BusinessException(400,
                    "还有 " + drafts + " 道 AI 草稿题未审核；通过或丢弃后再提交审核");
        }
        List<EvaluationItem> items = itemRepository.findByDatasetIdAndVersionAndStatus(
                id, ds.getCurrentVersion(), EvaluationItem.STATUS_ACTIVE);
        if (items.isEmpty()) {
            throw new BusinessException(400, "题集无题目，不能提交审核");
        }
        validatePurposeConsistency(ds.getPurpose(), items);
        ds.setStatus(EvaluationDataset.STATUS_IN_REVIEW);
        return datasetRepository.save(ds);
    }

    /** 审核（approve/reject，退回必填理由） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationDataset reviewDataset(UUID id, String action, String reason) {
        EvaluationDataset ds = findDataset(id);
        if (!EvaluationDataset.STATUS_IN_REVIEW.equals(ds.getStatus())) {
            throw new BusinessException(400, "仅审核中的题集可审核");
        }
        if ("approve".equals(action)) {
            ds.setStatus(EvaluationDataset.STATUS_APPROVED);
        } else if ("reject".equals(action)) {
            if (reason == null || reason.isBlank()) {
                throw new BusinessException(400, "退回必须填写理由");
            }
            ds.setStatus(EvaluationDataset.STATUS_DRAFT);
        } else {
            throw new BusinessException(400, "非法审核动作: " + action);
        }
        // 审核说明落库：退回理由必须让提交人看得到，否则流程不闭环
        ds.setLastReviewReason(reason == null ? null : reason.trim());
        ds.setReviewedBy(TokenContext.get());
        ds.setReviewedAt(LocalDateTime.now());
        return datasetRepository.save(ds);
    }

    /** 发布（冻结当前版本，approved → published） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationDataset publishDataset(UUID id) {
        EvaluationDataset ds = findDataset(id);
        if (!EvaluationDataset.STATUS_APPROVED.equals(ds.getStatus())) {
            throw new BusinessException(400, "仅审核通过的题集可发布");
        }
        // 只冻结可用题：draft/deprecated 不计入版本题数，避免把未审草稿带进考试
        long count = itemRepository.countByDatasetIdAndVersionAndStatus(
                id, ds.getCurrentVersion(), EvaluationItem.STATUS_ACTIVE);
        EvaluationDatasetVersion version = versionRepository.findByDatasetIdAndVersion(id, ds.getCurrentVersion())
                .orElseThrow(() -> new BusinessException(500, "题集版本不存在: " + ds.getCurrentVersion()));
        version.setStatus(EvaluationDatasetVersion.STATUS_PUBLISHED);
        version.setItemCount((int) count);
        version.setFrozenAt(LocalDateTime.now());
        versionRepository.save(version);

        ds.setStatus(EvaluationDataset.STATUS_PUBLISHED);
        ds.setItemCount((int) count);
        return datasetRepository.save(ds);
    }

    /** 审核通过 AI 草稿题：draft → active（仅草稿状态的题集可操作） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationItem approveGeneratedItem(UUID itemId) {
        EvaluationItem item = requireGeneratedDraft(itemId);
        EvaluationDataset ds = findDataset(item.getDatasetId());
        if (!EvaluationDataset.STATUS_DRAFT.equals(ds.getStatus())) {
            throw new BusinessException(400, "题集已提交审核或已发布，不能修改题目状态");
        }
        item.setStatus(EvaluationItem.STATUS_ACTIVE);
        item.setReviewOutcome("approved");
        item.setReviewedBy(TokenContext.get());
        item.setReviewedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        refreshItemCount(ds);
        return item;
    }

    /** 丢弃 AI 草稿题：draft → deprecated，理由必填 */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationItem rejectGeneratedItem(UUID itemId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "丢弃草稿必须填写理由");
        }
        EvaluationItem item = requireGeneratedDraft(itemId);
        EvaluationDataset ds = findDataset(item.getDatasetId());
        if (!EvaluationDataset.STATUS_DRAFT.equals(ds.getStatus())) {
            throw new BusinessException(400, "题集已提交审核或已发布，不能修改题目状态");
        }
        item.setStatus(EvaluationItem.STATUS_DEPRECATED);
        item.setReviewOutcome("rejected");
        item.setReviewedBy(TokenContext.get());
        item.setReviewedAt(LocalDateTime.now());
        item.setReviewNote(reason.trim());
        item.setUpdatedAt(LocalDateTime.now());
        itemRepository.save(item);
        refreshItemCount(ds);
        return item;
    }

    private EvaluationItem requireGeneratedDraft(UUID itemId) {
        EvaluationItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "题目不存在"));
        if (!Boolean.TRUE.equals(item.getGenerated())) {
            throw new BusinessException(400, "仅 AI 生成的题目走草稿审核");
        }
        if (!EvaluationItem.STATUS_DRAFT.equals(item.getStatus())) {
            throw new BusinessException(400, "该题目不是待审核草稿");
        }
        return item;
    }

    private void refreshItemCount(EvaluationDataset ds) {
        int version = ds.getCurrentVersion() == null ? 1 : ds.getCurrentVersion();
        ds.setItemCount((int) itemRepository.countByDatasetIdAndVersionAndStatus(
                ds.getId(), version, EvaluationItem.STATUS_ACTIVE));
        ds.setUpdatedAt(LocalDateTime.now());
        datasetRepository.save(ds);
    }

    /** 从已发布版本复制新草稿版本（version+1），不覆盖已发布版 */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationDataset createVersion(UUID id) {
        EvaluationDataset ds = findDataset(id);
        if (!EvaluationDataset.STATUS_PUBLISHED.equals(ds.getStatus())) {
            throw new BusinessException(400, "仅已发布的题集可创建新版本");
        }
        int newVersion = ds.getCurrentVersion() + 1;
        List<EvaluationItem> sourceItems = itemRepository.findByDatasetIdAndVersionAndStatus(
                id, ds.getCurrentVersion(), EvaluationItem.STATUS_ACTIVE);
        for (EvaluationItem src : sourceItems) {
            itemRepository.save(EvaluationItem.builder()
                    .id(UUID.randomUUID())
                    .companyId(src.getCompanyId())
                    .datasetId(id)
                    .version(newVersion)
                    .question(src.getQuestion())
                    .context(src.getContext())
                    .expectedPoints(src.getExpectedPoints())
                    .forbiddenPoints(src.getForbiddenPoints())
                    .citationRequirement(src.getCitationRequirement())
                    .sceneTag(src.getSceneTag())
                    .goldBasis(src.getGoldBasis())
                    .scoringRule(src.getScoringRule())
                    .retrievalExpected(src.getRetrievalExpected())
                    .orderNo(src.getOrderNo())
                    .status(EvaluationItem.STATUS_ACTIVE)
                    .createdBy(currentUserId())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        versionRepository.save(EvaluationDatasetVersion.builder()
                .id(UUID.randomUUID())
                .datasetId(id)
                .version(newVersion)
                .status(EvaluationDatasetVersion.STATUS_DRAFT)
                .itemCount(sourceItems.size())
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .build());
        ds.setCurrentVersion(newVersion);
        ds.setStatus(EvaluationDataset.STATUS_DRAFT);
        // item_count 的口径是「最新已发布版题数」（列表页冗余列），不是当前草稿题数：
        // 开出 v2 草稿后 v1 仍是可复制/可运行的已发布版本，把计数归零会让列表显示 0 题。
        return datasetRepository.save(ds);
    }

    /**
     * 复制平台公共模板（company_id=NULL）产新草稿。
     *
     * <p>只允许复制<b>已发布</b>的模板：草稿版本的题目尚未审核，复制出去等于把未审核内容
     * 分发到企业；同时保证「复制到的题目」与「模板的发布快照」一致。</p>
     *
     * <p>targetCompanyId 由 Controller 按角色解析：企业管理员=本企业；super_admin=null，
     * 即复制出的是新的平台模板（与建题集时 super_admin 产出 NULL 的口径一致）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationDataset copyPublicDataset(UUID sourceId, UUID targetCompanyId) {
        EvaluationDataset source = findDataset(sourceId);
        if (source.getCompanyId() != null) {
            throw new BusinessException(400, "只能复制平台公共模板");
        }
        if (EvaluationDataset.STATUS_RETIRED.equals(source.getStatus())) {
            throw new BusinessException(400, "已退役的题集不可复制");
        }
        // 复制「最新已发布版本」的快照，而不是 currentVersion：模板发布 v1 后又开出 v2 草稿时，
        // currentVersion=2 里是尚未审核的题目，复制出去等于分发未审核内容。
        EvaluationDatasetVersion publishedVersion = versionRepository
                .findFirstByDatasetIdAndStatusOrderByVersionDesc(sourceId, EvaluationDatasetVersion.STATUS_PUBLISHED)
                .orElseThrow(() -> new BusinessException(400, "公共模板尚未发布过版本，无法复制"));
        int sourceVersion = publishedVersion.getVersion();
        EvaluationDataset copy = EvaluationDataset.builder()
                .id(UUID.randomUUID())
                .companyId(targetCompanyId)
                .name(source.getName())
                .description(source.getDescription())
                .purpose(source.getPurpose())
                .status(EvaluationDataset.STATUS_DRAFT)
                .currentVersion(1)
                .itemCount(0)
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        datasetRepository.save(copy);
        versionRepository.save(EvaluationDatasetVersion.builder()
                .id(UUID.randomUUID())
                .datasetId(copy.getId())
                .version(1)
                .status(EvaluationDatasetVersion.STATUS_DRAFT)
                .itemCount(0)
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .build());
        // 复制已发布版本的题目到目标草稿（companyId 改为目标企业，新 ID）
        List<EvaluationItem> sourceItems = itemRepository.findByDatasetIdAndVersionAndStatus(
                sourceId, sourceVersion, EvaluationItem.STATUS_ACTIVE);
        for (EvaluationItem src : sourceItems) {
            itemRepository.save(EvaluationItem.builder()
                    .id(UUID.randomUUID())
                    .companyId(targetCompanyId)
                    .datasetId(copy.getId())
                    .version(1)
                    .question(src.getQuestion())
                    .context(src.getContext())
                    .expectedPoints(src.getExpectedPoints())
                    .forbiddenPoints(src.getForbiddenPoints())
                    .citationRequirement(src.getCitationRequirement())
                    .sceneTag(src.getSceneTag())
                    .goldBasis(src.getGoldBasis())
                    .scoringRule(src.getScoringRule())
                    .retrievalExpected(src.getRetrievalExpected())
                    .orderNo(src.getOrderNo())
                    .status(EvaluationItem.STATUS_ACTIVE)
                    .createdBy(currentUserId())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        return copy;
    }

    // ==================== 题目 ====================

    /** 添加题目（仅草稿期） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationItem addItem(UUID datasetId, EvaluationItemRequest req, UUID companyId) {
        EvaluationDataset ds = findDataset(datasetId);
        assertEditable(ds);
        return itemRepository.save(EvaluationItem.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .datasetId(datasetId)
                .version(ds.getCurrentVersion())
                .question(req.getQuestion())
                .context(req.getContext())
                .expectedPoints(req.getExpectedPoints())
                .forbiddenPoints(req.getForbiddenPoints())
                .citationRequirement(req.getCitationRequirement())
                .sceneTag(req.getSceneTag())
                .goldBasis(req.getGoldBasis())
                .scoringRule(req.getScoringRule())
                .retrievalExpected(req.getRetrievalExpected())
                .orderNo(req.getOrderNo() != null ? req.getOrderNo()
                        : itemRepository.findMaxOrderNo(datasetId, ds.getCurrentVersion()) + 1)
                .status(EvaluationItem.STATUS_ACTIVE)
                .createdBy(currentUserId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    /** 编辑题目（仅所属题集草稿期） */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationItem updateItem(UUID itemId, EvaluationItemRequest req) {
        EvaluationItem item = findItem(itemId);
        EvaluationDataset ds = findDataset(item.getDatasetId());
        assertEditable(ds);
        item.setQuestion(req.getQuestion());
        item.setContext(req.getContext());
        item.setExpectedPoints(req.getExpectedPoints());
        item.setForbiddenPoints(req.getForbiddenPoints());
        item.setCitationRequirement(req.getCitationRequirement());
        item.setSceneTag(req.getSceneTag());
        item.setGoldBasis(req.getGoldBasis());
        item.setScoringRule(req.getScoringRule());
        item.setRetrievalExpected(req.getRetrievalExpected());
        if (req.getOrderNo() != null) {
            item.setOrderNo(req.getOrderNo());
        }
        return itemRepository.save(item);
    }

    /**
     * 删除题目（仅所属题集草稿期）。
     *
     * <p>引用保护：题目一旦被任何评测运行引用过就不允许删除。历史运行回答、检索证据都挂着
     * 这个题目 ID，删除后运行详情的内连接会让该题从历史记录里消失（证据丢失），
     * 报告与分母也会对不上。需要更正题目时请新建版本而不是删历史题。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(UUID itemId) {
        EvaluationItem item = findItem(itemId);
        EvaluationDataset ds = findDataset(item.getDatasetId());
        assertEditable(ds);
        if (runItemRepository.existsByItemId(itemId)) {
            throw new BusinessException(409, "该题目已被评测运行引用，不能删除；请新建版本修改题目");
        }
        itemRepository.delete(item);
    }

    // ==================== 私有辅助 ====================

    private void assertEditable(EvaluationDataset ds) {
        if (!EvaluationDataset.STATUS_DRAFT.equals(ds.getStatus())) {
            throw new BusinessException(400, "仅草稿状态的题集可编辑题目");
        }
    }

    private void validatePurpose(String purpose) {
        if (!EvaluationDataset.isValidPurpose(purpose)) {
            throw new BusinessException(400, "非法用途: " + purpose + "（仅 regression/acceptance/training）");
        }
    }

    /** 用途一致性：regression 必须有检索期望，acceptance/training 必须有预期要点 */
    private void validatePurposeConsistency(String purpose, List<EvaluationItem> items) {
        for (EvaluationItem item : items) {
            if (EvaluationDataset.PURPOSE_REGRESSION.equals(purpose)) {
                if (item.getRetrievalExpected() == null || item.getRetrievalExpected().isBlank()) {
                    throw new BusinessException(400, "研发回归用途的题目必须有检索期望: " + item.getQuestion());
                }
            } else {
                if (item.getExpectedPoints() == null || item.getExpectedPoints().isBlank()) {
                    throw new BusinessException(400, "该用途的题目必须有预期要点: " + item.getQuestion());
                }
            }
        }
    }

    private EvaluationDataset findDataset(UUID id) {
        return datasetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "题集不存在: " + id));
    }

    private EvaluationItem findItem(UUID id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "题目不存在: " + id));
    }

    private UUID currentUserId() {
        return TokenContext.get();
    }
}
