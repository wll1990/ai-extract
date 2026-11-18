package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.EvaluationRunRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationDatasetVersion;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationReport;
import com.aiextract.model.EvaluationReview;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillProfile;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationDatasetVersionRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationReportRepository;
import com.aiextract.repository.EvaluationReviewRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.repository.SkillProfileRepository;
import com.aiextract.repository.SkillRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 评测运行服务 —— 建批次、查询、取消、重试失败题、定稿、对比。
 *
 * <p>执行本体在 {@link EvaluationWorker}；本类只负责状态与数据，事务内不做 AI / HTTP 调用。</p>
 *
 * <p>幂等：{@code idempotencyKey} 由前端生成，同企业内唯一索引兜底并发双击 ——
 * 先查，未命中则插入，插入撞唯一索引时回查已存在批次返回（不报错、不建两条）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationRunService {

    private final EvaluationRunRepository runRepository;
    private final EvaluationRunItemRepository runItemRepository;
    private final EvaluationItemRepository itemRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationDatasetVersionRepository versionRepository;
    private final EvaluationReportRepository reportRepository;
    private final EvaluationReviewRepository reviewRepository;
    private final SkillRepository skillRepository;
    private final SkillProfileRepository profileRepository;
    private final CompanyScopeService companyScopeService;
    private final EvaluationRunAggregator aggregator;
    private final ObjectMapper objectMapper;

    /** 默认模型标识（写入 model_config 快照；用于报告说明口径） */
    @Value("${spring.ai.openai.chat.model:deepseek-chat}")
    private String defaultModel;

    // ==================== 建批次 ====================

    /**
     * 创建评测批次并生成逐题执行记录（事务内只写库，毫秒级）。
     *
     * <p>校验顺序：题集可读 → 版本已发布 → 有题目 → 分身归属 → 用途一致。
     * 任何一项不满足直接失败，不建半成品批次。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationRun createRun(EvaluationRunRequest req) {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(400, "无企业归属，无法创建评测批次");
        }
        String idempotencyKey = blankToNull(req.getIdempotencyKey());
        if (idempotencyKey != null) {
            Optional<EvaluationRun> existed =
                    runRepository.findByCompanyIdAndIdempotencyKey(companyId, idempotencyKey);
            if (existed.isPresent()) {
                log.info("评测批次幂等命中 runId={} key={}", existed.get().getId(), idempotencyKey);
                return existed.get();
            }
        }

        companyScopeService.assertDatasetReadable(req.getDatasetId());
        EvaluationDataset dataset = datasetRepository.findById(req.getDatasetId())
                .orElseThrow(() -> new BusinessException(404, "题集不存在"));
        String purpose = blankToNull(req.getPurpose()) == null ? dataset.getPurpose() : req.getPurpose();
        if (!Objects.equals(purpose, dataset.getPurpose())) {
            throw new BusinessException(400, "运行用途必须与题集用途一致（题集=" + dataset.getPurpose() + "）");
        }
        assertRunnablePurpose(purpose);

        int version = req.getDatasetVersion() == null ? dataset.getCurrentVersion() : req.getDatasetVersion();
        EvaluationDatasetVersion frozen = versionRepository
                .findByDatasetIdAndVersion(dataset.getId(), version)
                .orElseThrow(() -> new BusinessException(404, "题集版本不存在: v" + version));
        if (!EvaluationDatasetVersion.STATUS_PUBLISHED.equals(frozen.getStatus())) {
            throw new BusinessException(400, "仅已发布（冻结）的题集版本可运行，当前状态=" + frozen.getStatus());
        }
        List<EvaluationItem> items = itemRepository.findByDatasetIdAndVersionAndStatus(
                dataset.getId(), version, EvaluationItem.STATUS_ACTIVE);
        if (items.isEmpty()) {
            throw new BusinessException(400, "该题集版本没有可运行题目");
        }

        companyScopeService.assertSkillOwnership(req.getSkillId());
        Skill skill = skillRepository.findById(req.getSkillId())
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        if (!"individual".equals(skill.getType())) {
            // 评测答题复用个体分身的提示词组装链路；组织分身的回答口径不同，强行跑会得出错误分数
            throw new BusinessException(400, "组织分身暂不支持评测，请选择个体分身");
        }

        LocalDateTime now = LocalDateTime.now();
        EvaluationRun run = EvaluationRun.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .datasetId(dataset.getId())
                .datasetVersion(version)
                .skillId(skill.getId())
                .modelConfig(blankToNull(req.getModelConfig()) == null
                        ? defaultModelConfig() : req.getModelConfig())
                .skillSnapshot(buildSkillSnapshot(skill))
                .purpose(purpose)
                .status(EvaluationRun.STATUS_QUEUED)
                .reviewStatus(EvaluationRun.REVIEW_NOT_READY)
                .totalItems(items.size())
                .usageTokens(0L)
                .usageCost(java.math.BigDecimal.ZERO)
                .idempotencyKey(idempotencyKey)
                .createdBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            // 只有带幂等键时才可能是并发双击；无键时冲突意味着其他数据问题，不能回查后随手返回一条旧批次
            if (idempotencyKey == null) {
                throw new BusinessException(409, "批次创建失败，请重试");
            }
            return runRepository.findByCompanyIdAndIdempotencyKey(companyId, idempotencyKey)
                    .orElseThrow(() -> new BusinessException(409, "批次创建冲突，请刷新后重试"));
        }

        List<EvaluationRunItem> runItems = new ArrayList<>(items.size());
        for (EvaluationItem item : items) {
            runItems.add(EvaluationRunItem.builder()
                    .id(UUID.randomUUID())
                    .runId(run.getId())
                    .itemId(item.getId())
                    .itemVersion(version)
                    .status(EvaluationRunItem.STATUS_PENDING)
                    .attemptNo(1)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        runItemRepository.saveAll(runItems);
        log.info("创建评测批次 runId={} datasetId={} version={} skillId={} purpose={} items={}",
                run.getId(), dataset.getId(), version, skill.getId(), purpose, items.size());
        return run;
    }

    // ==================== 查询 ====================

    /** 批次列表：企业隔离；super_admin 可看全部。 */
    public Page<EvaluationRun> listRuns(UUID datasetId, String status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, size);
        String normalizedStatus = blankToNull(status);
        UUID companyId = TokenContext.getCompanyId();
        boolean superAdmin = companyScopeService.isUnrestrictedDataScope();
        if (superAdmin) {
            if (normalizedStatus != null) {
                return runRepository.findByStatusOrderByCreatedAtDesc(normalizedStatus, pageable);
            }
            return runRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        if (companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问评测批次");
        }
        if (datasetId != null) {
            return normalizedStatus != null
                    ? runRepository.findByCompanyIdAndDatasetIdAndStatusOrderByCreatedAtDesc(
                            companyId, datasetId, normalizedStatus, pageable)
                    : runRepository.findByCompanyIdAndDatasetIdOrderByCreatedAtDesc(companyId, datasetId, pageable);
        }
        return normalizedStatus != null
                ? runRepository.findByCompanyIdAndStatusOrderByCreatedAtDesc(companyId, normalizedStatus, pageable)
                : runRepository.findByCompanyIdOrderByCreatedAtDesc(companyId, pageable);
    }

    /**
     * 质量基线趋势（CH-03 / EV-02）：同一数据集最近 N 个已完成批次的均分与通过率。
     * 只读，用于模型/Prompt 变更前后对比，不作硬门禁。
     */
    public List<Map<String, Object>> trend(UUID datasetId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        Pageable pageable = PageRequest.of(0, safeLimit);
        UUID companyId = TokenContext.getCompanyId();
        boolean superAdmin = companyScopeService.isUnrestrictedDataScope();
        List<String> doneStatuses = List.of(
                EvaluationRun.STATUS_COMPLETED, EvaluationRun.STATUS_PARTIAL_FAILED);
        List<EvaluationRun> runs;
        if (superAdmin) {
            runs = runRepository.findByDatasetIdAndStatusInOrderByFinishedAtDesc(
                    datasetId, doneStatuses, pageable);
        } else {
            if (companyId == null) {
                throw new BusinessException(403, "无企业归属，无权访问评测批次");
            }
            runs = runRepository.findByCompanyIdAndDatasetIdAndStatusInOrderByFinishedAtDesc(
                    companyId, datasetId, doneStatuses, pageable);
        }
        return runs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.getId().toString());
            m.put("datasetVersion", r.getDatasetVersion());
            m.put("finishedAt", r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
            m.put("scoreAvg", r.getScoreAvg());
            m.put("totalItems", r.getTotalItems());
            m.put("passedItems", r.getPassedItems());
            m.put("failedItems", r.getFailedItems());
            m.put("errorItems", r.getErrorItems());
            int scored = (r.getPassedItems() == null ? 0 : r.getPassedItems())
                    + (r.getFailedItems() == null ? 0 : r.getFailedItems());
            m.put("passRate", scored == 0 ? null
                    : Math.round((r.getPassedItems() == null ? 0 : r.getPassedItems()) * 1000.0 / scored) / 10.0);
            m.put("modelConfig", r.getModelConfig());
            return m;
        }).toList();
    }

    /** 批次详情（企业隔离校验）。 */
    public EvaluationRun getRun(UUID runId) {
        companyScopeService.assertRunReadable(runId);
        return runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "评测批次不存在"));
    }

    /** 逐题执行分页（详情页列表，按题序）。 */
    public Page<EvaluationRunItem> listRunItems(UUID runId, int page, int size) {
        companyScopeService.assertRunReadable(runId);
        return runItemRepository.findDetailPageByRunId(runId, PageRequest.of(Math.max(page, 1) - 1, size));
    }

    /** 单题最新一次尝试（详情抽屉）。 */
    public EvaluationRunItem getRunItem(UUID runId, UUID itemId) {
        companyScopeService.assertRunReadable(runId);
        return runItemRepository.findFirstByRunIdAndItemIdOrderByAttemptNoDesc(runId, itemId)
                .orElseThrow(() -> new BusinessException(404, "该题目在本批次中没有执行记录"));
    }

    /** 单题复核历史。 */
    public List<EvaluationReview> listReviews(UUID runItemId) {
        return reviewRepository.findByRunItemIdOrderByCreatedAtAsc(runItemId);
    }

    // ==================== 取消 ====================

    /**
     * 请求取消：在途批次标记 cancel_requested，由 Worker 释放租约并收尾为 cancelled。
     *
     * <p>已在终态的批次不能再取消（返回 409），历史证据不删除。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationRun cancelRun(UUID runId) {
        companyScopeService.assertRunReadable(runId);
        EvaluationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "评测批次不存在"));
        if (!EvaluationRun.isExecuting(run.getStatus())
                && !EvaluationRun.STATUS_CANCEL_REQUESTED.equals(run.getStatus())) {
            throw new BusinessException(409, "批次已结束，不能取消（当前状态=" + run.getStatus() + "）");
        }
        runRepository.markCancelRequested(runId);
        return runRepository.findById(runId).orElse(run);
    }

    // ==================== 重试失败题 ====================

    /**
     * 重试基础设施失败的题目：为每道 error 题新建 attempt_no+1 的待执行行，旧证据保留。
     *
     * <p>业务低分（verdict=fail）不能走这里刷分 —— 只认执行态 {@code error}。</p>
     *
     * @return 新建的尝试数
     */
    @Transactional(rollbackFor = Exception.class)
    public int retryFailed(UUID runId) {
        companyScopeService.assertRunReadable(runId);
        EvaluationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "评测批次不存在"));
        if (EvaluationRun.isExecuting(run.getStatus())
                || EvaluationRun.STATUS_CANCEL_REQUESTED.equals(run.getStatus())
                || EvaluationRun.STATUS_CANCELLED.equals(run.getStatus())) {
            // 取消的批次不允许重试：否则 requeue 会把整批（含从未执行的题）复活
            throw new BusinessException(409, "批次已取消或尚未结束，不能重试失败题");
        }
        List<EvaluationRunItem> latest = runItemRepository.findLatestAttempts(runId);
        List<EvaluationRunItem> failed = latest.stream()
                .filter(ri -> EvaluationRunItem.STATUS_ERROR.equals(ri.getStatus()))
                .toList();
        if (failed.isEmpty()) {
            throw new BusinessException(400, "没有执行失败的题目可重试");
        }
        LocalDateTime now = LocalDateTime.now();
        List<EvaluationRunItem> retries = new ArrayList<>(failed.size());
        for (EvaluationRunItem previous : failed) {
            retries.add(EvaluationRunItem.builder()
                    .id(UUID.randomUUID())
                    .runId(runId)
                    .itemId(previous.getItemId())
                    .itemVersion(previous.getItemVersion())
                    .status(EvaluationRunItem.STATUS_PENDING)
                    .attemptNo(previous.getAttemptNo() + 1)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        runItemRepository.saveAll(retries);
        runRepository.requeue(runId, now);
        log.info("评测批次重试失败题 runId={} retried={}", runId, retries.size());
        return retries.size();
    }

    // ==================== 定稿与报告 ====================

    /**
     * 定稿：校验完整性与必审项，冻结报告（版本化，不覆盖历史）。
     *
     * <p>报告正文由服务端按真实计数生成（不调模型），避免报告与数据不一致。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationReport finalizeRun(UUID runId) {
        companyScopeService.assertRunReadable(runId);
        // 定稿前重算一次计数与均分：报告数字必须与逐题记录一致，不能沿用可能过期的冗余列
        aggregator.recomputeAndMaybeFinish(runId);
        EvaluationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "评测批次不存在"));
        if (EvaluationRun.isExecuting(run.getStatus())
                || EvaluationRun.STATUS_CANCEL_REQUESTED.equals(run.getStatus())) {
            throw new BusinessException(409, "批次尚未结束，不能定稿（当前状态=" + run.getStatus() + "）");
        }
        // 幂等：已定稿且期间没有新的复核（复核会把审核态拉回 pending_review），直接返回当前报告，
        // 不重复产生空修订版本
        if (EvaluationRun.REVIEW_FINALIZED.equals(run.getReviewStatus())) {
            return reportRepository.findFirstByRunIdOrderByVersionDesc(runId)
                    .orElseThrow(() -> new BusinessException(409, "批次已定稿但报告缺失，请联系管理员"));
        }
        List<EvaluationRunItem> latest = runItemRepository.findLatestAttempts(runId);
        long unresolved = latest.stream()
                .filter(ri -> !EvaluationRunItem.STATUS_COMPLETED.equals(ri.getStatus())
                        && !EvaluationRunItem.STATUS_ERROR.equals(ri.getStatus()))
                .count();
        if (unresolved > 0) {
            throw new BusinessException(409, "还有 " + unresolved + " 题未执行完成，不能定稿");
        }
        List<UUID> needReviewIds = latest.stream()
                .filter(this::needsReview)
                .map(EvaluationRunItem::getId)
                .toList();
        if (!needReviewIds.isEmpty()) {
            Set<UUID> reviewed = new HashSet<>();
            reviewRepository.findByRunItemIdInOrderByCreatedAtAsc(needReviewIds)
                    .forEach(review -> reviewed.add(review.getRunItemId()));
            long missing = needReviewIds.stream().filter(id -> !reviewed.contains(id)).count();
            if (missing > 0) {
                throw new BusinessException(409, "还有 " + missing + " 题需人工复核后才能定稿");
            }
        }

        int nextVersion = reportRepository.findFirstByRunIdOrderByVersionDesc(runId)
                .map(report -> report.getVersion() + 1)
                .orElse(1);
        LocalDateTime now = LocalDateTime.now();
        // 检索指标：来自每题 rule_check_result.retrieval_metrics（Worker 执行时算好），这里只做聚合
        RetrievalAggregate retrieval = aggregateRetrieval(latest);
        if (retrieval != null) {
            runRepository.updateScoreDetail(runId, toRetrievalJson(retrieval), now);
        }
        String summary = buildReportSummary(run, latest, retrieval);
        EvaluationReport report = EvaluationReport.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .version(nextVersion)
                .status(EvaluationReport.STATUS_FINALIZED)
                .summary(summary)
                .content(buildReportContent(run, latest, summary, retrieval))
                .finalizedBy(TokenContext.get())
                .finalizedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        reportRepository.save(report);
        runRepository.updateReviewStatus(runId, EvaluationRun.REVIEW_FINALIZED, now);
        log.info("评测批次定稿 runId={} reportVersion={}", runId, nextVersion);
        return report;
    }

    // ==================== 对比 ====================

    /**
     * 两次运行逐题对比。
     *
     * <p>同题集同版本才可比：可逐题比较分数与结论；不同版本只比较公共题目并给出
     * 口径提示，不做总分相减（docs/38 E4）。</p>
     */
    public CompareResult compare(UUID leftId, UUID rightId) {
        if (Objects.equals(leftId, rightId)) {
            throw new BusinessException(400, "不能与自身对比");
        }
        EvaluationRun left = getRun(leftId);
        EvaluationRun right = getRun(rightId);
        boolean sameCaliber = Objects.equals(left.getDatasetId(), right.getDatasetId())
                && Objects.equals(left.getDatasetVersion(), right.getDatasetVersion());

        Map<UUID, EvaluationRunItem> leftItems = latestByItem(leftId);
        Map<UUID, EvaluationRunItem> rightItems = latestByItem(rightId);
        Set<UUID> commonItemIds = new HashSet<>(leftItems.keySet());
        commonItemIds.retainAll(rightItems.keySet());

        // 对比口径与逐题列表/均分一致：人工改分优先，否则回落 AI 原分
        Map<UUID, FinalScore> leftScores = finalScores(new ArrayList<>(leftItems.values()));
        Map<UUID, FinalScore> rightScores = finalScores(new ArrayList<>(rightItems.values()));

        List<Map<String, Object>> diffs = new ArrayList<>();
        for (UUID itemId : commonItemIds) {
            EvaluationRunItem l = leftItems.get(itemId);
            EvaluationRunItem r = rightItems.get(itemId);
            FinalScore ls = leftScores.get(l.getId());
            FinalScore rs = rightScores.get(r.getId());
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("itemId", itemId.toString());
            diff.put("leftVerdict", l.getVerdict());
            diff.put("rightVerdict", r.getVerdict());
            diff.put("leftScore", ls != null ? ls.score() : null);
            diff.put("leftScoreSource", ls != null ? ls.source() : null);
            diff.put("rightScore", rs != null ? rs.score() : null);
            diff.put("rightScoreSource", rs != null ? rs.source() : null);
            diff.put("verdictChanged", !Objects.equals(l.getVerdict(), r.getVerdict()));
            diffs.add(diff);
        }
        String caliberNote = sameCaliber
                ? "同题集同版本，可逐题比较分数与结论"
                : "题集或版本不同，仅比较公共题目（" + commonItemIds.size() + " 题），不可直接相减总分";
        return new CompareResult(leftId, rightId, sameCaliber, caliberNote,
                leftItems.size() + rightItems.size() - commonItemIds.size(), diffs);
    }

    // ==================== 私有辅助 ====================

    private void assertRunnablePurpose(String purpose) {
        if (!EvaluationDataset.PURPOSE_REGRESSION.equals(purpose)
                && !EvaluationDataset.PURPOSE_ACCEPTANCE.equals(purpose)) {
            throw new BusinessException(400, "题集用途 " + purpose + " 不适用于评测运行（培训考试请走培训模块）");
        }
    }

    private Map<UUID, EvaluationRunItem> latestByItem(UUID runId) {
        Map<UUID, EvaluationRunItem> map = new HashMap<>();
        for (EvaluationRunItem item : runItemRepository.findLatestAttempts(runId)) {
            map.put(item.getItemId(), item);
        }
        return map;
    }

    /** 是否需要人工复核：读 ai_score.need_review（评分时由服务端写入）。 */
    private boolean needsReview(EvaluationRunItem item) {
        if (item.getAiScore() == null) {
            return false;
        }
        try {
            return objectMapper.readTree(item.getAiScore()).path("need_review").asBoolean(false);
        } catch (Exception e) {
            log.warn("评分 JSON 解析失败 runItemId={}: {}", item.getId(), e.getMessage());
            return false;
        }
    }

    private Double totalScore(String aiScoreJson) {
        if (aiScoreJson == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(aiScoreJson);
            return node.has("total_score") ? node.get("total_score").asDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取已定稿报告（刷新页面后可直接展示，不需要再点一次定稿） */
    public EvaluationReport latestReport(UUID runId) {
        companyScopeService.assertRunReadable(runId);
        return reportRepository.findFirstByRunIdOrderByVersionDesc(runId)
                .orElseThrow(() -> new BusinessException(404, "该批次还没有生成报告"));
    }

    /** 最终分展示口径：人工改分优先，否则回落到 AI 原分 */
    public record FinalScore(Double score, String source) {
    }

    /**
     * 批量取每题最终分（人工改分优先），供逐题列表/对比页与均分口径保持一致。
     * 一次查询所有复核记录，避免逐题 N+1。
     */
    public Map<UUID, FinalScore> finalScores(List<EvaluationRunItem> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = items.stream().map(EvaluationRunItem::getId).toList();
        Map<UUID, Double> adjusted = new HashMap<>();
        for (EvaluationReview review : reviewRepository.findByRunItemIdInOrderByCreatedAtAsc(ids)) {
            Double value = totalScore(review.getAdjustedScore());
            if (value != null) {
                // 升序遍历，后出现的覆盖先出现的 → 保留最新一条改分
                adjusted.put(review.getRunItemId(), value);
            }
        }
        Map<UUID, FinalScore> result = new HashMap<>();
        for (EvaluationRunItem item : items) {
            Double manual = adjusted.get(item.getId());
            if (manual != null) {
                result.put(item.getId(), new FinalScore(manual, "manual"));
            } else {
                result.put(item.getId(), new FinalScore(totalScore(item.getAiScore()), "ai"));
            }
        }
        return result;
    }

    private String defaultModelConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model", defaultModel);
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("模型配置序列化失败", e);
        }
    }

    /** 分身画像/提示词快照：用于报告说明「本次评测对准的是哪一版分身」。 */
    private String buildSkillSnapshot(Skill skill) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("skillId", skill.getId().toString());
        snapshot.put("displayName", skill.getDisplayName());
        snapshot.put("type", skill.getType());
        snapshot.put("status", skill.getStatus());
        snapshot.put("updatedAt", skill.getUpdatedAt() == null ? null : skill.getUpdatedAt().toString());
        SkillProfile profile = profileRepository.findBySkillId(skill.getId()).orElse(null);
        if (profile != null) {
            snapshot.put("personality", profile.getPersonality());
            snapshot.put("background", profile.getBackground());
            snapshot.put("knowledgeDomains", profile.getKnowledgeDomains());
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("分身快照序列化失败", e);
        }
    }

    private String buildReportSummary(EvaluationRun run, List<EvaluationRunItem> latest,
                                      RetrievalAggregate retrieval) {
        long completed = latest.stream()
                .filter(ri -> EvaluationRunItem.STATUS_COMPLETED.equals(ri.getStatus())).count();
        long error = latest.stream()
                .filter(ri -> EvaluationRunItem.STATUS_ERROR.equals(ri.getStatus())).count();
        long passed = latest.stream()
                .filter(ri -> EvaluationRunItem.VERDICT_PASS.equals(ri.getVerdict())).count();
        long failed = latest.stream()
                .filter(ri -> EvaluationRunItem.VERDICT_FAIL.equals(ri.getVerdict())).count();
        long undetermined = latest.stream()
                .filter(ri -> EvaluationRunItem.VERDICT_UNDETERMINED.equals(ri.getVerdict())).count();
        long needReview = latest.stream().filter(this::needsReview).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", run.getId().toString());
        summary.put("datasetId", run.getDatasetId().toString());
        summary.put("datasetVersion", run.getDatasetVersion());
        summary.put("skillId", run.getSkillId().toString());
        summary.put("purpose", run.getPurpose());
        summary.put("status", run.getStatus());
        summary.put("totalItems", latest.size());
        summary.put("completedItems", completed);
        summary.put("errorItems", error);
        summary.put("passedItems", passed);
        summary.put("failedItems", failed);
        summary.put("undeterminedItems", undetermined);
        summary.put("needReviewItems", needReview);
        summary.put("scoreAvg", run.getScoreAvg());
        summary.put("durationSeconds", durationSeconds(run));
        summary.put("usageTokens", run.getUsageTokens());
        summary.put("usageCost", run.getUsageCost());
        if (retrieval != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("items", retrieval.items());
            r.put("recallAtK", retrieval.recall());
            r.put("precisionAtK", retrieval.precision());
            r.put("mrr", retrieval.mrr());
            r.put("fullyHitItems", retrieval.fullyHit());
            r.put("itemsWithMiss", retrieval.withMiss());
            summary.put("retrieval", r);
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalStateException("报告汇总序列化失败", e);
        }
    }

    // ==================== 检索指标聚合 ====================

    /**
     * 聚合每题检索指标（Worker 执行时已写进 rule_check_result.retrieval_metrics）。
     *
     * @return 没有任何题配置 retrieval_expected 时返回 null（报告不出现该段，而不是显示 0）
     */
    private RetrievalAggregate aggregateRetrieval(List<EvaluationRunItem> latest) {
        int items = 0;
        double recall = 0;
        double precision = 0;
        double mrr = 0;
        int fullyHit = 0;
        int withMiss = 0;
        for (EvaluationRunItem item : latest) {
            JsonNode metrics = readRetrievalMetrics(item.getRuleCheckResult());
            if (metrics == null) {
                continue;
            }
            items++;
            recall += metrics.path("recall").asDouble();
            precision += metrics.path("precisionAtK").asDouble();
            mrr += metrics.path("mrr").asDouble();
            int expected = metrics.path("expectedCount").asInt();
            int hit = metrics.path("hitExpected").asInt();
            if (expected > 0 && hit == expected) {
                fullyHit++;
            }
            if (metrics.path("missedExpected").size() > 0) {
                withMiss++;
            }
        }
        if (items == 0) {
            return null;
        }
        return new RetrievalAggregate(items, round4(recall / items), round4(precision / items),
                round4(mrr / items), fullyHit, withMiss);
    }

    private JsonNode readRetrievalMetrics(String ruleCheckJson) {
        if (ruleCheckJson == null || ruleCheckJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(ruleCheckJson).path("retrieval_metrics");
            return node.isMissingNode() || node.isNull() ? null : node;
        } catch (Exception e) {
            log.warn("规则检查 JSON 解析失败，跳过检索指标: {}", e.getMessage());
            return null;
        }
    }

    private String toRetrievalJson(RetrievalAggregate retrieval) {
        Map<String, Object> detail = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", retrieval.items());
        r.put("recallAtK", retrieval.recall());
        r.put("precisionAtK", retrieval.precision());
        r.put("mrr", retrieval.mrr());
        r.put("fullyHitItems", retrieval.fullyHit());
        r.put("itemsWithMiss", retrieval.withMiss());
        detail.put("retrieval", r);
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("检索指标序列化失败", e);
        }
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** 耗时：真实起止（started_at → finished_at）；缺失时返回 null 而不是编造数字。 */
    private Long durationSeconds(EvaluationRun run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null) {
            return null;
        }
        return Math.max(0, Duration.between(run.getStartedAt(), run.getFinishedAt()).getSeconds());
    }

    /**
     * 报告正文：全部来自真实计数，不调模型、不写套话。
     *
     * <p>明确标注「内容库未完全冻结」：首版只冻结题目与分身快照，知识库/颗粒仍在变化，
     * 因此不声称严格可复现（docs/38 E4）。</p>
     */
    private String buildReportContent(EvaluationRun run, List<EvaluationRunItem> latest, String summaryJson,
                                      RetrievalAggregate retrieval) {
        long completed = latest.stream()
                .filter(ri -> EvaluationRunItem.STATUS_COMPLETED.equals(ri.getStatus())).count();
        long error = latest.stream()
                .filter(ri -> EvaluationRunItem.STATUS_ERROR.equals(ri.getStatus())).count();
        long passed = latest.stream()
                .filter(ri -> EvaluationRunItem.VERDICT_PASS.equals(ri.getVerdict())).count();
        long failed = latest.stream()
                .filter(ri -> EvaluationRunItem.VERDICT_FAIL.equals(ri.getVerdict())).count();
        long undetermined = latest.stream()
                .filter(ri -> EvaluationRunItem.VERDICT_UNDETERMINED.equals(ri.getVerdict())).count();
        long needReview = latest.stream().filter(this::needsReview).count();
        Long duration = durationSeconds(run);

        StringBuilder sb = new StringBuilder();
        sb.append("# 评测验收报告\n\n");
        sb.append("- 批次：").append(run.getId()).append('\n');
        sb.append("- 题集版本：v").append(run.getDatasetVersion()).append('\n');
        sb.append("- 分身：").append(run.getSkillId()).append('\n');
        sb.append("- 用途：").append(run.getPurpose()).append('\n');
        sb.append("- 执行状态：").append(run.getStatus()).append('\n');
        sb.append("- 耗时：").append(duration == null ? "未记录" : duration + " 秒").append('\n');
        sb.append("- 用量：").append(run.getUsageTokens()).append(" tokens / ")
                .append(run.getUsageCost()).append('\n');
        if (run.getScoreAvg() != null) {
            sb.append("- 平均分：").append(run.getScoreAvg()).append('\n');
        }
        sb.append("\n## 结果统计\n\n");
        sb.append("| 总题数 | 执行成功 | 执行错误 | 通过 | 未通过 | 未判定 | 必审题 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        sb.append("| ").append(latest.size()).append(" | ").append(completed).append(" | ").append(error)
                .append(" | ").append(passed).append(" | ").append(failed)
                .append(" | ").append(undetermined).append(" | ").append(needReview).append(" |\n");
        if (retrieval != null) {
            sb.append("\n## 检索指标（仅统计配置了检索期望的题）\n\n");
            sb.append("| 参与题数 | Recall@K | Precision@K | MRR | 全部命中 | 有遗漏 |\n");
            sb.append("|---|---|---|---|---|---|\n");
            sb.append("| ").append(retrieval.items()).append(" | ").append(retrieval.recall())
                    .append(" | ").append(retrieval.precision()).append(" | ").append(retrieval.mrr())
                    .append(" | ").append(retrieval.fullyHit()).append(" | ").append(retrieval.withMiss())
                    .append(" |\n");
            sb.append("\n> 命中判定：金标条目归一化后被任一检回条目的标题/场景标签包含即算命中，")
                    .append("不使用语义相似度（否则未检回金标的题也会显示命中）。K = 本次实际检回条数。\n");
        } else {
            sb.append("\n> 本批次题集未配置 retrievalExpected，不产出召回指标。\n");
        }
        sb.append("\n## 口径说明\n\n");
        sb.append("1. 本题报告只冻结题目版本与分身快照；知识库/经验颗粒未冻结，")
                .append("同一批次重跑可能因内容库变化得到不同结果，不声称严格可复现。\n");
        sb.append("2. 执行错误（error）与业务不通过（fail）分开统计，前者可重试，后者不可通过重试刷分。\n");
        sb.append("3. 原始数据见 summary 字段（").append(summaryJson).append("）。\n");
        return sb.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 对比结果：是否同口径 + 口径说明 + 逐题差异。 */
    public record CompareResult(UUID leftId, UUID rightId, boolean sameCaliber, String caliberNote,
                                int nonCommonItems, List<Map<String, Object>> diffs) {
    }

    /** 批次级检索指标聚合（只统计配置了 retrievalExpected 的题）。 */
    private record RetrievalAggregate(int items, double recall, double precision, double mrr,
                                      int fullyHit, int withMiss) {
    }
}
