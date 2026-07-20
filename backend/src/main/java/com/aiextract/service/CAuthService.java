package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.dto.CLoginRequest;
import com.aiextract.dto.CRegisterRequest;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AnalyticsEvent;
import com.aiextract.model.AppUser;
import com.aiextract.repository.AnalyticsEventRepository;
import com.aiextract.repository.AppUserRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * C 端认证服务 — 平台级用户体系（app_user），与企业 user 表完全独立
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

    private final AppUserRepository appUserRepository;
    private final SkillMessageRepository skillMessageRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final AnalyticsEventRepository analyticsEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.share.c-user-token-ttl-days:30}")
    private int cUserTokenTtlDays;

    @Value("${app.share.guest-token-ttl-days:7}")
    private int guestTokenTtlDays;

    @Value("${app.share.guest-message-limit:5}")
    private int guestMessageLimit;

    /**
     * 游客升级注册（userId 从 JWT 取，SecurityConfig 已限定 hasRole(C_GUEST)）
     *
     * <p>account 全局 UNIQUE 双保险：先 existsByAccount 预查给友好提示，
     * saveAndFlush 触发约束兜底并发双提交。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public GuestSessionResponse register(UUID userId, CRegisterRequest request) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        if (!AppUser.STATUS_GUEST.equals(user.getStatus())) {
            throw new BusinessException(400, "当前账号已注册，无需升级");
        }
        String account = request.getAccount().trim();
        if (appUserRepository.existsByAccount(account)) {
            throw new BusinessException(400, "账号已被占用，换一个试试");
        }

        LocalDateTime now = LocalDateTime.now();
        user.setAccount(account);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            user.setNickname(request.getNickname().trim());
        }
        user.setStatus(AppUser.STATUS_REGISTERED);
        user.setLastActiveAt(now);
        user.setUpdatedAt(now);
        try {
            appUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // account 全局 UNIQUE 兜底并发双提交
            throw new BusinessException(400, "账号已被占用，换一个试试");
        }

        recordEvent("guest_registered", user);
        log.info("游客升级注册成功 userId={} account={}", userId, account);
        return buildSession(user, issueToken(user));
    }

    /**
     * C 端登录（平台级账号密码，无企业 ID）
     */
    @Transactional(rollbackFor = Exception.class)
    public GuestSessionResponse login(CLoginRequest request) {
        AppUser user = appUserRepository.findByAccount(request.getAccount().trim())
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("C端密码错误 userId={}", user.getId());
            throw new BusinessException(401, ErrorMessages.PASSWORD_WRONG);
        }
        LocalDateTime now = LocalDateTime.now();
        user.setLastActiveAt(now);
        user.setUpdatedAt(now);
        appUserRepository.save(user);

        log.info("C端登录成功 userId={}", user.getId());
        return buildSession(user, issueToken(user));
    }

    /**
     * 当前 C 端身份探测（token 为 null — 不重签；B 端 token 的 userId 在 app_user 查不到 → 404，
     * 前端据此按"无 C 端身份"处理）
     */
    @Transactional(readOnly = true)
    public GuestSessionResponse me(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.USER_NOT_FOUND));
        return buildSession(user, null);
    }

    private String issueToken(AppUser user) {
        boolean isGuest = AppUser.STATUS_GUEST.equals(user.getStatus());
        String role = isGuest ? ShareService.ROLE_C_GUEST : ShareService.ROLE_C_USER;
        long ttlDays = isGuest ? guestTokenTtlDays : cUserTokenTtlDays;
        return jwtUtil.generateToken(user.getId(), null, role, ttlDays * 86_400_000L);
    }

    private GuestSessionResponse buildSession(AppUser user, String token) {
        boolean isGuest = AppUser.STATUS_GUEST.equals(user.getStatus());
        Long remaining = null;
        if (isGuest) {
            long used = skillMessageRepository.countUserMessagesByUserId(user.getId());
            remaining = Math.max(0, guestMessageLimit - used);
        }
        return GuestSessionResponse.builder()
                .token(token)
                .userId(user.getId().toString())
                .nickname(user.getNickname())
                .status(user.getStatus())
                .remaining(remaining)
                .limit(isGuest ? guestMessageLimit : null)
                .build();
    }

    /** 埋点写入（内部吞异常，不影响注册/登录主流程与事务） */
    private void recordEvent(String eventType, AppUser user) {
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
