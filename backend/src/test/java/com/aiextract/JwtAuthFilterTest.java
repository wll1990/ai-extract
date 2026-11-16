package com.aiextract;

import com.aiextract.config.JwtAuthFilter;
import com.aiextract.config.PartnerJwtFilter;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenRevocationService;
import com.aiextract.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthFilter 单元测试 —— 撤销命中时的响应语义。
 *
 * <p>回归点：撤销必须返回 **401**（前端据此重新登录）。曾经用 {@code sendError(401)}，
 * 容器 error dispatch 会再走一遍过滤器链，被 {@code anyRequest().denyAll()} 改写成 403。</p>
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final String SECRET = "filter-test-secret-key-0123456789abcdef";
    private static final long TTL = 3_600_000L;

    @Mock
    private PartnerJwtFilter partnerJwtFilter;
    @Mock
    private TokenRevocationService revocationService;
    @Mock
    private com.aiextract.repository.CompanyRepository companyRepository;

    private JwtUtil jwtUtil;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", TTL);
        jwtUtil.init();
        filter = new JwtAuthFilter(jwtUtil, partnerJwtFilter, revocationService, companyRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blockedToken_returns401WithJsonBody() throws Exception {
        String token = token(3L);
        when(revocationService.isBlocked(any(), anyString(), eq(3L))).thenReturn(true);
        MockHttpServletRequest request = request(token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("登录已失效"));
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "被撤销的 token 不能建立认证上下文");
    }

    @Test
    void validToken_setsAuthenticationAndContinues() throws Exception {
        String token = token(3L);
        when(revocationService.isBlocked(any(), anyString(), anyLong())).thenReturn(false);
        MockHttpServletRequest request = request(token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> RolePermissions.EMPLOYEE.equals(a.getAuthority())));
    }

    @Test
    void versionIsTakenFromTokenClaim() throws Exception {
        String token = token(7L);
        when(revocationService.isBlocked(any(), anyString(), anyLong())).thenReturn(false);
        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        verify(revocationService).isBlocked(any(UUID.class), anyString(), eq(7L));
    }

    // ==================== 辅助 ====================

    private String token(long version) {
        return jwtUtil.generateToken(UUID.randomUUID(), null, RolePermissions.EMPLOYEE, TTL, version);
    }

    @Test
    void queryToken_onNonSsePath_isRejected() throws Exception {
        String token = token(1L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        request.addParameter("token", token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "非 SSE 路径不能使用 query token");
    }

    @Test
    void queryToken_onSsePath_isAccepted() throws Exception {
        String token = token(1L);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/me/training/attempts/abc/messages");
        request.addParameter("token", token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
                "SSE 白名单路径允许 query token");
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
