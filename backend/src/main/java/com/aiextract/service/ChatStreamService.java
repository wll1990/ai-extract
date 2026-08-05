package com.aiextract.service;

import java.util.concurrent.CompletableFuture;

import com.aiextract.common.ErrorMessages;
import com.aiextract.common.TraceContext;
import com.aiextract.dto.PracticeRespondRequest;
import org.springframework.scheduling.annotation.Async;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.config.DomainConfig;
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
    private final GrainRecommendationService grainRec;
    private final com.aiextract.repository.SkillEvaluationRepository skillEvaluationRepository;
    private final OrganizationSkillService orgSkillService;

    @Value("${app.chat.timeout-seconds:120}")
    private int chatTimeoutSeconds;

    @Value("${app.rag.top-k:5}")
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

        boolean isAdmin = userRepository.findById(userId)
            .map(u -> RolePermissions.hasPermission(u.getRole(), Permission.SKILL_MANAGE)).orElse(false);
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
        boolean record = "published".equals(skill.getStatus());
        LocalDateTime now = LocalDateTime.now();

        UUID convId = record ? convPersistence.upsertConversation(skillId, userId, request, mode, now, skill) : UUID.randomUUID();

        // RAG — 委托 RagPipelineService
        String ragHistory = record ? ragPipelineService.buildRagHistory(convId) : request.getHistory();
        String domain = domainConfigLoader.resolveDomain(skill);
        String ragQuery = ragPipelineService.rewriteQuery(request.getMessage(), ragHistory, domain, skill.getId());
        RagPipelineService.RagContext ragCtx = new RagPipelineService.RagContext(skill.getId(), convId, request.getMessage());
        RagPipelineService.GrainResult grains = ragPipelineService.retrieveGrainsWithScores(ragQuery, skill.getSpaceId(), ragTopK, domain, ragCtx);

        final UUID persistedGrainId = grains.grains().isEmpty() ? null : grains.grains().get(0).getId();
        final UUID persistedReportId = grains.grains().stream()
            .map(ExperienceGrain::getReportId)
            .filter(id -> id != null).findFirst().orElse(null);

        String systemPrompt = promptAssembly.buildSkillSystemPrompt(
            skill, grains.grains(), grains.tiers(), grains.similarities(), mode, request.getChannel());
        log.info("③ SystemPrompt构建完成 {}chars", systemPrompt.length());
        List<Map<String, String>> messages = promptAssembly.buildChatMessages(systemPrompt, request.getMessage(),
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
                convPersistence.saveAiMessage(finalConvId, record, aiContent.toString(), finalMode, now, skill,
                    persistedGrainId, persistedReportId);
            })
            .timeout(Duration.ofSeconds(chatTimeoutSeconds))
            .doOnError(e -> {
                hasStreamError.set(true);
                log.error("SSE流超时或异常", e);
            })
            .onErrorResume(err -> Flux.just(ChatChunk.error(ErrorMessages.AI_SERVICE_UNAVAILABLE)));

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
                Flux.just(ChatChunk.meta(finalConvId.toString())),
                buildSourceChunkFlux(skill.getSpaceId(), grains.grains(), grains.similarities()),
                suggestedFlux
            );
        });

        return Flux.concat(profileWarning, aiStream, postStream)
            .doFinally(s -> {
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
        boolean record = true;

        UUID convId = convPersistence.upsertConversation(orgSkillId, userId, request, mode, now, null);

        // RAG — 多 space 语义检索
        String ragHistory = record ? ragPipelineService.buildRagHistory(convId) : request.getHistory();
        String domain = orgSkill.getDomain() != null ? orgSkill.getDomain() : "sales";
        String ragQuery = ragPipelineService.rewriteQuery(request.getMessage(), ragHistory, domain, orgSkillId);
        RagPipelineService.RagContext ragCtx = new RagPipelineService.RagContext(orgSkillId, convId, request.getMessage());
        RagPipelineService.GrainResult grains = ragPipelineService.retrieveGrainsWithScores(
                ragQuery, spaceIds, ragTopK, domain, ragCtx);

        final UUID persistedGrainId = grains.grains().isEmpty() ? null : grains.grains().get(0).getId();
        final UUID persistedReportId = grains.grains().stream()
                .map(ExperienceGrain::getReportId)
                .filter(id -> id != null).findFirst().orElse(null);

        String systemPrompt = promptAssembly.buildOrgSkillSystemPrompt(
                orgSkill, grains.grains(), grains.tiers(), grains.similarities(), mode, request.getChannel());
        log.info("③ 组织分身SystemPrompt构建完成 {}chars", systemPrompt.length());
        List<Map<String, String>> messages = promptAssembly.buildChatMessages(systemPrompt, request.getMessage(),
                convId, request.getHistory());
        Map<String, Object> context = Map.of("mode", mode, "skillId", orgSkillId.toString(),
                "conversationId", convId.toString());

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
                            persistedGrainId, persistedReportId);
                })
                .timeout(Duration.ofSeconds(chatTimeoutSeconds))
                .doOnError(e -> {
                    hasStreamError.set(true);
                    log.error("组织分身SSE流超时或异常", e);
                })
                .onErrorResume(err -> Flux.just(ChatChunk.error(ErrorMessages.AI_SERVICE_UNAVAILABLE)));

        Flux<ChatChunk> postStream = Flux.defer(() -> {
            long totalMs = System.currentTimeMillis() - t0;
            log.info("✅ 组织分身问答完成 total={}ms", totalMs);
            return Flux.concat(
                    Flux.just(ChatChunk.meta(finalConvId.toString())),
                    buildMultiSpaceSourceChunkFlux(spaceIds, grains.grains(), grains.similarities())
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
                                .skillId(orgSkillId)
                                .skillType("organization")
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

        String systemPrompt = promptAssembly.buildPracticeSystemPrompt(skill, request);
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
        return Flux.concat(practiceStream, practiceSource)
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
                        .ragNoneCount(request.getSceneTag() != null ? 0 : 1)
                        .createdAt(LocalDateTime.now())
                        .build());
                } catch (Exception e) {
                    log.warn("写入practice stats失败: {}", e.getMessage());
                }
            })
            .timeout(Duration.ofSeconds(chatTimeoutSeconds))
            .onErrorResume(err -> Flux.just(ChatChunk.error("服务异常")));
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
        RagPipelineService.RagContext ragCtx = new RagPipelineService.RagContext(null, pseudoConvId, query);
        RagPipelineService.GrainResult grains = ragPipelineService.retrieveGrainsWithScores(
                ragQuery, companySpaceIds, ragTopK, domain, ragCtx);

        // 组装 prompt + 流式
        final long tAiStart = System.currentTimeMillis();
        final StringBuilder aiContent = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean hasStreamError = new java.util.concurrent.atomic.AtomicBoolean(false);

        String systemPrompt = promptAssembly.buildEnterpriseSystemPromptV2(
                ragQuery, grains.grains(), grains.tiers(), grains.similarities(), companySpaceIds, domain);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", query)
        );

        Flux<ChatChunk> aiStream = chatStreamAdapter.chatStream(messages, Map.of("mode", "enterprise"))
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
                .onErrorResume(err -> Flux.just(ChatChunk.error(ErrorMessages.AI_SERVICE_UNAVAILABLE)));

        Flux<ChatChunk> postStream = Flux.defer(() -> {
            long totalMs = System.currentTimeMillis() - t0;
            log.info("✅ 企业调度完成 total={}ms grains={}", totalMs, grains.grains().size());
            return Flux.concat(
                    Flux.just(ChatChunk.meta(pseudoConvId.toString())),
                    buildEnterpriseSourceChunkFlux(grains.grains(), grains.similarities())
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
                                .conversationId(pseudoConvId)
                                .userId(UUID.randomUUID())
                                .mode("enterprise")
                                .ragHighCount((int) grains.tiers().values().stream().filter("high"::equals).count())
                                .ragRefCount((int) grains.tiers().values().stream().filter("ref"::equals).count())
                                .ragNoneCount(grains.grains().isEmpty() ? 1 : 0)
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
            .onErrorResume(err -> Flux.just(ChatChunk.error("服务异常")));
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
}
