package com.aiextract.service;

import java.util.concurrent.CompletableFuture;

import com.aiextract.common.ErrorMessages;
import com.aiextract.common.TraceContext;
import com.aiextract.dto.PracticeRespondRequest;
import org.springframework.scheduling.annotation.Async;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.config.DomainConfig;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Report;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillProfile;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SkillProfileRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 流式聊天核心服务 — 协议无关
 *
 * <p>返回 {@link Flux}&lt;{@link ChatChunk}&gt;，不依赖 HTTP Servlet API。
 * Controller 层通过 {@link com.aiextract.config.SseAdapter} 包装为 SseEmitter；
 * IM Bot Adapter 订阅 Flux 逐 chunk 回写消息。</p>
 *
 * <p>与 {@link SkillService} 的区别：
 * SkillService 负责 CRUD / 业务编排（生命周期、评估、列表查询），
 * ChatStreamService 专注流式对话的生成与发射。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillProfileRepository profileRepository;
    private final GrainRetriever grainRetriever;
    private final ReportRepository reportRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final com.aiextract.config.PromptLoader promptLoader;
    private final com.aiextract.repository.SkillConversationRepository conversationRepository;
    private final com.aiextract.repository.SkillMessageRepository skillMessageRepository;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;
    private final SkillService skillService; // 复用 helper 方法
    private final com.aiextract.repository.KnowledgeGapRepository knowledgeGapRepository;
    private final com.aiextract.repository.FeedbackLogRepository feedbackLogRepository;
    private final com.aiextract.repository.ConversationStatsRepository conversationStatsRepository;
    private final com.aiextract.repository.GrainRetrieveLogRepository grainRetrieveLogRepository;
    private final com.aiextract.repository.SkillMaterialRepository skillMaterialRepository;
    private final com.aiextract.repository.InterviewSessionRepository interviewSessionRepository;

    /** RAG 查询改写开关（默认开启），关闭后直接用原始消息做语义检索 */
    @Value("${app.rag.query-rewrite.enabled:true}")
    private boolean ragRewriteEnabled;

    private final ShareRateLimiter shareRateLimiter;

    /** C 端游客免费消息额度（跨会话跨模式全局计数，达到后下发 limit 事件引导注册） */
    @Value("${app.share.guest-message-limit:5}")
    private int guestMessageLimit;

    /**
     * C 端游客拦截 — 频率限流 + 免费额度判定。
     *
     * <p>必须在 LLM 调用与消息落库之前执行：额度拦截零 AI 成本，
     * 且被拦截的消息不落库，注册后前端重发不会产生重复消息。</p>
     *
     * @return 非 null 表示拦截（直接把该 Flux 返回给前端），null 表示放行
     */
    private Flux<ChatChunk> interceptGuest(String role, UUID userId) {
        if (!"c_guest".equals(role)) {
            return null;
        }
        if (!shareRateLimiter.allowGuestMessage(userId)) {
            log.warn("游客消息频率超限 userId={}", userId);
            return Flux.just(ChatChunk.error("发送太频繁，请稍后再试"), ChatChunk.done());
        }
        long used = skillMessageRepository.countUserMessagesByUserId(userId);
        if (used >= guestMessageLimit) {
            log.info("游客免费额度已用完 userId={} used={}/{}", userId, used, guestMessageLimit);
            return Flux.just(
                ChatChunk.event("limit", Map.of(
                    "code", "GUEST_LIMIT_REACHED",
                    "used", used,
                    "limit", guestMessageLimit)),
                ChatChunk.done());
        }
        return null;
    }

    // ============================================================
    // 分身问答（Q&A）
    // ============================================================

    /**
     * 分身问答流式对话 — 核心方法
     *
     * <p>三阶段：
     * <ol>
     *   <li>Setup（同步）：校验、RAG 检索、创建会话、保存用户消息</li>
     *   <li>Stream（异步）：LLM 逐 chunk 输出，完成后保存 AI 回复</li>
     *   <li>Post-stream（异步）：发送 meta（conversationId）和 source（引用报告）</li>
     * </ol>
     */
    public Flux<ChatChunk> chat(UUID skillId, SkillChatRequest request, UUID userId, String role) {

        // ── Phase 1: Setup（同步，订阅前执行） ──

        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SKILL_NOT_FOUND));
        TraceContext.init(skill.getId());
        long t0 = System.currentTimeMillis();

        log.info("═══ 分身问答开始 ═══ skillId={} userId={} msg={}",
            skillId, userId, request.getMessage().substring(0, Math.min(50, request.getMessage().length())));

        boolean isAdmin = userRepository.findById(userId)
            .map(u -> "super_admin".equals(u.getRole())).orElse(false);
        // 分身所有者可在审核期预览效果，普通用户需等发布后
        boolean isOwner = spaceRepository.findById(skill.getSpaceId())
            .map(s -> userId.equals(s.getUserId())).orElse(false);
        boolean canChat = isAdmin || isOwner
            || "published".equals(skill.getStatus())
            || "active".equals(skill.getStatus()); // 兼容旧数据
        if (!canChat) {
            log.warn("Skill不可用 status={} userId={}", skill.getStatus(), userId);
            TraceContext.clear();
            return Flux.just(ChatChunk.error("分身未发布"));
        }

        // C 端游客拦截（LLM 调用与消息落库之前）
        Flux<ChatChunk> guestBlock = interceptGuest(role, userId);
        if (guestBlock != null) {
            TraceContext.clear();
            return guestBlock;
        }

        SkillProfile profile = profileRepository.findBySkillId(skillId).orElse(null);
        boolean profileIncomplete = profile == null
            || skillService.isBlank(profile.getPersonality())
            || skillService.isBlank(profile.getBackground())
            || skillService.isBlank(profile.getKnowledgeDomains())
            || "[]".equals(profile.getKnowledgeDomains());

        String mode = skillService.resolveMode(request.getMode(), request.getMessage());
        boolean record = "published".equals(skill.getStatus());
        LocalDateTime now = LocalDateTime.now();

        // 会话 + 用户消息
        UUID convId = record ? upsertConversation(skillId, userId, request, mode, now, skill) : UUID.randomUUID();

        // RAG — 从 DB 取历史做查询改写
        String ragHistory = record ? buildRagHistory(convId) : request.getHistory();
        String domain = domainConfigLoader.resolveDomain(skill);
        String ragQuery = rewriteQuery(request.getMessage(), ragHistory, domain, skill.getId());
        RagContext ragCtx = new RagContext(skill.getId(), convId, request.getMessage());
        GrainResult grains = retrieveGrainsWithScores(ragQuery, skill.getSpaceId(), 5, domain, ragCtx);

        // 捕获溯源数据用于持久化
        final UUID persistedGrainId = grains.grains().isEmpty() ? null : grains.grains().get(0).getId();
        final UUID persistedReportId = grains.grains().stream()
            .map(com.aiextract.model.ExperienceGrain::getReportId)
            .filter(id -> id != null).findFirst().orElse(null);

        String systemPrompt = skillService.buildSkillSystemPrompt(
            skill, grains.grains(), grains.tiers(), mode, request.getChannel());
        log.info("③ SystemPrompt构建完成 {}chars", systemPrompt.length());
        List<Map<String, String>> messages = skillService.buildChatMessages(systemPrompt, request.getMessage(),
            convId, request.getHistory());
        Map<String, Object> context = Map.of("mode", mode, "skillId", skillId.toString(),
            "conversationId", convId.toString());

        // ── Phase 2 & 3: Stream + Post-stream（Flux 管道） ──

        final UUID finalConvId = convId;
        final String finalMode = mode;
        final long tAiStart = System.currentTimeMillis();
        final StringBuilder aiContent = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean hasStreamError = new java.util.concurrent.atomic.AtomicBoolean(false);

        Flux<ChatChunk> profileWarning = profileIncomplete
            ? Flux.just(ChatChunk.warning(
                "该分身画像尚未完善，回复质量可能不佳。请管理员在审核页面补充画像信息。",
                "fill_profile", skillId.toString()))
            : Flux.empty();

        Flux<ChatChunk> aiStream = chatStreamAdapter.chatStream(messages, context)
            .map(event -> {
                ChatChunk chunk = ChatChunk.fromEventMap(event);
                if ("content".equals(chunk.getType()) && chunk.getContent() != null) {
                    aiContent.append(chunk.getContent());
                }
                return chunk;
            })
            .doOnComplete(() -> {
                long aiMs = System.currentTimeMillis() - tAiStart;
                log.info("④ AI流式完成 {}ms contentLen={}", aiMs, aiContent.length());
                saveAiMessage(finalConvId, record, aiContent.toString(), finalMode, now, skill,
                    persistedGrainId, persistedReportId);
            })
            .timeout(Duration.ofSeconds(120))
            .doOnError(e -> {
                hasStreamError.set(true);
                log.error("SSE流超时或异常", e);
            })
            .onErrorResume(err -> Flux.just(ChatChunk.error(ErrorMessages.AI_SERVICE_UNAVAILABLE)));

        Flux<ChatChunk> postStream = Flux.defer(() -> {
            long totalMs = System.currentTimeMillis() - t0;
            log.info("✅ 问答完成 total={}ms", totalMs);
            // RAG 无匹配时追加推荐问题
            Flux<ChatChunk> suggestedFlux = Flux.empty();
            if (grains.grains().isEmpty()) {
                try {
                    List<String> suggested = skillService.generateSuggestedQuestions(skill);
                    if (!suggested.isEmpty()) {
                        suggestedFlux = Flux.just(ChatChunk.event("suggested",
                            Map.of("questions", (Object) suggested)));
                    }
                } catch (Exception e) {
                    log.warn("生成推荐问题失败: {}", e.getMessage());
                }
            }
            return Flux.concat(
                Flux.just(ChatChunk.meta(finalConvId.toString())),
                buildSourceChunkFlux(skill.getSpaceId(), grains.grains(), grains.similarities()),
                suggestedFlux
            );
        });

        return Flux.concat(profileWarning, aiStream, postStream)
            .doFinally(s -> {
                // 写对话统计（无论成功/失败/超时）
                try {
                    double avgSim = grains.similarities().isEmpty() ? 0
                        : grains.similarities().values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    String errorType = null;
                    { if (hasStreamError.get()) errorType = "stream_error"; }
                    conversationStatsRepository.save(com.aiextract.model.ConversationStats.builder()
                        .id(UUID.randomUUID())
                        .skillId(skill.getId())
                        .conversationId(finalConvId)
                        .userId(userId)
                        .mode(finalMode)
                        .ragHighCount((int) grains.tiers().values().stream().filter("high"::equals).count())
                        .ragRefCount((int) grains.tiers().values().stream().filter("ref"::equals).count())
                        .ragNoneCount(grains.grains().isEmpty() ? 1 : 0)
                        .ragAvgSimilarity(avgSim)
                        .errorType(errorType)
                        .isTest(Boolean.TRUE.equals(request.getIsTest()))
                        .llmDurationMs((int) (System.currentTimeMillis() - tAiStart))
                        .totalDurationMs((int) (System.currentTimeMillis() - t0))
                        .createdAt(LocalDateTime.now())
                        .build());
                } catch (Exception e) {
                    log.warn("写入conversation_stats失败 convId={}: {}", finalConvId, e.getMessage());
                }
                TraceContext.clear();
            });
    }

    // ============================================================
    // 对练回应
    // ============================================================

    public Flux<ChatChunk> respondPractice(UUID skillId, PracticeRespondRequest request, UUID userId, String role) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {
            return Flux.just(ChatChunk.error(ErrorMessages.SKILL_NOT_FOUND));
        }

        // C 端游客拦截（对练轮次同样计入全局免费额度）
        Flux<ChatChunk> guestBlock = interceptGuest(role, userId);
        if (guestBlock != null) {
            return guestBlock;
        }

        String systemPrompt = skillService.buildPracticeSystemPrompt(skill, request);
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", request.getMessage())
        );

        // 持久化：保存用户消息（先做属主校验，不符则本轮不落库但继续对练）
        UUID parsedConvId = null;
        if (request.getConversationId() != null && !request.getConversationId().isEmpty()) {
            try {
                parsedConvId = UUID.fromString(request.getConversationId());
            } catch (IllegalArgumentException e) {
                log.warn("practice非法conversationId，不落库: {}", request.getConversationId());
            }
        }
        if (parsedConvId != null) {
            var conv = conversationRepository.findById(parsedConvId).orElse(null);
            if (conv == null || !conv.getUserId().equals(userId)) {
                log.warn("practice会话属主不符或不存在，本轮不落库 convId={} requester={}", parsedConvId, userId);
                parsedConvId = null;
            }
        }
        final UUID convId = parsedConvId;
        if (convId != null) {
            var now = LocalDateTime.now();
            skillMessageRepository.save(com.aiextract.model.SkillMessage.builder()
                    .id(UUID.randomUUID()).conversationId(convId)
                    .role("user").roleLabel("我（销冠）")
                    .content(request.getMessage()).createdAt(now).build());
        }

        StringBuilder aiContent = new StringBuilder();
        Flux<ChatChunk> practiceStream = chatStreamAdapter.chatStream(messages, Map.of("mode", "practice"))
            .retry(1)
            .map(ChatChunk::fromEventMap)
            .doOnNext(chunk -> {
                if ("content".equals(chunk.getType()) && chunk.getContent() != null) {
                    aiContent.append(chunk.getContent());
                }
            })
            .doOnComplete(() -> {
                if (convId != null && aiContent.length() > 0) {
                    var now = LocalDateTime.now();
                    skillMessageRepository.save(com.aiextract.model.SkillMessage.builder()
                            .id(UUID.randomUUID()).conversationId(convId)
                            .role("assistant").roleLabel("客户")
                            .content(aiContent.toString()).createdAt(now).build());
                    conversationRepository.findById(convId).ifPresent(c -> {
                        c.setUpdatedAt(now);
                        conversationRepository.save(c);
                    });
                }
            });

        // 场景溯源：加载该场景的颗粒，追加 source event
        Flux<ChatChunk> practiceSource = Flux.defer(() -> {
            if (request.getSceneTag() == null) return Flux.empty();
            try {
                var grains = grainRepository.findBySpaceIdAndSceneTagAndStatus(
                    skill.getSpaceId(), request.getSceneTag(), "active");
                { if (grains.isEmpty()) return Flux.empty(); }
                return buildSourceChunkFlux(skill.getSpaceId(), grains.stream().limit(3).collect(Collectors.toList()), Map.of());
            } catch (Exception e) {
                return Flux.empty();
            }
        });

        final UUID finalPracticeConvId = convId != null ? convId : UUID.randomUUID();
        return Flux.concat(practiceStream, practiceSource)
            .doFinally(s -> {
                try {
                    conversationStatsRepository.save(com.aiextract.model.ConversationStats.builder()
                        .id(UUID.randomUUID())
                        .skillId(skill.getId())
                        .conversationId(finalPracticeConvId)
                        .mode("practice")
                        .ragHighCount(request.getSceneTag() != null ? 1 : 0)
                        .ragNoneCount(request.getSceneTag() != null ? 0 : 1)
                        .createdAt(LocalDateTime.now())
                        .build());
                } catch (Exception e) {
                    log.warn("写入practice stats失败: {}", e.getMessage());
                }
            })
            .timeout(Duration.ofSeconds(120))
            .onErrorResume(err -> Flux.just(ChatChunk.error("服务异常")));
    }

    // ============================================================
    // 企业总调度问答
    // ============================================================

    public Flux<ChatChunk> enterpriseChat(SkillChatRequest request, UUID companyId) {
        List<UUID> companySpaceIds = spaceRepository.findByUserIdIn(
            userRepository.findByCompanyId(companyId).stream().map(User::getId).toList()
        ).stream().map(Space::getId).toList();

        List<ExperienceGrain> allGrains;
        if (companySpaceIds.isEmpty()) {
            allGrains = List.of();
        } else {
            allGrains = grainRepository.findBySpaceIdIn(companySpaceIds,
                PageRequest.of(0, 500));
        }

        String query = request.getMessage();
        List<ExperienceGrain> matched = allGrains.stream()
            .filter(g -> g.getSceneTag() != null || g.getExpertThought() != null)
            .sorted((a, b) -> {
                int scoreA = skillService.relevanceScore(a, query);
                int scoreB = skillService.relevanceScore(b, query);
                return Integer.compare(scoreB, scoreA);
            })
            .limit(20).toList();

        String systemPrompt = skillService.buildEnterpriseSystemPrompt(query, matched, "sales.b2b_enterprise");
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", query)
        );
        // ② enterpriseChat: 企业总调度模式，按关键词排序取前20条
        // 写统计：记命中颗粒数，不记模式（企业问答不绑定单个分身）
        return chatStreamAdapter.chatStream(messages, Map.of("mode", "enterprise"))
            .map(ChatChunk::fromEventMap)
            .doFinally(s -> {
                try {
                    conversationStatsRepository.save(com.aiextract.model.ConversationStats.builder()
                        .id(UUID.randomUUID())
                        .conversationId(UUID.randomUUID())
                        .mode("enterprise")
                        .ragHighCount(matched.size())  // 企业模式用关键词匹配命中数
                        .ragRefCount(0)                // 企业模式无 RAG 分层，统一记为 high
                        .ragNoneCount(0)
                        .isTest(false)                 // 企业问答为正式调用
                        .createdAt(LocalDateTime.now())
                        .build());
                } catch (Exception e) {
                    log.warn("写入enterprise stats失败: {}", e.getMessage());
                }
            })
            .timeout(Duration.ofSeconds(120))
            .onErrorResume(err -> Flux.just(ChatChunk.error("服务异常")));
    }

    // ============================================================
    // 同步版本 — 适配不支持流式编辑的 IM 平台（企微/钉钉）
    // ============================================================

    /**
     * 企业总调度同步版 — 收集完整回复后返回纯文本。
     *
     * <p>异步执行，LLM 流式调用在 embeddingExecutor 线程池中运行，
     * 不阻塞 servlet 线程。调用方通过 CompletableFuture.get() 等待结果。</p>
     */
    @Async("embeddingExecutor")
    public CompletableFuture<String> enterpriseChatSync(SkillChatRequest request, UUID companyId) {
        StringBuilder sb = new StringBuilder();
        enterpriseChat(request, companyId)
            .doOnNext(chunk -> {
                if ("content".equals(chunk.getType()) && chunk.getContent() != null) {
                    sb.append(chunk.getContent());
                }
            })
            .doOnError(err -> log.error("同步企业问答异常: {}", err.getMessage()))
            .blockLast(Duration.ofSeconds(120));
        return CompletableFuture.completedFuture(sb.length() > 0 ? sb.toString() : "抱歉，AI服务暂时不可用。");
    }

    // ============================================================
    // 对练综合评价
    // ============================================================

    public Flux<ChatChunk> evaluatePractice(String skillId, String conversation, String scene) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId)).orElse(null);
        String name = skill != null && skill.getOwnerName() != null ? skill.getOwnerName() : "销冠";
        String domain = domainConfigLoader.resolveDomain(skill);
        String evalPrompt = skillService.buildPracticeEvaluatePrompt(name, conversation, scene, List.of(), domain);
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", evalPrompt),
            Map.of("role", "user", "content", "请输出评价")
        );
        return chatStreamAdapter.chatStream(messages, Map.of("mode", "evaluate"))
            .map(ChatChunk::fromEventMap)
            .timeout(Duration.ofSeconds(120))
            .onErrorResume(err -> Flux.just(ChatChunk.error("服务异常")));
    }

    // ============================================================
    // Private helpers
    // ============================================================

    /**
     * RAG 查询改写 — 多轮对话场景下将缩写/省略/代词还原为独立查询
     *
     * <p>开关: {@code app.rag.query-rewrite.enabled}（默认true）</p>
     * <p>首轮消息、无历史或开关关闭时直接返回原消息。</p>
     */
    private String rewriteQuery(String message, String history, String domain, UUID skillId) {
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

    /** 从 DB 取最近消息拼成历史字符串，供查询改写用（替代前端传入的 history） */
    private String buildRagHistory(UUID convId) {
        try {
            var msgs = skillMessageRepository.findByConversationIdOrderByCreatedAtAsc(convId);
            { if (msgs.isEmpty()) return null; }
            // 排除最后一条（当前用户消息），取最近 12 条
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

    /** grains + tier map + similarity scores，传给 prompt builder 做分层标记 */
    private record GrainResult(List<ExperienceGrain> grains, Map<UUID, String> tiers,
                               Map<UUID, Double> similarities) {}

    /** RAG 检索上下文，用于写日志 */
    private record RagContext(UUID skillId, UUID conversationId, String originalQuery) {}

    private GrainResult retrieveGrainsWithScores(String query, UUID spaceId, int topK,
                                                  String domain, RagContext ragCtx) {
        long tRag = System.currentTimeMillis();
        java.util.List<GrainRetriever.GrainResult> scored = grainRetriever.retrieveWithScores(query, spaceId, topK);
        long ragMs = System.currentTimeMillis() - tRag;

        // 无匹配 → 写知识缺口（替代原来的"回退取最近20条"）
        if (scored.isEmpty()) {
            log.info("Step2 RAG无结果，记录缺口");
            writeKnowledgeGap(query, spaceId, ragCtx);
            log.info("Step2 RAG无结果，记录缺口完成");
            return new GrainResult(List.of(), Map.of(), Map.of());
        }

        // 从领域配置读取阈值
        double highThreshold = 0.50;
        double refThreshold = 0.30;
        if (domain != null) {
            DomainConfig dc = domainConfigLoader.load(domain);
            if (dc != null && dc.getPrecheck() != null) {
                highThreshold = dc.getPrecheck().getRagHighThreshold();
                refThreshold = dc.getPrecheck().getRagRefThreshold();
            }
        }

        // 按相似度分 tier + 写检索日志
        Map<UUID, String> tiers = new java.util.LinkedHashMap<>();
        Map<UUID, Double> similarities = new java.util.LinkedHashMap<>();
        int pos = 1;
        for (var r : scored) {
            similarities.put(r.grain().getId(), r.similarity());
            if (r.similarity() >= highThreshold) tiers.put(r.grain().getId(), "high");
            else if (r.similarity() >= refThreshold) tiers.put(r.grain().getId(), "ref");

            // 异步写检索日志（每条命中颗粒一行）
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
        List<ExperienceGrain> grains = scored.stream().map(GrainRetriever.GrainResult::grain).collect(Collectors.toList());
        log.info("Step2 RAG检索完成 {}ms topK={} tags={} high={} ref={}",
            ragMs, scored.size(),
            grains.stream().map(g -> g.getSceneTag()).distinct().limit(5).collect(Collectors.toList()),
            tiers.values().stream().filter("high"::equals).count(),
            tiers.values().stream().filter("ref"::equals).count());
        return new GrainResult(grains, tiers, similarities);
    }

    /** 写入知识缺口。过滤短消息（"继续""嗯"等），按 skill+scene 查已有次数。 */
    private void writeKnowledgeGap(String query, UUID spaceId, RagContext ragCtx) {
        { if (query == null || query.trim().length() < 5) return; }
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
            log.info("知识缺口已记录 skillId={} sceneTag={} count={}", ragCtx != null ? ragCtx.skillId() : null, sceneTag, prevCount + 1);
        } catch (Exception e) {
            log.warn("记录知识缺口失败: {}", e.getMessage());
        }
    }

    /** 快速推测问题场景。RAG 0 结果时通过关键词匹配空间已有场景标签。 */
    private String guessSceneTag(String query, UUID spaceId) {
        try {
            var grains = grainRepository.findBySpaceIdAndStatus(spaceId, "active");
            { if (grains.isEmpty()) return null; }
            Set<String> tags = grains.stream()
                .map(g -> g.getSceneTag())
                .filter(t -> t != null && !t.isEmpty())
                .collect(Collectors.toSet());
            for (String tag : tags) {
                { if (query.contains(tag)) return tag; }
            }
            // 返回颗粒最多的场景
            return grains.stream()
                .collect(Collectors.groupingBy(g -> g.getSceneTag() != null ? g.getSceneTag() : "通用", Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private UUID upsertConversation(UUID skillId, UUID userId,
                                      SkillChatRequest request, String mode, LocalDateTime now, Skill skill) {
        com.aiextract.model.SkillConversation conv = null;
        if (request.getConversationId() != null && !request.getConversationId().isEmpty()) {
            UUID reqConvId = null;
            try {
                reqConvId = UUID.fromString(request.getConversationId());
            } catch (IllegalArgumentException e) {
                log.warn("非法conversationId，按新会话处理: {}", request.getConversationId());
            }
            if (reqConvId != null) {
                conv = conversationRepository.findById(reqConvId).orElse(null);
            }
            // 属主 + 分身归属校验：不符则降级新建（meta 事件回传新 convId，前端自动纠正），不中断流
            if (conv != null && (!conv.getUserId().equals(userId) || !conv.getSkillId().equals(skillId))) {
                log.warn("会话续写属主不符，降级新建 reqConvId={} owner={} requester={}",
                    conv.getId(), conv.getUserId(), userId);
                conv = null;
            }
            if (conv != null) {
                conv.setUpdatedAt(now);
                conv.setMode(mode);
                conversationRepository.save(conv);
            }
        }
        if (conv == null) {
            conv = createConversation(skillId, userId, request.getMessage(), mode, now);
        }
        log.info("Step1 会话就绪 convId={} mode={}", conv.getId(), mode);

        String roleLabel = resolveRoleLabel(skill);
        String userLabel = "practice".equals(mode) ? "我（" + roleLabel + "）" : "我";
        skillMessageRepository.save(com.aiextract.model.SkillMessage.builder()
            .id(UUID.randomUUID()).conversationId(conv.getId())
            .role("user").roleLabel(userLabel).content(request.getMessage()).createdAt(now).build());
        return conv.getId();
    }

    private com.aiextract.model.SkillConversation createConversation(
        UUID skillId, UUID userId, String firstMsg, String mode, LocalDateTime now) {
        String title = firstMsg != null && firstMsg.length() > 30
            ? firstMsg.substring(0, 30) + "..." : firstMsg;
        return conversationRepository.save(
            com.aiextract.model.SkillConversation.builder()
                .id(UUID.randomUUID()).skillId(skillId)
                .userId(userId).title(title).mode(mode)
                .createdAt(now).updatedAt(now).build());
    }

    private String resolveRoleLabel(Skill skill) {
        String domainId = domainConfigLoader.resolveDomain(skill);
        if (domainId == null) {

            return "专家";

        }
        DomainConfig dc = domainConfigLoader.load(domainId);
        if (dc == null || dc.getDomain() == null) return "专家";
        return dc.getDomain().getRoleLabel();
    }

    private String resolveCounterpartyLabel(Skill skill) {
        String domainId = domainConfigLoader.resolveDomain(skill);
        if (domainId == null) {

            return "对方";

        }
        DomainConfig dc = domainConfigLoader.load(domainId);
        if (dc == null || dc.getDomain() == null) return "对方";
        return dc.getDomain().getCounterpartyLabel();
    }

    private void saveAiMessage(UUID convId, boolean record, String content, String mode, LocalDateTime now, Skill skill,
                                UUID grainId, UUID reportId) {
        { if (!record || content.isEmpty()) return; }
        String counterpartyLabel = resolveCounterpartyLabel(skill);
        String aiLabel = "practice".equals(mode) ? counterpartyLabel : resolveRoleLabel(skill);
        skillMessageRepository.save(com.aiextract.model.SkillMessage.builder()
            .id(UUID.randomUUID()).conversationId(convId)
            .role("assistant").roleLabel(aiLabel)
            .content(content).grainId(grainId).reportId(reportId).createdAt(now).build());
    }

    private Flux<ChatChunk> buildSourceChunkFlux(UUID spaceId, List<ExperienceGrain> grains,
                                                   Map<UUID, Double> similarities) {
        try {
            // 取前 3 个颗粒用于溯源展示
            List<ExperienceGrain> topGrains = grains.stream().limit(3).toList();

            String grainIds = topGrains.stream()
                .map(g -> g.getId().toString()).collect(Collectors.joining(","));
            String grainTags = topGrains.stream()
                .map(g -> g.getSceneTag() != null ? g.getSceneTag() : "")
                .filter(t -> !t.isEmpty()).distinct().collect(Collectors.joining(","));

            // 解析来源名称：素材文件名 或 访谈主题
            List<String> names = new java.util.ArrayList<>();
            for (ExperienceGrain g : topGrains) {
                if (g.getSourceMaterialId() != null) {
                    skillMaterialRepository.findById(g.getSourceMaterialId()).ifPresent(m -> {
                        if (m.getFileName() != null) names.add(m.getFileName());
                    });
                }
                if (g.getSourceInterviewId() != null) {
                    interviewSessionRepository.findById(g.getSourceInterviewId()).ifPresent(s -> {
                        if (s.getTopic() != null) names.add("访谈: " + s.getTopic());
                    });
                }
            }
            String sourceNames = names.stream().distinct().collect(Collectors.joining(", "));

            // 报告信息：优先用第一个 grain 的 reportId
            String reportId = null;
            String reportTitle = null;
            UUID firstReportId = topGrains.stream()
                .map(ExperienceGrain::getReportId).filter(id -> id != null).findFirst().orElse(null);
            if (firstReportId != null) {
                Report r = reportRepository.findById(firstReportId).orElse(null);
                if (r != null) {
                    reportId = r.getId().toString();
                    reportTitle = r.getTitle();
                }
            }

            double avgScore = topGrains.stream()
                .mapToDouble(g -> g.getQualityScore() != null ? g.getQualityScore() : 0)
                .average().orElse(0);
            double avgSim = topGrains.stream()
                .mapToDouble(g -> similarities.getOrDefault(g.getId(), 0.0))
                .average().orElse(0);

            return Flux.just(ChatChunk.source(
                reportId != null ? reportId : "",
                reportTitle != null ? reportTitle : "",
                grainIds, grainTags,
                Math.min(grains.size(), 3), String.format("%.1f", avgScore),
                String.format("%.0f", avgSim * 100), sourceNames));
        } catch (Exception e) {
            log.warn("构建溯源信息失败: {}", e.getMessage());
            return Flux.empty();
        }
    }
}
