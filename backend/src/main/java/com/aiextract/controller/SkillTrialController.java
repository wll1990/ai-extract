package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.BaseController;
import com.aiextract.config.SseAdapter;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.service.GrainRefineService;
import com.aiextract.service.SkillTrialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 试问台与对话式纠偏 —— 属主/管理员在发布前后演练分身。
 *
 * <p>路径全部挂在 /skills/{skillId}/... 下：SecurityConfig 的 /skills/** 已覆盖
 * （要求 SKILL_USE），企业隔离与属主判断在 Service 里做。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
public class SkillTrialController extends BaseController {

    private final SkillTrialService trialService;
    private final GrainRefineService refineService;

    // ============================================================
    // 试问
    // ============================================================

    /** 试问一轮（SSE）：content / source / trial 事件 */
    @PostMapping(value = "/{skillId}/trial/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter trialChat(@PathVariable String skillId,
                                @Valid @RequestBody SkillChatRequest request) {
        return SseAdapter.fromFlux(
                trialService.trialChat(UUID.fromString(skillId), request, extractUserId()));
    }

    /** 试问会话列表：员工只看自己的，管理员看本企业该分身全部 */
    @GetMapping("/{skillId}/trial/sessions")
    public ApiResponse<Map<String, Object>> listSessions(
            @PathVariable String skillId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                trialService.listSessions(UUID.fromString(skillId), page, size, extractUserId()));
    }

    /** 试问会话详情（含全部消息与引用颗粒快照） */
    @GetMapping("/{skillId}/trial/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable String skillId,
                                                       @PathVariable String sessionId) {
        return ApiResponse.success(trialService.getSession(
                UUID.fromString(skillId), UUID.fromString(sessionId), extractUserId()));
    }

    /** 颗粒详情 —— 试问右栏 / 纠偏面板用，属主可读自己空间下的颗粒 */
    @GetMapping("/{skillId}/trial/grains/{grainId}")
    public ApiResponse<Map<String, Object>> getGrain(@PathVariable String skillId,
                                                     @PathVariable String grainId) {
        return ApiResponse.success(trialService.getGrain(
                UUID.fromString(skillId), UUID.fromString(grainId), extractUserId()));
    }

    /** 经验溯源（属主可读）—— 与 /admin/grains/traceability 同形状，但只允许本分身空间下的颗粒 */
    @GetMapping("/{skillId}/trial/traceability")
    public ApiResponse<List<Map<String, Object>>> traceability(@PathVariable String skillId,
                                                              @RequestParam String grainIds) {
        return ApiResponse.success(trialService.traceability(
                UUID.fromString(skillId), grainIds, extractUserId()));
    }

    /** 颗粒溯源 —— 替代 /admin/grains/traceability（后者要求 GRAIN_MANAGE，属主会 403） */
    @GetMapping("/{skillId}/trial/grains/{grainId}/trace")
    public ApiResponse<List<Map<String, Object>>> traceGrain(@PathVariable String skillId,
                                                             @PathVariable String grainId) {
        return ApiResponse.success(trialService.traceGrain(
                UUID.fromString(skillId), UUID.fromString(grainId), extractUserId()));
    }

    // ============================================================
    // 对话式纠偏
    // ============================================================

    /** 纠偏一轮（SSE）：content（AI 回复）+ refine（结构化结果） */
    @PostMapping(value = "/{skillId}/grains/{grainId}/refine",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter refine(@PathVariable String skillId,
                             @PathVariable String grainId,
                             @RequestBody Map<String, Object> body) {
        String draftId = body.get("draftId") != null ? body.get("draftId").toString() : null;
        String message = body.get("message") != null ? body.get("message").toString() : null;
        return SseAdapter.fromFlux(refineService.refine(
                UUID.fromString(skillId), UUID.fromString(grainId), draftId, message, extractUserId()));
    }

    /** 应用草稿：写回颗粒并异步重建向量 */
    @PostMapping("/{skillId}/grains/{grainId}/refine/{draftId}/apply")
    public ApiResponse<Map<String, Object>> apply(@PathVariable String skillId,
                                                  @PathVariable String grainId,
                                                  @PathVariable String draftId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        Map<String, String> fields = null;
        if (body != null && body.get("fields") instanceof Map<?, ?> raw) {
            Map<String, String> parsed = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> parsed.put(String.valueOf(k), v != null ? String.valueOf(v) : null));
            fields = parsed;
        }
        return ApiResponse.success(refineService.apply(
                UUID.fromString(skillId), UUID.fromString(grainId),
                UUID.fromString(draftId), confirm, fields, extractUserId()));
    }

    /** 丢弃草稿 */
    @PostMapping("/{skillId}/grains/{grainId}/refine/{draftId}/discard")
    public ApiResponse<Void> discard(@PathVariable String skillId,
                                     @PathVariable String grainId,
                                     @PathVariable String draftId) {
        refineService.discard(UUID.fromString(skillId), UUID.fromString(grainId),
                UUID.fromString(draftId), extractUserId());
        return ApiResponse.success();
    }

    /** 回滚最近一次已应用的修订 */
    @PostMapping("/{skillId}/grains/{grainId}/refine/{draftId}/revert")
    public ApiResponse<Map<String, Object>> revert(@PathVariable String skillId,
                                                   @PathVariable String grainId,
                                                   @PathVariable String draftId) {
        return ApiResponse.success(refineService.revert(
                UUID.fromString(skillId), UUID.fromString(grainId),
                UUID.fromString(draftId), extractUserId()));
    }

    /** 草稿状态：status + embedding_status（索引就绪后才允许「再问一次」） */
    @GetMapping("/{skillId}/grains/{grainId}/refine/{draftId}")
    public ApiResponse<Map<String, Object>> getDraft(@PathVariable String skillId,
                                                     @PathVariable String grainId,
                                                     @PathVariable String draftId) {
        return ApiResponse.success(refineService.getDraft(
                UUID.fromString(skillId), UUID.fromString(grainId),
                UUID.fromString(draftId), extractUserId()));
    }

    /** 已发布分身：属主把草稿转为纠错建议，走现有审核队列 */
    @PostMapping("/{skillId}/grains/{grainId}/refine/{draftId}/submit-suggestion")
    public ApiResponse<Map<String, Object>> submitSuggestion(@PathVariable String skillId,
                                                             @PathVariable String grainId,
                                                             @PathVariable String draftId,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        String reason = body != null && body.get("reason") != null
                ? body.get("reason").toString() : null;
        return ApiResponse.success(refineService.submitSuggestion(
                UUID.fromString(skillId), UUID.fromString(grainId),
                UUID.fromString(draftId), reason, extractUserId()));
    }
}
