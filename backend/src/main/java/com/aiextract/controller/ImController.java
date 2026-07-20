package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.ImChannelRequest;
import com.aiextract.dto.ImChannelResponse;
import com.aiextract.service.ImGatewayService;
import com.aiextract.util.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/im")
@RequiredArgsConstructor
public class ImController {

    private final ImGatewayService imGatewayService;
    private final JwtUtil jwtUtil;

    private String getToken() {
        return (String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
    }

    /**
     * 从JWT Token中提取企业ID
     */
    private UUID extractCompanyId() {
        return jwtUtil.getCompanyIdFromToken(getToken());
    }

    /**
     * 接收IM平台消息回调（无需JWT鉴权）
     *
     * <p>由IM平台主动调用，通过平台自身签名机制验证。
     * 飞书首次配置时会发送challenge验证请求。</p>
     *
     * @param channel 渠道类型（feishu/wecom/wechat/dingtalk）
     * @param payload 回调请求体（已由过滤器注入）
     * @return 回复消息内容
     */
    @PostMapping("/{channel}/callback")
    public Object handleCallback(
            @PathVariable String channel,
            @RequestBody String payload) {
        log.info("接收IM回调, channel: {}, payloadLength: {}", channel, payload.length());
        return imGatewayService.handleCallback(channel, payload);
    }

    /**
     * 获取IM渠道列表
     *
     * @param authHeader Authorization请求头
     * @return 渠道列表
     */
    @GetMapping("/channels")
    public ApiResponse<List<ImChannelResponse>> getChannels() {
        UUID companyId = extractCompanyId();
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
        UUID companyId = extractCompanyId();
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
}
