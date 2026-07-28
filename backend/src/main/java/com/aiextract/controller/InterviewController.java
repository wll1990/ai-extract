package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.SseAdapter;
import com.aiextract.dto.ChatMessageRequest;
import com.aiextract.dto.CreateInterviewRequest;
import com.aiextract.dto.InterviewMessageResponse;
import com.aiextract.dto.InterviewSessionResponse;
import com.aiextract.service.InterviewService;
import com.aiextract.util.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 访谈控制器
 *
 * <p>提供AI萃取访谈的完整REST API，包括会话创建、状态查询、
 * SSE流式消息、历史消息、中断恢复和暂停控制。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@RestController
@RequestMapping("/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final JwtUtil jwtUtil;

    private String getToken() {
        return (String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
    }

    private String extractRole() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getAuthorities().stream()
                .map(Object::toString)
                .findFirst()
                .map(r -> r.replace("ROLE_", "").toLowerCase())
                .orElse("employee");
    }

    /**
     * 创建访谈会话
     *
     * @param request 创建请求（spaceId B端必填C端可选、topic、inviteCode、expertSkillId）
     * @return 会话信息
     */
    @PostMapping
    public ApiResponse<InterviewSessionResponse> createInterview(
            @Valid @RequestBody CreateInterviewRequest request) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        String role = extractRole();
        String interviewType = request.getInterviewType() != null ? request.getInterviewType() : "sales";
        InterviewSessionResponse response = interviewService.createSession(request, userId, interviewType, role);
        return ApiResponse.success(response);
    }

    /**
     * 获取会话状态和进度
     *
     * @param sessionId 会话ID
     * @return 会话状态
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<InterviewSessionResponse> getSession(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        InterviewSessionResponse response = interviewService.getSession(sessionId, userId);
        return ApiResponse.success(response);
    }

    /**
     * 发送消息（SSE流式响应）
     *
     * <p>核心SSE接口。用户发送消息，AI逐字流式返回追问。
     * Content-Type为text/event-stream。</p>
     *
     * @param sessionId 会话ID
     * @param request   消息请求体
     * @return SSE事件流
     */
    @PostMapping(value = "/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @PathVariable String sessionId,
            @Valid @RequestBody ChatMessageRequest request) {
        log.info("收到访谈消息, sessionId: {}", sessionId);
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        return SseAdapter.fromFlux(interviewService.processMessageFlux(sessionId, request.getMessage(), userId));
    }

    /**
     * 获取历史消息列表
     *
     * @param sessionId 会话ID
     * @return 消息列表（按时间升序）
     */
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<InterviewMessageResponse>> getMessages(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        List<InterviewMessageResponse> messages = interviewService.getMessages(sessionId, userId);
        return ApiResponse.success(messages);
    }

    /**
     * 中断恢复
     *
     * <p>从暂停或进行中状态恢复访谈，加载历史上下文后接续对话。</p>
     *
     * @param sessionId 会话ID
     * @return SSE事件流
     */
    @PostMapping(value = "/{sessionId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable String sessionId) {
        log.info("恢复访谈, sessionId: {}", sessionId);
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        return SseAdapter.fromFlux(interviewService.resumeSessionFlux(sessionId, userId));
    }

    /**
     * 强制完成访谈。
     *
     * @param sessionId 会话ID
     * @return reportId（完成时触发的报告标识）
     */
    @PostMapping("/{sessionId}/force-complete")
    public ApiResponse<Map<String, Object>> forceComplete(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        String resultId = interviewService.forceCompleteSession(sessionId, userId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("sessionId", resultId);
        return ApiResponse.success(result);
    }

    /**
     * 用户主动标记当前阶段完成，触发 AI 阶段小结 + 自然推进
     */
    @PostMapping(value = "/{sessionId}/mark-phase-complete", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter markPhaseComplete(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        String phase = body.getOrDefault("phase", "opening");
        log.info("用户标记阶段完成, sessionId: {}, phase: {}", sessionId, phase);
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        return SseAdapter.fromFlux(interviewService.markPhaseCompleteFlux(sessionId, phase, userId));
    }

    /**
     * "继续补充" — 已完成会话重新打开，AI 聚焦未采集模块继续追问。
     */
    @PostMapping(value = "/{sessionId}/supplement", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter supplement(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        log.info("用户触发补充模式请求, sessionId: {}", sessionId);
        return SseAdapter.fromFlux(interviewService.supplementSessionFlux(sessionId, userId));
    }

    @PostMapping("/{sessionId}/restart")
    public ApiResponse<Map<String, Object>> restart(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        String newSessionId = interviewService.restartSession(sessionId, userId);
        return ApiResponse.success(Map.of("sessionId", newSessionId));
    }

    /**
     * 暂停访谈
     *
     * @param sessionId 会话ID
     * @return 操作成功
     */
    @PostMapping("/{sessionId}/pause")
    public ApiResponse<Void> pause(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        interviewService.pauseSession(sessionId, userId);
        return ApiResponse.success();
    }

    /**
     * 获取访谈会话产生的颗粒列表（C端审核用）。
     */
    @GetMapping("/{sessionId}/grains")
    public ApiResponse<List<Map<String, Object>>> getGrains(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        return ApiResponse.success(interviewService.getSessionGrains(sessionId, userId));
    }

    /**
     * 检测当前用户的活跃会话
     *
     * @return 活跃会话信息
     */
    @GetMapping("/active")
    public ApiResponse<Map<String, Object>> getActiveSessions() {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        Map<String, Object> result = interviewService.getActiveSessions(userId);
        return ApiResponse.success(result);
    }

}
