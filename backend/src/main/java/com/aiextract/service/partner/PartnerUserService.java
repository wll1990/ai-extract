package com.aiextract.service.partner;

import com.aiextract.config.RolePermissions;
import com.aiextract.exception.PartnerException;
import com.aiextract.model.PartnerApp;
import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 合作方外部用户 → 统一 user 表映射。
 *
 * <p>account = {@code partner:{appId}:{externalUserId}}；appId 即 companyId。
 * 并发首登撞唯一键时重查，避免一个请求 500。</p>
 */
@Service
@RequiredArgsConstructor
public class PartnerUserService {

    private final UserRepository userRepository;

    public User findOrCreate(PartnerApp app, String externalUserId, String userName) {
        String account = account(app.getAppId(), externalUserId);
        Optional<User> existing = userRepository.findByAccount(account);
        if (existing.isPresent()) {
            User user = existing.get();
            if (Boolean.FALSE.equals(user.getIsActive())) {
                throw new PartnerException(403, "PARTNER_USER_DISABLED", "该用户已被停用");
            }
            user.setLastActiveAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            return userRepository.save(user);
        }

        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .account(account)
                .name(userName == null || userName.isBlank() ? externalUserId : userName)
                .role(RolePermissions.C_PARTNER)
                .status(User.STATUS_REGISTERED)
                .source(User.SOURCE_PARTNER)
                .companyId(UUID.fromString(app.getAppId()))
                .isActive(true)
                .lastActiveAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByAccount(account)
                    .orElseThrow(() -> new PartnerException(500, "PARTNER_USER_CREATE_FAILED",
                            "合作方用户创建失败，请重试"));
        }
    }

    public Optional<User> find(String appId, String externalUserId) {
        return userRepository.findByAccount(account(appId, externalUserId));
    }

    private static String account(String appId, String externalUserId) {
        return "partner:" + appId + ":" + externalUserId;
    }
}
