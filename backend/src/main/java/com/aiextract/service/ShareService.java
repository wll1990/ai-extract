package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.dto.ShareInfoResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AnalyticsEvent;
import com.aiextract.model.AppUser;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillShare;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.AnalyticsEventRepository;
import com.aiextract.repository.AppUserRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SkillShareRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.util.JwtUtil;
import com.aiextract.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 分身分享服务
 *
 * <p>管理端：生成/启停分享短码（建码时解析并冗余企业归属）。
 * 公开端：分享落地信息、游客发证（含滑动续期）。
 * 对外可聊判定 = skill.status='published'（兼容 active） 且 share.enabled=true，
 * 撤发布或关开关后分享链接立即 404。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareService {

    private static final int MAX_SHARE_CODE_RETRIES = 3;

    /** C 端游客角色（JWT role claim） */
    public static final String ROLE_C_GUEST = "c_guest";

    /** C 端注册用户角色（JWT role claim） */
    public static final String ROLE_C_USER = "c_user";

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHARE_CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SkillShareRepository shareRepository;
    private final SkillRepository skillRepository;
    private final com.aiextract.repository.OrganizationSkillRepository orgSkillRepository;
    private final OrganizationSkillService orgSkillService;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final AppUserRepository appUserRepository;
    private final SkillMessageRepository skillMessageRepository;
    private final SkillService skillService;
    private final ShareRateLimiter rateLimiter;
    private final JwtUtil jwtUtil;
    private final AnalyticsEventRepository analyticsEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.share.guest-message-limit:5}")
    private int guestMessageLimit;

    @Value("${app.share.guest-token-ttl-days:7}")
    private int guestTokenTtlDays;

    @Value("${app.share.c-user-token-ttl-days:30}")
    private int cUserTokenTtlDays;

    // ============================================================
    // 管理端
    // ============================================================

    /**
     * 获取或创建默认渠道分享（幂等）
     *
     * <p>companyId 在建码时经 skill→space→user 两跳解析一次并冗余存储，
     * 公开端运行时零额外查询。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillShare getOrCreateShare(UUID skillId, UUID adminUserId) {
        return getOrCreateShare(skillId, adminUserId, SkillShare.CHANNEL_PUBLIC);
    }

    /** 按渠道生成/获取分享码 */
    @Transactional(rollbackFor = Exception.class)
    public SkillShare getOrCreateShare(UUID skillId, UUID adminUserId, String channel) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));

        Optional<SkillShare> existing = shareRepository.findFirstBySkillIdAndChannel(skillId, channel);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID companyId = resolveCompanyId(skill);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < MAX_SHARE_CODE_RETRIES; i++) {
            String code = randomShareCode();
            if (shareRepository.findByShareCode(code).isEmpty()) {
                SkillShare share = shareRepository.save(SkillShare.builder()
                        .id(UUID.randomUUID())
                        .skillId(skillId)
                        .companyId(companyId)
                        .shareCode(code)
                        .channel(channel)
                        .enabled(true)
                        .createdBy(adminUserId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                log.info("分享码已生成 skillId={} shareCode={} channel={}", skillId, code, channel);
                return share;
            }
        }
        throw new BusinessException(500, "分享码生成失败，请重试");
    }

    /**
     * 查询分享（管理端回显，未生成时返回空）
     */
    public Optional<SkillShare> findShare(UUID skillId) {
        return shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_PUBLIC);
    }

    /**
     * 共享开关：关闭后分享链接立即 404，企业内部使用不受影响
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillShare toggleShare(UUID skillId, boolean enabled) {
        SkillShare share = shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_PUBLIC)
                .orElseThrow(() -> new BusinessException(404, "尚未生成分享链接"));
        share.setEnabled(enabled);
        share.setUpdatedAt(LocalDateTime.now());
        shareRepository.save(share);
        log.info("分享开关变更 skillId={} enabled={}", skillId, enabled);
        return share;
    }

    /**
     * 自定义短码 — 校验唯一性后更新。
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillShare updateShareCode(UUID skillId, String customCode) {
        SkillShare share = shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_PUBLIC)
                .orElseThrow(() -> new BusinessException(404, "尚未生成分享链接"));
        if (!share.getShareCode().equals(customCode)
                && shareRepository.findByShareCode(customCode).isPresent()) {
            throw new BusinessException(400, "该短码已被使用");
        }
        share.setShareCode(customCode);
        share.setUpdatedAt(LocalDateTime.now());
        shareRepository.save(share);
        log.info("短码已更新 skillId={} shareCode={}", skillId, customCode);
        return share;
    }

    // ============================================================
    // 管理端 — 组织分身分享
    // ============================================================

    @Transactional(rollbackFor = Exception.class)
    public SkillShare getOrCreateOrgSkillShare(UUID orgSkillId, UUID adminUserId) {
        return getOrCreateOrgSkillShare(orgSkillId, adminUserId, SkillShare.CHANNEL_PUBLIC);
    }

    @Transactional(rollbackFor = Exception.class)
    public SkillShare getOrCreateOrgSkillShare(UUID orgSkillId, UUID adminUserId, String channel) {
        com.aiextract.model.OrganizationSkill orgSkill = orgSkillRepository.findById(orgSkillId)
                .orElseThrow(() -> new BusinessException(404, "组织分身不存在"));

        Optional<SkillShare> existing = shareRepository.findFirstByOrgSkillIdAndChannel(orgSkillId, channel);
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < MAX_SHARE_CODE_RETRIES; i++) {
            String code = randomShareCode();
            if (shareRepository.findByShareCode(code).isEmpty()) {
                SkillShare share = shareRepository.save(SkillShare.builder()
                        .id(UUID.randomUUID())
                        .orgSkillId(orgSkillId)
                        .companyId(orgSkill.getCompanyId())
                        .shareCode(code)
                        .channel(channel)
                        .enabled(true)
                        .createdBy(adminUserId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                log.info("组织分身分享码已生成 orgSkillId={} shareCode={}", orgSkillId, code);
                return share;
            }
        }
        throw new BusinessException(500, "分享码生成失败，请重试");
    }

    public Optional<SkillShare> findOrgSkillShare(UUID orgSkillId) {
        return shareRepository.findFirstByOrgSkillIdAndChannel(orgSkillId, SkillShare.CHANNEL_PUBLIC);
    }

    @Transactional(rollbackFor = Exception.class)
    public SkillShare toggleOrgSkillShare(UUID orgSkillId, boolean enabled) {
        SkillShare share = shareRepository.findFirstByOrgSkillIdAndChannel(orgSkillId, SkillShare.CHANNEL_PUBLIC)
                .orElseThrow(() -> new BusinessException(404, "尚未生成分享链接"));
        share.setEnabled(enabled);
        share.setUpdatedAt(LocalDateTime.now());
        shareRepository.save(share);
        log.info("组织分身分享开关变更 orgSkillId={} enabled={}", orgSkillId, enabled);
        return share;
    }

    @Transactional(rollbackFor = Exception.class)
    public SkillShare updateOrgSkillShareCode(UUID orgSkillId, String customCode) {
        SkillShare share = shareRepository.findFirstByOrgSkillIdAndChannel(orgSkillId, SkillShare.CHANNEL_PUBLIC)
                .orElseThrow(() -> new BusinessException(404, "尚未生成分享链接"));
        if (!share.getShareCode().equals(customCode)
                && shareRepository.findByShareCode(customCode).isPresent()) {
            throw new BusinessException(400, "该短码已被使用");
        }
        share.setShareCode(customCode);
        share.setUpdatedAt(LocalDateTime.now());
        shareRepository.save(share);
        log.info("组织分身分享码已更新 orgSkillId={} newCode={}", orgSkillId, customCode);
        return share;
    }

    // ============================================================
    // 公开端
    // ============================================================

    /**
     * 分享落地页信息（无凭证可访问）
     */
    public ShareInfoResponse getShareInfo(String shareCode, UUID viewerUserIdOrNull) {
        SkillShare share = requireEnabledShare(shareCode);

        Long remaining = null;
        String viewerStatus = null;
        if (viewerUserIdOrNull != null) {
            AppUser viewer = appUserRepository.findById(viewerUserIdOrNull).orElse(null);
            if (viewer != null) {
                viewerStatus = viewer.getStatus();
                if (AppUser.STATUS_GUEST.equals(viewer.getStatus())) {
                    remaining = remainingQuota(viewer.getId());
                }
            }
        }

        // ── 组织分身分支 ──
        if (share.getOrgSkillId() != null) {
            com.aiextract.model.OrganizationSkill orgSkill = orgSkillRepository.findById(share.getOrgSkillId())
                    .orElseThrow(() -> new BusinessException(404, "组织分身不存在"));
            if (!"published".equals(orgSkill.getStatus())) {
                throw new BusinessException(404, "组织分身未发布");
            }

            recordEvent("share_visit", orgSkill.getId(), viewerUserIdOrNull, Map.of("shareCode", shareCode));

            Map<String, Object> stats = new HashMap<>();
            stats.put("conversationCount", orgSkill.getConversationCount() != null ? orgSkill.getConversationCount() : 0);
            stats.put("userCount", orgSkill.getUserCount() != null ? orgSkill.getUserCount() : 0);
            stats.put("satisfactionRate", orgSkill.getSatisfactionRate() != null ? orgSkill.getSatisfactionRate() : 0);

            List<Map<String, Object>> members = orgSkillService.resolveMembers(orgSkill).stream()
                    .map(m -> {
                        Map<String, Object> mInfo = new HashMap<>();
                        mInfo.put("id", m.getId().toString());
                        mInfo.put("ownerName", m.getOwnerName());
                        mInfo.put("avatarUrl", m.getAvatarUrl());
                        mInfo.put("ownerTitle", m.getOwnerTitle());
                        return mInfo;
                    }).collect(Collectors.toList());

            List<Map<String, Object>> sceneTags;
            try {
                sceneTags = orgSkillService.getSceneTags(orgSkill.getId().toString());
            } catch (Exception e) {
                log.warn("组织分身场景标签加载失败 orgSkillId={}: {}", orgSkill.getId(), e.getMessage());
                sceneTags = List.of();
            }

            return ShareInfoResponse.builder()
                    .skillId(orgSkill.getId().toString())
                    .shareCode(shareCode)
                    .ownerName(orgSkill.getName())
                    .ownerTitle(orgSkill.getDescription())
                    .avatarUrl(orgSkill.getAvatarUrl())
                    .tags(List.of())
                    .sceneTags(sceneTags)
                    .guestLimit(guestMessageLimit)
                    .remaining(remaining)
                    .viewerStatus(viewerStatus)
                    .openingMessage(orgSkill.getOpeningMessage())
                    .introProfile(JsonUtil.parseStringMap(orgSkill.getIntroProfile()))
                    .stats(stats)
                    .skillType("organization")
                    .memberCount(members.size())
                    .members(members)
                    .shareChannel(share.getChannel())
                    .build();
        }

        // ── 个体分身分支（原逻辑不变） ──
        Skill skill = requirePublishedSkill(share);

        List<Map<String, Object>> sceneTags;
        try {
            sceneTags = skillService.getSceneTags(skill.getId().toString());
        } catch (Exception e) {
            log.warn("场景标签加载失败 skillId={}: {}", skill.getId(), e.getMessage());
            sceneTags = List.of();
        }

        recordEvent("share_visit", skill.getId(), viewerUserIdOrNull, Map.of("shareCode", shareCode));

        Map<String, Object> stats = new HashMap<>();
        stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
        stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
        stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);

        return ShareInfoResponse.builder()
                .skillId(skill.getId().toString())
                .shareCode(shareCode)
                .ownerName(skill.getOwnerName() != null ? skill.getOwnerName() : skill.getDisplayName())
                .ownerTitle(skill.getOwnerTitle())
                .avatarUrl(skill.getAvatarUrl())
                .tags(parseTags(skill.getTags()))
                .sceneTags(sceneTags)
                .guestLimit(guestMessageLimit)
                .remaining(remaining)
                .viewerStatus(viewerStatus)
                .openingMessage(skill.getOpeningMessage())
                .introProfile(JsonUtil.parseStringMap(skill.getIntroProfile()))
                .stats(stats)
                .skillType("individual")
                .shareChannel(share.getChannel())
                .build();
    }

    /**
     * 游客发证（幂等 + 滑动续期）
     *
     * <p>无 C 端身份 → IP 限流后新建游客并签 7 天 token；
     * 已是 C 端身份（游客/注册均可）→ 刷新活跃时间并重签 token（滑动续期，活跃用户永不掉线）。
     * B 端 token 打进来时 userId 在 app_user 查不到，按无身份处理 —— 企业员工在分享页就是普通 C 端用户。</p>
     */
    public GuestSessionResponse createGuestSession(String shareCode, String clientIp, UUID currentUserIdOrNull) {
        SkillShare share = requireEnabledShare(shareCode);
        requirePublishedTarget(share);

        LocalDateTime now = LocalDateTime.now();

        // 已有 C 端身份：滑动续期
        if (currentUserIdOrNull != null) {
            AppUser existing = appUserRepository.findById(currentUserIdOrNull).orElse(null);
            if (existing != null) {
                existing.setLastActiveAt(now);
                existing.setUpdatedAt(now);
                appUserRepository.save(existing);
                return buildSession(existing, issueToken(existing));
            }
        }

        // 新访客：IP 防刷 → INSERT 一行 app_user（UUID 主键 = 访客身份本体）
        if (!rateLimiter.allowGuestCreate(clientIp)) {
            log.warn("游客创建IP限流触发 ip={}", clientIp);
            throw new BusinessException(429, "操作太频繁，请稍后再试");
        }

        UUID id = UUID.randomUUID();
        AppUser guest = appUserRepository.save(AppUser.builder()
                .id(id)
                .nickname("访客" + id.toString().replace("-", "").substring(0, 4))
                .status(AppUser.STATUS_GUEST)
                .sourceShareId(share.getId())
                .lastActiveAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        recordEvent("guest_created",
                share.getOrgSkillId() != null ? share.getOrgSkillId() : share.getSkillId(),
                guest.getId(), Map.of("shareCode", shareCode));
        log.info("游客已创建 userId={} shareCode={} ip={}", guest.getId(), shareCode, clientIp);
        return buildSession(guest, issueToken(guest));
    }

    // ============================================================
    // 内部方法
    // ============================================================

    private SkillShare requireEnabledShare(String shareCode) {
        return shareRepository.findByShareCode(shareCode)
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElseThrow(() -> new BusinessException(404, "分享链接已失效"));
    }

    private Skill requirePublishedSkill(SkillShare share) {
        return skillRepository.findById(share.getSkillId())
                .filter(s -> "published".equals(s.getStatus()) || "active".equals(s.getStatus()))
                .orElseThrow(() -> new BusinessException(404, "分身未发布"));
    }

    /** 校验分享对应的目标（个体或组织分身）已发布。 */
    private void requirePublishedTarget(SkillShare share) {
        if (share.getOrgSkillId() != null) {
            com.aiextract.model.OrganizationSkill orgSkill = orgSkillRepository.findById(share.getOrgSkillId())
                    .orElseThrow(() -> new BusinessException(404, "组织分身不存在"));
            if (!"published".equals(orgSkill.getStatus())) {
                throw new BusinessException(404, "组织分身未发布");
            }
            return;
        }
        requirePublishedSkill(share);
    }

    /**
     * 解析分身的企业归属。双表降级：先查 B 端 user，查不到再查 C 端 app_user。
     * B端 → user.companyId
     * Partner → app_user.companyId
     * 纯C端 → null
     */
    private UUID resolveCompanyId(Skill skill) {
        try {
            Space space = spaceRepository.findById(skill.getSpaceId()).orElse(null);
            if (space == null) return null;
            UUID ownerId = space.getUserId();
            // 先查 B 端 user 表
            User bUser = userRepository.findById(ownerId).orElse(null);
            if (bUser != null) return bUser.getCompanyId();
            // 降级查 C 端 app_user 表
            AppUser cUser = appUserRepository.findById(ownerId).orElse(null);
            if (cUser != null && AppUser.SOURCE_PARTNER.equals(cUser.getSource())) {
                return cUser.getCompanyId();
            }
            return null; // 纯 C 端独立用户 → 无企业归属
        } catch (Exception e) {
            log.warn("分身企业归属解析失败 skillId={}: {}", skill.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 属主校验：创建分享的人必须是 skill 所属 space 的 owner。
     */
    private void validateOwnership(UUID skillId, UUID userId) {
        Skill skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        Space space = spaceRepository.findById(skill.getSpaceId())
            .orElseThrow(() -> new BusinessException(404, "空间不存在"));
        if (!space.isOwnedBy(userId)) {
            throw new BusinessException(403, "无权操作");
        }
    }

    /**
     * 获取或创建对内分享（channel='internal'）。
     * 对内分享 → /i/{code}，需本公司员工或平台登录用户访问。
     * 有分身管理权限的用户免属主校验，可管理任意分身的内对分享。
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillShare getOrCreateInternalShare(UUID skillId, UUID userId, String role) {
        if (!com.aiextract.config.RolePermissions.hasPermission(role, com.aiextract.config.Permission.SKILL_MANAGE)) {
            validateOwnership(skillId, userId);
        }

        return shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_INTERNAL)
            .orElseGet(() -> {
                Skill skill = skillRepository.findById(skillId).orElseThrow();
                UUID companyId = resolveCompanyId(skill);
                String code;
                for (int i = 0; i < MAX_SHARE_CODE_RETRIES; i++) {
                    code = randomShareCode();
                    if (shareRepository.findByShareCode(code).isEmpty()) {
                        SkillShare share = shareRepository.save(SkillShare.builder()
                            .id(UUID.randomUUID()).skillId(skillId)
                            .companyId(companyId)
                            .shareCode(code)
                            .channel(SkillShare.CHANNEL_INTERNAL)
                            .enabled(true).createdBy(userId)
                            .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                            .build());
                        log.info("对内分享码已生成 skillId={} shareCode={}", skillId, code);
                        return share;
                    }
                }
                throw new BusinessException(500, "分享码生成失败，请重试");
            });
    }

    private String issueToken(AppUser user) {
        boolean isGuest = AppUser.STATUS_GUEST.equals(user.getStatus());
        String role = isGuest ? ROLE_C_GUEST : ROLE_C_USER;
        long ttlDays = isGuest ? guestTokenTtlDays : cUserTokenTtlDays;
        return jwtUtil.generateToken(user.getId(), null, role, ttlDays * 86_400_000L);
    }

    private GuestSessionResponse buildSession(AppUser user, String token) {
        boolean isGuest = AppUser.STATUS_GUEST.equals(user.getStatus());
        return GuestSessionResponse.builder()
                .token(token)
                .userId(user.getId().toString())
                .nickname(user.getNickname())
                .status(user.getStatus())
                .remaining(isGuest ? remainingQuota(user.getId()) : null)
                .limit(isGuest ? guestMessageLimit : null)
                .build();
    }

    private long remainingQuota(UUID userId) {
        long used = skillMessageRepository.countUserMessagesByUserIdSince(
            userId, LocalDate.now().atStartOfDay());
        return Math.max(0, guestMessageLimit - used);
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 埋点写入（失败不影响主流程） */
    private void recordEvent(String eventType, UUID skillId, UUID userId, Map<String, Object> data) {
        try {
            analyticsEventRepository.save(AnalyticsEvent.builder()
                    .id(UUID.randomUUID())
                    .skillId(skillId)
                    .userId(userId)
                    .eventType(eventType)
                    .eventData(objectMapper.writeValueAsString(data))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("埋点写入失败 type={}: {}", eventType, e.getMessage());
        }
    }

    private String randomShareCode() {
        StringBuilder sb = new StringBuilder(SHARE_CODE_LENGTH);
        for (int i = 0; i < SHARE_CODE_LENGTH; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
