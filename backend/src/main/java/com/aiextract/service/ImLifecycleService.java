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
     * 发起「发布到 IM 渠道」：飞书走扫码授权，企微/钉钉走凭据填写。
     */
    public ImPublishResponse publish(String channelType, UUID skillId, String name,
                                     String description, String avatar,
                                     Map<String, String> credentials) {
        if (ImConstants.CHANNEL_FEISHU.equals(channelType)) {
            return publishFeishu(skillId, name, description, avatar);
        }
        return publishWithCredentials(channelType, skillId, name, description, credentials);
    }

    /**
     * 发起「发布到飞书」：生成注册令牌、调桥拿二维码。
     */
    private ImPublishResponse publishFeishu(UUID skillId, String name, String description, String avatar) {
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

        // 防重复绑定（1 分身 = 1 bot）；publishing/failed 行允许复用重试
        ImChannel existing = imChannelRepository
                .findFirstByCompanyIdAndChannelTypeAndSkillId(
                        skillCompany, ImConstants.CHANNEL_FEISHU, skillId)
                .orElse(null);
        if (existing != null && ImConstants.STATUS_PUBLISHED.equals(existing.getStatus())) {
            throw new BusinessException(400, "该分身已发布到飞书");
        }
        writeAudit("publish_im", "skill", skillId, Map.of("channel", ImConstants.CHANNEL_FEISHU));

        // 机器人名称/描述/头像：优先用管理员配置值，缺省回退分身资料
        String botName = isNotBlank(name) ? name : skill.getDisplayName() != null ? skill.getDisplayName() : "AI分身";
        String botDesc = isNotBlank(description) ? description : resolveDescription(skill);
        String botAvatar = isNotBlank(avatar) ? avatar : skill.getAvatarUrl() != null ? skill.getAvatarUrl() : "";

        String registerToken = UUID.randomUUID().toString();
        storeRegisterToken(registerToken, skillId, skillCompany);

        Map<String, Object> bridgeResp;
        try {
            bridgeResp = postBridge("/register", Map.of(
                    "registerToken", registerToken,
                    "name", botName,
                    "description", botDesc,
                    "avatar", botAvatar));
        } catch (RuntimeException e) {
            // 桥不可用：落 failed 供管理端看到并重试，不留下 published 假象
            upsertPublishingRow(existing, skillCompany, ImConstants.CHANNEL_FEISHU, skillId,
                    ImConstants.STATUS_FAILED, "发起发布失败：" + e.getMessage());
            throw new BusinessException(502, "发布服务暂时不可用，请稍后重试");
        }
        Object qrUrl = bridgeResp.get("qrUrl");
        if (qrUrl == null || qrUrl.toString().isBlank()) {
            upsertPublishingRow(existing, skillCompany, ImConstants.CHANNEL_FEISHU, skillId,
                    ImConstants.STATUS_FAILED, "桥未返回授权二维码");
            throw new BusinessException(502, "未获取到飞书授权二维码，请稍后重试");
        }
        upsertPublishingRow(existing, skillCompany, ImConstants.CHANNEL_FEISHU, skillId,
                ImConstants.STATUS_PUBLISHING, null);

        return ImPublishResponse.builder()
                .registerToken(registerToken)
                .qrUrl(bridgeResp.get("qrUrl") != null ? bridgeResp.get("qrUrl").toString() : null)
                .expireIn(bridgeResp.get("expireIn") != null
                        ? ((Number) bridgeResp.get("expireIn")).longValue() : null)
                .build();
    }

    /**
     * 凭证式发布（企业微信自建应用 / 钉钉 Stream 机器人）。
     *
     * <p>与飞书扫码的区别：不需要二维码回调，管理员直接填 App 凭据；
     * 发布时先调平台接口校验凭据（事务外 HTTP），通过后加密落库并通知桥建连。</p>
     */
    private ImPublishResponse publishWithCredentials(String channelType, UUID skillId, String name,
                                                     String description, Map<String, String> credentials) {
        assertCompanyAdmin();
        if (!ImConstants.isCredentialChannel(channelType)) {
            throw new BusinessException(400, "不支持的渠道：" + channelType);
        }
        validateCredentials(channelType, credentials);

        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        assertPublishable(skill);
        UUID skillCompany = resolveSkillCompany(skill);
        if (skillCompany == null) {
            throw new BusinessException(400, "无法解析分身归属企业");
        }

        String label = ImConstants.channelLabel(channelType);
        ImChannel existing = imChannelRepository
                .findFirstByCompanyIdAndChannelTypeAndSkillId(skillCompany, channelType, skillId)
                .orElse(null);
        if (existing != null && ImConstants.STATUS_PUBLISHED.equals(existing.getStatus())) {
            throw new BusinessException(400, "该分身已发布到" + label);
        }

        String botId = buildBotId(channelType, credentials);
        // bot_id 全局唯一：同一应用不允许被两个分身/企业绑定（V83 唯一索引的业务前置校验）
        ImChannel conflict = imChannelRepository.findByBotId(botId).orElse(null);
        if (conflict != null && (existing == null || !conflict.getId().equals(existing.getId()))) {
            throw new BusinessException(400, "该应用已绑定其他分身，请先在原分身下线后再发布");
        }

        // 凭据真实可用才落库：验证失败直接 400，不留假 published
        probePlatform(channelType, credentials);

        Map<String, Object> bridgeConfig = buildBridgeConfig(channelType, credentials);
        LocalDateTime now = LocalDateTime.now();
        ImChannel row = existing != null ? existing : ImChannel.builder()
                .id(UUID.randomUUID())
                .companyId(skillCompany)
                .channelType(channelType)
                .linkedSkills("[]")
                .skillId(skillId)
                .createdAt(now)
                .build();
        row.setEnabled(true);
        row.setConfig(writeJson(buildPublicConfig(channelType, credentials)));
        // bot_id 是所有渠道的统一路由键：企业微信 corpId:agentId、钉钉 appKey
        row.setBotId(botId);
        row.setSkillId(skillId);
        row.setSecrets(encryptSecrets(credentials));
        row.setBridgeId(normalizeBridgeId(row.getBridgeId()));
        row.setStatus(ImConstants.STATUS_PUBLISHED);
        row.setLastError(null);
        row.setUpdatedAt(now);
        try {
            // 唯一索引兜底并发发布：两个请求同时通过上面的前置校验时，只有一个能落库
            imChannelRepository.saveAndFlush(row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException(400, "该应用已绑定其他分身，请先在原分身下线后再发布");
        }
        writeAudit("publish_im", "skill", skillId, Map.of("channel", channelType, "botId", botId));

        try {
            postBridge("/connect", Map.of(
                    "channelType", channelType,
                    "botId", botId,
                    "appId", bridgeAppId(channelType, credentials),
                    "appSecret", bridgeAppSecret(channelType, credentials),
                    "config", bridgeConfig,
                    "openingMessage", skill.getOpeningMessage() != null ? skill.getOpeningMessage() : "",
                    "recommendedQuestions", parseRecommendedQuestions(skill)));
        } catch (RuntimeException e) {
            row.setStatus(ImConstants.STATUS_FAILED);
            row.setLastError(truncate("桥连接失败：" + e.getMessage()));
            row.setUpdatedAt(LocalDateTime.now());
            imChannelRepository.save(row);
            throw new BusinessException(502, label + "凭据已保存，但连接消息服务失败，请稍后点「重连」");
        }
        log.info("IM 渠道已绑定 channelType={} botId={} skillId={}", channelType, botId, skillId);
        return ImPublishResponse.builder().bridgeBotId(botId).build();
    }

    /**
     * 扫码完成回调（桥 → 后端）：落库绑定 + 通知桥建立连接。
     * 注：save 为单语句原子操作；HTTP 调用（/connect）放在事务外，遵循「事务内禁止 HTTP 调用」。
     */
    public void completePublish(String channelType, ImCompleteRequest req, String bridgeId) {
        UUID skillId = loadRegisterToken(req.getRegisterToken());
        UUID companyId = loadRegisterCompany(req.getRegisterToken());

        ImChannel channel = imChannelRepository
                .findFirstByCompanyIdAndChannelTypeAndSkillId(companyId, channelType, skillId)
                .orElse(null);
        // 幂等守卫：已发布的不允许重复绑定
        if (channel != null && ImConstants.STATUS_PUBLISHED.equals(channel.getStatus())) {
            throw new BusinessException(400, "该分身已发布到" + ImConstants.channelLabel(channelType));
        }

        LocalDateTime now = LocalDateTime.now();
        if (channel == null) {
            channel = ImChannel.builder()
                    .id(UUID.randomUUID())
                    .companyId(companyId)
                    .channelType(channelType)
                    .linkedSkills("[]")
                    .createdAt(now)
                    .build();
        }
        channel.setEnabled(true);
        channel.setConfig(buildConfig(req.getAppId()));
        channel.setBotId(req.getAppId());
        channel.setSkillId(skillId);
        channel.setSecrets(encryptSecrets(req.getAppSecret()));
        channel.setBridgeId(normalizeBridgeId(bridgeId));
        channel.setStatus(ImConstants.STATUS_PUBLISHED);
        channel.setLastError(null);
        channel.setUpdatedAt(now);
        imChannelRepository.save(channel);

        redisTemplate.delete(REGISTER_PREFIX + req.getRegisterToken());
        log.info("IM 渠道已绑定 channelId={} botId={} skillId={} bridgeId={}",
                channel.getId(), req.getAppId(), skillId, channel.getBridgeId());

        // 通知桥建立 WebSocket 连接（桥已无本地状态，凭据由后端下发），并携带开场白/推荐问题供 botAdded 引导
        Skill skill = skillRepository.findById(skillId).orElse(null);
        try {
            postBridge("/connect", Map.of(
                    "channelType", channelType,
                    "botId", req.getAppId(),
                    "appId", req.getAppId(),
                    "appSecret", req.getAppSecret(),
                    "config", Map.of(),
                    "openingMessage", skill != null && skill.getOpeningMessage() != null ? skill.getOpeningMessage() : "",
                    "recommendedQuestions", skill != null ? parseRecommendedQuestions(skill) : List.of()));
        } catch (RuntimeException e) {
            // 凭据已保存，但桥没连上：状态回落 failed，管理端可重连
            channel.setStatus(ImConstants.STATUS_FAILED);
            channel.setLastError(truncate("桥连接失败：" + e.getMessage()));
            channel.setUpdatedAt(LocalDateTime.now());
            imChannelRepository.save(channel);
            throw new BusinessException(502, "机器人已授权，但连接消息服务失败，请在机器人列表点「重连」");
        }
    }

    /** 发布前置/失败状态落库；同一企业+渠道+分身复用一行 */
    private void upsertPublishingRow(ImChannel existing, UUID companyId, String channelType, UUID skillId,
                                     String status, String error) {
        LocalDateTime now = LocalDateTime.now();
        ImChannel row = existing;
        if (row == null) {
            row = ImChannel.builder()
                    .id(UUID.randomUUID())
                    .companyId(companyId)
                    .channelType(channelType)
                    .linkedSkills("[]")
                    .skillId(skillId)
                    .createdAt(now)
                    .build();
        }
        row.setStatus(status);
        row.setLastError(truncate(error));
        row.setUpdatedAt(now);
        imChannelRepository.save(row);
    }

    /** 管理端重连：凭据已加密保存，失败时可重新通知桥建连 */
    public void reconnect(String botId) {
        assertCompanyAdmin();
        ImChannel channel = imChannelRepository.findByBotId(botId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.CHANNEL_NOT_FOUND));
        if (!companyScopeService.isUnrestrictedDataScope()
                && !java.util.Objects.equals(TokenContext.getCompanyId(), channel.getCompanyId())) {
            throw new BusinessException(403, "无权操作其他企业的机器人");
        }
        Map<String, String> creds = credentialMap(channel);
        String channelType = channel.getChannelType();
        String secret = ImConstants.CHANNEL_WECOM.equals(channelType)
                ? creds.get("secret") : creds.get("appSecret");
        if (secret == null || secret.isBlank()) {
            throw new BusinessException(400, "凭据已清除，请重新发布");
        }
        Skill skill = channel.getSkillId() != null
                ? skillRepository.findById(channel.getSkillId()).orElse(null) : null;
        try {
            postBridge("/connect", Map.of(
                    "channelType", channelType,
                    "botId", botId,
                    "appId", ImConstants.CHANNEL_WECOM.equals(channelType)
                            ? creds.get("corpId") : channel.getBotId(),
                    "appSecret", secret,
                    "config", buildBridgeConfig(channelType, creds),
                    "openingMessage", skill != null && skill.getOpeningMessage() != null ? skill.getOpeningMessage() : "",
                    "recommendedQuestions", skill != null ? parseRecommendedQuestions(skill) : List.of()));
            channel.setStatus(ImConstants.STATUS_PUBLISHED);
            channel.setLastError(null);
        } catch (RuntimeException e) {
            channel.setStatus(ImConstants.STATUS_FAILED);
            channel.setLastError(truncate("重连失败：" + e.getMessage()));
            channel.setUpdatedAt(LocalDateTime.now());
            imChannelRepository.save(channel);
            throw new BusinessException(502, "重连失败，请检查桥服务或稍后再试");
        }
        channel.setUpdatedAt(LocalDateTime.now());
        imChannelRepository.save(channel);
        writeAudit("reconnect_im", "im_channel", channel.getId(), Map.of("botId", botId));
        log.info("IM 机器人已重连 botId={}", botId);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String normalizeBridgeId(String bridgeId) {
        return bridgeId == null || bridgeId.isBlank() ? "default" : bridgeId.trim();
    }

    /**
     * 下线机器人。
     */
    public void unpublish(String botId) {
        assertCompanyAdmin();
        ImChannel channel = imChannelRepository.findByBotId(botId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.CHANNEL_NOT_FOUND));
        if (!companyScopeService.isUnrestrictedDataScope()
                && !java.util.Objects.equals(
                        com.aiextract.config.TokenContext.getCompanyId(), channel.getCompanyId())) {
            throw new BusinessException(403, "无权下线其他企业的机器人");
        }

        channel.setStatus(ImConstants.STATUS_DISABLED);
        channel.setEnabled(false);
        channel.setSecrets(null);  // 下线即删凭据，不留 appSecret
        imChannelRepository.save(channel);

        writeAudit("unpublish_im", "im_channel", channel.getId(),
                Map.of("botId", botId, "channel", channel.getChannelType()));
        postBridge("/disconnect", Map.of(
                "channelType", channel.getChannelType(),
                "botId", botId));
        log.info("IM 机器人已下线 channelType={} botId={}", channel.getChannelType(), botId);
    }

    // ============================================================
    // 查询
    // ============================================================

    /**
     * 传输层开机对账：拉取需连接的 bot（含解密后的 appSecret）。
     */
    public List<ImActiveBotResponse> listActiveBots(String channelType, String bridgeId) {
        List<ImChannel> channels = imChannelRepository
                .findByChannelTypeAndStatusAndBridgeId(channelType, ImConstants.STATUS_PUBLISHED,
                        normalizeBridgeId(bridgeId))
                .stream()
                .filter(ch -> Boolean.TRUE.equals(ch.getEnabled()) && ch.getBotId() != null)
                .toList();
        Map<UUID, Skill> skillMap = loadSkillMap(channels);
        return channels.stream()
                .map(ch -> {
                    Skill skill = ch.getSkillId() != null ? skillMap.get(ch.getSkillId()) : null;
                    Map<String, String> creds = credentialMap(ch);
                    String type = ch.getChannelType();
                    String secret = ImConstants.CHANNEL_WECOM.equals(type)
                            ? creds.get("secret") : creds.get("appSecret");
                    return ImActiveBotResponse.builder()
                            .channelType(type)
                            .botId(ch.getBotId())
                            // 飞书 appId、钉钉 appKey 都等于 botId；企微是 corpId
                            .appId(ImConstants.CHANNEL_WECOM.equals(type)
                                    ? creds.get("corpId") : ch.getBotId())
                            .appSecret(secret)
                            .config(buildBridgeConfig(type, creds))
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
                            .lastError(ch.getLastError())
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
            throw new BusinessException(403, "仅企业管理员可发布到 IM 渠道");
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
        return encryptSecrets(Map.of("appSecret", appSecret));
    }

    /** 每个凭据字段单独 AES-GCM 加密，整张 map 以 JSON 落 secrets 列 */
    private String encryptSecrets(Map<String, String> plain) {
        try {
            java.util.Map<String, String> encrypted = new java.util.LinkedHashMap<>();
            plain.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    encrypted.put(k, partnerCrypto.encrypt(v.trim()));
                }
            });
            return objectMapper.writeValueAsString(encrypted);
        } catch (Exception e) {
            throw new BusinessException(500, "密钥加密失败");
        }
    }

    /** 解密 secrets 列；任一字段解密失败返回已成功解出的部分，调用方按缺失处理 */
    private Map<String, String> decryptSecrets(ImChannel channel) {
        if (channel.getSecrets() == null || channel.getSecrets().isBlank()) {
            return java.util.Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(channel.getSecrets());
            java.util.Map<String, String> plain = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                try {
                    plain.put(entry.getKey(), partnerCrypto.decrypt(entry.getValue().asText()));
                } catch (Exception e) {
                    log.warn("解密字段失败 channelId={} field={}", channel.getId(), entry.getKey());
                }
            });
            return plain;
        } catch (Exception e) {
            log.warn("解析 secrets 失败 channelId={}", channel.getId());
            return java.util.Map.of();
        }
    }

    // ============================================================
    // 多渠道路由 / 凭据
    // ============================================================

    private static final Map<String, List<String>> REQUIRED_CREDENTIALS = Map.of(
            ImConstants.CHANNEL_WECOM, List.of("corpId", "agentId", "secret", "token", "encodingAesKey"),
            ImConstants.CHANNEL_DINGTALK, List.of("appKey", "appSecret"));

    private void validateCredentials(String channelType, Map<String, String> creds) {
        String label = ImConstants.channelLabel(channelType);
        if (creds == null || creds.isEmpty()) {
            throw new BusinessException(400, "请填写" + label + "应用凭据");
        }
        for (String field : REQUIRED_CREDENTIALS.getOrDefault(channelType, List.of())) {
            String value = creds.get(field);
            if (value == null || value.isBlank()) {
                throw new BusinessException(400, "缺少必填凭据：" + field);
            }
        }
        creds.forEach((k, v) -> {
            if (v != null && v.length() > 500) {
                throw new BusinessException(400, "凭据字段过长：" + k);
            }
        });
        if (ImConstants.CHANNEL_WECOM.equals(channelType)) {
            if (creds.get("encodingAesKey").trim().length() != 43) {
                throw new BusinessException(400, "EncodingAESKey 必须是 43 位字符");
            }
            if (!creds.get("agentId").trim().matches("\\d{1,20}")) {
                throw new BusinessException(400, "AgentId 必须是数字");
            }
        }
    }

    /** 发布前校验凭据真实可用（事务外调用平台接口） */
    private void probePlatform(String channelType, Map<String, String> creds) {
        String label = ImConstants.channelLabel(channelType);
        try {
            if (ImConstants.CHANNEL_WECOM.equals(channelType)) {
                JsonNode node = webClient.get()
                        .uri(builder -> builder.scheme("https").host("qyapi.weixin.qq.com")
                                .path("/cgi-bin/gettoken")
                                .queryParam("corpid", creds.get("corpId").trim())
                                .queryParam("corpsecret", creds.get("secret").trim())
                                .build())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(Duration.ofSeconds(10));
                if (node == null || node.path("errcode").asInt(-1) != 0) {
                    throw new BusinessException(400, label + "凭据校验失败："
                            + (node == null ? "无响应" : node.path("errmsg").asText("未知错误")));
                }
                return;
            }
            JsonNode node = webClient.post()
                    .uri("https://api.dingtalk.com/v1.0/oauth2/accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "appKey", creds.get("appKey").trim(),
                            "appSecret", creds.get("appSecret").trim()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10));
            if (node == null || node.path("accessToken").asText("").isBlank()) {
                throw new BusinessException(400, label + "凭据校验失败："
                        + (node == null ? "无响应" : node.path("message").asText("未知错误")));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("IM 凭据探测失败 channelType={}: {}", channelType, e.getMessage());
            throw new BusinessException(502, "无法连接" + label + "开放平台，请检查网络或稍后重试");
        }
    }

    private String buildBotId(String channelType, Map<String, String> creds) {
        if (ImConstants.CHANNEL_WECOM.equals(channelType)) {
            return creds.get("corpId").trim() + ":" + creds.get("agentId").trim();
        }
        return creds.get("appKey").trim();
    }

    /** 可公开展示的配置（不含任何密钥） */
    private Map<String, Object> buildPublicConfig(String channelType, Map<String, String> creds) {
        java.util.Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        if (ImConstants.CHANNEL_WECOM.equals(channelType)) {
            putIfPresent(cfg, "corpId", creds.get("corpId"));
            putIfPresent(cfg, "agentId", creds.get("agentId"));
        } else if (ImConstants.CHANNEL_DINGTALK.equals(channelType)) {
            putIfPresent(cfg, "robotCode", creds.get("robotCode"));
        }
        return cfg;
    }

    /** 下发给传输层的非密钥配置 */
    private Map<String, Object> buildBridgeConfig(String channelType, Map<String, String> creds) {
        java.util.Map<String, Object> cfg = new java.util.LinkedHashMap<>(buildPublicConfig(channelType, creds));
        if (ImConstants.CHANNEL_WECOM.equals(channelType)) {
            putIfPresent(cfg, "token", creds.get("token"));
            putIfPresent(cfg, "encodingAesKey", creds.get("encodingAesKey"));
        }
        return cfg;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private String bridgeAppId(String channelType, Map<String, String> creds) {
        return ImConstants.CHANNEL_WECOM.equals(channelType)
                ? creds.get("corpId").trim() : creds.get("appKey").trim();
    }

    private String bridgeAppSecret(String channelType, Map<String, String> creds) {
        return ImConstants.CHANNEL_WECOM.equals(channelType)
                ? creds.get("secret").trim() : creds.get("appSecret").trim();
    }

    /** 渠道公开配置 + 解密后的密钥合成完整凭据表 */
    private Map<String, String> credentialMap(ImChannel channel) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (channel.getConfig() != null && !channel.getConfig().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(channel.getConfig());
                node.fields().forEachRemaining(e -> merged.put(e.getKey(), e.getValue().asText()));
            } catch (Exception e) {
                log.warn("解析渠道 config 失败 channelId={}", channel.getId());
            }
        }
        merged.putAll(decryptSecrets(channel));
        return merged;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
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
