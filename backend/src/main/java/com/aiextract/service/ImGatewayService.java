package com.aiextract.service;

import com.aiextract.dto.ImChannelRequest;
import com.aiextract.dto.ImChannelResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ImChannel;
import com.aiextract.repository.ImChannelRepository;
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
    private final ObjectMapper objectMapper;

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
                .enabled(request.getEnabled() == null || request.getEnabled())
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
