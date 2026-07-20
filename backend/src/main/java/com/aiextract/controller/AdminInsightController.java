package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin 分身数据洞察 —— 提供仪表盘所需的聚合查询。
 *
 * <p>Phase 1 提供基础 API，Phase 2 前端接入图表展示。</p>
  * @author AI Extract Team
 */
@Slf4j
@RestController
@RequestMapping("/admin/insights")
@RequiredArgsConstructor
public class AdminInsightController {
    private static final String KEY_NOTE = "note";


    private final ConversationStatsRepository conversationStatsRepository;
    private final FeedbackLogRepository feedbackLogRepository;
    private final GrainRetrieveLogRepository grainRetrieveLogRepository;
    private final KnowledgeGapRepository knowledgeGapRepository;
    private final com.aiextract.repository.ExperienceGrainRepository grainRepository;
    private final com.aiextract.repository.SkillRepository skillRepository;
    private final com.aiextract.service.AdminInsightService adminInsightService;
    private final com.aiextract.repository.AutoInsightRepository autoInsightRepository;
    private final com.aiextract.repository.CandidateGrainRepository candidateGrainRepository;

    /**
     * 分身使用概览 — 对话量 / 用户数 / 满意率。
     */
    @GetMapping("/{skillId}/overview")
    public ApiResponse<Map<String, Object>> getOverview(@PathVariable String skillId) {
        UUID id = UUID.fromString(skillId);
        skillRepository.findById(id).orElseThrow(() ->
            new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));

        // 对话量 + 用户数（从 conversation_stats 聚合）
        List<Object[]> stats = conversationStatsRepository
            .statsOverview(id, java.time.LocalDateTime.now().minusDays(7), java.time.LocalDateTime.now());
        long conversations = stats.isEmpty() || stats.get(0)[0] == null ? 0 : (Long) stats.get(0)[0];
        long activeUsers = stats.isEmpty() || stats.get(0)[1] == null ? 0 : (Long) stats.get(0)[1];

