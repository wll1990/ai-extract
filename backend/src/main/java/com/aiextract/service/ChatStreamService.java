package com.aiextract.service;

import java.util.concurrent.CompletableFuture;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.common.ErrorMessages;
import com.aiextract.common.TraceContext;
import com.aiextract.dto.PracticeRespondRequest;
import org.springframework.scheduling.annotation.Async;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.config.DomainConfig;
import com.aiextract.config.PromptSecurity;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.Permission;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 流式聊天核心服务 — 协议无关的 Flux&lt;ChatChunk&gt; 生成器。
 *
 * <p>依赖：
 * <ul>
 *   <li>{@link RagPipelineService} — RAG 检索管线（查询改写、检索、缺口）</li>
 *   <li>{@link SkillService} — Prompt 构建、模式解析、颗粒推荐</li>
 * </ul>
 *
 * <p>与 {@link SkillService} 的区别：
 * SkillService 负责 CRUD / 业务编排，ChatStreamService 专注流式对话。</p>
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
    private final ReportRepository reportRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final com.aiextract.repository.SkillConversationRepository conversationRepository;
    private final com.aiextract.repository.SkillMessageRepository skillMessageRepository;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;
    private final SkillService skillService;
    private final com.aiextract.repository.ConversationStatsRepository conversationStatsRepository;
    private final com.aiextract.repository.SkillMaterialRepository skillMaterialRepository;
    private final com.aiextract.repository.InterviewSessionRepository interviewSessionRepository;
    private final RagPipelineService ragPipelineService;
    private final ConversationPersistenceService convPersistence;
    private final PromptAssemblyService promptAssembly;
    private final com.aiextract.service.skill.AgentSkillPromptInjector agentSkillPromptInjector;
    private final com.aiextract.service.skill.AgentSkillSettingService agentSkillSettingService;
    private final GrainRecommendationService grainRec;
    private final com.aiextract.repository.SkillEvaluationRepository skillEvaluationRepository;
    private final OrganizationSkillService orgSkillService;
    private final KnowledgeCaseContextService knowledgeCaseContextService;
    private final PracticeKcSettingService practiceKcSettingService;
    private final KcScopeResolver kcScopeResolver;
    private final com.aiextract.repository.KnowledgeCaseRetrieveLogRepository retrieveLogRepository;

    @Value("${app.chat.timeout-seconds:120}")
    private int chatTimeoutSeconds;

    @Value("${app.rag.top-k:8}")
    private int ragTopK;

    // ============================================================
    // 分身问答（QA / Talk）
    // ============================================================

    /**
     * 分身问答入口分发 — 统一查 skill 表，按 type 字段分发到个体/组织两条链路。
     */
    public Flux<ChatChunk> chat(UUID skillId, SkillChatRequest request, UUID userId, String role) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {
            return Flux.just(ChatChunk.error(ErrorMessages.SKILL_NOT_FOUND));
        }
        if ("organization".equals(skill.getType())) {
            return chatOrganization(skill, skillId, request, userId, role);
        }
        return chatIndividual(skill, skillId, request, userId, role);
    }

    /**
     * 个体分身问答 — 原有 chat() 方法体，逻辑不变。
     *
     * <p>三阶段：Setup（同步校验+RAG+会话）→ Stream（LLM）→ Post-stream（meta+source）。</p>
     */
    private Flux<ChatChunk> chatIndividual(Skill skill, UUID skillId, SkillChatRequest request, UUID userId, String role) {
        // ── Phase 0: 参数校验 ──
        String msg = request.getMessage();
        if (msg == null || msg.isBlank()) {
            return Flux.just(ChatChunk.error("消息不能为空"));
        }

        // ── Phase 1: Setup（同步，订阅前执行） ──
        TraceContext.init(skill.getId());
        long t0 = System.currentTimeMillis();

        log.info("═══ 分身问答开始 ═══ skillId={} userId={} msg={}",
            skillId, userId, msg.substring(0, Math.min(50, msg.length())));

        User chatUser = userRepository.findById(userId).orElse(null);
        boolean isAdmin = chatUser != null
            && RolePermissions.hasPermission(chatUser.getRole(), Permission.SKILL_MANAGE);
        boolean isOwner = spaceRepository.findById(skill.getSpaceId())
            .map(s -> userId.equals(s.getUserId())).orElse(false);
        boolean canChat = isAdmin || isOwner
            || "published".equals(skill.getStatus())
            || "active".equals(skill.getStatus());
        if (!canChat) {
            log.warn("Skill不可用 status={} userId={}", skill.getStatus(), userId);
            TraceContext.clear();
            return Flux.just(ChatChunk.error("分身未发布"));
        }

        SkillProfile profile = profileRepository.findBySkillId(skillId).orElse(null);
        boolean profileIncomplete = profile == null
            || skillService.isBlank(profile.getPersonality())
            || skillService.isBlank(profile.getBackground())
            || skillService.isBlank(profile.getKnowledgeDomains())
            || "[]".equals(profile.getKnowledgeDomains());

        String mode = skillService.resolveMode(request.getMode(), request.getMessage());
        // 试问通道（persistMode=TRIAL）不写任何业务表：会话/消息/stats/缺口/检索日志全部关闭
        final boolean trial = request.isTrialPersist();
        boolean record = "published".equals(skill.getStatus()) && !trial;
        LocalDateTime now = LocalDateTime.now();

        UUID convId = record ? convPersistence.upsertConversation(skillId, userId, request, mode, now, skill) : UUID.randomUUID();

        // OPS-03 幂等：同一 clientMessageId 已有 AI 回复时直接重放，不再跑 RAG/模型
        Flux<ChatChunk> replay = replayIfDuplicate(record, convId, request.getClientMessageId());
        if (replay != null) {
            return replay;
        }

        // RAG — 委托 RagPipelineService
        String ragHistory = record ? ragPipelineService.buildRagHistory(convId) : request.getHistory();
        String domain = domainConfigLoader.resolveDomain(skill);
        String ragQuery = ragPipelineService.rewriteQuery(request.getMessage(), ragHistory, domain, skill.getId());
        // 知识/案例检索范围——同企业用私有库+公共库，跨企业/匿名只用公共库
        final KcScopeResolver.KcScope kcScope = kcScopeResolver.resolve(
                skill, chatUser != null ? chatUser.getCompanyId() : null);
        RagPipelineService.RagContext ragCtx = new RagPipelineService.RagContext(
                skill.getId(), convId, request.getMessage(), kcScope.external(), !trial);
        // 先查知识/案例：命中时不记知识缺口（否则 KC-only 命中会被误报为缺口）
        final KnowledgeCaseRetriever.KnowledgeCaseHits kcHits = knowledgeCaseContextService.retrieveSafely(
            ragQuery, kcScope.companyId(), "skillId=" + skillId);
        final boolean kcHit = !kcHits.knowledge().isEmpty() || !kcHits.cases().isEmpty();
        final boolean recordGap = !trial && !Boolean.TRUE.equals(request.getIsTest()) && !kcHit;
        final RagPipelineService.GrainResult grains = getGrainsSafely(ragQuery, skill.getSpaceId(), ragTopK, domain, ragCtx, "skillId=" + skillId, recordGap);
        // 未发布分身只做临时试聊，不写引用日志；组织调度可能没有单一 skillId，仍要记录引用
        if (record) {
            logKcReferences(skillId, convId, kcHits, kcScope, "skill_chat");
        }

        final UUID persistedGrainId = grains.grains().isEmpty() ? null : grains.grains().get(0).getId();
        final UUID persistedReportId = grains.grains().stream()
            .map(ExperienceGrain::getReportId)
            .filter(id -> id != null).findFirst().orElse(null);
        final String persistedGrainIds = grains.grains().stream().limit(3)
            .map(g -> g.getId().toString()).collect(Collectors.joining(","));
        final Double persistedAvgSimilarity = avgSimilarityOf(grains);

        String kcContext = PromptSecurity.wrapUntrusted(
            knowledgeCaseContextService.buildAssistantContext(kcHits));
        String systemPrompt = promptAssembly.buildSkillSystemPrompt(
            skill, grains.grains(), grains.tiers(), grains.similarities(), mode, request.getChannel())
            + kcContext;
        boolean skillsEnabled = agentSkillSettingService.isEnabled(
                com.aiextract.config.TokenContext.getCompanyId());
        systemPrompt = agentSkillPromptInjector.append(systemPrompt,
                com.aiextract.config.TokenContext.getCompanyId(), skillsEnabled);
        log.info("③ SystemPrompt构建完成 {}chars skillsEnabled={}", systemPrompt.length(), skillsEnabled);
        // 试问通道的历史在 skill_trial_message，不在 skill_message，必须显式注入
        List<Map<String, String>> messages = request.getTrialHistory() != null
            ? promptAssembly.buildChatMessagesWithHistory(
                systemPrompt, request.getMessage(), request.getTrialHistory())
            : promptAssembly.buildChatMessages(systemPrompt, request.getMessage(), convId, request.getHistory());
        Map<String, Object> context = Map.of("mode", mode, "skillId", skillId.toString(),
            "conversationId", convId.toString(), "agentSkills", skillsEnabled);

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
                convPersistence.saveAiMessage(finalConvId, record, aiContent.toString(), finalMode, now, skill,
                    persistedGrainId, persistedReportId, persistedGrainIds, persistedAvgSimilarity,
                    request.getClientMessageId(), knowledgeCaseContextService.sourceRefsJson(kcHits));
            })
            .timeout(Duration.ofSeconds(chatTimeoutSeconds))
            .doOnError(e -> {
                hasStreamError.set(true);
                log.error("SSE流超时或异常", e);
            })
            .onErrorResume(err -> {
                    AiErrorMapper.AiError aiError = AiErrorMapper.map(err);
                    return Flux.just(ChatChunk.error(aiError.code(), aiError.message()));
                });

        Flux<ChatChunk> postStream = Flux.defer(() -> {
            long totalMs = System.currentTimeMillis() - t0;
            log.info("✅ 问答完成 total={}ms", totalMs);
            Flux<ChatChunk> suggestedFlux = Flux.empty();
            if (grains.grains().isEmpty()) {
                try {
                    List<String> suggested = grainRec.generateSuggestedQuestions(skill);
                    if (!suggested.isEmpty()) {
                        suggestedFlux = Flux.just(ChatChunk.event("suggested",
                            Map.of("questions", (Object) suggested)));
                    }
                } catch (Exception e) {
                    log.warn("生成推荐问题失败: {}", e.getMessage());
                }
            }
            return Flux.concat(
                // 未发布分身的预览对话不落库，不下发临时 conversationId，避免前端存幽灵会话
                record ? Flux.just(ChatChunk.meta(finalConvId.toString())) : Flux.empty(),
                buildSourceChunkFlux(skill.getSpaceId(), grains.grains(), grains.similarities()),
                knowledgeCaseContextService.buildSourceFlux(kcHits),
                suggestedFlux
            );
        });

        return Flux.concat(profileWarning, aiStream, postStream)
            .doFinally(s -> {
                if (trial) {
                    TraceContext.clear();
                    return;
                }
                try {
                    double avgSim = grains.similarities().isEmpty() ? 0
                        : grains.similarities().values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    String errorType = null;
                    if (hasStreamError.get()) {
                        errorType = "stream_error";
                    }
                    conversationStatsRepository.save(com.aiextract.model.ConversationStats.builder()
                        .id(UUID.randomUUID())
                        .skillId(skill.getId())
                        .skillType("individual")
                        .companyId(kcScope.skillCompanyId())
                        .conversationId(finalConvId)
                        .userId(userId)
                        .mode(finalMode)
                        .ragHighCount((int) grains.tiers().values().stream().filter("high"::equals).count())
                        .ragRefCount((int) grains.tiers().values().stream().filter("ref"::equals).count())
                        .ragNoneCount(grains.grains().isEmpty() && kcHits.knowledge().isEmpty()
                                && kcHits.cases().isEmpty() ? 1 : 0)
                        .kcKnowledgeCount(kcHits.knowledge().size())
                        .kcCaseCount(kcHits.cases().size())
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
    // 组织分身问答
    // ============================================================

    /**
     * 组织分身问答 — 多 space RAG + org_skill prompt。
     * 三阶段对标 {@link #chatIndividual}：Setup → Stream → Post-stream。
     */
    private Flux<ChatChunk> chatOrganization(Skill orgSkill, UUID orgSkillId,
            SkillChatRequest request, UUID userId, String role) {
        TraceContext.init(orgSkillId);
        long t0 = System.currentTimeMillis();

        log.info("═══ 组织分身问答开始 ═══ orgSkillId={} name={} userId={}",
                orgSkillId, orgSkill.getDisplayName(), userId);

        // 仅 published 可对话
        if (!"published".equals(orgSkill.getStatus())) {
            TraceContext.clear();
            return Flux.just(ChatChunk.error("组织分身未发布"));
        }

        // 解析成员 spaceId 列表
        List<UUID> spaceIds = orgSkillService.resolveMemberSpaceIds(orgSkill);
        if (spaceIds.isEmpty()) {
            TraceContext.clear();
            return Flux.just(ChatChunk.error("组织分身尚未关联任何已发布成员分身"));
        }

        LocalDateTime now = LocalDateTime.now();
        String mode = skillService.resolveMode(request.getMode(), request.getMessage());
        final boolean trial = request.isTrialPersist();
        boolean record = !trial;

        UUID convId = convPersistence.upsertConversation(orgSkillId, userId, request, mode, now, null);

        Flux<ChatChunk> replay = replayIfDuplicate(record, convId, request.getClientMessageId());
        if (replay != null) {
            return replay;
        }

        // RAG — 多 space 语义检索
        String ragHistory = record ? ragPipelineService.buildRagHistory(convId) : request.getHistory();
        String domain = orgSkill.getDomain() != null ? orgSkill.getDomain() : "sales";
        String ragQuery = ragPipelineService.rewriteQuery(request.getMessage(), ragHistory, domain, orgSkillId);
        final UUID orgCallerCompanyId = userRepository.findById(userId)
                .map(User::getCompanyId).orElse(null);
        final KcScopeResolver.KcScope orgKcScope = kcScopeResolver.resolve(orgSkill, orgCallerCompanyId);
        RagPipelineService.RagContext ragCtx = new RagPipelineService.RagContext(
                orgSkillId, convId, request.getMessage(), orgKcScope.external(), !trial);
        // Phase B：先查知识/案例；命中时不记知识缺口
        final KnowledgeCaseRetriever.KnowledgeCaseHits kcHits = knowledgeCaseContextService.retrieveSafely(
                ragQuery, orgKcScope.companyId(), "org");
        if (record) {
            logKcReferences(orgSkillId, convId, kcHits, orgKcScope, "org_chat");
        }
        final boolean kcHit = !kcHits.knowledge().isEmpty() || !kcHits.cases().isEmpty();
        final RagPipelineService.GrainResult grains = getGrainsSafely(ragQuery, spaceIds, ragTopK, domain, ragCtx, "org",
                !trial && !Boolean.TRUE.equals(request.getIsTest()) && !kcHit);

        final UUID persistedGrainId = grains.grains().isEmpty() ? null : grains.grains().get(0).getId();
        final UUID persistedReportId = grains.grains().stream()
                .map(ExperienceGrain::getReportId)
                .filter(id -> id != null).findFirst().orElse(null);
        final String persistedGrainIds = grains.grains().stream().limit(3)
                .map(g -> g.getId().toString()).collect(Collectors.joining(","));
        final Double persistedAvgSimilarity = avgSimilarityOf(grains);

        String kcContext = PromptSecurity.wrapUntrusted(
                knowledgeCaseContextService.buildAssistantContext(kcHits));
        String systemPrompt = promptAssembly.buildOrgSkillSystemPrompt(
                orgSkill, grains.grains(), grains.tiers(), grains.similarities(), mode, request.getChannel())
                + kcContext;
        boolean skillsEnabled = agentSkillSettingService.isEnabled(
                com.aiextract.config.TokenContext.getCompanyId());
        systemPrompt = agentSkillPromptInjector.append(systemPrompt,
                com.aiextract.config.TokenContext.getCompanyId(), skillsEnabled);
        log.info("③ 组织分身SystemPrompt构建完成 {}chars skillsEnabled={}", systemPrompt.length(), skillsEnabled);
        List<Map<String, String>> messages = promptAssembly.buildChatMessages(systemPrompt, request.getMessage(),
                convId, request.getHistory());
        Map<String, Object> context = Map.of("mode", mode, "skillId", orgSkillId.toString(),
                "conversationId", convId.toString(), "agentSkills", skillsEnabled);

        // ── Phase 2 & 3 ──
        final UUID finalConvId = convId;
        final String finalMode = mode;
        final long tAiStart = System.currentTimeMillis();
        final StringBuilder aiContent = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean hasStreamError = new java.util.concurrent.atomic.AtomicBoolean(false);

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
                    log.info("④ 组织分身AI流式完成 {}ms contentLen={}", aiMs, aiContent.length());
                    convPersistence.saveAiMessage(finalConvId, record, aiContent.toString(), finalMode, now, null,
                            persistedGrainId, persistedReportId, persistedGrainIds, persistedAvgSimilarity,
                            request.getClientMessageId(), knowledgeCaseContextService.sourceRefsJson(kcHits));
                })
                .timeout(Duration.ofSeconds(chatTimeoutSeconds))
                .doOnError(e -> {
                    hasStreamError.set(true);
                    log.error("组织分身SSE流超时或异常", e);
                })
                .onErrorResume(err -> {
                    AiErrorMapper.AiError aiError = AiErrorMapper.map(err);
                    return Flux.just(ChatChunk.error(aiError.code(), aiError.message()));
                });

        Flux<ChatChunk> postStream = Flux.defer(() -> {
            long totalMs = System.currentTimeMillis() - t0;
            log.info("✅ 组织分身问答完成 total={}ms", totalMs);
            return Flux.concat(
                    Flux.just(ChatChunk.meta(finalConvId.toString())),
                    buildMultiSpaceSourceChunkFlux(spaceIds, grains.grains(), grains.similarities()),
                    knowledgeCaseContextService.buildSourceFlux(kcHits)
            );
        });

        return Flux.concat(aiStream, postStream)
                .doFinally(s -> {
                    if (trial) {
                        TraceContext.clear();
                        return;
                    }
                    try {
                        double avgSim = grains.similarities().isEmpty() ? 0
                                : grains.similarities().values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        String errorType = hasStreamError.get() ? "stream_error" : null;
                        conversationStatsRepository.save(com.aiextract.model.ConversationStats.builder()
                                .id(UUID.randomUUID())
                                .skillId(orgSkillId)
                                .skillType("organization")
                                .companyId(orgKcScope.skillCompanyId())
                                .conversationId(finalConvId)
                                .userId(userId)
                                .mode(finalMode)
                                .ragHighCount((int) grains.tiers().values().stream().filter("high"::equals).count())
                                .ragRefCount((int) grains.tiers().values().stream().filter("ref"::equals).count())
                                .ragNoneCount(grains.grains().isEmpty() && kcHits.knowledge().isEmpty()
                                        && kcHits.cases().isEmpty() ? 1 : 0)
                                .kcKnowledgeCount(kcHits.knowledge().size())
                                .kcCaseCount(kcHits.cases().size())
                                .ragAvgSimilarity(avgSim)
                                .errorType(errorType)
                                .isTest(Boolean.TRUE.equals(request.getIsTest()))
                                .llmDurationMs((int) (System.currentTimeMillis() - tAiStart))
                                .totalDurationMs((int) (System.currentTimeMillis() - t0))
                                .createdAt(LocalDateTime.now())
                                .build());
                    } catch (Exception e) {
                        log.warn("写入组织分身conversation_stats失败 convId={}: {}", finalConvId, e.getMessage());
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

        // 知识/案例注入（受公司级开关控制）：关闭时零检索；开启时只给客户侧字段
        final UUID callerCompanyId = com.aiextract.config.TokenContext.getCompanyId();
        final boolean kcEnabled = practiceKcSettingService.isEnabledForSkill(skill, callerCompanyId);
        final KnowledgeCaseRetriever.KnowledgeCaseHits practiceKcHits = kcEnabled
                ? knowledgeCaseContextService.retrieveSafely(
                        buildPracticeQuery(request),
                        kcScopeResolver.resolve(skill, callerCompanyId).companyId(),
                        "practice-respond:" + skillId)
                : KnowledgeCaseContextService.emptyHits();
        final String kcCustomerContext = kcEnabled
                ? knowledgeCaseContextService.buildCustomerRoleContext(practiceKcHits.cases())
                : "";

        String systemPrompt = promptAssembly.buildPracticeSystemPrompt(skill, request, kcCustomerContext);
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", request.getMessage())
        );

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
            .transform(ChatStreamService::retryBeforeFirstContent)
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

        Flux<ChatChunk> practiceSource = Flux.defer(() -> {
            if (request.getSceneTag() == null) return Flux.empty();
            try {
                var grains = grainRepository.findBySpaceIdAndSceneTagAndStatus(
                    skill.getSpaceId(), request.getSceneTag(), "active");
                if (grains.isEmpty()) return Flux.empty();
                return buildSourceChunkFlux(skill.getSpaceId(), grains.stream().limit(3).collect(Collectors.toList()), Map.of());
            } catch (Exception e) {
                return Flux.empty();
            }
        });

        final UUID finalPracticeConvId = convId != null ? convId : UUID.randomUUID();
        Flux<ChatChunk> practiceCaseSource = knowledgeCaseContextService.buildCaseSourceFlux(practiceKcHits.cases());
        final boolean anyHit = request.getSceneTag() != null || !practiceKcHits.cases().isEmpty();
        return Flux.concat(practiceStream, practiceSource, practiceCaseSource)
            .doFinally(s -> {
                try {
                    conversationStatsRepository.save(com.aiextract.model.ConversationStats.builder()
                        .id(UUID.randomUUID())
                        .skillId(skill.getId())
                        .skillType("individual")
                        .conversationId(finalPracticeConvId)
                        .mode("practice")
                        .userId(userId)
                        .ragHighCount(request.getSceneTag() != null ? 1 : 0)
                        .ragNoneCount(anyHit ? 0 : 1)
                        .kcKnowledgeCount(0)
                        .kcCaseCount(practiceKcHits.cases().size())
                        // 培训陪练不计入分身互动统计（SkillStatsScheduler 只统计 is_test=false）
                        .isTest(Boolean.TRUE.equals(request.getTrainingMode()))
                        .createdAt(LocalDateTime.now())
                        .build());
                } catch (Exception e) {
                    log.warn("写入practice stats失败: {}", e.getMessage());
                }
            })
            .timeout(Duration.ofSeconds(chatTimeoutSeconds))
            .onErrorResume(err -> {
                AiErrorMapper.AiError aiError = AiErrorMapper.map(err);
                return Flux.just(ChatChunk.error(aiError.code(), aiError.message()));
            });
    }

    /** 对练检索 query：场景标签 + 本轮消息 + 历史尾部（对练不做 LLM 改写，避免每轮多一次调用）。 */
    private String buildPracticeQuery(PracticeRespondRequest request) {
        String history = request.getHistory();
        String tail = history != null && history.length() > 300
                ? history.substring(history.length() - 300) : history;
        return java.util.stream.Stream.of(request.getSceneTag(), request.getMessage(), tail)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(" "));
    }

    // ============================================================
    // 企业总调度问答
    // ============================================================

    public Flux<ChatChunk> enterpriseChat(SkillChatRequest request, UUID companyId) {
        String query = request.getMessage();
        if (query == null || query.isBlank()) {
            return Flux.just(ChatChunk.error("消息不能为空"));
        }
        long t0 = System.currentTimeMillis();
        log.info("═══ 企业总调度问答开始 ═══ companyId={} msg={}",
                companyId, query.substring(0, Math.min(50, query.length())));

        // 收集公司下所有 space
        List<UUID> companySpaceIds = spaceRepository.findByUserIdIn(
                userRepository.findByCompanyId(companyId).stream().map(User::getId).toList()
        ).stream().map(Space::getId).toList();

        if (companySpaceIds.isEmpty()) {
            return Flux.just(ChatChunk.error("企业暂无可用分身"));
        }

        // RAG 管道：查询改写 + 多空间向量检索 + 分层
        String domain = "sales.b2b_enterprise";
        String ragQuery = ragPipelineService.rewriteQuery(query, null, domain, null);
        UUID pseudoConvId = UUID.randomUUID();
        RagPipelineService.RagContext ragCtx = new RagPipelineService.RagContext(
                null, pseudoConvId, query, false);
        // Phase B：先查知识/案例；命中时不记知识缺口
        final KcScopeResolver.KcScope enterpriseScope = kcScopeResolver.forCompany(companyId);
        final KnowledgeCaseRetriever.KnowledgeCaseHits kcHits = knowledgeCaseContextService.retrieveSafely(
                ragQuery, enterpriseScope.companyId(), "enterprise");
        logKcReferences(null, pseudoConvId, kcHits, enterpriseScope, "enterprise_chat");
        final boolean kcHit = !kcHits.knowledge().isEmpty() || !kcHits.cases().isEmpty();
        final RagPipelineService.GrainResult grains = getGrainsSafely(ragQuery, companySpaceIds, ragTopK, domain, ragCtx, "enterprise",
                !Boolean.TRUE.equals(request.getIsTest()) && !kcHit);

        // 组装 prompt + 流式
        final long tAiStart = System.currentTimeMillis();
        final StringBuilder aiContent = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean hasStreamError = new java.util.concurrent.atomic.AtomicBoolean(false);

        String kcContext = PromptSecurity.wrapUntrusted(
                knowledgeCaseContextService.buildAssistantContext(kcHits));
        String systemPrompt = promptAssembly.buildEnterpriseSystemPromptV2(
                ragQuery, grains.grains(), grains.tiers(), grains.similarities(), companySpaceIds, domain)
                + kcContext;
        boolean skillsEnabled = agentSkillSettingService.isEnabled(companyId);
        systemPrompt = agentSkillPromptInjector.append(systemPrompt, companyId, skillsEnabled);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", query)
        );

        Flux<ChatChunk> aiStream = chatStreamAdapter.chatStream(messages,
                        Map.of("mode", "enterprise", "agentSkills", skillsEnabled))
                .map(event -> {
                    ChatChunk chunk = ChatChunk.fromEventMap(event);
                    if ("content".equals(chunk.getType()) && chunk.getContent() != null) {
                        aiContent.append(chunk.getContent());
                    }
                    return chunk;
                })
                .timeout(Duration.ofSeconds(chatTimeoutSeconds))
                .doOnError(e -> {
                    hasStreamError.set(true);
                    log.error("企业调度SSE流异常", e);
                })
                .onErrorResume(err -> {
                    AiErrorMapper.AiError aiError = AiErrorMapper.map(err);
                    return Flux.just(ChatChunk.error(aiError.code(), aiError.message()));
                });

        Flux<ChatChunk> postStream = Flux.defer(() -> {
            long totalMs = System.currentTimeMillis() - t0;
            log.info("✅ 企业调度完成 total={}ms grains={}", totalMs, grains.grains().size());
            return Flux.concat(
                    Flux.just(ChatChunk.meta(pseudoConvId.toString())),
                    buildEnterpriseSourceChunkFlux(grains.grains(), grains.similarities()),
                    knowledgeCaseContextService.buildSourceFlux(kcHits)
            );
        });

        return Flux.concat(aiStream, postStream)
                .doFinally(s -> {
                    try {
                        double avgSim = grains.similarities().isEmpty() ? 0
                                : grains.similarities().values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        String errorType = hasStreamError.get() ? "stream_error" : null;
                        conversationStatsRepository.save(com.aiextract.model.ConversationStats.builder()
                                .id(UUID.randomUUID())
                                .skillId(companyId)
                                .skillType("enterprise")
                                .companyId(companyId)
                                .conversationId(pseudoConvId)
                                .userId(com.aiextract.config.TokenContext.get())
                                .mode("enterprise")
                                .ragHighCount((int) grains.tiers().values().stream().filter("high"::equals).count())
                                .ragRefCount((int) grains.tiers().values().stream().filter("ref"::equals).count())
                                .ragNoneCount(grains.grains().isEmpty() && kcHits.knowledge().isEmpty()
                                        && kcHits.cases().isEmpty() ? 1 : 0)
                                .kcKnowledgeCount(kcHits.knowledge().size())
                                .kcCaseCount(kcHits.cases().size())
                                .ragAvgSimilarity(avgSim)
                                .errorType(errorType)
                                .isTest(false)
                                .llmDurationMs((int) (System.currentTimeMillis() - tAiStart))
                                .totalDurationMs((int) (System.currentTimeMillis() - t0))
                                .createdAt(LocalDateTime.now())
                                .build());
                    } catch (Exception e) {
                        log.warn("写入enterprise stats失败: {}", e.getMessage());
                    }
                });
    }

    /**
     * 企业总调度同步版 — 适配飞书/企微回调。
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
            .blockLast(Duration.ofSeconds(chatTimeoutSeconds));
        return CompletableFuture.completedFuture(sb.length() > 0 ? sb.toString() : "抱歉，AI服务暂时不可用。");
    }

    // ============================================================
    // 对练综合评价
    // ============================================================

    public Flux<ChatChunk> evaluatePractice(String skillId, String conversation, String scene, UUID userId) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId)).orElse(null);
        String name = skill != null && skill.getOwnerName() != null ? skill.getOwnerName() : "销冠";
        String domain = domainConfigLoader.resolveDomain(skill);
        String evalPrompt = promptAssembly.buildPracticeEvaluatePrompt(name, conversation, scene, List.of(), domain);
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", evalPrompt),
            Map.of("role", "user", "content", "请输出评价")
        );
        final UUID skillUuid = skill != null ? skill.getId() : UUID.fromString(skillId);
        final StringBuilder accumulated = new StringBuilder();
        return chatStreamAdapter.chatStream(messages, Map.of("mode", "evaluate"))
            .map(ChatChunk::fromEventMap)
            .doOnNext(chunk -> {
                if ("content".equals(chunk.getType()) && chunk.getContent() != null) {
                    accumulated.append(chunk.getContent());
                }
            })
            .doOnComplete(() -> persistEvaluation(skillUuid, accumulated.toString(), name, userId))
            .timeout(Duration.ofSeconds(chatTimeoutSeconds))
            .onErrorResume(err -> {
                AiErrorMapper.AiError aiError = AiErrorMapper.map(err);
                return Flux.just(ChatChunk.error(aiError.code(), aiError.message()));
            });
    }

    /**
     * 解析 AI 评估 JSON 并持久化到 skill_evaluation 表。
     * 解析失败时静默吞异常 — 评估结果仍通过 SSE 到达前端，只是不存档。
     */
    private void persistEvaluation(UUID skillId, String rawJson, String ownerName, UUID userId) {
        if (rawJson == null || rawJson.isBlank()) return;
        try {
            // 提取 JSON 对象（AI 可能前后加 markdown fence 或中文说明）
            int start = rawJson.indexOf('{');
            int end = rawJson.lastIndexOf('}');
            if (start < 0 || end <= start) return;
            String jsonStr = rawJson.substring(start, end + 1);

            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            var map = om.readValue(jsonStr, java.util.Map.class);

            com.aiextract.model.SkillEvaluation eval = com.aiextract.model.SkillEvaluation.builder()
                .skillId(skillId)
                .mode("practice")
                .evaluatorId(userId)
                .score(map.get("score") instanceof Number s ? s.intValue() : null)
                .styleScore(map.get("style_score") instanceof Number s ? s.intValue() : null)
                .consistencyScore(map.get("consistency_score") instanceof Number s ? s.intValue() : null)
                .behaviorScore(map.get("behavior_score") instanceof Number s ? s.intValue() : null)
                .scriptReuseScore(map.get("script_reuse_score") instanceof Number s ? s.intValue() : null)
                .scoreDetail(om.writeValueAsString(map.getOrDefault("score_detail", "")))
                .strengths(om.writeValueAsString(map.getOrDefault("strengths", java.util.List.of())))
                .improvements(om.writeValueAsString(map.getOrDefault("improvements", java.util.List.of())))
                .demoScript(map.get("demo_script") instanceof String s ? s : null)
                .createdAt(java.time.LocalDateTime.now())
                .build();
            skillEvaluationRepository.save(eval);
            log.info("对练评估已持久化 skillId={} score={}", skillId, eval.getScore());
        } catch (Exception e) {
            log.warn("对练评估持久化失败 skillId={}: {}", skillId, e.getMessage());
        }
    }

    // ============================================================
    // 会话持久化 — 已迁移到 ConversationPersistenceService；chat() 通过 convPersistence 委托
    // ============================================================

    // ============================================================
    // 溯源 — 企业调度 & 组织分身
    // ============================================================

    /**
     * 多空间溯源 SSE — 企业调度和组织分身共用。
     * 预加载 space→ownerName 映射，标注来源销冠姓名。
     */
    private Flux<ChatChunk> buildMultiSpaceSourceChunkFlux(List<UUID> spaceIds,
            List<ExperienceGrain> grains, Map<UUID, Double> similarities) {
        if (grains.isEmpty() || spaceIds.isEmpty()) return Flux.empty();

        Map<UUID, String> ownerNames = new java.util.HashMap<>();
        try {
            List<Space> spaces = spaceRepository.findAllById(spaceIds);
            List<UUID> userIds = spaces.stream().map(Space::getUserId).distinct().collect(Collectors.toList());
            Map<UUID, String> userNames = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
            for (Space sp : spaces) {
                ownerNames.put(sp.getId(), userNames.getOrDefault(sp.getUserId(), "销冠"));
            }
        } catch (Exception e) {
            log.warn("加载多空间溯源销冠名失败: {}", e.getMessage());
        }

        String grainIds = grains.stream().map(g -> g.getId().toString()).collect(Collectors.joining(","));
        String grainTags = grains.stream()
                .map(g -> {
                    String name = ownerNames.getOrDefault(g.getSpaceId(), "");
                    String tag = g.getSceneTag() != null ? g.getSceneTag() : "";
                    return name.isEmpty() ? tag : name + "·" + tag;
                })
                .collect(Collectors.joining(","));
        double avgSim = similarities.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return Flux.just(ChatChunk.source("", "", grainIds, grainTags,
                Math.min(grains.size(), 5), String.format("%.2f", avgSim),
                String.format("%.0f", avgSim * 100), ""));
    }

    /** 企业调度溯源 — 从 grains 推导 spaceIds 后委托多空间方法。 */
    private Flux<ChatChunk> buildEnterpriseSourceChunkFlux(
            List<ExperienceGrain> grains, Map<UUID, Double> similarities) {
        if (grains.isEmpty()) return Flux.empty();
        List<UUID> spaceIds = grains.stream().map(ExperienceGrain::getSpaceId).distinct().collect(Collectors.toList());
        return buildMultiSpaceSourceChunkFlux(spaceIds, grains, similarities);
    }

    /**
     * OPS-03 幂等重放：同一会话内 clientMessageId 已有 AI 回复时，直接回放已存内容，
     * 不再重复跑 RAG / 模型，也不重复落库。返回 null 表示不是重复请求。
     */
    private Flux<ChatChunk> replayIfDuplicate(boolean record, UUID convId, String clientMessageId) {
        if (!record || clientMessageId == null || clientMessageId.isBlank()) {
            return null;
        }
        return skillMessageRepository
                .findFirstByConversationIdAndClientMessageIdAndRole(convId, clientMessageId.trim(), "assistant")
                .map(msg -> {
                    log.info("幂等命中，重放已存回复 convId={} clientMessageId={}", convId, clientMessageId);
                    return Flux.concat(
                            Flux.just(ChatChunk.meta(convId.toString())),
                            Flux.just(ChatChunk.content(msg.getContent())),
                            Flux.just(ChatChunk.done()));
                })
                .orElse(null);
    }

    /** 持久化用的平均相似度（0-100）；无相似度数据时返回 null，而不是伪造 0 */
    private Double avgSimilarityOf(RagPipelineService.GrainResult grains) {
        if (grains.grains().isEmpty() || grains.similarities() == null || grains.similarities().isEmpty()) {
            return null;
        }
        OptionalDouble avg = grains.grains().stream().limit(3)
                .map(g -> grains.similarities().get(g.getId()))
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
        return avg.isPresent() ? avg.getAsDouble() * 100 : null;
    }

    private Flux<ChatChunk> buildSourceChunkFlux(UUID spaceId, List<ExperienceGrain> grains,
                                                   Map<UUID, Double> similarities) {
        try {
            List<ExperienceGrain> topGrains = grains.stream().limit(3).toList();

            String grainIds = topGrains.stream()
                .map(g -> g.getId().toString()).collect(Collectors.joining(","));
            String grainTags = topGrains.stream()
                .map(g -> g.getSceneTag() != null ? g.getSceneTag() : "")
                .filter(t -> !t.isEmpty()).distinct().collect(Collectors.joining(","));

            // 批量加载 source material + interview session，消除 N+1
            List<UUID> materialIds = topGrains.stream()
                .map(ExperienceGrain::getSourceMaterialId).filter(id -> id != null).distinct().toList();
            List<UUID> interviewIds = topGrains.stream()
                .map(ExperienceGrain::getSourceInterviewId).filter(id -> id != null).distinct().toList();
            Map<UUID, String> materialNames = materialIds.isEmpty() ? Map.of()
                : skillMaterialRepository.findAllById(materialIds).stream()
                    .filter(m -> m.getFileName() != null)
                    .collect(Collectors.toMap(
                        com.aiextract.model.SkillMaterial::getId,
                        com.aiextract.model.SkillMaterial::getFileName,
                        (a, b) -> a));
            Map<UUID, String> interviewNames = interviewIds.isEmpty() ? Map.of()
                : interviewSessionRepository.findAllById(interviewIds).stream()
                    .filter(s -> s.getTopic() != null)
                    .collect(Collectors.toMap(
                        com.aiextract.model.InterviewSession::getId,
                        s -> "访谈: " + s.getTopic(),
                        (a, b) -> a));
            java.util.Set<String> nameSet = new java.util.LinkedHashSet<>();
            materialIds.forEach(id -> { if (materialNames.containsKey(id)) nameSet.add(materialNames.get(id)); });
            interviewIds.forEach(id -> { if (interviewNames.containsKey(id)) nameSet.add(interviewNames.get(id)); });
            String sourceNames = String.join(", ", nameSet);

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

    private RagPipelineService.GrainResult getGrainsSafely(String query, UUID spaceId, int topK, String domain,
                                                          RagPipelineService.RagContext ragCtx, String label,
                                                          boolean recordKnowledgeGap) {
        try {
            return ragPipelineService.retrieveGrainsWithScores(query, spaceId, topK, domain, ragCtx, recordKnowledgeGap);
        } catch (Exception e) {
            log.warn("RAG检索失败({})，降级为空: {}", label, e.getMessage());
            return new RagPipelineService.GrainResult(List.of(), Map.of(), Map.of());
        }
    }

    private RagPipelineService.GrainResult getGrainsSafely(String query, List<UUID> spaceIds, int topK, String domain,
                                                          RagPipelineService.RagContext ragCtx, String label,
                                                          boolean recordKnowledgeGap) {
        try {
            return ragPipelineService.retrieveGrainsWithScores(query, spaceIds, topK, domain, ragCtx, recordKnowledgeGap);
        } catch (Exception e) {
            log.warn("RAG检索失败({})，降级为空: {}", label, e.getMessage());
            return new RagPipelineService.GrainResult(List.of(), Map.of(), Map.of());
        }
    }

    // ============================================================
    // 知识/案例检索追加（Phase B，独立于 grain 主路径）
    // ============================================================

    /**
     * 记录知识/案例引用（供详情页「被引用」与废弃影响面）。写日志失败不影响对话。
     * 组织总调度可能没有单一 skillId，仍记录（skill_id 允许为空）；未发布分身由调用方跳过。
     */
    private void logKcReferences(UUID skillId, UUID conversationId,
                                 KnowledgeCaseRetriever.KnowledgeCaseHits hits,
                                 KcScopeResolver.KcScope scope, String usageType) {
        if (hits.knowledge().isEmpty() && hits.cases().isEmpty()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            String effectiveUsage = scope.external() ? "public_chat" : usageType;
            List<com.aiextract.model.KnowledgeCaseRetrieveLog> rows = new java.util.ArrayList<>();
            for (var k : hits.knowledge()) {
                rows.add(com.aiextract.model.KnowledgeCaseRetrieveLog.builder()
                        .id(UUID.randomUUID()).knowledgeId(k.id()).skillId(skillId)
                        .companyId(scope.skillCompanyId()).usageType(effectiveUsage)
                        .usageRefId(conversationId).conversationId(conversationId)
                        .similarity(k.similarity()).createdAt(now).build());
            }
            for (var c : hits.cases()) {
                rows.add(com.aiextract.model.KnowledgeCaseRetrieveLog.builder()
                        .id(UUID.randomUUID()).caseId(c.id()).skillId(skillId)
                        .companyId(scope.skillCompanyId()).usageType(effectiveUsage)
                        .usageRefId(conversationId).conversationId(conversationId)
                        .similarity(c.similarity()).createdAt(now).build());
            }
            retrieveLogRepository.saveAll(rows);
        } catch (Exception e) {
            log.warn("写知识/案例引用日志失败 skillId={}: {}", skillId, e.getMessage());
        }
    }

    /**
     * 只在尚未输出任何 content 时重试一次。
     *
     * <p>已向客户端吐字后重放，会让用户看到重复内容、供应商重复计费，
     * 落库的 AI 回复也会重复拼接。</p>
     */
    private static <T> Flux<T> retryBeforeFirstContent(Flux<T> source) {
        java.util.concurrent.atomic.AtomicBoolean emitted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        return source
                .doOnNext(event -> {
                    if (event instanceof Map<?, ?> map && "content".equals(map.get("type"))) {
                        emitted.set(true);
                    }
                })
                .retryWhen(reactor.util.retry.Retry.max(1).filter(error -> !emitted.get()));
    }
}
