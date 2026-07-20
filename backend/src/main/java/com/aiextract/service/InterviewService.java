package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.dto.CreateInterviewRequest;
import com.aiextract.dto.InterviewMessageResponse;
import com.aiextract.dto.InterviewSessionResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.ExpertGrain;
import com.aiextract.model.ExpertSkill;
import com.aiextract.model.InterviewMessage;
import com.aiextract.model.InterviewSession;
import com.aiextract.model.Space;
import com.aiextract.repository.ExpertGrainRepository;
import com.aiextract.repository.ExpertSkillRepository;
import com.aiextract.repository.InterviewMessageRepository;
import com.aiextract.repository.InterviewSessionRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 访谈服务 — 管理 AI 萃取访谈的完整生命周期。
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>用户创建访谈会话 → {@link #createSession}</li>
 *   <li>AI 开场引导 → 用户回复 → AI 追问（SSE 流式）→ {@link #processMessageFlux}</li>
 *   <li>四阶段推进: 开场定调 → 故事深描 → 模型提炼 → 收网确认</li>
 *   <li>完成时触发报告生成</li>
 * </ol>
 *
 * <h3>两种访谈类型</h3>
 * <ul>
 *   <li><b>sales</b>: 销冠萃取，从销冠对话中提取经验颗粒</li>
 *   <li><b>expert</b>: 萃取师访谈，由萃取师引导提炼 7 类方法论颗粒</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    // ==================== 依赖注入 ====================

    private final InterviewSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final InterviewMessageRepository messageRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final ReportGenerationService reportGenerationService;
    private final ReportRepository reportRepository;
    private final ExpertSkillRepository expertSkillRepository;
    private final ExpertGrainRepository expertGrainRepository;
    private final SpaceRepository spaceRepository;
    private final PromptLoader promptLoader;
    private final DomainConfigLoader domainConfigLoader;
    private final SkillRepository skillRepository;
    private final InterviewTranscriptExtractor interviewTranscriptExtractor;
    private final ExpertInterviewProcessor expertInterviewProcessor;

    /** 自注入代理，用于解决同类内方法调用的事务代理问题 */
    @Autowired
    @Lazy
    private InterviewService self;

    // 使用 @RequiredArgsConstructor (Lombok)，无需手动构造函数

    // ==================== 常量 ====================

    private static final String PHASE_OPENING = "opening";
    private static final String PHASE_STORYTELLING = "storytelling";
    private static final String PHASE_MODELING = "modeling";
    private static final String PHASE_CLOSING = "closing";

    private static final String STATUS_CREATED = "created";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_PAUSED = "paused";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_ABANDONED = "abandoned";

    /** 活跃状态：查询当前用户有未完成访谈时使用 */
    private static final List<String> ACTIVE_STATUSES = Arrays.asList(STATUS_CREATED, STATUS_IN_PROGRESS, STATUS_PAUSED);

    /** 可中断状态：只有这两种状态允许暂停和恢复 */
    private static final List<String> INTERRUPTIBLE_STATUSES = Arrays.asList(STATUS_IN_PROGRESS, STATUS_PAUSED);

    // ==================== 公开 API — Controller 直接调用 ====================

    /**
     * 创建访谈会话。
     *
     * <p>流程:
     * <ol>
     *   <li>校验空间归属，解析领域配置</li>
     *   <li>判断是否首次访谈（决定开场白风格）</li>
     *   <li>创建 InterviewSession（status=created, phase=opening）</li>
     *   <li>生成 AI 开场引导消息并持久化</li>
     * </ol>
     *
     * @param request       创建请求（spaceId, topic, inviteCode, expertSkillId）
     * @param userId        当前登录用户
     * @param interviewType 访谈类型：sales / expert
     * @return 会话详情（含四阶段进度、采集状态、开场白消息）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionResponse createSession(CreateInterviewRequest request, UUID userId, String interviewType) {
        UUID spaceId = parseUuid(request.getSpaceId());
        log.info("创建访谈会话, spaceId: {}, topic: {}, userId: {}", spaceId, request.getTopic(), userId);

        // 1. 解析萃取师：有指定 → 单个萃取师名称；无 → "综合"
        String expertSkillUsed = resolveExpertSkillUsed(request.getExpertSkillId());
        UUID expertSkillId = parseExpertSkillId(request.getExpertSkillId());

        // 2. 判断是否首次访谈
        long completedCount = sessionRepository.countBySpaceIdAndStatus(spaceId, STATUS_COMPLETED);
        boolean isFirstInterview = completedCount == 0;

        // 3. 解析领域：已有 Skill 直接取，否则用萃取师领域自动建 draft Skill
        com.aiextract.model.Skill skill = skillRepository.findBySpaceId(spaceId).orElseGet(() -> {
            String fallbackDomain = expertSkillId != null
                ? expertSkillRepository.findById(expertSkillId).map(ExpertSkill::getDomain).orElse("sales.b2b_enterprise")
                : "sales.b2b_enterprise";
            com.aiextract.model.Skill s = com.aiextract.model.Skill.builder()
                .id(UUID.randomUUID()).spaceId(spaceId)
                .domain(fallbackDomain).status("generating")
                .modelName("deepseek-chat").modelConfig("{}")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
            log.info("自动创建 draft Skill spaceId={} domain={}", spaceId, fallbackDomain);
            return skillRepository.save(s);
        });
        String domain = domainConfigLoader.resolveDomain(skill);

        // 4. 构建并保存会话实体
        LocalDateTime now = LocalDateTime.now();
        InterviewSession session = InterviewSession.builder()
                .id(UUID.randomUUID())
                .spaceId(spaceId)
                .topic(request.getTopic())
                .status(STATUS_CREATED)
                .currentPhase(PHASE_OPENING)
                .collectCaseStory(false).collectSteps(false).collectDecision(false)
                .collectMindset(false).collectBoundary(false).collectChecklist(false)
                .inviteCode(request.getInviteCode())
                .expertSkillId(expertSkillId)
                .interviewType(interviewType)
                .domain(domain)
                .lastActiveAt(now)
                .createdAt(now)
                .build();
        sessionRepository.save(session);

        // 5. 生成并保存开场引导消息（AI 角色，depth=-1 表示系统引导）
        String openingMessage = generateOpeningMessage(isFirstInterview, interviewType, domain);
        InterviewMessage guideMessage = InterviewMessage.builder()
                .id(UUID.randomUUID())
                .sessionId(session.getId())
                .role("ai")
                .content(openingMessage)
                .phase(PHASE_OPENING)
                .depth(-1)
                .stageStatus("{}")
                .createdAt(now)
                .build();
        messageRepository.save(guideMessage);

        log.info("访谈会话创建完成, sessionId: {}, isFirstInterview: {}", session.getId(), isFirstInterview);
        return buildSessionResponse(session, expertSkillUsed);
    }

    /**
     * 获取会话状态和进度。
     *
     * <p>查询会话基本信息 + 四阶段进度 + 采集状态 + 报告模块预览。
     * 前端据此渲染访谈进度条和阶段指示器。
     */
    @Transactional(readOnly = true)
    public InterviewSessionResponse getSession(String sessionId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("会话不存在, sessionId: {}", sessionId);
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND);
                });
        String expertSkillUsed = resolveExpertSkillUsed(session.getExpertSkillId());
        return buildSessionResponse(session, expertSkillUsed);
    }

    /**
     * 获取会话的历史消息列表（按时间升序）。
     */
    @Transactional(readOnly = true)
    public List<InterviewMessageResponse> getMessages(String sessionId) {
        UUID id = UUID.fromString(sessionId);
        List<InterviewMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(id);

        List<InterviewMessageResponse> responses = new ArrayList<>();
        for (InterviewMessage msg : messages) {
            responses.add(InterviewMessageResponse.builder()
                    .id(msg.getId().toString())
                    .role(msg.getRole())
                    .content(msg.getContent())
                    .depth(msg.getDepth())
                    .phase(msg.getPhase())
                    .createdAt(msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null)
                    .build());
        }
        return responses;
    }

    /**
     * 发送消息并获取 AI 追问（SSE 流式）。
     *
     * <p>核心流程:
     * <ol>
     *   <li>created → in_progress（首次发言自动启动）</li>
     *   <li>持久化用户消息（通过 self 代理确保事务生效）</li>
     *   <li>构建 system prompt（含领域配置 + 萃取师经验 + 当前阶段指令）</li>
     *   <li>加载历史消息作为上下文</li>
     *   <li>通过 ChatStreamAdapter 调用 LLM 流式返回</li>
     * </ol>
     */
    public reactor.core.publisher.Flux<ChatChunk> processMessageFlux(String sessionId, String message) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));

        // 首次发言：created → in_progress
        if (STATUS_CREATED.equals(session.getStatus())) {
            session.setStatus(STATUS_IN_PROGRESS);
            sessionRepository.save(session);
        }

        // 通过 self 代理调用，确保 @Transactional 生效
        self.persistUserMessage(id, session, message);
        session.setLastActiveAt(LocalDateTime.now());

        // 构建对话上下文
        String systemPrompt = loadInterviewSystemPrompt(session);
        List<Map<String, String>> historyMsgs = buildMessagesList(session);

        // 委托 ChatStreamAdapter 调用 LLM，逐 token 返回
        return callChatStream(session, systemPrompt, message, historyMsgs);
    }

    private reactor.core.publisher.Flux<ChatChunk> callChatStream(
            InterviewSession session, String systemPrompt, String userMsg,
            List<Map<String, String>> historyMsgs) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(historyMsgs);
        messages.add(Map.of("role", "user", "content", userMsg));
        Map<String, Object> ctx = buildSessionContext(session);
        return chatStreamAdapter.chatStream(messages, ctx).map(ChatChunk::fromEventMap);
    }

    /**
     * 恢复中断的访谈（SSE 流式）。
     *
     * <p>仅 in_progress 和 paused 状态可恢复。
     * 加载完整历史上下文后，AI 从中断处继续追问。
     */
    public reactor.core.publisher.Flux<ChatChunk> resumeSessionFlux(String sessionId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));

        if (!INTERRUPTIBLE_STATUSES.contains(session.getStatus())) {
            return reactor.core.publisher.Flux.just(
                    ChatChunk.error("当前状态不允许恢复: " + session.getStatus()));
        }

        session.setStatus(STATUS_IN_PROGRESS);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);

        String systemPrompt = loadInterviewSystemPrompt(session);
        List<Map<String, String>> historyMsgs = buildMessagesList(session);

        return callChatStream(session, systemPrompt,
                "继续访谈，请从上次中断的地方继续。", historyMsgs);
    }

    /**
     * 标记当前阶段完成，AI 做阶段小结后推进到下一阶段（SSE 流式）。
     *
     * <p>流程:
     * <ol>
     *   <li>标记当前阶段的采集数据为"已采集"</li>
     *   <li>如果是最后阶段(closing) → 触发报告生成</li>
     *   <li>否则 → 推进到下一阶段，AI 以新阶段角色继续引导</li>
     * </ol>
     */
    public reactor.core.publisher.Flux<ChatChunk> markPhaseCompleteFlux(String sessionId, String phase) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));

        // 标记该阶段的采集数据
        markCollectForPhase(session, phase);
        String nextPhase = getNextPhase(phase);

        // 最后阶段 → 完成会话 + 触发报告生成
        if (PHASE_CLOSING.equals(phase) || nextPhase == null) {
            return reactor.core.publisher.Flux.create(sink -> {
                try {
                    UUID reportId = self.checkAndCompleteSession(session);
                    sink.next(ChatChunk.event("phase_summary", Map.of(
                            "phase", phase,
                            "message", "所有阶段已完成，报告生成中..."
                    )));
                    sink.next(ChatChunk.meta(reportId != null ? reportId.toString() : ""));
                    sink.next(ChatChunk.done());
                    sink.complete();
                } catch (Exception e) {
                    log.error("完成访谈失败", e);
                    sink.next(ChatChunk.error("完成访谈失败: " + e.getMessage()));
                    sink.complete();
                }
            });
        }

        // 推进阶段 → 触发新阶段的 AI 引导
        self.markPhaseAndSaveTransition(id, nextPhase);
        String systemPrompt = loadInterviewSystemPrompt(session);
        List<Map<String, String>> historyMsgs = buildMessagesList(session);

        return callChatStream(session, systemPrompt,
                "阶段 «" + phaseLabel(phase) + "» 已完成，请推进到下一阶段 «" + phaseLabel(nextPhase) + "»",
                historyMsgs);
    }

    /**
     * 强制完成会话。
     *
     * <p>管理员或用户手动结束访谈，触发完成逻辑：
     * 标记所有采集 → 调用萃取管道 → 返回报告 ID。
     */
    @Transactional(rollbackFor = Exception.class)
    public String forceCompleteSession(String sessionId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));

        // 已完成会话：expert 不支持重复触发，sales 幂等重触发萃取管道
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            if ("expert".equals(session.getInterviewType())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "访谈已完成，不能重复强制完成");
            }
            log.info("访谈已完成，重触发萃取管道, sessionId={}", sessionId);
            interviewTranscriptExtractor.extractFromInterview(session.getId());
            return null;
        }

        UUID reportId = checkAndCompleteSession(session);
        return reportId != null ? reportId.toString() : null;
    }

    /**
     * 重新开始访谈。
     *
     * <p>将会话重置为 created 状态 + opening 阶段，
     * 保留历史消息但清除采集进度。
     */
    @Transactional(rollbackFor = Exception.class)
    public void restartSession(String sessionId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        session.setStatus(STATUS_CREATED);
        session.setCurrentPhase(PHASE_OPENING);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("访谈已重新开始, sessionId: {}", sessionId);
    }

    /**
     * 暂停访谈。
     *
     * <p>仅 in_progress 和 paused 状态可暂停。
     * 暂停后可通过 resume 恢复。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pauseSession(String sessionId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        if (!INTERRUPTIBLE_STATUSES.contains(session.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "当前状态不允许暂停: " + session.getStatus());
        }
        session.setStatus(STATUS_PAUSED);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("访谈已暂停, sessionId: {}", sessionId);
    }

    /**
     * 获取当前用户的活跃会话列表。
     *
     * <p>查询用户所有空间下 status in (created, in_progress, paused) 的会话，
     * 按最后活跃时间降序排列。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getActiveSessions(UUID userId) {
        List<Space> spaces = spaceRepository.findByUserId(userId);
        if (spaces.isEmpty()) {
            return Map.of("sessions", List.of());
        }

        List<UUID> spaceIds = spaces.stream().map(Space::getId).toList();
        List<InterviewSession> activeSessions = sessionRepository
                .findBySpaceIdInAndStatusIn(spaceIds, ACTIVE_STATUSES);

        List<Map<String, Object>> list = new ArrayList<>();
        for (InterviewSession s : activeSessions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", s.getId().toString());
            item.put("topic", s.getTopic());
            item.put("status", s.getStatus());
            item.put("currentPhase", s.getCurrentPhase());
            item.put("phaseLabel", phaseLabel(s.getCurrentPhase()));
            item.put("interviewType", s.getInterviewType());
            item.put("lastActiveAt", s.getLastActiveAt() != null ? s.getLastActiveAt().toString() : null);
            list.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessions", list);
        return result;
    }

    // ==================== 内部方法 — 状态管理 ====================

    /**
     * 保存阶段推进并记录到数据库。
     *
     * <p>通过 self 代理调用，确保 @Transactional 生效
     * （Spring AOP 不会拦截同类内部直接调用）。
     *
     * @return 下一阶段标识
     */
    @Transactional(rollbackFor = Exception.class)
    public String markPhaseAndSaveTransition(UUID sessionId, String nextPhase) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        session.setCurrentPhase(nextPhase);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("阶段推进, sessionId: {}, phase: {}", sessionId, phaseLabel(nextPhase));
        return nextPhase;
    }

    /**
     * 根据当前阶段标记对应的采集项为已完成。
     *
     * <p>四阶段与采集项的对应关系:
     * <ul>
     *   <li>opening → 案例故事</li>
     *   <li>storytelling → 核心步骤 + 关键决策</li>
     *   <li>modeling → 专家心法 + 适用边界</li>
     *   <li>closing → 检查清单</li>
     * </ul>
     */
    void markCollectForPhase(InterviewSession session, String phase) {
        switch (phase) {
            case PHASE_OPENING:
                session.setCollectCaseStory(true);
                break;
            case PHASE_STORYTELLING:
                session.setCollectSteps(true);
                session.setCollectDecision(true);
                break;
            case PHASE_MODELING:
                session.setCollectMindset(true);
                session.setCollectBoundary(true);
                break;
            case PHASE_CLOSING:
                session.setCollectChecklist(true);
                break;
        }
    }

    /**
     * 获取四阶段的下一阶段，closing 后返回 null。
     */
    private String getNextPhase(String currentPhase) {
        switch (currentPhase) {
            case PHASE_OPENING: return PHASE_STORYTELLING;
            case PHASE_STORYTELLING: return PHASE_MODELING;
            case PHASE_MODELING: return PHASE_CLOSING;
            case PHASE_CLOSING: return null;
            default: return null;
        }
    }

    /**
     * 阶段标识 → 中文显示名。
     */
    private String phaseLabel(String phase) {
        switch (phase) {
            case PHASE_OPENING: return "开场定调";
            case PHASE_STORYTELLING: return "故事深描";
            case PHASE_MODELING: return "模型提炼";
            case PHASE_CLOSING: return "收网确认";
            default: return phase;
        }
    }

    /**
     * 检查并完成会话。
     *
     * <p>核心逻辑:
     * <ol>
     *   <li>校验会话状态（只处理 in_progress 和 paused）</li>
     *   <li>标记所有采集项为已完成</li>
     *   <li>设置 status=completed + phase=closing + finishedAt</li>
     *   <li>根据 interviewType 选择萃取管道：
     *       <ul>
     *         <li>expert → {@link ExpertInterviewProcessor#processCompletedInterview}</li>
     *         <li>sales → {@link InterviewTranscriptExtractor#extractFromInterview}</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @return 会话 ID（后续可关联报告）
     */
    UUID checkAndCompleteSession(InterviewSession session) {
        if (!STATUS_IN_PROGRESS.equals(session.getStatus()) && !STATUS_PAUSED.equals(session.getStatus())) {
            return null;
        }

        session.setCollectCaseStory(true);
        session.setCollectSteps(true);
        session.setCollectDecision(true);
        session.setCollectMindset(true);
        session.setCollectBoundary(true);
        session.setCollectChecklist(true);
        session.setStatus(STATUS_COMPLETED);
        session.setCurrentPhase(PHASE_CLOSING);
        session.setFinishedAt(LocalDateTime.now());
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);

        // 根据访谈类型触发不同的后处理管道
        if ("expert".equals(session.getInterviewType())) {
            expertInterviewProcessor.processExpertInterview(session.getId());
        } else {
            interviewTranscriptExtractor.extractFromInterview(session.getId());
        }

        log.info("访谈已完成, sessionId: {}", session.getId());
        return session.getId();
    }

    // ==================== 内部方法 — 上下文构建 ====================

    /**
     * 构建会话上下文 Map，用于生成 AI prompt 时的变量替换。
     */
    Map<String, Object> buildSessionContext(InterviewSession session) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("topic", session.getTopic());
        ctx.put("domain", session.getDomain());
        ctx.put("phase", session.getCurrentPhase());
        ctx.put("phaseLabel", phaseLabel(session.getCurrentPhase()));
        ctx.put("collectCaseStory", session.getCollectCaseStory());
        ctx.put("collectSteps", session.getCollectSteps());
        ctx.put("collectMindset", session.getCollectMindset());
        ctx.put("collectBoundary", session.getCollectBoundary());
        ctx.put("interviewType", session.getInterviewType());
        return ctx;
    }

    /**
     * 加载会话的历史消息，构建 LLM 对话列表。
     *
     * <p>消息按创建时间升序排列，每条包含 role 和 content。
     */
    List<Map<String, String>> buildMessagesList(InterviewSession session) {
        List<InterviewMessage> historyMsgs = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId());

        List<Map<String, String>> messages = new ArrayList<>();
        for (InterviewMessage msg : historyMsgs) {
            messages.add(Map.of("role", msg.getRole(), "content",
                    msg.getContent() != null ? msg.getContent() : ""));
        }
        return messages;
    }

    /**
     * 加载历史消息 + 追加当前用户消息。
     */
    List<Map<String, String>> buildMessagesListWithUserMsg(InterviewSession session, String userMessage) {
        List<Map<String, String>> messages = buildMessagesList(session);
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    /**
     * 加载访谈的 AI System Prompt。
     *
     * <p>从 prompt 模板文件（interview_system.md）加载，替换以下变量:
     * <ul>
     *   <li>topic — 萃取主题</li>
     *   <li>phase / phase_label — 当前阶段及其标签</li>
     *   <li>expert_knowledge — 萃取师经验（如有）</li>
     *   <li>domain — 领域标识</li>
     * </ul>
     */
    String loadInterviewSystemPrompt(InterviewSession session) {
        String domain = session.getDomain() != null ? session.getDomain() : "sales.b2b_enterprise";
        String expertKnowledge = loadExpertKnowledge(session.getExpertSkillId(), domain);

        Map<String, String> params = new HashMap<>();
        params.put("topic", session.getTopic());
        params.put("phase", session.getCurrentPhase());
        params.put("phase_label", phaseLabel(session.getCurrentPhase()));
        params.put("expert_knowledge", expertKnowledge != null ? expertKnowledge : "");
        params.put("domain", domain);

        return promptLoader.format("interview_system.md", params, domain);
    }

    /**
     * 加载指定萃取师的经验知识文本。
     *
     * <p>如果 expertSkillId 为 null，则加载该领域所有活跃萃取师的综合经验。
     * 经验按 category（7 类方法论）分组输出。
     *
     * <p>返回格式示例:
     * <pre>
     * ## 打法方法
     * - 面对大客户时先建信任再报价...
     *   应用规则: 适用于初次接触的 B2B 场景...
     * </pre>
     */
    private String loadExpertKnowledge(UUID expertSkillId, String domain) {
        // 未指定萃取师 → 加载综合经验
        if (expertSkillId == null) {
            return loadCompositeExpertKnowledge(domain);
        }

        try {
            ExpertSkill expert = expertSkillRepository.findById(expertSkillId).orElse(null);
            if (expert == null || !"active".equals(expert.getStatus())) {
                return null;
            }

            // 按 category 分组输出
            List<ExpertGrain> grains = expertGrainRepository.findByExpertId(expertSkillId);
            StringBuilder sb = new StringBuilder();
            Map<String, List<ExpertGrain>> grouped = new LinkedHashMap<>();
            for (ExpertGrain g : grains) {
                String cat = g.getCategory() != null ? g.getCategory() : "通用";
                grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(g);
            }

            for (Map.Entry<String, List<ExpertGrain>> entry : grouped.entrySet()) {
                sb.append("## ").append(categoryLabel(entry.getKey())).append("\n");
                for (ExpertGrain g : entry.getValue()) {
                    if (g.getKnowledgeContent() != null) {
                        sb.append("- ").append(g.getKnowledgeContent()).append("\n");
                    }
                    if (g.getApplicationRule() != null) {
                        sb.append("  应用规则: ").append(g.getApplicationRule()).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("加载萃取师经验失败, expertSkillId: {}", expertSkillId, e);
            return null;
        }
    }

    /**
     * 加载所有活跃萃取师的综合经验（每人最多取 10 条颗粒）。
     */
    private String loadCompositeExpertKnowledge(String domain) {
        List<ExpertSkill> activeExperts = expertSkillRepository.findByStatusAndDomain("active", domain);
        if (activeExperts.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ExpertSkill e : activeExperts) {
            List<ExpertGrain> grains = expertGrainRepository.findByExpertId(e.getId());
            if (grains.isEmpty()) continue;

            sb.append("## ").append(e.getName()).append("\n");
            for (ExpertGrain g : grains.stream().limit(10).toList()) {
                if (g.getKnowledgeContent() != null) {
                    sb.append("- ").append(g.getKnowledgeContent()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 生成开场白消息。
     *
     * <p>根据访谈类型和是否首次访谈，生成不同风格的开场引导:
     * <ul>
     *   <li>expert 类型 → 萃取师访谈专用开场白</li>
     *   <li>首次 sales 访谈 → 温暖的引导式开场</li>
     *   <li>非首次 sales 访谈 → 简洁的继续引导</li>
     * </ul>
     */
    private String generateOpeningMessage(boolean isFirstInterview, String interviewType, String domain) {
        if ("expert".equals(interviewType)) {
            return "欢迎来到元萃取师访谈！我是你的 AI 访谈助手。\n\n"
                    + "今天的目标是帮助你系统地梳理和提炼你的专业知识，形成可复用的方法论颗粒。"
                    + "请放松，我们以对话的形式进行，我会逐步引导你分享你的经验和见解。\n\n"
                    + "首先，请简单介绍一下你的专业领域和核心专长？";
        }
        if (isFirstInterview) {
            return "欢迎来到 AI 萃取访谈！"
                    + "今天由我来引导你，系统地梳理你的实战经验。"
                    + "请放松，这不是考试，而是一次有温度的对话。"
                    + "我会逐步引导你回顾典型案例、提炼核心方法论。"
                    + "请用你习惯的方式表达，不用刻意组织。\n\n"
                    + "首先，请简单介绍一下你的主要职责和核心工作内容？";
        }
        return "欢迎回来！让我们继续深入挖掘你的经验。"
                + "上次我们聊了一些内容，这次可以更聚焦——"
                + "请分享一个你印象最深的案例，越具体越好。";
    }

    // ==================== 内部方法 — 工具 ====================

    /**
     * 解析萃取师标识为显示名称。
     *
     * <p>返回值含义:
     * <ul>
     *   <li>"综合" — 未指定萃取师，使用所有活跃萃取师的综合经验</li>
     *   <li>萃取师姓名 — 指定了某个萃取师</li>
     * </ul>
     */
    private String resolveExpertSkillUsed(UUID expertSkillId) {
        if (expertSkillId == null) {
            return "综合";
        }
        try {
            return expertSkillRepository.findById(expertSkillId)
                    .map(ExpertSkill::getName)
                    .orElse("综合");
        } catch (Exception e) {
            return "综合";
        }
    }

    /** 重载：接收 String 类型的萃取师 ID */
    private String resolveExpertSkillUsed(String expertSkillIdStr) {
        UUID id = parseExpertSkillId(expertSkillIdStr);
        return resolveExpertSkillUsed(id);
    }

    /**
     * 安全解析萃取师 ID 字符串为 UUID。
     *
     * <p>处理三种情况:
     * <ul>
     *   <li>null / 空字符串 → null（使用综合经验）</li>
     *   <li>"none" → null（不使用萃取师经验）</li>
     *   <li>有效 UUID → 解析后返回</li>
     * </ul>
     */
    private UUID parseExpertSkillId(String expertSkillIdStr) {
        if (expertSkillIdStr == null || expertSkillIdStr.isEmpty() || "none".equals(expertSkillIdStr)) {
            return null;
        }
        try {
            return UUID.fromString(expertSkillIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 安全解析 UUID，空值时抛 400 异常 */
    private UUID parseUuid(String str) {
        if (str == null || str.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "ID 不能为空");
        }
        return UUID.fromString(str);
    }

    /** 更新会话的当前阶段 */
    void updateSessionPhase(InterviewSession session, String phase) {
        session.setCurrentPhase(phase != null ? phase : PHASE_OPENING);
    }

    /** 更新会话的采集标记 */
    void updateCollectFlags(InterviewSession session, String phase) {
        markCollectForPhase(session, phase);
    }

    /**
     * 构建 InterviewSessionResponse DTO。
     *
     * <p>组装以下信息:
     * <ul>
     *   <li>会话基本信息（id, topic, status, currentPhase）</li>
     *   <li>四阶段进度列表（每个阶段的 name / label / status）</li>
     *   <li>报告模板模块预览（6 个模块的采集状态）</li>
     *   <li>采集状态映射</li>
     * </ul>
     */
    InterviewSessionResponse buildSessionResponse(InterviewSession session, String expertSkillUsed) {
        String phaseName = session.getCurrentPhase();

        // 构建四阶段进度
        List<InterviewSessionResponse.PhaseInfo> phases = new ArrayList<>();
        for (String p : Arrays.asList(PHASE_OPENING, PHASE_STORYTELLING, PHASE_MODELING, PHASE_CLOSING)) {
            phases.add(InterviewSessionResponse.PhaseInfo.builder()
                    .name(p)
                    .label(phaseLabel(p))
                    .status(isPhaseCompleted(session, p) ? "completed"
                            : p.equals(phaseName) ? "active" : "pending")
                    .build());
        }

        // 构建模板模块预览
        List<InterviewSessionResponse.ModuleInfo> modules = new ArrayList<>();
        modules.add(buildModuleInfo("案例故事", session.getCollectCaseStory()));
        modules.add(buildModuleInfo("核心步骤", session.getCollectSteps()));
        modules.add(buildModuleInfo("关键决策", session.getCollectDecision()));
        modules.add(buildModuleInfo("专家心法", session.getCollectMindset()));
        modules.add(buildModuleInfo("适用边界", session.getCollectBoundary()));
        modules.add(buildModuleInfo("检查清单", session.getCollectChecklist()));

        InterviewSessionResponse.TemplatePreview templatePreview =
                InterviewSessionResponse.TemplatePreview.builder().modules(modules).build();

        // 构建采集状态
        InterviewSessionResponse.CollectStatus collectStatus =
                InterviewSessionResponse.CollectStatus.builder()
                        .caseStory(getCollectStatus(session.getCollectCaseStory()))
                        .steps(getCollectStatus(session.getCollectSteps()))
                        .decision(getCollectStatus(session.getCollectDecision()))
                        .mindset(getCollectStatus(session.getCollectMindset()))
                        .boundary(getCollectStatus(session.getCollectBoundary()))
                        .checklist(getCollectStatus(session.getCollectChecklist()))
                        .build();

        return InterviewSessionResponse.builder()
                .sessionId(session.getId().toString())
                .topic(session.getTopic())
                .status(session.getStatus())
                .currentPhase(session.getCurrentPhase())
                .expertSkillUsed(expertSkillUsed)
                .phases(phases)
                .templatePreview(templatePreview)
                .collectStatus(collectStatus)
                .lastActiveAt(session.getLastActiveAt() != null ? session.getLastActiveAt().toString() : null)
                .reportId(null)
                .interviewType(session.getInterviewType())
                .build();
    }

    /**
     * 判断指定阶段是否已完成。
     *
     * <p>规则: 阶段序号小于当前阶段序号 = 已完成。
     * 如果会话状态为 completed，则当前阶段也算已完成。
     */
    private boolean isPhaseCompleted(InterviewSession session, String phase) {
        int currentIdx = phaseIndex(session.getCurrentPhase());
        int checkIdx = phaseIndex(phase);
        return checkIdx < currentIdx
                || (STATUS_COMPLETED.equals(session.getStatus()) && checkIdx <= currentIdx);
    }

    /** 阶段名称 → 序号 */
    private int phaseIndex(String phase) {
        switch (phase) {
            case PHASE_OPENING: return 0;
            case PHASE_STORYTELLING: return 1;
            case PHASE_MODELING: return 2;
            case PHASE_CLOSING: return 3;
            default: return -1;
        }
    }

    /** 构建模块信息 */
    private InterviewSessionResponse.ModuleInfo buildModuleInfo(String name, Boolean collected) {
        return InterviewSessionResponse.ModuleInfo.builder()
                .name(name)
                .collected(collected)
                .build();
    }

    /** Boolean 采集状态 → 字符串状态 */
    private String getCollectStatus(Boolean collected) {
        return Boolean.TRUE.equals(collected) ? "completed" : "pending";
    }

    /**
     * 萃取师经验 category → 中文分类名。
     *
     * <p>对应 ExpertGrain 的 7 类方法论颗粒:
     * method / intuition / mental_model / failure / verification / metaphor / rhythm
     */
    private String categoryLabel(String category) {
        if (category == null) return "通用";
        switch (category) {
            case "method": return "打法方法";
            case "intuition": return "判断直觉";
            case "mental_model": return "心智模型";
            case "failure": return "失败教训";
            case "verification": return "验证方法";
            case "metaphor": return "隐喻框架";
            case "rhythm": return "节奏感知";
            default: return category;
        }
    }

    // ==================== 持久化操作 ====================

    /**
     * 持久化用户消息到 interview_message 表。
     *
     * <p>@Transactional 通过 self 代理调用时生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistUserMessage(UUID sessionId, InterviewSession session, String message) {
        InterviewMessage msg = InterviewMessage.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .role("user")
                .content(message)
                .phase(session.getCurrentPhase())
                .depth(0)
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(msg);
    }

    /**
     * 更新会话最后活跃时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public void touchSession(UUID sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            session.setLastActiveAt(LocalDateTime.now());
            sessionRepository.save(session);
        }
    }
}
