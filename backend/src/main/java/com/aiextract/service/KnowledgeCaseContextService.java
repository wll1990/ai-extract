package com.aiextract.service;

import com.aiextract.model.ChatChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识/案例检索结果的「取用与拼装」统一入口。
 *
 * <p>把检索降级、Prompt 上下文拼装、SSE 溯源、历史快照从 {@code ChatStreamService}
 * 抽出来，供分身聊天与对练共用，避免两套文案漂移。</p>
 *
 * <p>安全边界：对练「客户角色」只能用 {@link #buildCustomerRoleContext}，
 * 它刻意不输出关键动作/话术/可复用点/失败点，防止把标准答案喂给扮演客户的模型。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCaseContextService {

    private static final KnowledgeCaseRetriever.KnowledgeCaseHits EMPTY =
            new KnowledgeCaseRetriever.KnowledgeCaseHits(List.of(), List.of());

    private final KnowledgeCaseRetriever knowledgeCaseRetriever;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.kc-top-k:3}")
    private int topK;

    @Value("${app.rag.kc-knowledge-min-similarity:0.45}")
    private double knowledgeMinSimilarity;

    @Value("${app.rag.kc-case-min-similarity:0.50}")
    private double caseMinSimilarity;

    public static KnowledgeCaseRetriever.KnowledgeCaseHits emptyHits() {
        return EMPTY;
    }

    /**
     * 安全检索知识 + 案例：query 为空、无 active 数据、或 embedding 服务异常时返回空命中，
     * 绝不抛出，避免拖垮对话/对练主链路。
     */
    public KnowledgeCaseRetriever.KnowledgeCaseHits retrieveSafely(String query, UUID companyId, String label) {
        if (query == null || query.isBlank()) {
            return EMPTY;
        }
        try {
            if (!knowledgeCaseRetriever.hasAnyActive(companyId)) {
                return EMPTY;
            }
            return knowledgeCaseRetriever.retrieve(query, companyId, topK,
                    knowledgeMinSimilarity, caseMinSimilarity);
        } catch (Exception e) {
            log.warn("知识/案例检索失败({})，降级为空: {}", label, e.getMessage());
            return EMPTY;
        }
    }

    /** 分身聊天：知识事实 + 案例完整打法。 */
    public String buildAssistantContext(KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
        if (isEmpty(hits)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!hits.knowledge().isEmpty()) {
            sb.append("\n\n## 【知识库 · 事实参考】\n")
              .append("以下为企业知识库检索到的权威事实（产品/价格/合规/术语/方法论），可据实引用，但不得与经验区冲突、不得编造数字。\n");
            for (var k : hits.knowledge()) {
                sb.append("- 【").append(k.category()).append("】").append(k.title())
                  .append("：").append(truncate(k.content(), 200)).append("\n");
            }
        }
        appendCases(sb, hits.cases());
        return sb.toString();
    }

    /**
     * 对练客户角色：只输出客户侧信息（画像、情绪、异议、结果）。
     *
     * <p>禁止输出关键动作、话术、可复用点、失败点——这些是学员要做的事。</p>
     */
    public String buildCustomerRoleContext(List<KnowledgeCaseRetriever.CaseHit> cases) {
        if (cases == null || cases.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 客户背景（来自真实案例，仅供你保持人物一致性；绝不透露来源或「案例」二字）\n");
        for (var c : cases) {
            sb.append("- ").append(c.title());
            if (c.sceneTag() != null && !c.sceneTag().isBlank()) sb.append("（").append(c.sceneTag()).append("）");
            if (c.funnelStage() != null && !c.funnelStage().isBlank())
                sb.append("｜阶段：").append(c.funnelStage());
            if (c.customerProfile() != null && !c.customerProfile().isBlank())
                sb.append("｜画像：").append(truncate(c.customerProfile(), 160));
            if (c.emotionChange() != null && !c.emotionChange().isBlank())
                sb.append("｜情绪：").append(truncate(c.emotionChange(), 80));
            if (c.objection() != null && !c.objection().isBlank())
                sb.append("｜你的核心顾虑：").append(truncate(c.objection(), 120));
            sb.append("\n");
        }
        sb.append("你只以客户身份表达上述顾虑与情绪，不要说教、不要替对方总结打法。\n");
        return sb.toString();
    }

    /** 对练逐轮评价：知识事实 + 案例完整打法，作为教练侧参考。 */
    public String buildCoachContext(KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
        if (isEmpty(hits)) {
            return "";
        }
        return buildAssistantContext(hits);
    }

    /** SSE 溯源：每条命中一个 source 事件（sourceType=knowledge/case）。 */
    public Flux<ChatChunk> buildSourceFlux(KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
        List<ChatChunk> chunks = new ArrayList<>();
        for (var k : hits.knowledge()) {
            chunks.add(ChatChunk.knowledgeSource(k.id(), k.title(), k.source(), k.similarity()));
        }
        for (var c : hits.cases()) {
            chunks.add(ChatChunk.caseSource(c.id(), c.title(), c.source(), c.similarity()));
        }
        return chunks.isEmpty() ? Flux.empty() : Flux.fromIterable(chunks);
    }

    /** 对练客户链路只发案例溯源：本链路没有使用知识事实，发了会误导。 */
    public Flux<ChatChunk> buildCaseSourceFlux(List<KnowledgeCaseRetriever.CaseHit> cases) {
        if (cases == null || cases.isEmpty()) {
            return Flux.empty();
        }
        List<ChatChunk> chunks = new ArrayList<>();
        for (var c : cases) {
            chunks.add(ChatChunk.caseSource(c.id(), c.title(), c.source(), c.similarity()));
        }
        return Flux.fromIterable(chunks);
    }

    /**
     * 知识/案例参考快照（持久化到 assistant 消息），用于刷新历史后仍能展示来源。
     * 旧消息没有该字段，前端按空数组兼容。
     */
    public String sourceRefsJson(KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
        if (isEmpty(hits)) {
            return null;
        }
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 1);
            root.put("knowledge", hits.knowledge().stream().map(k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", k.id().toString());
                m.put("title", k.title());
                m.put("source", k.source());
                m.put("similarity", k.similarity());
                return m;
            }).toList());
            root.put("cases", hits.cases().stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.id().toString());
                m.put("title", c.title());
                m.put("source", c.source());
                m.put("similarity", c.similarity());
                return m;
            }).toList());
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("构建知识/案例参考快照失败: {}", e.getMessage());
            return null;
        }
    }

    private void appendCases(StringBuilder sb, List<KnowledgeCaseRetriever.CaseHit> cases) {
        if (cases.isEmpty()) {
            return;
        }
        sb.append("\n\n## 【案例库 · 实战参考】\n")
          .append("以下为真实案例，可引用其过程与结果作为佐证，不编造案例细节。\n");
        for (var c : cases) {
            sb.append("- ").append(c.title());
            if (c.sceneTag() != null && !c.sceneTag().isBlank()) sb.append("（").append(c.sceneTag()).append("）");
            if (c.keyActions() != null && !c.keyActions().isBlank())
                sb.append("｜关键动作：").append(truncate(c.keyActions(), 150));
            if (c.objection() != null && !c.objection().isBlank())
                sb.append("｜核心异议：").append(truncate(c.objection(), 120));
            if (c.scripts() != null && !c.scripts().isBlank())
                sb.append("｜话术：").append(truncate(c.scripts(), 200));
            if (c.reusablePoints() != null && !c.reusablePoints().isBlank())
                sb.append("｜可复用点：").append(truncate(c.reusablePoints(), 150));
            if (c.failurePoints() != null && !c.failurePoints().isBlank())
                sb.append("｜失败点：").append(truncate(c.failurePoints(), 120));
            if (c.result() != null && !c.result().isBlank())
                sb.append("｜结果：").append(c.result());
            sb.append("\n");
        }
    }

    private boolean isEmpty(KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
        return hits == null || (hits.knowledge().isEmpty() && hits.cases().isEmpty());
    }

    /** 截断超长文本，保留前 maxChars 字符，超长加 "…" */
    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "…";
    }
}
