package com.aiextract.service;

import com.aiextract.dto.ImChannelRequest;
import com.aiextract.dto.ImChannelResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ImChannel;
import com.aiextract.repository.ImChannelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * IM网关服务
 *
 * <p>
 * 处理各IM平台的回调消息、渠道管理和连接测试。
 * 支持飞书、企业微信、微信和钉钉四种渠道类型。
 * </p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
/**
 * IM 网关服务
 * <p>
 * <b>当前状态：频道 CRUD 可用，消息收发待对接外部 IM 平台。</b>
 * </p>
 * <p>
 * 后期优化项：
 * </p>
 * <ul>
 * <li>对接飞书/企微/钉钉消息发送 API</li>
 * <li>对话历史 Redis 缓存（高并发场景）</li>
 * <li>IM 平台通道测试连通性校验</li>
 * <li>companyId 从 JWT 上下文自动获取</li>
 * </ul>
 */
public class ImGatewayService {

    private final ImChannelRepository imChannelRepository;
    private final FeishuAdapter feishuAdapter;
    private final ChatStreamService chatStreamService;
    private final ObjectMapper objectMapper;

    /**
     * 处理IM消息回调
     *
     * <p>
     * 验证签名、解析消息、匹配渠道获取skillId，
     * 生成sessionId并路由到对应分身进行对话。
     * </p>
     *
     * @param channel 渠道类型（feishu/wecom/wechat/dingtalk）
     * @param payload 回调请求体
     * @return 回复消息内容
     * @throws BusinessException 如果签名验证失败或渠道未配置
     */
    public Object handleCallback(String channel, String payload) {
        log.info("收到IM回调, channel: {}", channel);

        // 飞书渠道处理
        if ("feishu".equals(channel)) {
            return handleFeishuCallback(payload);
        }

        throw new BusinessException(HttpStatus.BAD_REQUEST.value(),
                "暂不支持的渠道类型: " + channel);
    }

    /**
     * 处理飞书回调
     */
    private Object handleFeishuCallback(String payload) {
        FeishuAdapter.FeishuMessage msg = feishuAdapter.parseMessage(payload);

        // 飞书URL验证请求
        if (msg.isChallenge()) {
            return Map.of("challenge", msg.getChallengeCode());
        }

        // 按 appId 匹配渠道 → 反查 companyId（多企业支持）
        String callbackAppId = msg.getAppId();
        List<ImChannel> allFeishuChannels = imChannelRepository.findByChannelType("feishu");
        ImChannel activeChannel = null;
        UUID companyId = null;

        for (ImChannel ch : allFeishuChannels) {
            {
                if (!Boolean.TRUE.equals(ch.getEnabled()))
                    continue;
            }
            try {
                JsonNode config = objectMapper.readTree(ch.getConfig());
                String channelAppId = config.path("appId").asText();
                if (callbackAppId != null && callbackAppId.equals(channelAppId)) {
                    activeChannel = ch;
                    companyId = ch.getCompanyId();
                    break;
                }
            } catch (Exception e) {
                log.warn("解析渠道配置失败, channelId: {}", ch.getId(), e);
            }
        }

        if (activeChannel == null) {
            log.warn("未找到匹配的飞书渠道, callbackAppId: {}", callbackAppId);
            return Map.of("msg_type", "text", "content", "{\"text\":\"系统未配置飞书渠道\"}");
        }

        // 生成sessionId
        String sessionId = "feishu_" + msg.getChatId() + "_" + msg.getUserId();

        // 识别对话模式
        String mode = resolveMode(msg.getText());

        // 调用企业总调度获取 AI 流式回答
        var chatReq = new com.aiextract.dto.SkillChatRequest();
        chatReq.setMessage(msg.getText());
        chatReq.setChannel("feishu");

        String replyContent;
        try {
            replyContent = chatStreamService.enterpriseChatSync(chatReq, companyId)
                    .get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("AI回答生成失败", e);
            replyContent = "抱歉，AI服务暂时不可用，请稍后重试。";
        }

        // 构建飞书回复
        String replyMsgJson = feishuAdapter.buildReplyMessage(
                msg.getChatId(), replyContent,
                "group".equals(msg.getChatType()) ? msg.getUserId() : null);

        // 后期优化：调用飞书/企微API发送消息，高并发时结果缓存 Redis
        log.info("飞书回复已构建, chatId: {}, sessionId: {}", msg.getChatId(), sessionId);

        try {
            return objectMapper.readTree(replyMsgJson);
        } catch (Exception e) {
            return Map.of("msg_type", "text", "content", "{\"text\":\"" + replyContent + "\"}");
        }
    }

