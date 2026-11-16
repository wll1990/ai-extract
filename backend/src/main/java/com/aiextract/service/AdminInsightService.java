package com.aiextract.service;

import com.aiextract.common.PageResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 数据看板聚合 Service —— 批量查询 + 异步并行，避免 N+1 和串行等待。
 * <p>全局看板需要汇总多个数据源（对话统计、反馈、缺口、颗粒），
 * 4 个批量查询互不依赖，用 CompletableFuture 并行执行后汇总。</p>
 */
@Slf4j
@Service
public class AdminInsightService {

    private final ConversationStatsRepository conversationStatsRepository;
    private final FeedbackLogRepository feedbackLogRepository;
    private final KnowledgeGapRepository knowledgeGapRepository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final GrainRetrieveLogRepository grainRetrieveLogRepository;
    private final Executor taskExecutor;
    private final CompanyScopeService companyScopeService;
    private final AnswerCorrectionRepository correctionRepository;

    public AdminInsightService(
            ConversationStatsRepository conversationStatsRepository,
            FeedbackLogRepository feedbackLogRepository,
            KnowledgeGapRepository knowledgeGapRepository,
            ExperienceGrainRepository grainRepository,
            SkillRepository skillRepository,
            GrainRetrieveLogRepository grainRetrieveLogRepository,
            @Qualifier("embeddingExecutor") Executor taskExecutor,
            CompanyScopeService companyScopeService,
            AnswerCorrectionRepository correctionRepository) {
        this.conversationStatsRepository = conversationStatsRepository;
        this.feedbackLogRepository = feedbackLogRepository;
        this.knowledgeGapRepository = knowledgeGapRepository;
        this.grainRepository = grainRepository;
        this.skillRepository = skillRepository;
        this.grainRetrieveLogRepository = grainRetrieveLogRepository;
        this.taskExecutor = taskExecutor;
        this.companyScopeService = companyScopeService;
        this.correctionRepository = correctionRepository;
    }

    /**
     * 保存回答矫正并批量衰减颗粒权重。
     *
     * <p>放在 Service 的事务里：要么矫正记录与全部权重一起成功，要么都不写。</p>
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int applyCorrection(com.aiextract.model.AnswerCorrection correction,
                               List<ExperienceGrain> grains) {
        correctionRepository.save(correction);
        for (ExperienceGrain grain : grains) {
            double oldWeight = grain.getWeight() != null ? grain.getWeight() : 1.0;
            grain.setWeight(Math.max(0.1, oldWeight * 0.7));
            grainRepository.save(grain);
        }
        return grains.size();
    }

    /** 单分身概览（同步，复用现有查询） */
    public Map<String, Object> getSkillOverview(UUID skillId) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {

            return Map.of();

        }

        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime now = LocalDateTime.now();

        List<Object[]> stats = conversationStatsRepository.statsOverview(skillId, weekAgo, now);
        long conversations = stats.isEmpty() || stats.get(0)[0] == null ? 0 : (Long) stats.get(0)[0];
        long users = stats.isEmpty() || stats.get(0)[1] == null ? 0 : (Long) stats.get(0)[1];

