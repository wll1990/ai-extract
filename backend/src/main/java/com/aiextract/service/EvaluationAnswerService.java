package com.aiextract.service;

import com.aiextract.config.PromptSecurity;

import com.aiextract.config.DomainConfigLoader;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测答题服务 —— 用分身真实链路回答评测题目，并冻结检索证据。
 *
 * <p>复用生产链路的同一批组件（不复制 RAG）：
 * {@link RagPipelineService} 检索 → {@link KnowledgeCaseRetriever} 知识/案例 →
 * {@link PromptAssemblyService} 拼 SystemPrompt 与消息 →
 * {@link ChatStreamAdapter} 调用模型。本类只做编排与证据冻结，不写会话、不写统计。</p>
 *
 * <p>为什么不用 {@code ChatStreamService.chat}：那条链路会建会话、落消息、写知识缺口、
 * 触发业务活跃度统计，而评测运行明确要求「不生成普通销售会话、不污染业务统计」（docs/38 E2）。
 * 因此这里直接复用下层组件，并把 {@code recordKnowledgeGap} 传 false。</p>
 *
 * <p>评测固定使用 {@code quick} 模式，不解析「/对练」等模式指令：
 * 同一道题在两次运行中必须走同一套提示词，否则报告不可比（docs/38 E4）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationAnswerService {

    /** 评测固定模式：不解析用户模式指令，保证跨运行可比 */
    private static final String EVALUATION_MODE = "quick";

    private final RagPipelineService ragPipelineService;
    private final KnowledgeCaseRetriever knowledgeCaseRetriever;
    private final PromptAssemblyService promptAssembly;
    private final ChatStreamAdapter chatStreamAdapter;
    private final DomainConfigLoader domainConfigLoader;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.top-k:8}")
    private int ragTopK;

    @Value("${app.rag.kc-top-k:3}")
    private int kcTopK;

    @Value("${app.rag.kc-knowledge-min-similarity:0.45}")
    private double kcKnowledgeMinSimilarity;

    @Value("${app.rag.kc-case-min-similarity:0.50}")
    private double kcCaseMinSimilarity;

    @Value("${app.chat.timeout-seconds:120}")
    private int answerTimeoutSeconds;

    /**
     * 让分身回答一道评测题。
     *
     * @param skill     被评测分身
     * @param item      题目（question + 可选 context）
     * @param companyId 分身所属企业（知识库检索范围，可为 null=仅公共知识）
     * @return 回答正文 + 检索证据 JSON
     */
    public AnswerResult answer(Skill skill, EvaluationItem item, UUID companyId) {
        String question = buildQuestion(item);
        String domain = domainConfigLoader.resolveDomain(skill);

        String ragQuery = ragPipelineService.rewriteQuery(question, "", domain, skill.getId());
        RagPipelineService.RagContext ragCtx =
                new RagPipelineService.RagContext(skill.getId(), UUID.randomUUID(), question);
        RagPipelineService.GrainResult grains = retrieveGrains(skill, ragQuery, domain, ragCtx);
        KnowledgeCaseRetriever.KnowledgeCaseHits kcHits = retrieveKnowledgeAndCases(question, companyId);

        String systemPrompt = promptAssembly.buildSkillSystemPrompt(
                skill, grains.grains(), grains.tiers(), grains.similarities(), EVALUATION_MODE, null)
                + PromptSecurity.wrapUntrusted(buildKnowledgeCaseContext(kcHits));
        // conversationId 用一次性 UUID：评测不读任何历史消息，也不会写入消息表
        List<Map<String, String>> messages = promptAssembly.buildChatMessages(
                systemPrompt, question, UUID.randomUUID(), null);

        String answer = callModel(messages, skill.getId());
        String evidence = buildEvidence(ragQuery, grains, kcHits);
        log.info("评测答题完成 skillId={} itemId={} answerChars={} grains={} knowledge={} cases={}",
                skill.getId(), item.getId(), answer.length(),
                grains.grains().size(), kcHits.knowledge().size(), kcHits.cases().size());
        return new AnswerResult(answer, evidence, kcHits);
    }

    /** 题目 + 固定上下文拼成实际提问（context 参与提问，保证不同运行拿到同样输入）。 */
    private String buildQuestion(EvaluationItem item) {
        String question = item.getQuestion() == null ? "" : item.getQuestion().trim();
        String context = item.getContext();
        if (context == null || context.isBlank()) {
            return question;
        }
        return "【固定上下文】\n" + context.trim() + "\n\n【问题】\n" + question;
    }

    /** 颗粒检索；异常降级为空（证据里 fallbackLevel=4 可追溯），不阻断答题。 */
    private RagPipelineService.GrainResult retrieveGrains(Skill skill, String ragQuery, String domain,
                                                          RagPipelineService.RagContext ragCtx) {
        try {
            // recordKnowledgeGap=false：评测题目不是真实用户提问，不能污染知识缺口统计
            return ragPipelineService.retrieveGrainsWithScores(
                    ragQuery, skill.getSpaceId(), ragTopK, domain, ragCtx, false);
        } catch (Exception e) {
            log.warn("评测 RAG 检索失败，降级为空 skillId={}: {}", skill.getId(), e.getMessage());
            return new RagPipelineService.GrainResult(List.of(), Map.of(), Map.of(), 4);
        }
    }

    /** 知识/案例检索；异常降级为空，不阻断答题。 */
    private KnowledgeCaseRetriever.KnowledgeCaseHits retrieveKnowledgeAndCases(String query, UUID companyId) {
        try {
            if (!knowledgeCaseRetriever.hasAnyActive(companyId)) {
                return new KnowledgeCaseRetriever.KnowledgeCaseHits(List.of(), List.of());
            }
            return knowledgeCaseRetriever.retrieve(
                    query, companyId, kcTopK, kcKnowledgeMinSimilarity, kcCaseMinSimilarity);
        } catch (Exception e) {
            log.warn("评测知识/案例检索失败，降级为空: {}", e.getMessage());
            return new KnowledgeCaseRetriever.KnowledgeCaseHits(List.of(), List.of());
        }
    }

    /** 走生产同一条流式调用，同步聚合为完整回答；超时或空回答抛异常（不造假答案）。 */
    private String callModel(List<Map<String, String>> messages, UUID skillId) {
        Map<String, Object> context = Map.of(
                "mode", EVALUATION_MODE,
                "skillId", skillId.toString());
        StringBuilder content = new StringBuilder();
        chatStreamAdapter.chatStream(messages, context)
                .doOnNext(event -> {
                    ChatChunk chunk = ChatChunk.fromEventMap(event);
                    if ("content".equals(chunk.getType()) && chunk.getContent() != null) {
                        content.append(chunk.getContent());
                    }
                })
                .blockLast(Duration.ofSeconds(answerTimeoutSeconds));
        if (content.length() == 0) {
            throw new IllegalStateException("分身回答为空，无法评分");
        }
        return content.toString();
    }

    /**
     * 冻结检索证据：命中颗粒（含质量分层与相似度）+ 知识/案例命中。
     *
     * <p>结构供召回指标计算：{@code expected} 与 {@code grains[].sceneTag} / {@code knowledge[].title}
     * / {@code cases[].title} 求交，即可算 Context Precision@K / Recall@K / MRR。</p>
     */
    private String buildEvidence(String ragQuery, RagPipelineService.GrainResult grains,
                                 KnowledgeCaseRetriever.KnowledgeCaseHits kcHits) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ragQuery", ragQuery);
        root.put("fallbackLevel", grains.fallbackLevel());

        List<Map<String, Object>> grainList = new ArrayList<>(grains.grains().size());
        for (var g : grains.grains()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId() == null ? null : g.getId().toString());
            m.put("sceneTag", g.getSceneTag());
            m.put("tier", grains.tiers().get(g.getId()));
            m.put("similarity", grains.similarities().get(g.getId()));
            grainList.add(m);
        }
        root.put("grains", grainList);

        List<Map<String, Object>> knowledgeList = new ArrayList<>(kcHits.knowledge().size());
        for (var k : kcHits.knowledge()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.id() == null ? null : k.id().toString());
            m.put("title", k.title());
            m.put("similarity", k.similarity());
            knowledgeList.add(m);
        }
        root.put("knowledge", knowledgeList);

        List<Map<String, Object>> caseList = new ArrayList<>(kcHits.cases().size());
        for (var c : kcHits.cases()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id() == null ? null : c.id().toString());
            m.put("title", c.title());
            m.put("sceneTag", c.sceneTag());
            m.put("similarity", c.similarity());
            caseList.add(m);
        }
        root.put("cases", caseList);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("检索证据序列化失败", e);
        }
    }

    /** 知识/案例命中拼成 SystemPrompt 末尾的上下文块（与生产对话同格式，空则空串）。 */
    private String buildKnowledgeCaseContext(KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
        if (hits.knowledge().isEmpty() && hits.cases().isEmpty()) {
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
        if (!hits.cases().isEmpty()) {
            sb.append("\n\n## 【案例库 · 实战参考】\n")
                    .append("以下为真实案例，可引用其过程与结果作为佐证，不编造案例细节。\n");
            for (var c : hits.cases()) {
                sb.append("- ").append(c.title());
                if (c.sceneTag() != null && !c.sceneTag().isBlank()) {
                    sb.append("（").append(c.sceneTag()).append("）");
                }
                if (c.keyActions() != null && !c.keyActions().isBlank()) {
                    sb.append("｜关键动作：").append(truncate(c.keyActions(), 150));
                }
                if (c.result() != null && !c.result().isBlank()) {
                    sb.append("｜结果：").append(c.result());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    /** 答题结果：正文 + 检索证据 JSON + 知识/案例命中（用于消费回流）。 */
    public record AnswerResult(String answer, String evidenceJson,
                               KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
    }
}
