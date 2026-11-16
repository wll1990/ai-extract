package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.ImConstants;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.ImBotResponse;
import com.aiextract.dto.ImChannelRequest;
import com.aiextract.dto.ImChannelResponse;
import com.aiextract.dto.ImPublishRequest;
import com.aiextract.dto.ImPublishResponse;
import com.aiextract.service.ImGatewayService;
import com.aiextract.service.ImLifecycleService;
import com.aiextract.service.QueryGate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IM集成控制器
 *
 * <p>提供IM平台回调接收、渠道管理和连接测试六个接口。
 * 回调接口无需JWT鉴权，使用IM平台自身签名验证。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@RestController
@RequestMapping("/im")
@RequiredArgsConstructor
public class ImController {

    private final ImGatewayService imGatewayService;
    private final QueryGate queryGate;
    private final ImLifecycleService imLifecycleService;
    private final com.aiextract.service.ImPushService imPushService;
    private final com.aiextract.repository.ImUserBindingRepository bindingRepository;

    /** 管理端：某用户的 IM 绑定列表（含渠道、最后活跃时间） */
    @org.springframework.web.bind.annotation.GetMapping("/users/{userId}/bindings")
    public ApiResponse<List<Map<String, Object>>> userBindings(
            @org.springframework.web.bind.annotation.PathVariable UUID userId) {
        UUID companyId = TokenContext.getCompanyId();
        boolean sameCompany = bindingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .anyMatch(b -> companyId == null || companyId.equals(b.getCompanyId()));
        if (!sameCompany) {
            return ApiResponse.success(List.of());
        }
        List<Map<String, Object>> rows = bindingRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(b -> companyId == null || companyId.equals(b.getCompanyId()))
                .map(b -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", b.getId().toString());
                    m.put("channelType", b.getChannelType());
                    m.put("botId", b.getBotId());
                    m.put("displayName", b.getDisplayName());
                    m.put("chatId", b.getChatId());
                    m.put("createdAt", b.getCreatedAt());
                    m.put("lastSeenAt", b.getLastSeenAt());
                    return m;
                }).toList();
        return ApiResponse.success(rows);
    }

    /** 管理端：给某用户发一条测试消息，验证绑定/凭据是否可用 */
    @org.springframework.web.bind.annotation.PostMapping("/users/{userId}/test-push")
    public ApiResponse<Map<String, Object>> testPush(
            @org.springframework.web.bind.annotation.PathVariable UUID userId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, String> body) {
        String title = body == null || body.get("title") == null
                ? "培训提醒测试" : body.get("title");
        String content = body == null || body.get("content") == null
                ? "这是一条测试消息：如果你收到，说明 IM 绑定和机器人配置正常。"
                : body.get("content");
        return ApiResponse.success(imPushService.pushTest(userId, title, content));
    }

    /**
     * 获取IM渠道列表
     *
     * @param authHeader Authorization请求头
     * @return 渠道列表
     */
    @GetMapping("/channels")
    public ApiResponse<List<ImChannelResponse>> getChannels() {
        UUID companyId = TokenContext.getCompanyId();
        List<ImChannelResponse> channels = imGatewayService.getChannels(companyId);
        return ApiResponse.success(channels);
    }

    /**
     * 新增IM渠道
     *
     * @param authHeader Authorization请求头
     * @param request 渠道配置
     * @return 创建的渠道信息
     */
    @PostMapping("/channels")
    public ApiResponse<ImChannelResponse> createChannel(
            @Valid @RequestBody ImChannelRequest request) {
        UUID companyId = TokenContext.getCompanyId();
        ImChannelResponse response = imGatewayService.createChannel(companyId, request);
        return ApiResponse.success(response);
    }

    /**
     * 编辑IM渠道
     *
     * @param channelId 渠道ID
     * @param request   更新后的配置
     * @return 更新后的渠道信息
     */
    @PutMapping("/channels/{channelId}")
    public ApiResponse<ImChannelResponse> updateChannel(
            @PathVariable String channelId,
            @Valid @RequestBody ImChannelRequest request) {
        ImChannelResponse response = imGatewayService.updateChannel(channelId, request);
        return ApiResponse.success(response);
    }

    /**
     * 删除IM渠道
     *
     * @param channelId 渠道ID
     * @return 操作成功
     */
    @DeleteMapping("/channels/{channelId}")
    public ApiResponse<Void> deleteChannel(@PathVariable String channelId) {
        imGatewayService.deleteChannel(channelId);
        return ApiResponse.success();
    }

    /**
     * 测试IM渠道连接
     *
     * @param channelId 渠道ID
     * @return 测试结果
     */
    @PostMapping("/channels/{channelId}/test")
    public ApiResponse<Map<String, Object>> testChannel(@PathVariable String channelId) {
        Map<String, Object> result = imGatewayService.testChannel(channelId);
        return ApiResponse.success(result);
    }

    /**
     * 发布分身到 IM 渠道（仅 company_admin）。
     *
     * <p>飞书走扫码授权（返回 qrUrl）；企业微信/钉钉走凭据发布
     * （请求体带 credentials，校验通过后立即绑定）。</p>
     */
    @PostMapping("/channels/{channelType}/publish")
    public ApiResponse<ImPublishResponse> publish(
            @PathVariable String channelType,
            @Valid @RequestBody ImPublishRequest request) {
        if (!ImConstants.isSupportedChannel(channelType)) {
            throw new com.aiextract.exception.BusinessException(400,
                    "不支持的渠道：" + channelType + "（支持 feishu / wecom / dingtalk）");
        }
        UUID skillId;
        try {
            skillId = UUID.fromString(request.getSkillId());
        } catch (IllegalArgumentException e) {
            throw new com.aiextract.exception.BusinessException(400, "分身ID格式不正确");
        }
        ImPublishResponse response = imLifecycleService.publish(
                channelType.trim().toLowerCase(), skillId,
                request.getName(), request.getDescription(), request.getAvatar(),
                request.getCredentials());
        return ApiResponse.success(response);
    }

    /**
     * 已发布机器人列表（仅 company_admin）
     */
    @GetMapping("/channels/bots")
    public ApiResponse<List<ImBotResponse>> listBots() {
        UUID companyId = TokenContext.getCompanyId();
        return ApiResponse.success(imLifecycleService.listBots(companyId));
    }

    /**
     * 下线机器人（仅 company_admin）
     */
    @DeleteMapping("/channels/bots/{botId}")
    public ApiResponse<Void> unpublish(@PathVariable String botId) {
        imLifecycleService.unpublish(botId);
        return ApiResponse.success();
    }

    /** 重连机器人（桥连接失败后重试） */
    @PostMapping("/channels/bots/{botId}/reconnect")
    public ApiResponse<Void> reconnect(@PathVariable String botId) {
        imLifecycleService.reconnect(botId);
        return ApiResponse.success();
    }
}
