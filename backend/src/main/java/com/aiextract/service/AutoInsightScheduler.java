package com.aiextract.service;

import com.aiextract.config.PromptLoader;
import com.aiextract.model.AutoInsight;
import com.aiextract.model.CandidateGrain;
import com.aiextract.model.KnowledgeGap;
import com.aiextract.repository.AutoInsightRepository;
import com.aiextract.repository.CandidateGrainRepository;
import com.aiextract.repository.KnowledgeGapRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 自动发现引擎调度器 —— 每天凌晨 3 点从知识缺口和反馈数据中自动发现规律。
 *
 * <p>流程：
 * <ol>
 *   <li>扫描未向量化的 open knowledge_gap → DashScope embed → 写入 pgvector</li>
 *   <li>高频缺口（attempt ≥ 阈值）→ 聚类 → LLM 命名 + 生成洞察</li>
 *   <li>洞察类型为 new_pattern → LLM 生成候选颗粒（candidate_grain）</li>
 * </ol>
 *
 * <p>设计模式参照 {@link ExpertAnalysisScheduler}：
 * {@code @Scheduled} + {@code @Lazy @Autowired self} 代理
 * + {@code REQUIRES_NEW} 短事务。
 *
 * @author AI Extract Team
 * @since 2026-07-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoInsightScheduler {

    private final KnowledgeGapRepository knowledgeGapRepository;
    private final AutoInsightRepository autoInsightRepository;
    private final CandidateGrainRepository candidateGrainRepository;
    private final DashScopeEmbeddingService embeddingService;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private AutoInsightScheduler self;

    /** 每批处理的缺口数（嵌入） */
    @Value("${app.insight.embed-batch-size:20}")
    private int embedBatchSize;

    /** 聚类余弦距离阈值（< 此值视为同类） */
    @Value("${app.insight.cluster-threshold:0.15}")
    private double clusterThreshold;

    /** 触发洞察的最低尝试次数 */
    @Value("${app.insight.min-attempts:5}")
    private int minAttempts;

    // ==================== 主调度入口 ====================

    /**
     * 每天凌晨 3:00 执行自动发现引擎。
     * 分两步：① 嵌入未向量化的缺口 ② 聚类 + 生成洞察。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void runDiscoveryPipeline() {
        log.info("=== 自动发现引擎启动 ===");
        long start = System.currentTimeMillis();

        try {
            // 第一步：嵌入
            self.embedPendingGaps();

            // 第二步：聚类 + 生成洞察
            self.clusterAndGenerateInsights();

            log.info("=== 自动发现引擎完成, 耗时 {}ms ===", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("自动发现引擎异常", e);
        }
    }

    // ==================== 第一步：嵌入 ====================

    /**
     * 扫描未向量化的 open 缺口，调用 DashScope 嵌入，写入 pgvector。
     * 短事务逐批处理，每批提交一次。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void embedPendingGaps() {
        List<KnowledgeGap> gaps = knowledgeGapRepository
            .findOpenGapsWithoutEmbedding(embedBatchSize);

        if (gaps.isEmpty()) {
            log.info("无需嵌入的缺口");
            return;
        }

        log.info("嵌入缺口 batch size={}", gaps.size());

        // 批量调用 DashScope
        List<String> texts = gaps.stream().map(this::gapToText).collect(Collectors.toList());
        List<float[]> embeddings = embeddingService.embedBatch(texts);

        if (embeddings == null || embeddings.size() != gaps.size()) {
            log.error("嵌入返回数量不匹配 expected={} actual={}", gaps.size(),
                embeddings != null ? embeddings.size() : 0);
            return;
        }

        // 逐条写回 pgvector
        int updated = 0;
        for (int i = 0; i < gaps.size(); i++) {
            if (embeddings.get(i) != null) {
                String vectorStr = floatArrayToPgVector(embeddings.get(i));
                knowledgeGapRepository.updateEmbedding(gaps.get(i).getId(), vectorStr);
                updated++;
            }
        }
        log.info("嵌入完成 updated={}/{}", updated, gaps.size());
    }

    // ==================== 第二步：聚类 + 洞察生成 ====================

    /**
     * 对高频缺口做聚类分析，满足阈值则触发生成洞察。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void clusterAndGenerateInsights() {
        // 获取已嵌入的高频缺口
        List<KnowledgeGap> frequentGaps = knowledgeGapRepository
            .findFrequentGapsWithEmbedding(minAttempts, PageRequest.of(0, 50));

        if (frequentGaps.isEmpty()) {
            log.info("无高频缺口（attempt ≥ {}）", minAttempts);
            return;
        }

        log.info("高频缺口聚类 candidateCount={}", frequentGaps.size());

        // 贪心聚类：遍历缺口，找最近簇
        List<GapCluster> clusters = new ArrayList<>();

        for (KnowledgeGap gap : frequentGaps) {
            // 找最近的簇
            GapCluster bestCluster = null;
            double bestDist = Double.MAX_VALUE;

            for (GapCluster cluster : clusters) {
                for (KnowledgeGap member : cluster.members) {
                    double dist = cosineDistance(gap, member);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestCluster = cluster;
                    }
                }
            }

            if (bestCluster != null && bestDist < clusterThreshold) {
                bestCluster.members.add(gap);
                bestCluster.totalAttempts += gap.getAttemptedQueryCount();
                bestCluster.skillIds.add(gap.getSkillId());
            } else {
                // 新建簇
                GapCluster newCluster = new GapCluster();
                newCluster.members.add(gap);
                newCluster.totalAttempts = gap.getAttemptedQueryCount();
                newCluster.skillIds.add(gap.getSkillId());
                clusters.add(newCluster);
            }
        }

        log.info("聚类完成 clusterCount={}", clusters.size());

        // 对满足阈值的簇生成洞察
        int generated = 0;
        for (GapCluster cluster : clusters) {
            if (cluster.totalAttempts < minAttempts) {

                continue;

            }

            try {
                self.generateInsightForCluster(cluster);
                generated++;
            } catch (Exception e) {
                log.error("为簇生成洞察失败 size={}", cluster.members.size(), e);
            }
        }

        log.info("洞察生成完成 generated={}/{}", generated, clusters.size());
    }

    // ==================== 洞察 + 候选颗粒生成 ====================

    /**
     * 为单个簇调用 LLM 生成洞察标题和描述。
     * 如果洞察类型为 new_pattern，额外生成候选颗粒。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void generateInsightForCluster(GapCluster cluster) {
        // 1. 构建 LLM prompt
        String queriesText = cluster.members.stream()
            .limit(10)
            .map(g -> "- \"" + truncate(g.getQuery(), 80) + "\" (出现" + g.getAttemptedQueryCount() + "次)")
            .collect(Collectors.joining("\n"));

        String skillIdsText = cluster.skillIds.stream()
            .limit(5)
            .map(UUID::toString)
            .collect(Collectors.joining(","));

        String prompt = promptLoader.format("insight_gap_cluster.md", Map.of(
            "queries", queriesText,
            "total_attempts", String.valueOf(cluster.totalAttempts),
            "member_count", String.valueOf(cluster.members.size()),
            "skill_ids", skillIdsText
        ));

        // 2. LLM 生成洞察
        String llmResponse = chatStreamAdapter.chat(prompt);
        if (llmResponse == null || llmResponse.isBlank()) {
            log.warn("LLM 返回空响应, 跳过此簇");
            return;
        }

        ParsedInsight parsed = parseInsightResponse(llmResponse);
        if (parsed == null) {
            log.warn("解析 LLM 响应失败, 跳过此簇 raw={}", truncate(llmResponse, 200));
            return;
        }

        // 3. 写 auto_insight
        AutoInsight insight = AutoInsight.builder()
            .id(UUID.randomUUID())
            .skillId(cluster.skillIds.isEmpty() ? null : cluster.skillIds.iterator().next())
            .type(parsed.type)
            .title(parsed.title)
            .description(parsed.description)
            .severity(parsed.severity)
            .evidence(buildEvidence(cluster))
            .status(AutoInsight.STATUS_ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();
        autoInsightRepository.save(insight);

        log.info("洞察已生成 id={} type={} title={}", insight.getId(), parsed.type, parsed.title);

        // 4. new_pattern 类型 → 生成候选颗粒
        if (AutoInsight.TYPE_NEW_PATTERN.equals(parsed.type)) {
            self.generateCandidateGrain(insight, cluster);
        }
    }

    /**
     * 为 new_pattern 类型的洞察生成候选颗粒。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void generateCandidateGrain(AutoInsight insight, GapCluster cluster) {
        String sampleQueries = cluster.members.stream()
            .limit(5)
            .map(KnowledgeGap::getQuery)
            .collect(Collectors.joining("\n---\n"));

        String prompt = promptLoader.format("insight_candidate_grain.md", Map.of(
            "insight_title", insight.getTitle(),
            "insight_description", insight.getDescription() != null ? insight.getDescription() : "",
            "sample_queries", sampleQueries,
            "total_attempts", String.valueOf(cluster.totalAttempts)
        ));

        String llmResponse = chatStreamAdapter.chat(prompt);
        if (llmResponse == null || llmResponse.isBlank()) {
            log.warn("候选颗粒 LLM 返回空响应");
            return;
        }

        ParsedCandidateGrain parsed = parseCandidateGrainResponse(llmResponse);
        if (parsed == null) {
            log.warn("解析候选颗粒失败 raw={}", truncate(llmResponse, 200));
            return;
        }

        // 取第一个 skillId 作为候选颗粒的归属
        UUID skillId = cluster.skillIds.isEmpty() ? null : cluster.skillIds.iterator().next();

        // 构建证据 JSON
        String evidence = buildCandidateEvidence(cluster);
        // 推断场景标签
        String sceneTag = inferSceneTag(cluster);

        CandidateGrain grain = CandidateGrain.builder()
            .id(UUID.randomUUID())
            .skillId(skillId)
            .sceneTag(sceneTag)
            .sceneDescription(parsed.sceneDescription)
            .expertThought(parsed.expertThought)
            .standardScript(parsed.standardScript)
            .commonMistakes(parsed.commonMistakes)
            .applicableCondition(parsed.applicableCondition)
            .sourceInsightId(insight.getId())
            .sourceEvidence(evidence)
            .status(CandidateGrain.STATUS_PENDING_REVIEW)
            .createdAt(LocalDateTime.now())
            .build();
        candidateGrainRepository.save(grain);

        // 回写 insight 的 candidateGrainId
        insight.setCandidateGrainId(grain.getId());
        autoInsightRepository.save(insight);

        log.info("候选颗粒已生成 id={} sceneTag={}", grain.getId(), sceneTag);
    }

    // ==================== util ====================

    /** 缺口文本用于嵌入 */
    private String gapToText(KnowledgeGap gap) {
        return (gap.getSceneTag() != null ? gap.getSceneTag() + " " : "")
            + gap.getQuery();
    }

    /** float[] 转 pgvector 字符串格式 '[0.1,0.2,...]' */
    private String floatArrayToPgVector(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {

                sb.append(",");

            }
            sb.append(String.format("%.8f", vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /** 从缺口实体读取 embedding 并计算余弦距离（pgvector，走 Repository native query） */
    private double cosineDistance(KnowledgeGap a, KnowledgeGap b) {
        try {
            Double dist = knowledgeGapRepository.cosineDistance(a.getId(), b.getId());
            return dist != null ? dist : 1.0;
        } catch (Exception e) {
            log.warn("计算余弦距离失败 gapA={} gapB={}", a.getId(), b.getId(), e);
            return 1.0;
        }
    }

    private String buildEvidence(GapCluster cluster) {
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("total_attempts", cluster.totalAttempts);
            ev.put("member_count", cluster.members.size());
            ev.put("skill_ids", cluster.skillIds.stream().map(UUID::toString).collect(Collectors.toList()));
            ev.put("sample_queries", cluster.members.stream().limit(5)
                .map(KnowledgeGap::getQuery).collect(Collectors.toList()));
            ev.put("scene_tags", cluster.members.stream()
                .map(KnowledgeGap::getSceneTag).filter(Objects::nonNull).distinct()
                .collect(Collectors.toList()));
            return objectMapper.writeValueAsString(ev);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String buildCandidateEvidence(GapCluster cluster) {
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("total_attempts", cluster.totalAttempts);
            ev.put("member_count", cluster.members.size());
            ev.put("sample_queries", cluster.members.stream().limit(5)
                .map(KnowledgeGap::getQuery).collect(Collectors.toList()));
            ev.put("source_gap_ids", cluster.members.stream()
                .map(g -> g.getId().toString()).limit(20).collect(Collectors.toList()));
            return objectMapper.writeValueAsString(ev);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** 从簇的成员中推断最可能的场景标签 */
    private String inferSceneTag(GapCluster cluster) {
        return cluster.members.stream()
            .map(KnowledgeGap::getSceneTag)
            .filter(Objects::nonNull)
            .filter(tag -> !tag.isEmpty())
            .reduce((a, b) -> b)
            // 取最后一条（最新缺口）的 sceneTag
            .orElse("未分类");
    }

    /** 解析 LLM 返回的洞察 JSON */
    private ParsedInsight parseInsightResponse(String raw) {
        try {
            // 尝试提取 JSON 块
            String json = extractJson(raw);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            ParsedInsight pi = new ParsedInsight();
            pi.type = getString(map, "type", "new_pattern");
            pi.title = getString(map, "title", "未命名洞察");
            pi.description = getString(map, "description", "");
            pi.severity = getString(map, "severity", "info");
            return pi;
        } catch (Exception e) {
            log.warn("解析洞察 JSON 失败", e);
            return null;
        }
    }

    /** 解析 LLM 返回的候选颗粒 JSON */
    private ParsedCandidateGrain parseCandidateGrainResponse(String raw) {
        try {
            String json = extractJson(raw);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            ParsedCandidateGrain pcg = new ParsedCandidateGrain();
            pcg.sceneDescription = getString(map, "scene_description", "");
            pcg.expertThought = getString(map, "expert_thought", "");
            pcg.standardScript = getString(map, "standard_script", "");
            pcg.commonMistakes = getString(map, "common_mistakes", "");
            pcg.applicableCondition = getString(map, "applicable_condition", "");
            return pcg;
        } catch (Exception e) {
            log.warn("解析候选颗粒 JSON 失败", e);
            return null;
        }
    }

    /** 从 LLM 响应中提取 JSON 块 */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {

            return "";

        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== inner classes ====================

    static class GapCluster {
        List<KnowledgeGap> members = new ArrayList<>();
        int totalAttempts = 0;
        Set<UUID> skillIds = new HashSet<>();
    }

    static class ParsedInsight {
        String type;
        String title;
        String description;
        String severity;
    }

    static class ParsedCandidateGrain {
        String sceneDescription;
        String expertThought;
        String standardScript;
        String commonMistakes;
        String applicableCondition;
    }
}