        List<Object[]> satStats = feedbackLogRepository.satisfactionStats(skillId);
        long up = 0, totalFeedback = 0;
        if (!satStats.isEmpty() && satStats.get(0)[0] != null) {
            up = (Long) satStats.get(0)[0];
            totalFeedback = (Long) satStats.get(0)[1];
        }
        double satRate = totalFeedback > 0 ? Math.round((double) up / totalFeedback * 1000.0) / 10.0 : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversations", conversations);
        result.put("activeUsers", users);
        result.put("satisfactionRate", satRate);
        result.put("totalFeedback", totalFeedback);
        return result;
    }

    /**
     * 全局数据看板 —— 4 路批量查询并行，汇总后组装。
     * <p>查询计划（4 次 SQL，不随分身数量增长）：</p>
     * <ol>
     *   <li>conversation_stats 按 skillId GROUP BY</li>
     *   <li>feedback_log 按 skillId GROUP BY</li>
     *   <li>knowledge_gap 按 skillId GROUP BY</li>
     *   <li>experience_grain 按 spaceId GROUP BY</li>
     * </ol>
     * 4 个查询抛入 CompletableFuture 并行执行，全部完成后纯内存组装。
     */
    public Map<String, Object> getGlobalOverview(UUID companyId) {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime now = LocalDateTime.now();

        // ① 已发布分身列表（1 次 SQL），按企业过滤
        List<Skill> skills;
        List<UUID> companySkillIds = companyScopeService.getCompanySkillIds(companyId);
        if (!companyScopeService.isUnrestrictedDataScope()) {
            // company_admin — 只取本企业已发布分身
            skills = companySkillIds.isEmpty() ? List.of() : skillRepository.findByIdInAndStatus(companySkillIds, "published");
        } else {
            // super_admin — 全平台
            skills = skillRepository.findByStatus("published");
        }
        List<UUID> skillIds = skills.stream().map(Skill::getId).toList();
        List<UUID> spaceIds = skills.stream().map(Skill::getSpaceId).toList();

        { if (skillIds.isEmpty()) return emptyOverview(); }

        // ② 4 路批量查询并行（显式传入 taskExecutor，不使用 ForkJoinPool）
        CompletableFuture<Map<UUID, Object[]>> statsFuture = CompletableFuture.supplyAsync(() -> {
            Map<UUID, Object[]> map = new HashMap<>();
             for (Object[] row : conversationStatsRepository.batchStatsOverview(skillIds, weekAgo, now)){
                map.put((UUID) row[0], row);
            }
                
            return map;
        }, taskExecutor);

        CompletableFuture<Map<UUID, Object[]>> satFuture = CompletableFuture.supplyAsync(() -> {
            Map<UUID, Object[]> map = new HashMap<>();
            { for (Object[] row : feedbackLogRepository.batchSatisfactionStats(skillIds))
            
                map.put((UUID) row[0], row);}
            return map;
        }, taskExecutor);

        CompletableFuture<Map<UUID, Long>> gapsFuture = CompletableFuture.supplyAsync(() -> {
            Map<UUID, Long> map = new HashMap<>();
            { for (Object[] row : knowledgeGapRepository.countOpenGapsBySkillIds(skillIds))
                 map.put((UUID) row[0], (Long) row[1]);
            }
               
            return map;
        }, taskExecutor);

        CompletableFuture<Map<UUID, Long>> grainsFuture = CompletableFuture.supplyAsync(() -> {
            Map<UUID, Long> map = new HashMap<>();
            { for (Object[] row : grainRepository.countBySpaceIdIn(spaceIds))
                 map.put((UUID) row[0], (Long) row[1]);
            }
               
            return map;
        }, taskExecutor);

        // ③ 等待全部完成（最长路径 = 最慢那路查询，~10-20ms）
        CompletableFuture.allOf(statsFuture, satFuture, gapsFuture, grainsFuture).join();

        Map<UUID, Object[]> statsBySkill = statsFuture.join();
        Map<UUID, Object[]> satBySkill = satFuture.join();
        Map<UUID, Long> gapsBySkill = gapsFuture.join();
        Map<UUID, Long> grainsBySpace = grainsFuture.join();

        // ④ 全局汇总（从批量结果中聚合，不再查 DB）
        long totalConversations = 0, totalUsers = 0, totalHigh = 0, totalRef = 0, totalNone = 0;
        long totalKcKnowledge = 0, totalKcCase = 0;
        for (Object[] ss : statsBySkill.values()) {
            totalConversations += ss[1] != null ? (Long) ss[1] : 0;
            totalUsers += ss[2] != null ? (Long) ss[2] : 0;
            totalHigh += ss[3] != null ? (Long) ss[3] : 0;
            totalRef += ss[4] != null ? (Long) ss[4] : 0;
            totalNone += ss[5] != null ? (Long) ss[5] : 0;
            totalKcKnowledge += ss.length > 7 && ss[7] != null ? (Long) ss[7] : 0;
            totalKcCase += ss.length > 8 && ss[8] != null ? (Long) ss[8] : 0;
        }
        long totalHit = totalHigh + totalRef + totalKcKnowledge + totalKcCase;
        long totalRag = totalHit + totalNone;
        double hitRate = totalRag > 0 ? Math.round((double) totalHit / totalRag * 1000.0) / 10.0 : 0;

        // 全局满意率（也可以从 batch 结果汇总，这里走专用查询更快）
        long totalUp = 0, totalFeedback = 0;
        for (Object[] sat : satBySkill.values()) {
            totalUp += sat[1] != null ? (Long) sat[1] : 0;
            totalFeedback += sat[2] != null ? (Long) sat[2] : 0;
        }
        double satisfactionRate = totalFeedback > 0 ? Math.round((double) totalUp / totalFeedback * 1000.0) / 10.0 : 0;

        long totalGrains = grainsBySpace.values().stream().mapToLong(Long::longValue).sum();
        long totalGaps = gapsBySkill.values().stream().mapToLong(Long::longValue).sum();

        // ⑤ 组装健康度行（纯内存，0 SQL）
        List<Map<String, Object>> skillRows = new ArrayList<>();
        for (var skill : skills) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skillId", skill.getId().toString());
            row.put("name", skill.getOwnerName() != null ? skill.getOwnerName()
                : skill.getDisplayName() != null ? skill.getDisplayName() : "未命名");
            row.put("ownerTitle", skill.getOwnerTitle());
            row.put("department", skill.getDepartment());

            Object[] ss = statsBySkill.get(skill.getId());
            long convs = ss != null && ss[1] != null ? (Long) ss[1] : 0;
            long sHigh = ss != null && ss[3] != null ? (Long) ss[3] : 0;
            long sRef = ss != null && ss[4] != null ? (Long) ss[4] : 0;
            long sNone = ss != null && ss[5] != null ? (Long) ss[5] : 0;
            long sKcKnowledge = ss != null && ss.length > 7 && ss[7] != null ? (Long) ss[7] : 0;
            long sKcCase = ss != null && ss.length > 8 && ss[8] != null ? (Long) ss[8] : 0;
            long sHit = sHigh + sRef + sKcKnowledge + sKcCase;
            long sRagTotal = sHit + sNone;
            String lastActive = ss != null && ss[6] != null ? ss[6].toString().substring(0, 16) : null;
            row.put("conversations", convs);
            row.put("users", ss != null && ss[2] != null ? (Long) ss[2] : 0);
            row.put("hitRate", sRagTotal > 0 ? Math.round((double) sHit / sRagTotal * 100) : 0);
            row.put("lastActive", lastActive);

            Object[] sSat = satBySkill.get(skill.getId());
            long sUp = sSat != null && sSat[1] != null ? (Long) sSat[1] : 0;
            long sTotal = sSat != null && sSat[2] != null ? (Long) sSat[2] : 0;
            row.put("satisfactionRate", sTotal > 0 ? Math.round((double) sUp / sTotal * 1000.0) / 10.0 : 0);

            row.put("openGaps", gapsBySkill.getOrDefault(skill.getId(), 0L));
            row.put("grainCount", grainsBySpace.getOrDefault(skill.getSpaceId(), 0L));

            // 告警
            List<String> alerts = new ArrayList<>();
            if (gapsBySkill.getOrDefault(skill.getId(), 0L) >= 10) alerts.add("缺口爆发"); 
            if (convs == 0) {

                alerts.add("不活跃");

            }
            if (sTotal >= 5 && (double) 

                sUp / sTotal < 0.6) {alerts.add("满意率低");

            }
            if (sRagTotal >= 10 && (double) 

                (sHigh + sRef) / sRagTotal < 0.5){ alerts.add("命中率低");

            }
            row.put("alerts", alerts);

            skillRows.add(row);
        }

        // ⑤ 按对话量降序取 Top 50，避免前端渲染数百张卡片
        skillRows.sort((a, b) -> Long.compare(
            (Long) b.getOrDefault("conversations", 0L),
            (Long) a.getOrDefault("conversations", 0L)));
        List<Map<String, Object>> topSkillRows = skillRows.size() > 50
            ? skillRows.subList(0, 50) : skillRows;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalConversations", totalConversations);
        result.put("activeUsers", totalUsers);
        result.put("satisfactionRate", satisfactionRate);
        result.put("hitRate", hitRate);
        result.put("totalGrains", totalGrains);
        result.put("totalOpenGaps", totalGaps);
        result.put("totalSkills", skills.size());
        result.put("skills", topSkillRows);

        return result;
    }

    /** 场景提问 TOP5 — 检索日志按场景标签聚合 */
    public List<Map<String, Object>> getSceneTop(UUID skillId) {
        List<Object[]> rows = grainRetrieveLogRepository.countBySkillIdGroupBySceneTag(skillId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows.stream().limit(5).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", row[0] != null ? row[0] : "未知");
            m.put("count", row[1]);
            result.add(m);
        }
        return result;
    }

    /** RAG 匹配分布 — 高匹配 / 参考 / 无匹配 合计 */
    public Map<String, Object> getRagDistribution(UUID skillId) {
        List<Object[]> rows = conversationStatsRepository.ragDistribution(skillId);
        long high = 0, ref = 0, none = 0, kcKnowledge = 0, kcCase = 0;
        if (!rows.isEmpty() && rows.get(0)[0] != null) {
            high = (Long) rows.get(0)[0];
            ref = rows.get(0)[1] != null ? (Long) rows.get(0)[1] : 0;
            none = rows.get(0)[2] != null ? (Long) rows.get(0)[2] : 0;
            kcKnowledge = rows.get(0).length > 3 && rows.get(0)[3] != null ? (Long) rows.get(0)[3] : 0;
            kcCase = rows.get(0).length > 4 && rows.get(0)[4] != null ? (Long) rows.get(0)[4] : 0;
        }
        long total = high + ref + kcKnowledge + kcCase + none;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("high", high);
        result.put("ref", ref);
        result.put("kcKnowledge", kcKnowledge);
        result.put("kcCase", kcCase);
        result.put("none", none);
        result.put("total", total);
        result.put("highPct", total > 0 ? Math.round((double) high / total * 100) : 0);
        result.put("refPct", total > 0 ? Math.round((double) ref / total * 100) : 0);
        result.put("kcKnowledgePct", total > 0 ? Math.round((double) kcKnowledge / total * 100) : 0);
        result.put("kcCasePct", total > 0 ? Math.round((double) kcCase / total * 100) : 0);
        result.put("nonePct", total > 0 ? Math.round((double) none / total * 100) : 0);
        return result;
    }

    /** 👍/👎 颗粒排行榜 */
    public List<Map<String, Object>> getGrainsTop(UUID skillId, String sort) {
        Skill skill = skillRepository.findById(skillId).orElseThrow(() ->
                new com.aiextract.exception.BusinessException(404, "分身不存在"));
        PageRequest top = PageRequest.of(0, 10);
        List<ExperienceGrain> grains = "best".equals(sort)
                ? grainRepository.findTopBySpaceIdOrderByHelpfulCountDesc(skill.getSpaceId(), top)
                : grainRepository.findTopBySpaceIdOrderByUnhelpfulCountDesc(skill.getSpaceId(), top);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExperienceGrain g : grains) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId().toString());
            m.put("description", g.getSceneDescription() != null ? g.getSceneDescription()
                    : g.getSceneTag() != null ? g.getSceneTag() : "未命名");
            m.put("sceneTag", g.getSceneTag());
            m.put("helpful", g.getHelpfulCount());
            m.put("unhelpful", g.getUnhelpfulCount());
            m.put("qualityScore", g.getQualityScore());
            result.add(m);
        }
        return result;
    }

    /** 知识缺口列表 — 按次数降序分页 */
    public Map<String, Object> getKnowledgeGaps(UUID skillId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        var gapPage = knowledgeGapRepository
                .findBySkillIdAndStatusOrderByAttemptedQueryCountDesc(skillId, "open", pageable);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var g : gapPage.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId().toString());
            m.put("query", g.getQuery());
            m.put("sceneTag", g.getSceneTag());
            m.put("count", g.getAttemptedQueryCount());
            m.put("lastSeen", g.getUpdatedAt() != null ? g.getUpdatedAt().toString() : null);
            m.put("status", g.getStatus());
            m.put("note", g.getNote());
            result.add(m);
        }
        return PageResponse.of(result, gapPage, page, size);
    }

    private Map<String, Object> emptyOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalConversations", 0);
        result.put("activeUsers", 0);
        result.put("satisfactionRate", 0);
        result.put("hitRate", 0);
        result.put("totalGrains", 0);
        result.put("totalOpenGaps", 0);
        result.put("totalSkills", 0);
        result.put("skills", List.of());
        return result;
    }
}