        // 满意率（从 feedback_log 聚合）
        List<Object[]> satStats = feedbackLogRepository.satisfactionStats(id);
        long upCount = 0, totalFeedback = 0;
        if (!satStats.isEmpty() && satStats.get(0)[0] != null) {
            upCount = (Long) satStats.get(0)[0];
            totalFeedback = (Long) satStats.get(0)[1];
        }
        double satisfactionRate = totalFeedback > 0 ? (double) upCount / totalFeedback * 100 : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversations", conversations);
        result.put("activeUsers", activeUsers);
        result.put("satisfactionRate", Math.round(satisfactionRate * 10.0) / 10.0);
        result.put("totalFeedback", totalFeedback);
        return ApiResponse.success(result);
    }

    /**
     * 场景提问 TOP5 — 从检索日志按场景标签聚合。
     */
    @GetMapping("/{skillId}/scene-top")
    public ApiResponse<List<Map<String, Object>>> getSceneTop(@PathVariable String skillId) {
        UUID id = UUID.fromString(skillId);
        List<Object[]> rows = grainRetrieveLogRepository.countBySkillIdGroupBySceneTag(id);
        List<Map<String, Object>> result = rows.stream().limit(5).map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", row[0] != null ? row[0] : "未知");
            m.put("count", row[1]);
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * RAG 匹配分布 — 高匹配 / 参考 / 无匹配 合计。
     */
    @GetMapping("/{skillId}/rag-distribution")
    public ApiResponse<Map<String, Object>> getRagDistribution(@PathVariable String skillId) {
        UUID id = UUID.fromString(skillId);
        List<Object[]> rows = conversationStatsRepository.ragDistribution(id);
        long high = 0, ref = 0, none = 0;
        if (!rows.isEmpty() && rows.get(0)[0] != null) {
            high = (Long) rows.get(0)[0];
            ref = rows.get(0)[1] != null ? (Long) rows.get(0)[1] : 0;
            none = rows.get(0)[2] != null ? (Long) rows.get(0)[2] : 0;
        }
        long total = high + ref + none;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("high", high);
        result.put("ref", ref);
        result.put("none", none);
        result.put("total", total);
        result.put("highPct", total > 0 ? Math.round((double) high / total * 100) : 0);
        result.put("refPct", total > 0 ? Math.round((double) ref / total * 100) : 0);
        result.put("nonePct", total > 0 ? Math.round((double) none / total * 100) : 0);
        return ApiResponse.success(result);
    }

    /**
     * 👍/👎 颗粒排行榜。
     *
     * @param sort best=👍最多, worst=👎最多
     */
    @GetMapping("/{skillId}/grains-top")
    public ApiResponse<List<Map<String, Object>>> getGrainsTop(
            @PathVariable String skillId, @RequestParam(defaultValue = "best") String sort) {
        UUID id = UUID.fromString(skillId);
        var skill = skillRepository.findById(id).orElseThrow(() ->
            new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        List<com.aiextract.model.ExperienceGrain> grains = "best".equals(sort)
            ? grainRepository.findTopBySpaceIdOrderByHelpfulCountDesc(
                skill.getSpaceId(), org.springframework.data.domain.PageRequest.of(0, 10))
            : grainRepository.findTopBySpaceIdOrderByUnhelpfulCountDesc(
                skill.getSpaceId(), org.springframework.data.domain.PageRequest.of(0, 10));

        List<Map<String, Object>> result = grains.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId().toString());
            m.put("description", g.getSceneDescription() != null ? g.getSceneDescription()
                : (g.getSceneTag() != null ? g.getSceneTag() : "未命名"));
            m.put("sceneTag", g.getSceneTag());
            m.put("helpful", g.getHelpfulCount());
            m.put("unhelpful", g.getUnhelpfulCount());
            m.put("qualityScore", g.getQualityScore());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 知识缺口列表 — 按次数降序。
     */
    @GetMapping("/{skillId}/knowledge-gaps")
    public ApiResponse<List<Map<String, Object>>> getKnowledgeGaps(@PathVariable String skillId) {
        UUID id = UUID.fromString(skillId);
        List<com.aiextract.model.KnowledgeGap> gaps = knowledgeGapRepository
            .findBySkillIdAndStatusOrderByAttemptedQueryCountDesc(id, "open");
        List<Map<String, Object>> result = gaps.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId().toString());
            m.put("query", g.getQuery());
            m.put("sceneTag", g.getSceneTag());
            m.put("count", g.getAttemptedQueryCount());
            m.put("lastSeen", g.getUpdatedAt() != null ? g.getUpdatedAt().toString() : null);
            m.put("status", g.getStatus());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 反馈审查列表 —— 分页查询用户打分记录。
     * 支持按 rating 筛选（up/down），默认全部。
     */
    @GetMapping("/{skillId}/feedback-logs")
    public ApiResponse<Map<String, Object>> getFeedbackLogs(
            @PathVariable String skillId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String rating) {
        UUID id = UUID.fromString(skillId);
        org.springframework.data.domain.Pageable pageable =
            org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<com.aiextract.model.FeedbackLog> logs;
        if (rating != null && !rating.isEmpty()) {
            logs = feedbackLogRepository.findBySkillIdAndRatingOrderByCreatedAtDesc(id, rating, pageable);
        } else {
            logs = feedbackLogRepository.findBySkillIdOrderByCreatedAtDesc(id, pageable);
        }
        List<Map<String, Object>> content = logs.getContent().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId().toString());
            m.put("rating", f.getRating());
            m.put("query", f.getQuery());
            m.put("aiResponse", f.getAiResponse());
            m.put("ragScore", f.getRagScore());
            m.put("grainId", f.getGrainId() != null ? f.getGrainId().toString() : null);
            m.put("createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", logs.getTotalElements());
        result.put("totalPages", logs.getTotalPages());
        return ApiResponse.success(result);
    }

    /**
     * 处理知识缺口 —— 标记为 resolved 或 ignored。
     * Admin 查看高频缺口后，可通过此接口更新状态。
     */
    @PutMapping("/knowledge-gaps/{gapId}")
    public ApiResponse<Void> resolveKnowledgeGap(
            @PathVariable String gapId, @RequestBody Map<String, String> body) {
        com.aiextract.model.KnowledgeGap gap = knowledgeGapRepository
            .findById(UUID.fromString(gapId))
            .orElseThrow(() -> new BusinessException(404, "缺口不存在"));
        String newStatus = body.getOrDefault("status", "resolved");
        gap.setStatus(newStatus);
        if (body.containsKey(KEY_NOTE)) { gap.setNote(body.get(KEY_NOTE)); }
        gap.setResolvedAt(LocalDateTime.now());
        knowledgeGapRepository.save(gap);
        log.info("缺口已处理 gapId={} status={}", gapId, newStatus);
        return ApiResponse.success(null);
    }

    /**
     * 对话抽样 —— 随机取 20 条对话统计，管理员逐条审查回答质量。
     * 过滤测试对话，按创建时间降序取最近 200 条中随机选 20 条。
     */
    @GetMapping("/{skillId}/random-sample")
    public ApiResponse<List<Map<String, Object>>> getRandomSample(@PathVariable String skillId) {
        UUID id = UUID.fromString(skillId);
        // 从最近 200 条中随机取 20 条
        var pageable = org.springframework.data.domain.PageRequest.of(0, 200);
        var stats = conversationStatsRepository.findBySkillIdAndIsTestFalse(id, pageable);
        List<com.aiextract.model.ConversationStats> list = new ArrayList<>(stats.getContent());
        java.util.Collections.shuffle(list);
        List<Map<String, Object>> result = list.stream().limit(20).map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId().toString());
            m.put("conversationId", s.getConversationId().toString());
            m.put("mode", s.getMode());
            m.put("ragAvgSimilarity", s.getRagAvgSimilarity());
            m.put("errorType", s.getErrorType());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    // ============================================================
    // 全局数据看板 v1
    // ============================================================

    /**
     * 全局数据看板 —— 跨所有分身聚合汇总 + 分身健康度对比表。
     * 管理员每天一眼看清平台整体运行状态。
     */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getGlobalOverview() {
        return ApiResponse.success(adminInsightService.getGlobalOverview());
    }

    // ============================================================
    // 自动发现引擎 v1
    // ============================================================

    /**
     * 自动发现洞察列表 —— 支持按分身、严重程度、状态筛选。
     */
    @GetMapping("/discoveries")
    public ApiResponse<List<Map<String, Object>>> getDiscoveries(
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        List<com.aiextract.model.AutoInsight> insights;

        if (skillId != null && !skillId.isEmpty()) {
            UUID sid = UUID.fromString(skillId);
            if (severity != null && !severity.isEmpty()) {
                insights = autoInsightRepository.findBySkillIdAndSeverityAndStatusOrderByCreatedAtDesc(
                    sid, severity, status, pageable);
            } else {
                insights = autoInsightRepository.findBySkillIdAndStatusOrderByCreatedAtDesc(
                    sid, status, pageable);
            }
        } else {
            if (severity != null && !severity.isEmpty()) {
                insights = autoInsightRepository.findBySeverityAndStatusOrderByCreatedAtDesc(
                    severity, status, pageable);
            } else {
                insights = autoInsightRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
            }
        }

        List<Map<String, Object>> result = insights.stream().map(this::toInsightMap).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 洞察详情 —— 包含关联的候选颗粒。
     */
    @GetMapping("/discoveries/{id}")
    public ApiResponse<Map<String, Object>> getDiscoveryDetail(@PathVariable String id) {
        UUID insightId = UUID.fromString(id);
        var insight = autoInsightRepository.findById(insightId)
            .orElseThrow(() -> new BusinessException(404, "洞察不存在"));

        Map<String, Object> result = toInsightMap(insight);

        // 关联的候选颗粒
        if (insight.getCandidateGrainId() != null) {
            candidateGrainRepository.findById(insight.getCandidateGrainId())
                .ifPresent(grain -> result.put("candidateGrain", toCandidateGrainMap(grain)));
        }

        // 同洞察产生的所有候选颗粒
        List<com.aiextract.model.CandidateGrain> grains = candidateGrainRepository
            .findBySourceInsightId(insightId);
        result.put("candidateGrains", grains.stream().map(this::toCandidateGrainMap).collect(Collectors.toList()));

        return ApiResponse.success(result);
    }

    /**
     * 审核通过候选颗粒 —— 管理员确认 AI 发现的知识正确，将其写入 experience_grain 并触发向量化。
     */
    @PostMapping("/candidate-grains/{id}/approve")
    public ApiResponse<Map<String, Object>> approveCandidateGrain(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        UUID grainId = UUID.fromString(id);
        var candidate = candidateGrainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404, "候选颗粒不存在"));

        if (!CandidateGrain.STATUS_PENDING_REVIEW.equals(candidate.getStatus())) {
            throw new BusinessException(400, "只能审核待处理的候选颗粒，当前状态: " + candidate.getStatus());
        }

        // 标记为已通过（实际写入 experience_grain + 向量化由 AutoInsightScheduler 或 AdminGrainService 处理）
        candidate.setStatus(com.aiextract.model.CandidateGrain.STATUS_APPROVED);
        candidate.setReviewedAt(LocalDateTime.now());
        if (body != null && body.containsKey(KEY_NOTE)) {
            candidate.setNote(body.get(KEY_NOTE));
        }
        candidateGrainRepository.save(candidate);

        log.info("候选颗粒已审核通过 grainId={} sceneTag={}", id, candidate.getSceneTag());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", candidate.getId().toString());
        result.put("status", candidate.getStatus());
        result.put("message", "候选颗粒已审核通过，将由调度器写入 experience_grain 并完成向量化");
        return ApiResponse.success(result);
    }

    /**
     * 拒绝候选颗粒 —— 管理员认为 AI 发现的知识不正确或不适用。
     */
    @PostMapping("/candidate-grains/{id}/reject")
    public ApiResponse<Map<String, Object>> rejectCandidateGrain(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        UUID grainId = UUID.fromString(id);
        var candidate = candidateGrainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404, "候选颗粒不存在"));

        if (!CandidateGrain.STATUS_PENDING_REVIEW.equals(candidate.getStatus())) {
            throw new BusinessException(400, "只能审核待处理的候选颗粒，当前状态: " + candidate.getStatus());
        }

        candidate.setStatus(com.aiextract.model.CandidateGrain.STATUS_REJECTED);
        candidate.setReviewedAt(LocalDateTime.now());
        candidate.setNote(body != null ? body.getOrDefault("note", "") : "");
        candidateGrainRepository.save(candidate);

        log.info("候选颗粒已拒绝 grainId={} sceneTag={} note={}", id, candidate.getSceneTag(),
            body != null ? body.get(KEY_NOTE) : "");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", candidate.getId().toString());
        result.put("status", candidate.getStatus());
        return ApiResponse.success(result);
    }

    // ============================================================
    // helper
    // ============================================================

    private Map<String, Object> toInsightMap(com.aiextract.model.AutoInsight i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId().toString());
        m.put("skillId", i.getSkillId() != null ? i.getSkillId().toString() : null);
        m.put("type", i.getType());
        m.put("title", i.getTitle());
        m.put("description", i.getDescription());
        m.put("severity", i.getSeverity());
        m.put("evidence", i.getEvidence());
        m.put("candidateGrainId", i.getCandidateGrainId() != null ? i.getCandidateGrainId().toString() : null);
        m.put("status", i.getStatus());
        m.put("createdAt", i.getCreatedAt() != null ? i.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toCandidateGrainMap(com.aiextract.model.CandidateGrain g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId().toString());
        m.put("skillId", g.getSkillId() != null ? g.getSkillId().toString() : null);
        m.put("sceneTag", g.getSceneTag());
        m.put("sceneDescription", g.getSceneDescription());
        m.put("expertThought", g.getExpertThought());
        m.put("standardScript", g.getStandardScript());
        m.put("commonMistakes", g.getCommonMistakes());
        m.put("applicableCondition", g.getApplicableCondition());
        m.put("sourceInsightId", g.getSourceInsightId().toString());
        m.put("sourceEvidence", g.getSourceEvidence());
        m.put("status", g.getStatus());
        m.put("reviewedAt", g.getReviewedAt() != null ? g.getReviewedAt().toString() : null);
        m.put(KEY_NOTE, g.getNote());
        m.put("createdAt", g.getCreatedAt() != null ? g.getCreatedAt().toString() : null);
        return m;
    }

}
