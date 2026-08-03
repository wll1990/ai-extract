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
import com.aiextract.model.InterviewInviteCode;
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
    private final com.aiextract.repository.InterviewInviteCodeRepository inviteCodeRepository;
    private final ObjectMapper objectMapper;
    private final InterviewMessageRepository messageRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final ReportGenerationService reportGenerationService;
    private final ReportRepository reportRepository;
    private final com.aiextract.repository.ExperienceGrainRepository grainRepository;
    private final ExpertSkillRepository expertSkillRepository;
    private final ExpertGrainRepository expertGrainRepository;
    private final SpaceRepository spaceRepository;
    private final PromptLoader promptLoader;
    private final DomainConfigLoader domainConfigLoader;
    private final SkillRepository skillRepository;
    private final InterviewTranscriptExtractor interviewTranscriptExtractor;
    private final ExpertInterviewProcessor expertInterviewProcessor;
    private final ContextWindowGuard contextWindowGuard;
    private final com.aiextract.repository.PhaseSummaryRepository phaseSummaryRepository;

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

    /** C 端免费萃取上限 */
    @org.springframework.beans.factory.annotation.Value("${app.interview.c-user-free-limit:3}")
    private int cUserFreeLimit;

    /** 报告就绪最低颗粒数，与 report.min-grains 保持一致 */
    @org.springframework.beans.factory.annotation.Value("${app.interview.grain-enough:10}")
    private int grainEnough;

    /** 低于此值强烈引导用户继续补充 */
    @org.springframework.beans.factory.annotation.Value("${app.interview.grain-suggest-more:5}")
    private int grainSuggestMore;

    /**
     * 创建访谈会话。
     *
     * <p>B端：spaceId 由前端传入（管理员选了 space）。
     * C端：spaceId 不传，从当前 userId（app_user.id）找/建 personal Space。</p>
     *
     * @param request       创建请求
     * @param userId        当前登录用户
     * @param interviewType 访谈类型：sales / expert
     * @param role          当前用户角色（JWT role claim）
     * @return 会话详情
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionResponse createSession(CreateInterviewRequest request, UUID userId,
                                                   String interviewType, String role) {
        UUID spaceId;
        if (request.getSpaceId() != null && !request.getSpaceId().isBlank()) {
            // 桌面端传了 spaceId → 直接用
            spaceId = parseUuid(request.getSpaceId());
        } else {
            // H5/移动端没传 → 查找个人 Space（注册时已建；Partner 等未走注册的兜底创建）
            Space space = spaceRepository.findByUserId(userId).stream().findFirst().orElse(null);
            if (space == null) {
                space = Space.builder()
                    .id(UUID.randomUUID()).userId(userId)
                    .title(request.getTopic() != null ? request.getTopic() : "我的经验空间")
                    .isPublic(false).status("active")
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
                space = spaceRepository.save(space);
            }
            spaceId = space.getId();
        }

        // 一次查询用户所有 Space，复用给后续 C 端限制和 TOCTOU 检查
        List<UUID> userSpaceIds = spaceRepository.findByUserId(userId).stream()
            .map(Space::getId).toList();

        // C端免费次数限制
        if ("c_user".equalsIgnoreCase(role)) {
            long completedCount = userSpaceIds.isEmpty() ? 0
                : sessionRepository.countBySpaceIdInAndStatus(userSpaceIds, STATUS_COMPLETED);
            if (completedCount >= cUserFreeLimit) {
                throw new BusinessException(402, "免费萃取次数已用完，请升级会员");
            }
        }

        log.info("创建访谈会话, spaceId: {}, topic: {}, userId: {}, role: {}", spaceId, request.getTopic(), userId, role);

        // 0. 检查是否已有进行中的同类型访谈 — 两类独立，互不阻塞
        long activeCount = sessionRepository.countBySpaceIdInAndStatusInAndInterviewType(
            userSpaceIds, ACTIVE_STATUSES, interviewType);
        if (activeCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                "你已有进行中的访谈，请先完成或放弃后再创建新的");
        }

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

        // 4. 验证邀请码（防止绕过前端直接调 API 或使用已失效的邀请码）
        String inviteCode = request.getInviteCode();
        if (inviteCode != null && !inviteCode.isBlank()) {
            InterviewInviteCode code = inviteCodeRepository.findByCode(inviteCode)
                .orElseThrow(() -> new BusinessException(400, "邀请码无效"));
            if (Boolean.FALSE.equals(code.getEnabled())) {
                throw new BusinessException(400, "邀请码已失效");
            }
            if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new BusinessException(400, "邀请码已过期");
            }
        }

        // 5. 构建并保存会话实体
        LocalDateTime now = LocalDateTime.now();
        InterviewSession session = InterviewSession.builder()
                .id(UUID.randomUUID())
                .spaceId(spaceId)
                .topic(request.getTopic())
                .status(STATUS_CREATED)
                .currentPhase(PHASE_OPENING)
                .collectStatus("{}")
                .inviteCode(request.getInviteCode())
                .expertSkillId(expertSkillId)
                .interviewType(interviewType)
                .domain(domain)
                .lastActiveAt(now)
                .createdAt(now)
                .build();
        sessionRepository.save(session);

        // 尾校验：save 后再次检查活跃会话数，防止 TOCTOU 竞态窗口
        // （pre-check 和 INSERT 之间有多次 DB 往返，另一并发请求可能也通过了 pre-check）
        long recheckCount = sessionRepository.countBySpaceIdInAndStatusInAndInterviewType(
            userSpaceIds, ACTIVE_STATUSES, interviewType);
        if (recheckCount > 1) {
            // 本次 save 产生了第 2 个活跃会话，标记为 abandoned 并回滚本次创建
            session.setStatus(STATUS_ABANDONED);
            sessionRepository.save(session);
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                "你已有进行中的访谈，请先完成或放弃后再创建新的");
        }

        // 6. 生成并保存开场引导消息（AI 角色，depth=-1 表示系统引导）
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
    public InterviewSessionResponse getSession(String sessionId, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("会话不存在, sessionId: {}", sessionId);
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND);
                });
        validateOwnership(session, userId);
        String expertSkillUsed = resolveExpertSkillUsed(session.getExpertSkillId());
        return buildSessionResponse(session, expertSkillUsed);
    }

    /**
     * 获取会话的历史消息列表（按时间升序）。
     */
    @Transactional(readOnly = true)
    public List<InterviewMessageResponse> getMessages(String sessionId, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        validateOwnership(session, userId);
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
    @Transactional(rollbackFor = Exception.class)
    public reactor.core.publisher.Flux<ChatChunk> processMessageFlux(String sessionId, String message, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        validateOwnership(session, userId);

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
        // P1-12: token 预检，超限自动裁剪最早消息
        List<Map<String, String>> trimmedHistory = contextWindowGuard.trimIfNeeded(historyMsgs,
                systemPrompt != null ? systemPrompt.length() : 0);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.addAll(trimmedHistory);
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
    @Transactional(rollbackFor = Exception.class)
    public reactor.core.publisher.Flux<ChatChunk> resumeSessionFlux(String sessionId, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        validateOwnership(session, userId);

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
    @Transactional(rollbackFor = Exception.class)
    public reactor.core.publisher.Flux<ChatChunk> markPhaseCompleteFlux(String sessionId, String phase, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        validateOwnership(session, userId);

        // 标记该阶段的采集数据
        markCollectForPhase(session, phase);
        String nextPhase = getNextPhase(phase);

        // 最后阶段 → 完成会话 + 触发报告生成
        if (PHASE_CLOSING.equals(phase) || nextPhase == null) {
            return reactor.core.publisher.Flux.create(sink -> {
                try {
                    self.checkAndCompleteSession(session);
                    sink.next(ChatChunk.event("phase_summary", Map.of(
                            "phase", phase,
                            "message", "所有阶段已完成，报告生成中..."
                    )));
                    sink.next(ChatChunk.meta(session.getId().toString()));
                    sink.next(ChatChunk.done());
                    sink.complete();
                } catch (Exception e) {
                    log.error("完成访谈失败", e);
                    sink.next(ChatChunk.error("完成访谈失败: " + e.getMessage()));
                    sink.complete();
                }
            });
        }

        // P1-11: 异步生成阶段摘要，不阻塞阶段切换
        self.generatePhaseSummary(session.getId(), phase);

        // 推进阶段 → 触发新阶段的 AI 引导（传 entity 而非 ID，避免重新加载丢失 collectStatus）
        self.markPhaseAndSaveTransition(session, nextPhase);
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
     * 标记所有采集 → 调用萃取管道 → 返回 sessionId。
     * 前端使用 sessionId 访问 /reports/by-session/{sessionId} 查询报告就绪状态。
     * 无 @Transactional — DB 状态更新和萃取管道各自独立事务，
     * 萃取管道不在事务内执行（遵循"事务内禁止 AI 调用"原则）。
     */
    public String forceCompleteSession(String sessionId, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        validateOwnership(session, userId);

        // 已完成会话：expert 不支持重复触发，sales 幂等重触发萃取管道
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            if ("expert".equals(session.getInterviewType())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "访谈已完成，不能重复强制完成");
            }
            log.info("访谈已完成，重触发萃取管道, sessionId={}", sessionId);
            interviewTranscriptExtractor.extractFromInterview(session.getId());
            return sessionId;
        }

        self.checkAndCompleteSession(session);
        return sessionId;
    }

    /**
     * 重新开始访谈。
     *
     * <p>将会话重置为 created 状态 + opening 阶段，
     * 保留历史消息但清除采集进度。
     */
    @Transactional(rollbackFor = Exception.class)
    public String restartSession(String sessionId, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession oldSession = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        validateOwnership(oldSession, userId);
        // 旧会话标记为 abandoned，保留历史
        oldSession.setStatus(STATUS_ABANDONED);
        oldSession.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(oldSession);

        // 新建会话，复用 topic、spaceId、inviteCode
        InterviewSession newSession = InterviewSession.builder()
                .id(UUID.randomUUID())
                .spaceId(oldSession.getSpaceId())
                .topic(oldSession.getTopic())
                .status(STATUS_CREATED)
                .currentPhase(PHASE_OPENING)
                .collectStatus("{}")
                .inviteCode(oldSession.getInviteCode())
                .expertSkillId(oldSession.getExpertSkillId())
                .interviewType(oldSession.getInterviewType())
                .domain(oldSession.getDomain())
                .lastActiveAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        sessionRepository.save(newSession);

        // 生成新开场白
        boolean isFirstInterview = sessionRepository
            .countBySpaceIdAndStatus(oldSession.getSpaceId(), STATUS_COMPLETED) == 0;
        String openingMessage = generateOpeningMessage(isFirstInterview, oldSession.getInterviewType(), oldSession.getDomain());
        messageRepository.save(InterviewMessage.builder()
                .id(UUID.randomUUID()).sessionId(newSession.getId())
                .role("ai").content(openingMessage).depth(-1)
                .phase(PHASE_OPENING).stageStatus("{}")
                .createdAt(LocalDateTime.now()).build());

        log.info("访谈已重新开始 oldSessionId={} newSessionId={}", sessionId, newSession.getId());
        return newSession.getId().toString();
    }

    /**
     * 暂停访谈。
     *
     * <p>仅 in_progress 和 paused 状态可暂停。
     * 暂停后可通过 resume 恢复。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pauseSession(String sessionId, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SESSION_NOT_FOUND));
        validateOwnership(session, userId);
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
        result.put("hasActive", !list.isEmpty());
        result.put("sessions", list);
        return result;
    }

    /**
     * 获取指定访谈会话产生的颗粒列表（C端审核用）。
     * 属主校验：session → space → space.isOwnedBy(currentUserId)。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSessionGrains(String sessionId, UUID userId) {
        InterviewSession session = sessionRepository.findById(parseUuid(sessionId))
            .orElseThrow(() -> new BusinessException(404, ErrorMessages.SESSION_NOT_FOUND));
        Space space = spaceRepository.findById(session.getSpaceId())
            .orElseThrow(() -> new BusinessException(404, "空间不存在"));
        if (!space.isOwnedBy(userId)) {
            throw new BusinessException(403, "无权访问");
        }
        // 注：颗粒由萃取管道异步生成，这里查的是已生成的颗粒
        return grainRepository.findBySourceInterviewId(session.getId()).stream()
            .map(g -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", g.getId().toString());
                m.put("sceneTag", g.getSceneTag());
                m.put("sceneDescription", g.getSceneDescription());
                m.put("expertThought", g.getExpertThought());
                m.put("standardScript", g.getStandardScript());
                m.put("commonMistakes", g.getCommonMistakes());
                m.put("status", g.getStatus());
                return m;
            }).toList();
    }

    /**
     * 生成访谈邀请码（8 位 base62），写入 interview_invite_code 表。
     * 不创建 session，不绑定 space。UNIQUE 约束兜底，冲突自动重试 3 次。
     */
    public String generateInviteCode(UUID companyId, int expireDays, UUID createdBy) {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 3; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 8; j++) sb.append(chars.charAt(random.nextInt(62)));
            String code = sb.toString();
            try {
                var builder = com.aiextract.model.InterviewInviteCode.builder()
                    .id(UUID.randomUUID()).companyId(companyId).code(code)
                    .type("enterprise")
                    .enabled(true).maxUses(0).usedCount(0)
                    .createdBy(createdBy).createdAt(LocalDateTime.now());
                if (expireDays > 0) {
                    builder.expiresAt(LocalDateTime.now().plusDays(expireDays));
                }
                inviteCodeRepository.save(builder.build());
                return code;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                log.warn("邀请码 UNIQUE 冲突，重试中 companyId={} attempt={}", companyId, i + 1);
            }
        }
        throw new BusinessException(500, "邀请码生成失败");
    }

    /** 生成个人访谈邀请码（C 端用户发起，无企业绑定） */
    public String generatePersonalInviteCode(String invitedBy, UUID createdBy) {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 3; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 8; j++) sb.append(chars.charAt(random.nextInt(62)));
            String code = sb.toString();
            try {
                inviteCodeRepository.save(com.aiextract.model.InterviewInviteCode.builder()
                    .id(UUID.randomUUID()).code(code)
                    .type("personal").invitedBy(invitedBy)
                    .enabled(true).maxUses(0).usedCount(0)
                    .createdBy(createdBy).createdAt(LocalDateTime.now())
                    .build());
                return code;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                log.warn("邀请码 UNIQUE 冲突，重试中 personal attempt={}", i + 1);
            }
        }
        throw new BusinessException(500, "邀请码生成失败");
    }

    // ==================== 内部方法 — 鉴权 ====================

    /**
     * 校验当前用户是否拥有该访谈会话的访问权限。
     * 通过 session → space → space.isOwnedBy(userId) 链路校验，
     * 与 {@link #getSessionGrains} 属主校验模式一致。
     */
    private void validateOwnership(InterviewSession session, UUID userId) {
        Space space = spaceRepository.findById(session.getSpaceId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "空间不存在"));
        if (!space.isOwnedBy(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "无权访问该访谈会话");
        }
    }

    // ==================== 内部方法 — 状态管理 ====================

    /**
     * 保存阶段推进并记录到数据库。
     *
     * <p>直接操作调用者传入的 entity（含 collectStatus 等已修改字段），
     * 不重新从 DB 加载，避免覆盖调用者在内存中的修改。
     * 通过 self 代理调用，确保 @Transactional 生效。
     *
     * @param session   调用者已加载并可能修改过的会话实体
     * @param nextPhase 下一阶段标识
     * @return 下一阶段标识
     */
    @Transactional(rollbackFor = Exception.class)
    public String markPhaseAndSaveTransition(InterviewSession session, String nextPhase) {
        session.setCurrentPhase(nextPhase);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("阶段推进, sessionId: {}, phase: {}", session.getId(), phaseLabel(nextPhase));
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
        Map<String, String> cs = parseCollectStatus(session.getCollectStatus());
        switch (phase) {
            case PHASE_OPENING:
                cs.put("caseStory", "collected"); break;
            case PHASE_STORYTELLING:
                cs.put("steps", "collected");
                cs.put("decision", "collected"); break;
            case PHASE_MODELING:
                cs.put("mindset", "collected");
                cs.put("boundary", "collected"); break;
            case PHASE_CLOSING:
                cs.put("checklist", "collected"); break;
        }
        session.setCollectStatus(toCollectJson(cs));
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
     *         <li>expert → {@link ExpertInterviewProcessor#processExpertInterview}</li>
     *         <li>sales → {@link InterviewTranscriptExtractor#extractFromInterview}</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>不返回 reportId —— 报告由异步管道生成，此时尚不存在。
     * 调用方应使用 sessionId 配合 /reports/by-session/{sessionId} 端点查询报告。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    void checkAndCompleteSession(InterviewSession session) {
        if (!STATUS_IN_PROGRESS.equals(session.getStatus()) && !STATUS_PAUSED.equals(session.getStatus())) {
            return;
        }

        // 保留 markCollectForPhase 逐阶段积累的真实采集状态，不强制覆盖
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

        log.info("访谈已完成, sessionId: {} grainCount={} enough={}", session.getId(),
            grainRepository.countBySpaceIdAndStatus(session.getSpaceId(), "active"), grainEnough);
    }

    // ==================== "继续补充" — 颗粒不足时重新打开已完成的会话 ====================

    /** 模块 key → 中文标签，用于补充指令 */
    private static final Map<String, String> MODULE_CN = Map.of(
        "caseStory", "案例故事", "steps", "核心步骤", "decision", "关键决策",
        "mindset", "专家心法", "boundary", "适用边界", "checklist", "行动清单"
    );

    /**
     * 将已完成的会话重新打开，用于"继续补充"场景。
     * 状态 completed → in_progress，阶段回到 modeling（针对性引导补充未采集模块）。
     * collectStatus 保留，AI 据此聚焦未采集部分，不会从零开始。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSession reopenForSupplement(UUID sessionId, UUID userId) {
        InterviewSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(404, "会话不存在"));
        validateOwnership(session, userId);
        if (!STATUS_COMPLETED.equals(session.getStatus())) {
            throw new BusinessException(400, "当前状态不允许补充");
        }
        String oldStatus = session.getStatus();
        String oldPhase = session.getCurrentPhase();
        session.setStatus(STATUS_IN_PROGRESS);
        session.setCurrentPhase(PHASE_MODELING);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("补充模式已启动 sessionId={} status={}→{} phase={}→{}", sessionId, oldStatus, STATUS_IN_PROGRESS, oldPhase, PHASE_MODELING);
        return session;
    }

    /**
     * "继续补充" SSE 端点。
     * 1. reopenForSupplement 将状态改回 in_progress + modeling
     * 2. 构建补充指令告知 AI 哪些模块已采集/未采集
     * 3. AI 以 modeling 阶段角色继续追问，加载完整历史消息上下文
     */
    @Transactional(rollbackFor = Exception.class)
    public reactor.core.publisher.Flux<ChatChunk> supplementSessionFlux(String sessionId, UUID userId) {
        UUID id = parseUuid(sessionId);
        InterviewSession session = self.reopenForSupplement(id, userId);

        log.info("用户触发补充模式 sessionId={} grainCount={} collectStatus={}",
            sessionId, grainRepository.countBySpaceIdAndStatus(session.getSpaceId(), "active"),
            session.getCollectStatus());

        String systemPrompt = loadInterviewSystemPrompt(session);
        List<Map<String, String>> historyMsgs = buildMessagesList(session);

        // 补充指令作为 user 消息传入，AI 据此聚焦未采集模块
        String supplementMsg = buildSupplementMessage(session);
        return callChatStream(session, systemPrompt, supplementMsg, historyMsgs);
    }

    /** 根据 collectStatus 生成补充指令，模块名用中文 */
    private String buildSupplementMessage(InterviewSession session) {
        Map<String, String> cs = parseCollectStatus(session.getCollectStatus());
        List<String> done = new ArrayList<>();
        List<String> todo = new ArrayList<>();
        for (String key : COLLECT_MODULE_KEYS) {
            String label = MODULE_CN.getOrDefault(key, key);
            if ("collected".equals(cs.get(key))) done.add(label);
            else todo.add(label);
        }
        if (todo.isEmpty()) {
            return "用户选择了继续补充。已经完成了所有模块的采集，请从整体角度再深挖一下细节，特别是具体话术和决策背后的思考。";
        }
        return "用户选择了继续补充。以下模块已经采集完成：" + String.join("、", done)
            + "。以下模块还需要继续深入：" + String.join("、", todo)
            + "。请自然地引导用户补充缺失的模块，不要重复已采集的内容，直接切入主题。";
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
        Map<String, String> cs = parseCollectStatus(session.getCollectStatus());
        ctx.put("collectCaseStory", isCollected(cs, "caseStory"));
        ctx.put("collectSteps", isCollected(cs, "steps"));
        ctx.put("collectMindset", isCollected(cs, "mindset"));
        ctx.put("collectBoundary", isCollected(cs, "boundary"));
        ctx.put("interviewType", session.getInterviewType());
        return ctx;
    }

    /**
     * 加载会话的历史消息，构建 LLM 对话列表。
     *
     * <p>P1-11: 已完成阶段用 AI 摘要替代全量历史，当前阶段保留全部消息。
     * PhaseSummary 按 sessionId 查，不区分访谈类型（sales/expert 通用）。
     */
    List<Map<String, String>> buildMessagesList(InterviewSession session) {
        String currentPhase = session.getCurrentPhase();
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. 已完成阶段的摘要（替代全量历史，减少 token）
        List<com.aiextract.model.PhaseSummary> summaries = phaseSummaryRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId());
        for (com.aiextract.model.PhaseSummary s : summaries) {
            if (!s.getPhase().equals(currentPhase)) {
                messages.add(Map.of("role", "system", "content",
                    "[阶段回顾] " + s.getPhaseLabel() + "：" + s.getSummary()));
            }
        }

        // 2. 当前阶段：保留全部消息
        List<InterviewMessage> historyMsgs = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId());
        for (InterviewMessage msg : historyMsgs) {
            if (currentPhase == null || currentPhase.equals(msg.getPhase())) {
                messages.add(Map.of("role", msg.getRole(), "content",
                        msg.getContent() != null ? msg.getContent() : ""));
            }
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

        String templateName = "expert".equals(session.getInterviewType())
            ? "interview_system_expert.md"
            : "interview_system.md";
        return promptLoader.format(templateName, params, domain);
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

    // ==================== P1-11: 阶段摘要 ====================

    /**
     * 异步生成阶段摘要，不阻塞阶段切换。
     * 摘要用于后续阶段替代全量历史消息，减少 token 消耗。
     */
    @org.springframework.scheduling.annotation.Async("embeddingExecutor")
    public void generatePhaseSummary(UUID sessionId, String completedPhase) {
        try {
            InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) return;

            List<InterviewMessage> phaseMsgs = messageRepository
                    .findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                    .filter(m -> completedPhase.equals(m.getPhase()))
                    .toList();
            if (phaseMsgs.isEmpty()) return;

            String conversation = phaseMsgs.stream()
                    .map(m -> (m.getRole() != null ? m.getRole() : "unknown") + "：" +
                            (m.getContent() != null ? m.getContent() : ""))
                    .collect(java.util.stream.Collectors.joining("\n"));

            String domain = session.getDomain() != null ? session.getDomain() : "sales.b2b_enterprise";
            String summary = chatStreamAdapter.chat(
                    promptLoader.format("interview_phase_summary.md", java.util.Map.of(
                            "phase", completedPhase,
                            "conversation", conversation
                    ), domain));

            if (summary != null && !summary.isBlank()) {
                phaseSummaryRepository.save(com.aiextract.model.PhaseSummary.builder()
                        .id(UUID.randomUUID())
                        .sessionId(sessionId)
                        .phase(completedPhase)
                        .phaseLabel(phaseLabel(completedPhase))
                        .summary(summary.trim())
                        .createdAt(java.time.LocalDateTime.now())
                        .build());
                log.info("阶段摘要已生成 sessionId={} phase={} len={}",
                        sessionId, completedPhase, summary.length());
            }
        } catch (Exception e) {
            log.warn("阶段摘要生成失败 sessionId={} phase={}: {}", sessionId, completedPhase, e.getMessage());
        }
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
        Map<String, String> cs = parseCollectStatus(session.getCollectStatus());
        modules.add(buildModuleInfo("案例故事", isCollected(cs, "caseStory")));
        modules.add(buildModuleInfo("核心步骤", isCollected(cs, "steps")));
        modules.add(buildModuleInfo("关键决策", isCollected(cs, "decision")));
        modules.add(buildModuleInfo("专家心法", isCollected(cs, "mindset")));
        modules.add(buildModuleInfo("适用边界", isCollected(cs, "boundary")));
        modules.add(buildModuleInfo("检查清单", isCollected(cs, "checklist")));

        InterviewSessionResponse.TemplatePreview templatePreview =
                InterviewSessionResponse.TemplatePreview.builder().modules(modules).build();

        // 构建采集状态
        InterviewSessionResponse.CollectStatus collectStatus =
                InterviewSessionResponse.CollectStatus.builder()
                        .caseStory(cs.getOrDefault("caseStory", "pending"))
                        .steps(cs.getOrDefault("steps", "pending"))
                        .decision(cs.getOrDefault("decision", "pending"))
                        .mindset(cs.getOrDefault("mindset", "pending"))
                        .boundary(cs.getOrDefault("boundary", "pending"))
                        .checklist(cs.getOrDefault("checklist", "pending"))
                        .build();

        return InterviewSessionResponse.builder()
                .sessionId(session.getId().toString())
                .topic(session.getTopic())
                .status(session.getStatus())
                .currentPhase(session.getCurrentPhase())
                // space 级别活跃颗粒数（非 session 级别：同一 space 多次访谈累计，与报告就绪检查口径一致）
                .grainCount((int) grainRepository.countBySpaceIdAndStatus(session.getSpaceId(), "active"))
                .expertSkillUsed(expertSkillUsed)
                .phases(phases)
                .templatePreview(templatePreview)
                .collectStatus(collectStatus)
                .lastActiveAt(session.getLastActiveAt() != null ? session.getLastActiveAt().toString() : null)
                // report.session_id 未填充，按 spaceId 查找（一个 space 一个 Report）
                .reportId(reportRepository.findBySpaceIdOrderByCreatedAtDesc(session.getSpaceId(),
                        org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst().map(r -> r.getId().toString()).orElse(null))
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

    // ── collect_status JSONB 辅助方法 ──

    private static final List<String> COLLECT_MODULE_KEYS = List.of(
        "caseStory", "steps", "decision", "mindset", "boundary", "checklist");

    /** JSONB collect_status → Map。null/"{}" 时返回全 pending。一次解析，下游复用 */
    private Map<String, String> parseCollectStatus(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            Map<String, String> m = new LinkedHashMap<>();
            COLLECT_MODULE_KEYS.forEach(k -> m.put(k, "pending"));
            return m;
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析 collectStatus 失败: {}", e.getMessage());
            Map<String, String> m = new LinkedHashMap<>();
            COLLECT_MODULE_KEYS.forEach(k -> m.put(k, "pending"));
            return m;
        }
    }

    /** Map → JSONB 字符串。序列化失败时记录日志并返回兜底值 */
    private String toCollectJson(Map<String, String> map) {
        try { return objectMapper.writeValueAsString(map); }
        catch (Exception e) {
            log.warn("序列化 collectStatus 失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** "collected"/"pending" → Boolean */
    private Boolean isCollected(Map<String, String> cs, String key) {
        return "collected".equals(cs.get(key));
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
