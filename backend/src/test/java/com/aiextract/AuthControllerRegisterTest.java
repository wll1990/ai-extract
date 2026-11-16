package com.aiextract;

import com.aiextract.config.RolePermissions;
import com.aiextract.controller.AuthController;
import com.aiextract.dto.LoginResponse;
import com.aiextract.dto.RegisterRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.AuthService;
import com.aiextract.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匿名注册入口回归 —— P0-2。
 *
 * <p>请求体里的 companyId 不可信：没有签名邀请令牌一律拒绝；
 * 有令牌时企业归属只取令牌里的值。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerRegisterTest {

    private static final String SECRET = "unit-test-secret-key-for-jwt-0123456789abcdef";

    @Mock private AuthService authService;
    @Mock private com.aiextract.config.TokenRevocationService tokenRevocationService;

    private JwtUtil jwtUtil;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        jwtUtil.init();
        controller = new AuthController(authService, jwtUtil, tokenRevocationService,
                org.mockito.Mockito.mock(com.aiextract.service.AuthRateLimiter.class),
                new com.aiextract.common.ClientIpResolver());
        ReflectionTestUtils.setField(controller, "cookieSecure", false);
        ReflectionTestUtils.setField(controller, "jwtExpirationMs", 3_600_000L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCannotRegisterWithArbitraryCompanyId() {
        RegisterRequest req = request(UUID.randomUUID(), null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.register(req, new MockHttpServletResponse(),
                        new MockHttpServletRequest()));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void inviteTokenOverridesBodyCompanyId() {
        UUID inviteCompany = UUID.randomUUID();
        UUID bodyCompany = UUID.randomUUID();
        RegisterRequest req = request(bodyCompany, jwtUtil.generateInviteToken(
                inviteCompany, "share", JwtUtil.REGISTER_INVITE_TTL_MILLIS));
        when(authService.register(any())).thenReturn(LoginResponse.builder().token("t").build());

        controller.register(req, new MockHttpServletResponse(), new MockHttpServletRequest());

        ArgumentCaptor<RegisterRequest> captor = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(authService).register(captor.capture());
        assertEquals(inviteCompany.toString(), captor.getValue().getCompanyId());
    }

    @Test
    void invalidInviteTokenIsRejected() {
        RegisterRequest req = request(UUID.randomUUID(), "not-a-token");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.register(req, new MockHttpServletResponse(),
                        new MockHttpServletRequest()));
        assertEquals(400, ex.getErrorCode());
    }

    private RegisterRequest request(UUID companyId, String inviteToken) {
        return RegisterRequest.builder()
                .companyId(companyId.toString())
                .name("张三").account("zhangsan").password("123456")
                .role(RolePermissions.EMPLOYEE)
                .inviteToken(inviteToken)
                .build();
    }
}
