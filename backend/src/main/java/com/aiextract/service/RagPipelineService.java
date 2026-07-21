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
        List<GrainRetriever.GrainResult> scored = grainRetriever.retrieveWithScores(query, spaceId, topK);
        long ragMs = System.currentTimeMillis() - tRag;

        if (scored.isEmpty()) {
            log.info("Step2 RAG无结果，记录缺口");
            writeKnowledgeGap(query, spaceId, ragCtx);
            return new GrainResult(List.of(), Map.of(), Map.of());
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

        Map<UUID, String> tiers = new java.util.LinkedHashMap<>();
        Map<UUID, Double> similarities = new java.util.LinkedHashMap<>();
        int pos = 1;
        for (var r : scored) {
            similarities.put(r.grain().getId(), r.similarity());
            if (r.similarity() >= highThreshold) {
                tiers.put(r.grain().getId(), "high");
            } else if (r.similarity() >= refThreshold) {
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
                    .similarity(r.similarity())
                    .tier(tiers.get(r.grain().getId()))
                    .position(pos++)
                    .createdAt(LocalDateTime.now())
                    .build());
            } catch (Exception e) {
                log.debug("写检索日志失败: {}", e.getMessage());
            }
        }
        List<ExperienceGrain> grains = scored.stream()
            .map(GrainRetriever.GrainResult::grain).collect(Collectors.toList());
        log.info("Step2 RAG检索完成 {}ms topK={} tags={} high={} ref={}",
            ragMs, scored.size(),
            grains.stream().map(g -> g.getSceneTag()).distinct().limit(5).toList(),
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
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
            log.info("知识缺口已记录 skillId={} sceneTag={} count={}",
                ragCtx != null ? ragCtx.skillId() : null, sceneTag, prevCount + 1);
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
