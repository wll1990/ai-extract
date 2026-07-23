package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.dto.ShareInfoResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AnalyticsEvent;
import com.aiextract.model.AppUser;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillShare;
import com.aiextract.model.User;
import com.aiextract.repository.AnalyticsEventRepository;
import com.aiextract.repository.AppUserRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SkillShareRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.util.JwtUtil;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    /** V1 种子默认企业 — 分身归属解析失败时的兜底 */
    private static final UUID DEFAULT_COMPANY_ID = UUID.fromString("c0000000-0000-0000-0000-000000000001");

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHARE_CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SkillShareRepository shareRepository;
    private final SkillRepository skillRepository;
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
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));

        Optional<SkillShare> existing = shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_DEFAULT);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID companyId = resolveCompanyId(skill);
        LocalDateTime now = LocalDateTime.now();
        // 10 位 base62 随机码，预查重 + 3 次重试（碰撞概率工程上为零，UNIQUE 约束兜底）
        for (int i = 0; i < MAX_SHARE_CODE_RETRIES; i++) {
            String code = randomShareCode();
            if (shareRepository.findByShareCode(code).isEmpty()) {
                SkillShare share = shareRepository.save(SkillShare.builder()
                        .id(UUID.randomUUID())
                        .skillId(skillId)
                        .companyId(companyId)
                        .shareCode(code)
                        .channel(SkillShare.CHANNEL_DEFAULT)
                        .enabled(true)
                        .createdBy(adminUserId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                log.info("分享码已生成 skillId={} shareCode={} companyId={}", skillId, code, companyId);
                return share;
            }
        }
        throw new BusinessException(500, "分享码生成失败，请重试");
    }

    /**
     * 查询分享（管理端回显，未生成时返回空）
     */
    public Optional<SkillShare> findShare(UUID skillId) {
        return shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_DEFAULT);
    }

    /**
     * 共享开关：关闭后分享链接立即 404，企业内部使用不受影响
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillShare toggleShare(UUID skillId, boolean enabled) {
        SkillShare share = shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_DEFAULT)
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
        SkillShare share = shareRepository.findFirstBySkillIdAndChannel(skillId, SkillShare.CHANNEL_DEFAULT)
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
        Skill skill = requirePublishedSkill(share);

        List<Map<String, Object>> sceneTags;
        try {
            sceneTags = skillService.getSceneTags(skill.getId().toString());
        } catch (Exception e) {
            log.warn("场景标签加载失败 skillId={}: {}", skill.getId(), e.getMessage());
            sceneTags = List.of();
        }

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

        recordEvent("share_visit", skill.getId(), viewerUserIdOrNull, Map.of("shareCode", shareCode));

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
        requirePublishedSkill(share);

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

        recordEvent("guest_created", share.getSkillId(), guest.getId(), Map.of("shareCode", shareCode));
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

    private UUID resolveCompanyId(Skill skill) {
        try {
            return spaceRepository.findById(skill.getSpaceId())
                    .flatMap(space -> userRepository.findById(space.getUserId()))
                    .map(User::getCompanyId)
                    .orElse(DEFAULT_COMPANY_ID);
        } catch (Exception e) {
            log.warn("分身企业归属解析失败，回退默认企业 skillId={}: {}", skill.getId(), e.getMessage());
            return DEFAULT_COMPANY_ID;
        }
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
