package com.aiextract;

import com.aiextract.model.User;
import com.aiextract.repository.CompanyRegisterCodeRepository;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.AuthService;
import com.aiextract.service.OssService;
import com.aiextract.service.UserSecurityService;
import com.aiextract.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改密后的安全版本号必须走统一入口（提交后刷新 Redis 版本缓存），
 * 否则新签发的 token 会被旧缓存判为过期。
 */
@ExtendWith(MockitoExtension.class)
class AuthServicePasswordTest {

    @Mock private UserRepository userRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private CompanyRegisterCodeRepository registerCodeRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SpaceRepository spaceRepository;
    @Mock private OssService ossService;
    @Mock private UserSecurityService userSecurityService;

    @InjectMocks private AuthService authService;

    @Test
    void changePassword_refreshesVersionAndSignsNewToken() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        User user = User.builder()
                .id(userId).companyId(companyId).role("employee")
                .passwordHash("old-hash").tokenVersion(3L).isActive(true).build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Old12345", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("New12345", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("New12345")).thenReturn("new-hash");
        // 模拟真实实现：自增版本号（缓存刷新在事务提交后）
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setTokenVersion(u.getTokenVersion() + 1);
            return null;
        }).when(userSecurityService).onAuthoritiesChanged(user);
        when(jwtUtil.generateToken(eq(userId), eq(companyId), eq("employee"), anyLong(), eq(4L)))
                .thenReturn("new-token");

        String token = authService.changePassword(userId, "Old12345", "New12345");

        assertEquals("new-token", token);
        assertEquals("new-hash", user.getPasswordHash());
        verify(userSecurityService).onAuthoritiesChanged(user);
        verify(userRepository).save(user);
    }
}
