package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.config.RolePermissions;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.dto.ShareInfoResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AnalyticsEvent;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillShare;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.AnalyticsEventRepository;
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

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHARE_CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SkillShareRepository shareRepository;
    private final SkillRepository skillRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
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
        return getOrCreateShare(skillId, adminUserId, channel, true);
    }

    /** 按渠道+初始状态生成/获取分享码 */
    @Transactional(rollbackFor = Exception.class)
    public SkillShare getOrCreateShare(UUID skillId, UUID adminUserId, String channel, boolean enabled) {
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
                        .enabled(enabled)
                        .createdBy(adminUserId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                log.info("分享码已生成 skillId={} shareCode={} channel={} enabled={}", skillId, code, channel, enabled);
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

    // ============================================================
    // 发布时统一初始化默认分享
    // ============================================================

    /**
     * 个体分身发布时统一初始化默认分享记录。
     *
     * <p>C端：public enabled=true（立刻进发现页），无 internal</p>
     * <p>B端：public enabled=false（管理员手动开启），internal enabled=true 企业内流通</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void initDefaultShares(UUID skillId, UUID createdBy, boolean isCEnd) {
        getOrCreateShare(skillId, createdBy, SkillShare.CHANNEL_PUBLIC, isCEnd);
        if (!isCEnd) {
            getOrCreateShare(skillId, createdBy, SkillShare.CHANNEL_INTERNAL, true);
        }
        log.info("默认分享已初始化 skillId={} isCEnd={}", skillId, isCEnd);
    }

    /**
     * 共享开关：关闭后分享链接立即 404
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
            User viewer = userRepository.findById(viewerUserIdOrNull).orElse(null);
            if (viewer != null) {
                viewerStatus = viewer.getStatus();
                if (User.STATUS_GUEST.equals(viewer.getStatus())) {
                    remaining = remainingQuota(viewer.getId());
                }
            }
        }

        // 统一查 skill 表，按 type 分发（统一表架构）
        Skill skill = skillRepository.findById(share.getSkillId())
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        if (!"published".equals(skill.getStatus())) {
            throw new BusinessException(404, "分身未发布");
        }

        recordEvent("share_visit", skill.getId(), viewerUserIdOrNull, Map.of("shareCode", shareCode));

        Map<String, Object> stats = new HashMap<>();
        stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
        stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
        stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);

        // ── 组织分身分支 ──
        if ("organization".equals(skill.getType())) {
            // 解析成员（内联，避免循环依赖 OrganizationSkillService）
            List<UUID> memberIds = JsonUtil.parseList(skill.getMemberSkillIds(), UUID::fromString);
            List<Skill> activeMembers = memberIds.isEmpty() ? List.of()
                    : skillRepository.findAllById(memberIds).stream()
                        .filter(s -> "published".equals(s.getStatus())).toList();
            List<Map<String, Object>> members = activeMembers.stream()
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
                sceneTags = skillService.getSceneTags(skill.getId().toString());
            } catch (Exception e) {
                log.warn("组织分身场景标签加载失败 skillId={}: {}", skill.getId(), e.getMessage());
                sceneTags = List.of();
            }

            return ShareInfoResponse.builder()
                    .skillId(skill.getId().toString())
                    .shareCode(shareCode)
                    .ownerName(skill.getDisplayName())
                    .ownerTitle(skill.getDescription())
                    .avatarUrl(resolveAvatarUrl(skill))
                    .tags(List.of())
                    .sceneTags(sceneTags)
                    .guestLimit(guestMessageLimit)
                    .remaining(remaining)
                    .viewerStatus(viewerStatus)
                    .openingMessage(skill.getOpeningMessage())
                    .introProfile(JsonUtil.parseStringMap(skill.getIntroProfile()))
                    .stats(stats)
                    .skillType("organization")
                    .memberCount(activeMembers.size())
                    .members(members)
                    .shareChannel(share.getChannel())
                    .build();
        }

        // ── 个体分身分支 ──
        List<Map<String, Object>> sceneTags;
        try {
            sceneTags = skillService.getSceneTags(skill.getId().toString());
        } catch (Exception e) {
            log.warn("场景标签加载失败 skillId={}: {}", skill.getId(), e.getMessage());
            sceneTags = List.of();
        }

        return ShareInfoResponse.builder()
                .skillId(skill.getId().toString())
                .shareCode(shareCode)
                .ownerName(skill.getOwnerName() != null ? skill.getOwnerName() : skill.getDisplayName())
                .ownerTitle(skill.getOwnerTitle())
                .avatarUrl(resolveAvatarUrl(skill))
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

    /** Skill 没头像时降级取 space owner 的头像 */
    private String resolveAvatarUrl(Skill skill) {
        if (skill.getAvatarUrl() != null) return skill.getAvatarUrl();
        Space space = spaceRepository.findById(skill.getSpaceId()).orElse(null);
        if (space == null) return null;
        return userRepository.findById(space.getUserId())
                .map(User::getAvatarUrl).orElse(null);
    }

    /**
     * 游客发证（幂等 + 滑动续期）
     *
     * <p>无 C 端身份 → IP 限流后新建游客并签 7 天 token；
     * 已是 C 端身份（游客/注册均可）→ 刷新活跃时间并重签 token（滑动续期，活跃用户永不掉线）。
     * B 端 token 打进来时查到 source=enterprise 的用户，按无 C 端身份处理。</p>
     */
    public GuestSessionResponse createGuestSession(String shareCode, String clientIp, UUID currentUserIdOrNull) {
        SkillShare share = requireEnabledShare(shareCode);
        requirePublishedTarget(share);
        return issueGuestSession(share, clientIp, currentUserIdOrNull, shareCode);
    }

    /**
     * 按 skillId 创建/续期游客会话（平台端 PC 聊天页入口，无需分享码）。
     *
     * <p>与 {@link #createGuestSession} 共用核心发证逻辑，仅入口不同：
     * 分享页带 shareCode，PC 聊天页直接 skillId（内部取/建 public share 作溯源关联）。</p>
     */
    public GuestSessionResponse createGuestSessionBySkillId(UUID skillId, String clientIp, UUID currentUserIdOrNull) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        if (!"published".equals(skill.getStatus())) {
            throw new BusinessException(404, "分身未发布");
        }
        SkillShare share = getOrCreateShare(skillId, skill.getCreatedBy(), SkillShare.CHANNEL_PUBLIC);
        return issueGuestSession(share, clientIp, currentUserIdOrNull, share.getShareCode());
    }

    /** 游客发证核心逻辑（两个入口共用）：滑动续期 → IP 限流 → 新建游客 */
    private GuestSessionResponse issueGuestSession(SkillShare share, String clientIp, UUID currentUserIdOrNull, String shareCode) {
        LocalDateTime now = LocalDateTime.now();

        // 已有 C 端身份：滑动续期
        if (currentUserIdOrNull != null) {
            User existing = userRepository.findById(currentUserIdOrNull).orElse(null);
            if (existing != null && existing.isCEnd()) {
                existing.setLastActiveAt(now);
                existing.setUpdatedAt(now);
                userRepository.save(existing);
                return buildSession(existing, issueToken(existing));
            }
        }

        // 新访客：IP 防刷 → INSERT 一行 user（UUID = 访客身份，role=c_guest）
        if (!rateLimiter.allowGuestCreate(clientIp)) {
            log.warn("游客创建IP限流触发 ip={}", clientIp);
            throw new BusinessException(429, "操作太频繁，请稍后再试");
        }

        UUID id = UUID.randomUUID();
        User guest = userRepository.save(User.builder()
                .id(id)
                .name("访客" + id.toString().replace("-", "").substring(0, 4))
                .role(RolePermissions.C_GUEST)
                .status(User.STATUS_GUEST)
                .source(User.SOURCE_SHARE)
                .sourceShareId(share.getId())
                .isActive(true)
                .lastActiveAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        recordEvent("guest_created", share.getSkillId(),
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

    /** 校验分享对应的目标分身已发布。统一表架构：按 skillId 查即可。 */
    private void requirePublishedTarget(SkillShare share) {
        Skill skill = skillRepository.findById(share.getSkillId())
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        if (!"published".equals(skill.getStatus())) {
            throw new BusinessException(404, "分身未发布");
        }
    }

    /**
     * 解析分身的企业归属。统一查 user 表，按 source 区分：
     * enterprise/partner → 有 companyId，纯 C 端 → null
     */
    private UUID resolveCompanyId(Skill skill) {
        try {
            Space space = spaceRepository.findById(skill.getSpaceId()).orElse(null);
            if (space == null) return null;
            User owner = userRepository.findById(space.getUserId()).orElse(null);
            if (owner == null) return null;
            if (User.SOURCE_ENTERPRISE.equals(owner.getSource())
                    || User.SOURCE_PARTNER.equals(owner.getSource())) {
                return owner.getCompanyId();
            }
            return null;
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

    private String issueToken(User user) {
        boolean isGuest = User.STATUS_GUEST.equals(user.getStatus());
        long ttlDays = isGuest ? guestTokenTtlDays : cUserTokenTtlDays;
        return jwtUtil.generateToken(user.getId(), null, user.getRole(), ttlDays * 86_400_000L);
    }

    private GuestSessionResponse buildSession(User user, String token) {
        boolean isGuest = User.STATUS_GUEST.equals(user.getStatus());
        return GuestSessionResponse.builder()
                .token(token)
                .userId(user.getId().toString())
                .nickname(user.getName())
                .nickname(user.getName())
                .avatarUrl(user.getAvatarUrl())
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