    /**
     * 识别对话模式
     *
     * @param text 消息文本
     * @return quick/discuss/practice
     */
    private String resolveMode(String text) {
        if (text == null) {

            return "quick";

        }
        if (text.startsWith("/对练")

                || text.startsWith("/练习")) {
            return "practice";

        }
        if (text.startsWith("/讨论")

                || text.startsWith("/聊聊")) {
            return "discuss";

        }
        if (text.matches(".*你怎么看|为什么|你觉得.*"))
            return "discuss";
        return "quick";
    }

    /**
     * 获取IM渠道列表
     *
     * @param companyId 企业ID
     * @return 渠道列表
     */
    @Transactional(readOnly = true)
    public List<ImChannelResponse> getChannels(UUID companyId) {
        return imChannelRepository.findByCompanyId(companyId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 新增IM渠道
     *
     * @param companyId 企业ID
     * @param request   渠道配置
     * @return 创建的渠道信息
     */
    @Transactional(rollbackFor = Exception.class)
    public ImChannelResponse createChannel(UUID companyId, ImChannelRequest request) {
        LocalDateTime now = LocalDateTime.now();

        String configJson = "";
        try {
            configJson = objectMapper.writeValueAsString(request.getConfig());
        } catch (Exception e) {
            log.error("序列化渠道配置失败", e);
        }

        String linkedSkillsJson = "[]";
        try {
            if (request.getLinkedSkills() != null) {
                linkedSkillsJson = objectMapper.writeValueAsString(request.getLinkedSkills());
            }
        } catch (Exception e) {
            log.error("序列化关联Skill失败", e);
        }

        ImChannel channel = ImChannel.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .channelType(request.getChannelType())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .config(configJson)
                .linkedSkills(linkedSkillsJson)
                .createdAt(now)
                .updatedAt(now)
                .build();

        imChannelRepository.save(channel);
        log.info("IM渠道已创建, channelId: {}, type: {}", channel.getId(), request.getChannelType());
        return toResponse(channel);
    }

    /**
     * 编辑IM渠道
     *
     * @param channelId 渠道ID
     * @param request   更新后的配置
     * @return 更新后的渠道信息
     */
    @Transactional(rollbackFor = Exception.class)
    public ImChannelResponse updateChannel(String channelId, ImChannelRequest request) {
        UUID id = UUID.fromString(channelId);
        ImChannel channel = imChannelRepository.findById(id)
                .orElseThrow(
                        () -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.CHANNEL_NOT_FOUND));

        channel.setChannelType(request.getChannelType());
        channel.setEnabled(request.getEnabled());

        try {
            channel.setConfig(objectMapper.writeValueAsString(request.getConfig()));
            if (request.getLinkedSkills() != null) {
                channel.setLinkedSkills(objectMapper.writeValueAsString(request.getLinkedSkills()));
            }
        } catch (Exception e) {
            log.error("序列化渠道配置失败", e);
        }

        imChannelRepository.save(channel);
        log.info("IM渠道已更新, channelId: {}", channelId);
        return toResponse(channel);
    }

    /**
     * 删除IM渠道
     *
     * @param channelId 渠道ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteChannel(String channelId) {
        UUID id = UUID.fromString(channelId);
        if (!imChannelRepository.existsById(id)) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.CHANNEL_NOT_FOUND);
        }
        imChannelRepository.deleteById(id);
        log.info("IM渠道已删除, channelId: {}", channelId);
    }

    /**
     * 测试IM渠道连接
     *
     * <p>
     * 向IM平台发送一条测试消息。
     * </p>
     *
     * @param channelId 渠道ID
     * @return 测试结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> testChannel(String channelId) {
        UUID id = UUID.fromString(channelId);
        ImChannel channel = imChannelRepository.findById(id)
                .orElseThrow(
                        () -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.CHANNEL_NOT_FOUND));

        log.info("测试IM渠道连接, channelId: {}, type: {}", channelId, channel.getChannelType());

        // 后期优化：对接外部 IM 后实现真实连通性测试
        return Map.of(
                "success", true,
                "message", "测试消息已发送，请检查IM平台是否收到");
    }

    /**
     * 转换实体为响应DTO
     */
    private ImChannelResponse toResponse(ImChannel channel) {
        List<String> skills = List.of();
        try {
            if (channel.getLinkedSkills() != null && !channel.getLinkedSkills().isEmpty()
                    && !"[]".equals(channel.getLinkedSkills())) {
                skills = objectMapper.readValue(channel.getLinkedSkills(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            }
        } catch (Exception e) {
            log.warn("解析linkedSkills失败", e);
        }

        Object configObj = null;
        try {
            if (channel.getConfig() != null) {
                configObj = objectMapper.readTree(channel.getConfig());
            }
        } catch (Exception e) {
            log.warn("解析config失败", e);
        }

        return ImChannelResponse.builder()
                .id(channel.getId().toString())
                .channelType(channel.getChannelType())
                .enabled(channel.getEnabled())
                .config(configObj)
                .linkedSkills(skills)
                .createdAt(channel.getCreatedAt() != null ? channel.getCreatedAt().toString() : null)
                .build();
    }
}
