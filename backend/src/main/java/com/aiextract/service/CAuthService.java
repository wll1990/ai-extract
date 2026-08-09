package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.config.RolePermissions;
import com.aiextract.dto.CLoginRequest;
import com.aiextract.dto.CRegisterRequest;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AnalyticsEvent;
import com.aiextract.model.User;
import com.aiextract.repository.AnalyticsEventRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.repository.InterviewSessionRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C 端认证服务 — 统一用户体系，查 user 表按 source 区分 B/C 端
 *
 * <p>注册 = 游客原地升级（同一行补 account/password，status→registered，
 * UUID 不变，会话历史自动继承）。登录无需企业 ID。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.ClassNamingShouldBeCamelRule")
public class CAuthService {

    private final UserRepository userRepository;
    private final SkillMessageRepository skillMessageRepository;
    private final SpaceRepository spaceRepository;
    private final InterviewSessionRepository sessionRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final AnalyticsEventRepository analyticsEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.share.c-user-token-ttl-days:30}")
    private int cUserTokenTtlDays;

    @Value("${app.storage.base-path:data/files}")
    private String storageBasePath;

    @Value("${app.share.guest-token-ttl-days:7}")
    private int guestTokenTtlDays;

    @Value("${app.share.guest-message-limit:5}")
    private int guestMessageLimit;

    @Value("${app.interview.c-user-free-limit:3}")
    private int cUserFreeLimit;

    /**
     * 游客升级注册（userId 从 JWT 取，SecurityConfig 已限定 hasRole(C_GUEST)）
     *
     * <p>account 全局 UNIQUE 双保险：先 existsByAccount 预查给友好提示，
     * saveAndFlush 触发约束兜底并发双提交。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public GuestSessionResponse register(UUID userId, CRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        if (!User.STATUS_GUEST.equals(user.getStatus())) {
            throw new BusinessException(400, "当前账号已注册，无需升级");
        }
        String account = request.getAccount().trim();
        if (userRepository.existsByAccount(account)) {
            throw new BusinessException(400, "账号已被占用，换一个试试");
        }

        LocalDateTime now = LocalDateTime.now();
        user.setAccount(account);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            user.setName(request.getNickname().trim());
        }
        user.setStatus(User.STATUS_REGISTERED);
        user.setRole(RolePermissions.C_USER);
        // 游客升级注册的，来源标记为分享链接
        if (user.getSource() == null) {
            user.setSource(User.SOURCE_SHARE);
        }
        user.setLastActiveAt(now);
        user.setUpdatedAt(now);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // account 全局 UNIQUE 兜底并发双提交
            throw new BusinessException(400, "账号已被占用，换一个试试");
        }

        // 自动创建个人空间（如果还没有）
        if (spaceRepository.findByUserId(userId).isEmpty()) {
            spaceRepository.save(com.aiextract.model.Space.builder()
                .id(UUID.randomUUID()).userId(userId)
                .title((user.getName() != null ? user.getName() : account) + "的空间")
                .isPublic(false).status("active")
                .createdAt(now).updatedAt(now)
                .build());
        }

        recordEvent("guest_registered", user);
        log.info("游客升级注册成功 userId={} account={}", userId, account);
        return buildSession(user, issueToken(user));
    }

    /**
     * C 端独立注册（非游客升级，source='platform'），支持上传头像。
     * 用户在 platform 直接注册，没有经过分享链接。
     */
    @Transactional(rollbackFor = Exception.class)
    public GuestSessionResponse registerNew(String account, String password, String nickname, MultipartFile avatar) {
        if (userRepository.existsByAccount(account)) {
            throw new BusinessException(400, "账号已被占用");
        }
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
            .id(UUID.randomUUID())
            .account(account)
            .passwordHash(passwordEncoder.encode(password))
            .name(nickname)
            .role(RolePermissions.C_USER)
            .status(User.STATUS_REGISTERED)
            .source(User.SOURCE_PLATFORM)
            .isActive(true)
            .createdAt(now)
            .updatedAt(now)
            .build();

        // 上传头像（可选）
        if (avatar != null && !avatar.isEmpty()) {
            String avatarUrl = saveUserAvatar(user.getId(), avatar);
            user.setAvatarUrl(avatarUrl);
        }

        userRepository.save(user);
        // 自动创建个人空间
        spaceRepository.save(com.aiextract.model.Space.builder()
            .id(UUID.randomUUID()).userId(user.getId())
            .title((nickname != null ? nickname : account) + "的空间")
            .isPublic(false).status("active")
            .createdAt(now).updatedAt(now)
            .build());
        log.info("C端独立注册成功 userId={} account={}", user.getId(), account);
        return buildSession(user, issueToken(user));
    }

    /** 保存用户头像文件，返回相对路径 URL */
    private String saveUserAvatar(UUID userId, MultipartFile file) {
        String dir = storageBasePath + "/avatars/users/" + userId + "/";
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "avatar";
        String safeName = System.currentTimeMillis() + "_" + originalName.replaceAll("[^a-zA-Z0-9._\\-]", "_");

        java.io.File destDir = new java.io.File(dir).getAbsoluteFile();
        if (!destDir.exists()) destDir.mkdirs();
        java.io.File dest = new java.io.File(destDir, safeName);

        try {
            file.transferTo(dest);
        } catch (Exception e) {
            log.error("用户头像保存失败 userId={} path={}", userId, dest.getAbsolutePath(), e);
            throw new RuntimeException("头像保存失败: " + e.getMessage());
        }

        return "/files/avatars/users/" + userId + "/" + safeName;
    }

    /**
     * C 端登录（平台级账号密码，无企业 ID）
     */
    @Transactional(rollbackFor = Exception.class)
    public GuestSessionResponse login(CLoginRequest request) {
        User user = userRepository.findByAccount(request.getAccount().trim())
                .filter(User::isCEnd)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("C端密码错误 userId={}", user.getId());
            throw new BusinessException(400, ErrorMessages.PASSWORD_WRONG);
        }
        LocalDateTime now = LocalDateTime.now();
        user.setLastActiveAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        log.info("C端登录成功 userId={}", user.getId());
        return buildSession(user, issueToken(user));
    }

    /**
     * 当前 C 端身份探测（token 为 null — 不重签；B 端 token 的 userId 查到 source=enterprise → 404）。
     * 返回萃取剩余次数（用于前端展示）。
     */
    @Transactional(readOnly = true)
    public GuestSessionResponse me(UUID userId) {
        User user = userRepository.findById(userId)
                .filter(User::isCEnd)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        GuestSessionResponse resp = buildSession(user, null);

        // 统计已完成的访谈数，计算剩余免费次数
        List<UUID> spaceIds = spaceRepository.findByUserId(userId).stream()
                .map(com.aiextract.model.Space::getId).toList();
        long completed = spaceIds.isEmpty() ? 0
                : sessionRepository.countBySpaceIdInAndStatus(spaceIds, "completed");
        long remaining = Math.max(0, cUserFreeLimit - completed);
        resp.setExtractionRemaining(remaining);
        resp.setExtractionLimit(cUserFreeLimit);
        return resp;
    }

    private String issueToken(User user) {
        boolean isGuest = User.STATUS_GUEST.equals(user.getStatus());
        long ttlDays = isGuest ? guestTokenTtlDays : cUserTokenTtlDays;
        return jwtUtil.generateToken(user.getId(), null, user.getRole(), ttlDays * 86_400_000L);
    }

    private GuestSessionResponse buildSession(User user, String token) {
        boolean isGuest = User.STATUS_GUEST.equals(user.getStatus());
        Long remaining = null;
        if (isGuest) {
            long used = skillMessageRepository.countUserMessagesByUserIdSince(
                user.getId(), java.time.LocalDate.now().atStartOfDay());
            remaining = Math.max(0, guestMessageLimit - used);
        }
        return GuestSessionResponse.builder()
                .token(token)
                .userId(user.getId().toString())
                .nickname(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus())
                .remaining(remaining)
                .limit(isGuest ? guestMessageLimit : null)
                .build();
    }

    /** 埋点写入（内部吞异常，不影响注册/登录主流程与事务） */
    private void recordEvent(String eventType, User user) {
        try {
            analyticsEventRepository.save(AnalyticsEvent.builder()
                    .id(UUID.randomUUID())
                    .userId(user.getId())
                    .eventType(eventType)
                    .eventData(objectMapper.writeValueAsString(Map.of(
                            "sourceShareId", user.getSourceShareId() != null ? user.getSourceShareId().toString() : "")))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("埋点写入失败 type={}: {}", eventType, e.getMessage());
        }
    }
}
