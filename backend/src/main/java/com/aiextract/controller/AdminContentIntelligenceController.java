package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ContentGenerationJob;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.ContentGenerationJobRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.KnowledgeGapRepository;
import com.aiextract.service.ContentGenerationService;
import com.aiextract.service.ContentHealthService;
import com.aiextract.service.ContentMetricsService;
import com.aiextract.service.ContentSignalService;
import com.aiextract.service.EvaluationAnalysisService;
import com.aiextract.service.EvaluationRunService;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.dto.EvaluationRunRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内容智能 —— 信号 → AI 草稿 → 审核 → 派生；评测题目分析与回归。
 *
 * <p>生成接口只建任务并立即返回 jobId，AI 在后台线程执行；前端轮询
 * {@code GET /admin/content/jobs/{id}} 获取产出。AI 只出草稿，发布仍走原有审核链路。</p>
 */
@RestController
@RequestMapping("/admin/content")
@RequiredArgsConstructor
public class AdminContentIntelligenceController {

    private final ContentSignalService signalService;
    private final ContentGenerationService generationService;
    private final ContentHealthService healthService;
    private final ContentMetricsService metricsService;
    private final EvaluationAnalysisService analysisService;
    private final EvaluationRunService runService;
    private final ContentGenerationJobRepository jobRepository;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final KnowledgeGapRepository gapRepository;
    private final com.aiextract.repository.EvaluationRunRepository runRepository;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;

    // ==================== 信号与生成 ====================

    @GetMapping("/needs")
    public ApiResponse<ContentSignalService.NeedsView> needs(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(signalService.needs(limit));
    }

    @PostMapping("/generate/knowledge")
    public ApiResponse<Map<String, Object>> generateKnowledge(@RequestBody Map<String, Object> body) {
        UUID jobId = generationService.startKnowledgeGeneration(
                string(body, "sceneTag"), signals(body));
        return ApiResponse.success(jobAccepted(jobId, "knowledge"));
    }

