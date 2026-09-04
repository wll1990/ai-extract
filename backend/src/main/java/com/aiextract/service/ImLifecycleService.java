package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.common.ImConstants;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.ImBridgeAuth;
import com.aiextract.config.PartnerCrypto;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.ImActiveBotResponse;
import com.aiextract.dto.ImBotResponse;
import com.aiextract.dto.ImCompleteRequest;
import com.aiextract.dto.ImPublishResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.ImChannel;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.ImChannelRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * IM 生命周期服务 — 发布/扫码完成/下线 + secrets 加解密。
 *
 * <p>后端拥有生命周期状态机（im_channel 是唯一真相源），传输层拥有物理连接。
 * 一切状态变迁由后端发起：publish → 调桥 /register；complete → 写库 + 调桥 /connect；
 * unpublish → 置 disabled + 删 secrets + 调桥 /disconnect。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImLifecycleService {

    private static final String REGISTER_PREFIX = "im:register:";
    private static final Duration REGISTER_TTL = Duration.ofMinutes(10);

    private final ImChannelRepository imChannelRepository;
    private final SkillRepository skillRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final CompanyScopeService companyScopeService;
    private final AdminAuditLogRepository auditLogRepository;
    private final PartnerCrypto partnerCrypto;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final WebClient webClient;
    private final ImBridgeAuth imBridgeAuth;

    @Value("${app.im.bridge-url:http://localhost:3010}")
    private String bridgeUrl;

    // ============================================================
    // 发布（扫码授权）
    // ============================================================

    /**
     * 发起「发布到飞书」：生成注册令牌、调桥拿二维码。
     */
    public ImPublishResponse publish(UUID skillId, String name, String description, String avatar) {
        assertCompanyAdmin();

        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        assertPublishable(skill);

        // 分身归属企业（org 用 skill.companyId；个体经 space→user→company 解析），
        // 不依赖 TokenContext（super_admin 无 companyId）
        UUID skillCompany = resolveSkillCompany(skill);
        if (skillCompany == null) {
            throw new BusinessException(400, "无法解析分身归属企业");
        }

        // 防重复绑定（1 分身 = 1 bot）
        if (imChannelRepository.existsByCompanyIdAndChannelTypeAndSkillId(skillCompany, ImConstants.CHANNEL_FEISHU, skillId)) {
            throw new BusinessException(400, "该分身已发布到飞书");
        }
        writeAudit("publish_im", "skill", skillId, Map.of("channel", ImConstants.CHANNEL_FEISHU));

        // 机器人名称/描述/头像：优先用管理员配置值，缺省回退分身资料
        String botName = isNotBlank(name) ? name : skill.getDisplayName() != null ? skill.getDisplayName() : "AI分身";
        String botDesc = isNotBlank(description) ? description : resolveDescription(skill);
        String botAvatar = isNotBlank(avatar) ? avatar : skill.getAvatarUrl() != null ? skill.getAvatarUrl() : "";

        String registerToken = UUID.randomUUID().toString();
        storeRegisterToken(registerToken, skillId, skillCompany);

        Map<String, Object> bridgeResp = postBridge("/register", Map.of(
                "registerToken", registerToken,
                "name", botName,
                "description", botDesc,
                "avatar", botAvatar));

        return ImPublishResponse.builder()
                .registerToken(registerToken)
                .qrUrl(bridgeResp.get("qrUrl") != null ? bridgeResp.get("qrUrl").toString() : null)
                .expireIn(bridgeResp.get("expireIn") != null
                        ? ((Number) bridgeResp.get("expireIn")).longValue() : null)
                .build();
    }

    /**
     * 扫码完成回调（桥 → 后端）：落库绑定 + 通知桥建立连接。
     * 注：save 为单语句原子操作；HTTP 调用（/connect）放在事务外，遵循「事务内禁止 HTTP 调用」。
     */
    public void completePublish(String channelType, ImCompleteRequest req) {
        UUID skillId = loadRegisterToken(req.getRegisterToken());
        UUID companyId = loadRegisterCompany(req.getRegisterToken());

        // 幂等守卫：同一分身只允许绑定一次（并发下仍有唯一索引兜底）
        if (imChannelRepository.existsByCompanyIdAndChannelTypeAndSkillId(companyId, channelType, skillId)) {
            throw new BusinessException(400, "该分身已发布到飞书");
        }

        ImChannel channel = ImChannel.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .channelType(channelType)
                .enabled(true)
                .config(buildConfig(req.getAppId()))
                .linkedSkills("[]")
                .botId(req.getAppId())
                .skillId(skillId)
                .status(ImConstants.STATUS_PUBLISHED)
                .secrets(encryptSecrets(req.getAppSecret()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        imChannelRepository.save(channel);

        redisTemplate.delete(REGISTER_PREFIX + req.getRegisterToken());
        log.info("IM 渠道已绑定 channelId={} botId={} skillId={}", channel.getId(), req.getAppId(), skillId);

        // 通知桥建立 WebSocket 连接（桥已无本地状态，凭据由后端下发），并携带开场白/推荐问题供 botAdded 引导
        Skill skill = skillRepository.findById(skillId).orElse(null);
        postBridge("/connect", Map.of(
                "botId", req.getAppId(),
                "appId", req.getAppId(),
                "appSecret", req.getAppSecret(),
                "openingMessage", skill != null && skill.getOpeningMessage() != null ? skill.getOpeningMessage() : "",
                "recommendedQuestions", skill != null ? parseRecommendedQuestions(skill) : List.of()));
    }

    /**
     * 下线机器人。
     */
    public void unpublish(String botId) {
        assertCompanyAdmin();
        ImChannel channel = imChannelRepository.findByBotId(botId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.CHANNEL_NOT_FOUND));

        channel.setStatus(ImConstants.STATUS_DISABLED);
        channel.setEnabled(false);
        channel.setSecrets(null);  // 下线即删凭据，不留 appSecret
        imChannelRepository.save(channel);

        writeAudit("unpublish_im", "im_channel", channel.getId(), Map.of("botId", botId));
        postBridge("/disconnect", Map.of("botId", botId));
        log.info("IM 机器人已下线 botId={}", botId);
    }

    // ============================================================
    // 查询
    // ============================================================

    /**
     * 传输层开机对账：拉取需连接的 bot（含解密后的 appSecret）。
     */
    public List<ImActiveBotResponse> listActiveBots(String channelType) {
        List<ImChannel> channels = imChannelRepository.findByChannelTypeAndStatus(channelType, ImConstants.STATUS_PUBLISHED).stream()
                .filter(ch -> Boolean.TRUE.equals(ch.getEnabled()) && ch.getBotId() != null)
                .toList();
        Map<UUID, Skill> skillMap = loadSkillMap(channels);
        return channels.stream()
                .map(ch -> {
                    Skill skill = ch.getSkillId() != null ? skillMap.get(ch.getSkillId()) : null;
                    return ImActiveBotResponse.builder()
                            .botId(ch.getBotId())
                            .appId(ch.getBotId())  // 飞书 botId == app_id
                            .appSecret(decryptAppSecret(ch))
                            .skillId(ch.getSkillId() != null ? ch.getSkillId().toString() : null)
                            .openingMessage(skill != null ? skill.getOpeningMessage() : null)
                            .recommendedQuestions(skill != null ? parseRecommendedQuestions(skill) : List.of())
                            .build();
                })
                .toList();
    }

    /**
     * 管理后台：本企业已发布机器人列表。
     */
    public List<ImBotResponse> listBots(UUID companyId) {
        assertCompanyAdmin();
        List<ImChannel> channels = companyId == null
                ? imChannelRepository.findByBotIdIsNotNull()
                : imChannelRepository.findByCompanyId(companyId);
        List<ImChannel> bound = channels.stream()
                .filter(ch -> ch.getBotId() != null && ch.getSkillId() != null)
                .toList();
        Map<UUID, Skill> skillMap = loadSkillMap(bound);
        return bound.stream()
                .map(ch -> {
                    Skill skill = skillMap.get(ch.getSkillId());
                    String skillName = skill != null ? skill.getDisplayName() : null;
                    return ImBotResponse.builder()
                            .id(ch.getId().toString())
                            .channelType(ch.getChannelType())
                            .botId(ch.getBotId())
                            .appId(ch.getBotId())
                            .skillId(ch.getSkillId().toString())
                            .skillName(skillName)
                            .status(ch.getStatus())
                            .enabled(ch.getEnabled())
                            .connected(null)  // 连接状态由桥回报，Phase 2 接线
                            .createdAt(ch.getCreatedAt() != null ? ch.getCreatedAt().toString() : null)
                            .build();
                })
                .toList();
    }

    // ============================================================
    // 私有
    // ============================================================

    private void assertCompanyAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean ok = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> {
                    String g = a.getAuthority();
                    return RolePermissions.COMPANY_ADMIN.equals(g) || RolePermissions.SUPER_ADMIN.equals(g);
                });
        if (!ok) {
            throw new BusinessException(403, "仅企业管理员可发布到飞书");
        }
    }

    private void assertPublishable(Skill skill) {
        if (!ImConstants.SKILL_STATUS_PUBLISHED.equals(skill.getStatus())) {
            throw new BusinessException(400, "分身未发布");
        }
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            return; // super_admin（平台级）跳过企业归属校验
        }
        if (ImConstants.SKILL_TYPE_ORGANIZATION.equals(skill.getType())) {
            if (!companyId.equals(skill.getCompanyId())) {
                throw new BusinessException(403, "无权发布其他企业的分身");
            }
        } else {
            companyScopeService.assertSkillOwnership(skill.getId());
        }
    }

    /** 解析分身的归属企业：org 用 skill.companyId，个体经 space→user→company */
    private UUID resolveSkillCompany(Skill skill) {
        if (ImConstants.SKILL_TYPE_ORGANIZATION.equals(skill.getType())) {
            return skill.getCompanyId();
        }
        if (skill.getSpaceId() == null) {
            return null;
        }
        return spaceRepository.findById(skill.getSpaceId())
                .map(Space::getUserId)
                .flatMap(userRepository::findById)
                .map(User::getCompanyId)
                .orElse(null);
    }

    private String resolveDescription(Skill skill) {
        if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
            return skill.getDescription();
        }
        return skill.getOpeningMessage() == null ? "" : skill.getOpeningMessage();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 写管理员操作审计（复用 admin_audit_log，adminId 取请求上下文） */
    private void writeAudit(String action, String targetType, UUID targetId, Map<String, Object> detail) {
        try {
            UUID adminId = TokenContext.get();
            if (adminId == null) {
                adminId = UUID.fromString("00000000-0000-0000-0000-000000000000");
            }
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(adminId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(objectMapper.writeValueAsString(detail))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }

    private void storeRegisterToken(String token, UUID skillId, UUID companyId) {
        try {
            String value = objectMapper.writeValueAsString(Map.of(
                    "skillId", skillId.toString(),
                    "companyId", companyId.toString()));
            redisTemplate.opsForValue().set(REGISTER_PREFIX + token, value, REGISTER_TTL);
        } catch (Exception e) {
            throw new BusinessException(500, "注册令牌存储失败");
        }
    }

    private UUID loadRegisterToken(String token) {
        JsonNode node = readRegisterNode(token);
        String skillId = node.path("skillId").asText();
        if (skillId.isEmpty()) {
            throw new BusinessException(400, "注册令牌已失效，请重新扫码");
        }
        return UUID.fromString(skillId);
    }

    private UUID loadRegisterCompany(String token) {
        JsonNode node = readRegisterNode(token);
        String companyId = node.path("companyId").asText();
        return companyId.isEmpty() ? null : UUID.fromString(companyId);
    }

    private JsonNode readRegisterNode(String token) {
        String value = redisTemplate.opsForValue().get(REGISTER_PREFIX + token);
        if (value == null) {
            throw new BusinessException(400, "注册令牌已失效，请重新扫码");
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new BusinessException(400, "注册令牌已失效，请重新扫码");
        }
    }

    private String encryptSecrets(String appSecret) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "appSecret", partnerCrypto.encrypt(appSecret)));
        } catch (Exception e) {
            throw new BusinessException(500, "密钥加密失败");
        }
    }

    private String decryptAppSecret(ImChannel channel) {
        if (channel.getSecrets() == null || channel.getSecrets().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(channel.getSecrets());
            String encrypted = node.path("appSecret").asText("");
            return encrypted.isEmpty() ? null : partnerCrypto.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("解密 appSecret 失败 channelId={}", channel.getId());
            return null;
        }
    }

    private String buildConfig(String appId) {
        try {
            return objectMapper.writeValueAsString(Map.of("appId", appId));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 批量加载分身为 Map，避免 list 里逐条 findById 的 N+1 */
    private Map<UUID, Skill> loadSkillMap(List<ImChannel> channels) {
        List<UUID> skillIds = channels.stream()
                .map(ImChannel::getSkillId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        return skillRepository.findAllById(skillIds).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));
    }

    /** 解析分身的推荐问题 JSONB 为字符串列表（容错） */
    private List<String> parseRecommendedQuestions(Skill skill) {
        if (skill.getRecommendedQuestions() == null || skill.getRecommendedQuestions().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(skill.getRecommendedQuestions(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> postBridge(String path, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            Map<String, String> headers = imBridgeAuth.signHeaders(json);
            String resp = webClient.post()
                    .uri(bridgeUrl + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::set))
                    .bodyValue(json)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (resp == null || resp.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(resp, new TypeReference<Map<String, Object>>() {});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 IM 桥失败 path={}: {}", path, e.getMessage());
            throw new BusinessException(500, "IM 桥服务不可用，请稍后重试");
        }
    }
}
