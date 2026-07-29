package com.aiextract.service;

import com.aiextract.config.DomainConfig;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.GrainRetrieveLogRepository;
import com.aiextract.repository.KnowledgeGapRepository;
import com.aiextract.repository.SkillMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RAG 检索管线 — 查询改写、pgvector 语义检索、分层标记、知识缺口记录。
 *
 * <p>从 {@link ChatStreamService} 提取，职责内聚在 RAG 全链路：
 * <ul>
 *   <li>查询改写（多轮对话代词还原）</li>
 *   <li>向量检索 + tier 分层（high / ref / none）</li>
 *   <li>检索日志写入</li>
 *   <li>知识缺口自动记录</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-07-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagPipelineService {

    private final GrainRetriever grainRetriever;
    private final GrainRetrieveLogRepository grainRetrieveLogRepository;
    private final KnowledgeGapRepository knowledgeGapRepository;
    private final ExperienceGrainRepository grainRepository;
    private final DomainConfigLoader domainConfigLoader;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final SkillMessageRepository skillMessageRepository;

    @Value("${app.rag.query-rewrite.enabled:true}")
    private boolean ragRewriteEnabled;

    @Value("${app.rag.min-similarity:0.25}")
    private double minSimilarity;

    @Value("${app.rag.hybrid-search.enabled:false}")
    private boolean hybridSearchEnabled;

    // ============================================================
    // 数据传输 record
    // ============================================================

    /**
     * RAG 检索聚合结果。
     *
     * @param grains       检索到的颗粒列表
     * @param tiers        grainId → tier 标记（"high" / "ref"）
     * @param similarities grainId → 相似度分数
     */
    public record GrainResult(List<ExperienceGrain> grains, Map<UUID, String> tiers,
                               Map<UUID, Double> similarities) {}

    /**
     * RAG 检索上下文 — 用于检索日志与知识缺口记录。
     *
     * @param skillId        分身 ID
     * @param conversationId 会话 ID
     * @param originalQuery  用户原始消息
     */
    public record RagContext(UUID skillId, UUID conversationId, String originalQuery) {}

    // ============================================================
    // 查询改写
    // ============================================================

    /**
     * 多轮对话查询改写 — 将缩写/省略/代词还原为独立查询。
     *
     * <p>开关 {@code app.rag.query-rewrite.enabled}（默认 true）。
     * 首轮消息、无历史或开关关闭时直接返回原消息，改写失败时回退原消息。</p>
     *
     * @param message 用户原始消息
     * @param history RAG 历史（最多 12 轮）
     * @param domain  领域 ID
     * @param skillId 分身 ID（日志）
     * @return 改写后的查询，失败回退原始消息
     */
    public String rewriteQuery(String message, String history, String domain, UUID skillId) {
        if (!ragRewriteEnabled || history == null || history.isBlank()) {
            return message;
        }
        String truncated = history.length() > 500
            ? history.substring(history.length() - 500) : history;
        String prompt = promptLoader.format("query_rewrite.md", Map.of(
            "history", truncated, "message", message), domain);
        try {
            long t0 = System.currentTimeMillis();
            String rewritten = chatStreamAdapter.chat(prompt);
            long ms = System.currentTimeMillis() - t0;
            boolean improved = rewritten != null && !rewritten.isBlank() && rewritten.length() < 200;
            String result = improved ? rewritten : message;
            log.info("{{\"event\":\"rag_rewrite\",\"skill_id\":\"{}\",\"original_len\":{},\"rewritten_len\":{},\"improved\":{},\"ms\":{}}}",
                skillId, message.length(), result.length(), improved, ms);
            return result;
        } catch (Exception e) {
            log.warn("RAG查询改写失败，回退原始消息: {}", e.getMessage());
        }
        return message;
    }

    /**
     * 取 DB 最近 12 条消息拼成历史字符串，供查询改写使用。
     *
     * @param convId 会话 ID
     * @return 格式化的对话历史，无历史返回 null
     */
    public String buildRagHistory(UUID convId) {
        try {
            var msgs = skillMessageRepository.findByConversationIdOrderByCreatedAtAsc(convId);
            if (msgs.isEmpty()) {
                return null;
            }
            int end = Math.max(0, msgs.size() - 1);
            int start = Math.max(0, end - 12);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                var m = msgs.get(i);
                sb.append(m.getRole()).append("：");
                String c = m.getContent();
                if (c != null) {
                    sb.append(c.length() > 200 ? c.substring(0, 200) : c);
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("构建RAG历史失败 convId={}: {}", convId, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // 语义检索 + 分层
    // ============================================================

    /**
     * pgvector 语义检索并按领域阈值分层标记。
     *
     * <p>阈值从 {@link DomainConfig.PreCheckConfig} 读取，默认 high ≥ 0.50、ref ≥ 0.30。
     * 每条命中颗粒写检索日志；无匹配时自动记录知识缺口并返回空结果。</p>
     *
     * @param query   改写后查询
     * @param spaceId 空间 ID
     * @param topK    最大返回数
     * @param domain  领域标识
     * @param ragCtx  检索上下文
     * @return 聚合结果，无匹配返回空列表
     */
    public GrainResult retrieveGrainsWithScores(String query, UUID spaceId, int topK,
                                                 String domain, RagContext ragCtx) {
        long tRag = System.currentTimeMillis();
        // P1-9: Hybrid Search 开关 — Dense + Sparse → RRF
        List<GrainRetriever.GrainResult> scored = hybridSearchEnabled
            ? grainRetriever.retrieveHybrid(query, spaceId, topK)
            : grainRetriever.retrieveWithScores(query, spaceId, topK);
        long ragMs = System.currentTimeMillis() - tRag;

        if (scored.isEmpty()) {
            log.info("Step2 RAG无结果 hybrid={}，记录缺口", hybridSearchEnabled);
            writeKnowledgeGap(query, spaceId, ragCtx);
            return new GrainResult(List.of(), Map.of(), Map.of());
        }

        // ── min-similarity 硬拦截：低于阈值的颗粒不进 LLM ──
        List<GrainRetriever.GrainResult> filtered = scored.stream()
                .filter(r -> r.similarity() >= minSimilarity)
                .toList();
        if (filtered.isEmpty()) {
            log.info("Step2 RAG全部低于阈值 minSim={} results={}，记录缺口",
                    minSimilarity, scored.size());
            writeKnowledgeGap(query, spaceId, ragCtx);
            return new GrainResult(List.of(), Map.of(), Map.of());
        }
        if (filtered.size() < scored.size()) {
            log.info("Step2 硬拦截过滤 {}→{} 条 (minSim={})",
                    scored.size(), filtered.size(), minSimilarity);
        }

        double highThreshold = 0.50;
        double refThreshold = 0.30;
        if (domain != null) {
            DomainConfig dc = domainConfigLoader.load(domain);
            if (dc != null && dc.getPrecheck() != null) {
                highThreshold = dc.getPrecheck().getRagHighThreshold();
                refThreshold = dc.getPrecheck().getRagRefThreshold();
            }
        }

        // 先建 similarities map（初始分数）
        Map<UUID, Double> similarities = new java.util.LinkedHashMap<>();
        List<ExperienceGrain> grains = filtered.stream()
            .map(GrainRetriever.GrainResult::grain).collect(Collectors.toList());
        for (var r : filtered) {
            similarities.put(r.grain().getId(), r.similarity());
        }

        // P1-2: sceneTag boost — 必须在 tier 分层之前，否则分数变了标签没变
        boostBySceneTagMatch(query, grains, similarities);

        // tier 分层（使用 boost 后的分数）
        Map<UUID, String> tiers = new java.util.LinkedHashMap<>();
        int pos = 1;
        for (var r : filtered) {
            double sim = similarities.getOrDefault(r.grain().getId(), r.similarity());
            if (sim >= highThreshold) {
                tiers.put(r.grain().getId(), "high");
            } else if (sim >= refThreshold) {
                tiers.put(r.grain().getId(), "ref");
            }
            try {
                grainRetrieveLogRepository.save(com.aiextract.model.GrainRetrieveLog.builder()
                    .id(UUID.randomUUID())
                    .skillId(ragCtx != null ? ragCtx.skillId() : null)
                    .conversationId(ragCtx != null ? ragCtx.conversationId() : UUID.randomUUID())
                    .originalQuery(ragCtx != null ? ragCtx.originalQuery() : null)
                    .rewrittenQuery(query)
                    .grainId(r.grain().getId())
                    .sceneTag(r.grain().getSceneTag())
                    .similarity(sim)
                    .tier(tiers.get(r.grain().getId()))
                    .position(pos++)
                    .createdAt(LocalDateTime.now())
                    .build());
            } catch (Exception e) {
                log.debug("写检索日志失败: {}", e.getMessage());
            }
        }

        // 所有颗粒均低于参考阈值 → 记录缺口
        boolean allBelowRef = tiers.isEmpty();
        if (allBelowRef && !filtered.isEmpty()) {
            double bestSim = filtered.stream().mapToDouble(r ->
                similarities.getOrDefault(r.grain().getId(), r.similarity())).max().orElse(0);
            String matchInfo = String.format("type=all_low_similarity bestSim=%.3f grainCount=%d threshold=%.2f",
                bestSim, filtered.size(), refThreshold);
            log.info("Step2 RAG全部低分匹配 bestSim={} threshold={}，记录缺口", bestSim, refThreshold);
            writeKnowledgeGap(query, spaceId, ragCtx, matchInfo);
        }

        log.info("Step2 RAG检索完成 {}ms topK={} tags={} high={} ref={}",
            ragMs, filtered.size(),
            grains.stream().map(g -> g.getSceneTag()).distinct().limit(5).toList(),
            tiers.values().stream().filter("high"::equals).count(),
            tiers.values().stream().filter("ref"::equals).count());
        return new GrainResult(grains, tiers, similarities);
    }

    /**
     * P1-2: 查询中的关键词匹配到颗粒 sceneTag 时提升相似度。
     * 使用 2-4 字滑动窗口匹配，零 AI 调用延迟。
     */
    private void boostBySceneTagMatch(String query, List<ExperienceGrain> grains,
                                       Map<UUID, Double> similarities) {
        if (query == null || query.isBlank() || grains.isEmpty()) return;
        // 提取 query 中的 2-4 字片段用于匹配
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (int len = 2; len <= 4; len++) {
            for (int i = 0; i <= query.length() - len; i++) {
                tokens.add(query.substring(i, i + len));
            }
        }
        int boosted = 0;
        for (ExperienceGrain g : grains) {
            String tag = g.getSceneTag();
            if (tag == null) continue;
            boolean matched = tokens.stream().anyMatch(tag::contains);
            if (matched) {
                double orig = similarities.getOrDefault(g.getId(), 0.0);
                similarities.put(g.getId(), Math.min(1.0, orig * 1.15));
                boosted++;
            }
        }
        if (boosted > 0) {
            log.debug("sceneTag boost: {}/{} grains boosted", boosted, grains.size());
        }
    }

    /**
     * 多空间语义检索 + 分层标记 — 用于 enterprise chat 和综合分身。
     * 与单 space 版本共享同一套 tier 阈值和检索日志逻辑。
     *
     * @param query    改写后查询
     * @param spaceIds 空间 ID 列表
     * @param topK     最大返回数
     * @param domain   领域标识
     * @param ragCtx   检索上下文（skillId 可为 null，企业调度不关联单个分身）
     */
    public GrainResult retrieveGrainsWithScores(String query, List<UUID> spaceIds, int topK,
                                                 String domain, RagContext ragCtx) {
        long tRag = System.currentTimeMillis();
        // P1-9: multi-space hybrid — 每个 space 独立跑 Dense+Sparse→RRF 后合并
        List<GrainRetriever.GrainResult> scored;
        if (hybridSearchEnabled) {
            scored = java.util.Collections.synchronizedList(new ArrayList<>());
            java.util.List<java.util.concurrent.CompletableFuture<Void>> futs = spaceIds.stream()
                .map(sid -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                    java.util.List<GrainRetriever.GrainResult> r =
                        grainRetriever.retrieveHybrid(query, sid, topK);
                    scored.addAll(r);
                }))
                .toList();
            java.util.concurrent.CompletableFuture.allOf(
                futs.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        } else {
            scored = grainRetriever.retrieveWithScores(query, spaceIds, topK);
        }
        long ragMs = System.currentTimeMillis() - tRag;

        if (scored.isEmpty()) {
            log.info("多空间RAG无结果 hybrid={} spaceIds={} query={}", hybridSearchEnabled,
                    spaceIds.size(), query.substring(0, Math.min(50, query.length())));
            return new GrainResult(List.of(), Map.of(), Map.of());
        }

        // ── min-similarity 硬拦截（与单 space 版本共享） ──
        List<GrainRetriever.GrainResult> filtered = scored.stream()
                .filter(r -> r.similarity() >= minSimilarity)
                .toList();
        if (filtered.isEmpty()) {
            log.info("多空间RAG全部低于阈值 minSim={} results={} spaceIds={}",
                    minSimilarity, scored.size(), spaceIds.size());
            return new GrainResult(List.of(), Map.of(), Map.of());
        }

        // tier 阈值（从 domain config 读取，与单 space 版本共享逻辑）
        double highThreshold = 0.50;
        double refThreshold = 0.30;
        if (domain != null) {
            try {
                DomainConfig dc = domainConfigLoader.load(domain);
                if (dc != null && dc.getPrecheck() != null) {
                    highThreshold = dc.getPrecheck().getRagHighThreshold();
                    refThreshold = dc.getPrecheck().getRagRefThreshold();
                }
            } catch (Exception e) {
                log.debug("加载领域配置失败，使用默认阈值 domain={}", domain);
            }
        }

        // 先建 similarities map + grains 列表
        Map<UUID, Double> similarities = new java.util.LinkedHashMap<>();
        List<ExperienceGrain> grains = filtered.stream()
                .map(GrainRetriever.GrainResult::grain).collect(Collectors.toList());
        for (var r : filtered) {
            similarities.put(r.grain().getId(), r.similarity());
        }

        // P1-2: boost 必须在 tier 分层之前
        boostBySceneTagMatch(query, grains, similarities);

        // tier 分层（使用 boost 后的分数）
        Map<UUID, String> tiers = new java.util.LinkedHashMap<>();
        int pos = 1;
        for (var r : filtered) {
            double sim = similarities.getOrDefault(r.grain().getId(), r.similarity());
            if (sim >= highThreshold) {
                tiers.put(r.grain().getId(), "high");
            } else if (sim >= refThreshold) {
                tiers.put(r.grain().getId(), "ref");
            }
            try {
                grainRetrieveLogRepository.save(com.aiextract.model.GrainRetrieveLog.builder()
                        .id(UUID.randomUUID())
                        .skillId(ragCtx != null ? ragCtx.skillId() : null)
                        .conversationId(ragCtx != null ? ragCtx.conversationId() : UUID.randomUUID())
                        .originalQuery(ragCtx != null ? ragCtx.originalQuery() : null)
                        .rewrittenQuery(query)
                        .grainId(r.grain().getId())
                        .sceneTag(r.grain().getSceneTag())
                        .similarity(sim)
                        .tier(tiers.get(r.grain().getId()))
                        .position(pos++)
                        .createdAt(LocalDateTime.now())
                        .build());
            } catch (Exception e) {
                log.debug("写检索日志失败: {}", e.getMessage());
            }
        }

        log.info("多空间RAG检索完成 {}ms topK={} tags={} high={} ref={}",
                ragMs, filtered.size(),
                grains.stream().map(ExperienceGrain::getSceneTag).distinct().limit(5).collect(Collectors.toList()),
                tiers.values().stream().filter("high"::equals).count(),
                tiers.values().stream().filter("ref"::equals).count());
        return new GrainResult(grains, tiers, similarities);
    }

    // ============================================================
    // 知识缺口
    // ============================================================

    /**
     * 写入知识缺口 — 记录 RAG 覆盖盲区。
     *
     * <p>过滤短消息（"继续""嗯"等 ≤5 字），按 skill+scene 统计累计次数。</p>
     *
     * @param query   原始查询
     * @param spaceId 空间 ID
     * @param ragCtx  检索上下文
     */
    public void writeKnowledgeGap(String query, UUID spaceId, RagContext ragCtx) {
        writeKnowledgeGap(query, spaceId, ragCtx, null);
    }

    /**
     * 写入知识缺口（含匹配质量元数据）。
     *
     * @param query     原始查询
     * @param spaceId   空间 ID
     * @param ragCtx    检索上下文
     * @param matchInfo 匹配质量信息，null 表示 RAG 零结果；非 null 表示有结果但全部低分
     */
    public void writeKnowledgeGap(String query, UUID spaceId, RagContext ragCtx, String matchInfo) {
        if (query == null || query.trim().length() < 5) {
            return;
        }
        try {
            String sceneTag = guessSceneTag(query, spaceId);
            long prevCount = knowledgeGapRepository.countBySkillIdAndSceneTag(
                ragCtx != null ? ragCtx.skillId() : null, sceneTag);
            knowledgeGapRepository.save(com.aiextract.model.KnowledgeGap.builder()
                .id(UUID.randomUUID())
                .skillId(ragCtx != null ? ragCtx.skillId() : null)
                .spaceId(spaceId)
                .query(query)
                .sceneTag(sceneTag)
                .attemptedQueryCount((int) prevCount + 1)
                .status("open")
                .note(matchInfo)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
            log.info("知识缺口已记录 skillId={} sceneTag={} count={} matchInfo={}",
                ragCtx != null ? ragCtx.skillId() : null, sceneTag, prevCount + 1, matchInfo);
        } catch (Exception e) {
            log.warn("记录知识缺口失败: {}", e.getMessage());
        }
    }

    /**
     * 推测问题场景标签 — RAG 无结果时用关键词匹配空间已有场景。
     */
    private String guessSceneTag(String query, UUID spaceId) {
        try {
            var grains = grainRepository.findBySpaceIdAndStatus(spaceId, "active");
            if (grains.isEmpty()) {
                return null;
            }
            Set<String> tags = grains.stream()
                .map(g -> g.getSceneTag())
                .filter(t -> t != null && !t.isEmpty())
                .collect(Collectors.toSet());
            for (String tag : tags) {
                if (query.contains(tag)) {
                    return tag;
                }
            }
            return grains.stream()
                .collect(Collectors.groupingBy(
                    g -> g.getSceneTag() != null ? g.getSceneTag() : "通用", Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