    @PostMapping("/generate/case")
    public ApiResponse<Map<String, Object>> generateCase(@RequestBody Map<String, Object> body) {
        UUID jobId = generationService.startCaseGeneration(
                string(body, "sceneTag"), signals(body), string(body, "conversation"));
        return ApiResponse.success(jobAccepted(jobId, "case"));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<ContentGenerationService.JobView>> jobs(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(generationService.recentJobs(limit));
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<ContentGenerationService.JobDetail> job(@PathVariable UUID id) {
        return ApiResponse.success(generationService.jobDetail(id));
    }

    @PostMapping("/derive")
    public ApiResponse<Map<String, Object>> derive(@RequestBody Map<String, Object> body) {
        String type = string(body, "sourceType");
        String id = string(body, "sourceId");
        if (type == null || id == null) {
            throw new BusinessException(400, "缺少 sourceType 或 sourceId");
        }
        if (!"knowledge".equals(type) && !"case".equals(type)) {
            throw new BusinessException(400, "sourceType 只能是 knowledge 或 case");
        }
        UUID jobId = generationService.startDerive(type, UUID.fromString(id));
        return ApiResponse.success(jobAccepted(jobId, "derive"));
    }

    // ==================== 体检 ====================

    @GetMapping("/health/knowledge/{id}")
    public ApiResponse<ContentHealthService.HealthView> knowledgeHealth(@PathVariable UUID id) {
        return ApiResponse.success(healthService.knowledgeHealth(id));
    }

    @GetMapping("/health/case/{id}")
    public ApiResponse<ContentHealthService.HealthView> caseHealth(@PathVariable UUID id) {
        return ApiResponse.success(healthService.caseHealth(id));
    }

    // ==================== 看板 ====================

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        UUID companyId = accessService.requireCompanyId();
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<ContentGenerationJob> jobs = jobRepository
                .findByCompanyIdAndCreatedAtAfterOrderByCreatedAtDesc(companyId, since);
        long produced = jobs.stream()
                .mapToLong(j -> j.getProducedCount() == null ? 0 : j.getProducedCount()).sum();
        long done = jobs.stream()
                .filter(j -> ContentGenerationJob.STATUS_DONE.equals(j.getStatus())).count();
        long failed = jobs.stream()
                .filter(j -> ContentGenerationJob.STATUS_FAILED.equals(j.getStatus())).count();

        long pendingKnowledge = knowledgeRepository.search(false, companyId, null, false,
                        null, null, KnowledgeEntry.STATUS_PENDING, null, false, false, false,
                        PageRequest.of(0, 1)).getTotalElements();
        long pendingCases = caseRepository.search(false, companyId, null, false,
                        null, null, null, "pending", null, false, false, false,
                        PageRequest.of(0, 1)).getTotalElements();
        Set<UUID> spaceIds = companyScopeService.getSpaceIds(companyId);
        long openGaps = spaceIds.isEmpty() ? 0
                : gapRepository.countBySpaceIdInAndStatus(spaceIds, "open");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", 30);
        result.put("generationJobs", jobs.size());
        result.put("generationDone", done);
        result.put("generationFailed", failed);
        result.put("draftsProduced", produced);
        result.put("pendingKnowledge", pendingKnowledge);
        result.put("pendingCases", pendingCases);
        result.put("openGaps", openGaps);
        result.put("adoption", metricsService.adoption());
        result.put("recentJobs", generationService.recentJobs(10));
        return ApiResponse.success(result);
    }

    // ==================== 评测分析 / 回归 ====================

    @GetMapping("/evaluation/datasets/{id}/analysis")
    public ApiResponse<EvaluationAnalysisService.DatasetAnalysis> datasetAnalysis(
            @PathVariable UUID id) {
        return ApiResponse.success(analysisService.datasetAnalysis(id));
    }

    @PostMapping("/evaluation/datasets/{id}/generate-from-signals")
    public ApiResponse<Map<String, Object>> generateEvalItems(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String scene = string(body, "sceneTag");
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 5;
        UUID jobId = generationService.startEvalItemGeneration(id, scene, limit);
        return ApiResponse.success(jobAccepted(jobId, "eval_item"));
    }

    /** 发起回归评测：默认以上一次已完成批次为基线 */
    @PostMapping("/evaluation/regression")
    public ApiResponse<Map<String, Object>> regression(@RequestBody Map<String, Object> body) {
        String datasetId = string(body, "datasetId");
        String skillId = string(body, "skillId");
        if (datasetId == null || skillId == null) {
            throw new BusinessException(400, "缺少 datasetId 或 skillId");
        }
        UUID baseline = findBaseline(UUID.fromString(datasetId), UUID.fromString(skillId),
                string(body, "baselineRunId"));
        EvaluationRunRequest req = new EvaluationRunRequest();
        req.setDatasetId(UUID.fromString(datasetId));
        req.setSkillId(UUID.fromString(skillId));
        // 用途沿用题集配置（createRun 会校验 purpose 与题集一致）
        // 幂等键按小时分桶：双击/网络重试只建一个批次，隔一段时间再跑仍是新任务
        req.setIdempotencyKey("regression:" + datasetId + ":" + skillId + ":"
                + (baseline == null ? "none" : baseline) + ":"
                + LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS));
        EvaluationRun run = runService.createRun(req);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId().toString());
        result.put("status", run.getStatus());
        result.put("baselineRunId", baseline == null ? null : baseline.toString());
        result.put("message", baseline == null
                ? "已发起评测；该分身暂无历史基线，本次将作为基线"
                : "已发起回归评测，完成后可对比基线");
        return ApiResponse.success(result);
    }

    @GetMapping("/evaluation/runs/{id}/diff")
    public ApiResponse<EvaluationAnalysisService.RunDiff> diff(
            @PathVariable UUID id,
            @RequestParam UUID datasetId,
            @RequestParam(required = false) UUID baselineRunId) {
        UUID baseline = baselineRunId != null ? baselineRunId
                : findBaseline(datasetId, null, null, id);
        if (baseline == null) {
            throw new BusinessException(409, "没有可对比的历史批次");
        }
        return ApiResponse.success(analysisService.diffRuns(datasetId, baseline, id));
    }

    @PostMapping("/evaluation/runs/{id}/promote-issues")
    public ApiResponse<Map<String, Object>> promoteIssues(@PathVariable UUID id) {
        UUID jobId = generationService.startPromoteIssues(id);
        return ApiResponse.success(jobAccepted(jobId, "promote_issues"));
    }

    // ==================== 内部 ====================

    private Map<String, Object> jobAccepted(UUID jobId, String kind) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId.toString());
        result.put("kind", kind);
        result.put("status", ContentGenerationJob.STATUS_RUNNING);
        result.put("message", "已提交生成任务，完成后可在审核台查看");
        return result;
    }

    private UUID findBaseline(UUID datasetId, UUID skillId, String explicit) {
        return findBaseline(datasetId, skillId, explicit, null);
    }

    /** @param excludeRunId 对比场景要排除当前批次，否则会拿自己当基线返回空 diff */
    private UUID findBaseline(UUID datasetId, UUID skillId, String explicit, UUID excludeRunId) {
        if (explicit != null && !explicit.isBlank()) {
            return UUID.fromString(explicit);
        }
        UUID companyId = accessService.requireCompanyId();
        return runRepository.findByCompanyIdAndDatasetIdAndStatusInOrderByFinishedAtDesc(
                        companyId, datasetId, List.of(EvaluationRun.STATUS_COMPLETED,
                                EvaluationRun.STATUS_PARTIAL_FAILED), PageRequest.of(0, 20)).stream()
                .filter(r -> skillId == null || skillId.equals(r.getSkillId()))
                .filter(r -> excludeRunId == null || !excludeRunId.equals(r.getId()))
                .findFirst().map(EvaluationRun::getId).orElse(null);
    }

    private String string(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    private List<String> signals(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("signals");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }
}
