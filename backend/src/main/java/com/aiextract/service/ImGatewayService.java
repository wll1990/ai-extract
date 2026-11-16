package com.aiextract.service;

import com.aiextract.dto.ImChannelRequest;
import com.aiextract.dto.ImChannelResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.common.ImConstants;
import com.aiextract.config.PartnerCrypto;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ImChannel;
import com.aiextract.repository.ImChannelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final com.aiextract.config.CompanyScopeService companyScopeService;
    private final PartnerCrypto partnerCrypto;
    private final WebClient webClient;

    /** 只进 secrets 加密列的字段，绝不出现在 config JSON 里 */
    private static final Set<String> SECRET_FIELDS =
            Set.of("appSecret", "secret", "token", "encodingAesKey");

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

        Map<String, Object> rawConfig = toConfigMap(request.getConfig());
        String configJson = writeJson(sanitizeConfig(rawConfig));
        String secretsJson = encryptSecrets(rawConfig);

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
                .secrets(secretsJson)
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
        assertChannelAccess(channel);

        channel.setChannelType(request.getChannelType());
        channel.setEnabled(request.getEnabled());

        Map<String, Object> rawConfig = toConfigMap(request.getConfig());
        channel.setConfig(writeJson(sanitizeConfig(rawConfig)));
        String secretsJson = encryptSecrets(rawConfig);
        if (!"{}".equals(secretsJson)) {
            channel.setSecrets(secretsJson);
        }
        try {
            if (request.getLinkedSkills() != null) {
                channel.setLinkedSkills(objectMapper.writeValueAsString(request.getLinkedSkills()));
            }
        } catch (Exception e) {
            log.error("序列化关联Skill失败", e);
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
        ImChannel channel = imChannelRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.CHANNEL_NOT_FOUND));
        assertChannelAccess(channel);
        imChannelRepository.delete(channel);
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
    /**
     * 真实连通性探测。注意：这里会打平台鉴权接口，因此不加 @Transactional，
     * 避免 HTTP 调用占着数据库连接。
     */
    public Map<String, Object> testChannel(String channelId) {
        UUID id = UUID.fromString(channelId);
        ImChannel channel = imChannelRepository.findById(id)
                .orElseThrow(
                        () -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.CHANNEL_NOT_FOUND));
        assertChannelAccess(channel);

        log.info("测试IM渠道连接, channelId: {}, type: {}", channelId, channel.getChannelType());

        Map<String, Object> cfg = parseConfig(channel.getConfig());
        Map<String, String> secrets = decryptSecrets(channel);
        try {
            String detail = probe(channel, cfg, secrets);
            return Map.of("success", true, "message", detail);
        } catch (BusinessException e) {
            return Map.of("success", false, "message", e.getMessage());
        } catch (Exception e) {
            log.warn("渠道连通性探测失败 channelId={}", channelId, e);
            return Map.of("success", false, "message", "连接失败：" + e.getMessage());
        }
    }

    /** 真实调用平台鉴权接口；成功返回可读结果，失败抛 BusinessException */
    private String probe(ImChannel channel, Map<String, Object> cfg, Map<String, String> secrets) {
        String type = channel.getChannelType();
        if (ImConstants.CHANNEL_FEISHU.equals(type)) {
            String appId = firstNonBlank(cfg.get("appId"), channel.getBotId());
            JsonNode node = webClient.post()
                    .uri("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("app_id", appId, "app_secret", secrets.getOrDefault("appSecret", "")))
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
            if (node == null || node.path("code").asInt(-1) != 0
                    || node.path("tenant_access_token").asText("").isBlank()) {
                throw new BusinessException(400, "飞书凭据校验失败："
                        + (node == null ? "无响应" : node.path("msg").asText("未知错误")));
            }
            return "飞书应用凭据有效";
        }
        if (ImConstants.CHANNEL_WECOM.equals(type)) {
            JsonNode node = webClient.get()
                    .uri(builder -> builder.scheme("https").host("qyapi.weixin.qq.com")
                            .path("/cgi-bin/gettoken")
                            .queryParam("corpid", String.valueOf(cfg.getOrDefault("corpId", "")))
                            .queryParam("corpsecret", secrets.getOrDefault("secret", ""))
                            .build())
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
            if (node == null || node.path("errcode").asInt(-1) != 0) {
                throw new BusinessException(400, "企业微信凭据校验失败："
                        + (node == null ? "无响应" : node.path("errmsg").asText("未知错误")));
            }
            return "企业微信应用凭据有效";
        }
        if (ImConstants.CHANNEL_DINGTALK.equals(type)) {
            String appKey = firstNonBlank(cfg.get("appId"), channel.getBotId());
            JsonNode node = webClient.post()
                    .uri("https://api.dingtalk.com/v1.0/oauth2/accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("appKey", appKey,
                            "appSecret", secrets.getOrDefault("appSecret", "")))
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
            if (node == null || node.path("accessToken").asText("").isBlank()) {
                throw new BusinessException(400, "钉钉凭据校验失败："
                        + (node == null ? "无响应" : node.path("message").asText("未知错误")));
            }
            return "钉钉应用凭据有效";
        }
        throw new BusinessException(400, "该渠道暂不支持连通性探测：" + type);
    }

    private String firstNonBlank(Object primary, String fallback) {
        if (primary != null && !primary.toString().isBlank()) {
            return primary.toString();
        }
        return fallback == null ? "" : fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toConfigMap(ImChannelRequest.ImChannelConfig config) {
        if (config == null) {
            return Map.of();
        }
        return objectMapper.convertValue(config, Map.class);
    }

    /** 去掉空值与密钥字段，只留可公开展示的配置 */
    private Map<String, Object> sanitizeConfig(Map<String, Object> raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v == null || SECRET_FIELDS.contains(k)) {
                return;
            }
            if (v instanceof String s && s.isBlank()) {
                return;
            }
            safe.put(k, v);
        });
        return safe;
    }

    /** 密钥字段单独 AES-GCM 加密；没有密钥时返回 "{}" */
    private String encryptSecrets(Map<String, Object> raw) {
        Map<String, String> encrypted = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (SECRET_FIELDS.contains(k) && v instanceof String s && !s.isBlank()) {
                encrypted.put(k, partnerCrypto.encrypt(s.trim()));
            }
        });
        return writeJson(encrypted);
    }

    private Map<String, String> decryptSecrets(ImChannel channel) {
        Map<String, String> plain = new LinkedHashMap<>();
        if (channel.getSecrets() == null || channel.getSecrets().isBlank()) {
            return plain;
        }
        try {
            JsonNode node = objectMapper.readTree(channel.getSecrets());
            node.fields().forEachRemaining(e -> {
                try {
                    plain.put(e.getKey(), partnerCrypto.decrypt(e.getValue().asText()));
                } catch (Exception ex) {
                    log.warn("解密渠道密钥失败 channelId={} field={}", channel.getId(), e.getKey());
                }
            });
        } catch (Exception e) {
            log.warn("解析渠道 secrets 失败 channelId={}", channel.getId());
        }
        return plain;
    }

    private String writeJson(Map<?, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("序列化渠道配置失败", e);
            return "{}";
        }
    }

    /** 渠道写操作按企业隔离；超管放行。 */
    private void assertChannelAccess(ImChannel channel) {
        if (companyScopeService.isUnrestrictedDataScope()) {
            return;
        }
        java.util.UUID companyId = com.aiextract.config.TokenContext.getCompanyId();
        if (companyId == null || !companyId.equals(channel.getCompanyId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "无权操作其他企业的 IM 渠道");
        }
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

        // 历史行可能把密钥写在 config 里；返回前统一剔除，前端永远拿不到明文
        Object configObj = null;
        Map<String, Object> parsed = parseConfig(channel.getConfig());
        if (!parsed.isEmpty()) {
            configObj = sanitizeConfig(parsed);
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
