package com.aiextract;

import com.aiextract.config.SessionAuthFilter;
import com.aiextract.config.RolePermissions;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.service.session.SessionInfo;
import com.aiextract.service.session.SessionService;
import com.aiextract.service.session.SessionUnavailableException;
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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话认证过滤器单元测试 —— 不透明 token、SSE query token、强制改密、降级 503。
 */
@ExtendWith(MockitoExtension.class)
class SessionAuthFilterTest {

    @Mock
    private SessionService sessionService;
    @Mock
    private CompanyRepository companyRepository;

    private SessionAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SessionAuthFilter(sessionService, companyRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validSession_setsAuthenticationAndContinues() throws Exception {
        String token = "opaque-valid-token";
        when(sessionService.get(token)).thenReturn(session(false));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWithBearer(token), response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> RolePermissions.EMPLOYEE.equals(a.getAuthority())));
    }

    @Test
    void missingSession_doesNotSetAuthentication() throws Exception {
        String token = "opaque-missing-token";
        when(sessionService.get(token)).thenReturn(null);

        filter.doFilter(requestWithBearer(token), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void sessionStoreUnavailable_returns503() throws Exception {
        String token = "opaque-store-down";
        when(sessionService.get(token))
                .thenThrow(new SessionUnavailableException("down", new RuntimeException("boom")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWithBearer(token), response, new MockFilterChain());

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("认证服务暂不可用"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void queryToken_onNonSsePath_isRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        request.addParameter("token", "opaque-query-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(sessionService, never()).get(anyString());
    }

    @Test
    void queryToken_onSsePath_isAccepted() throws Exception {
        String token = "opaque-sse-token";
        when(sessionService.get(token)).thenReturn(session(false));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/me/training/attempts/abc/messages");
        request.addParameter("token", token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void mustChangePassword_blocksNonExemptPath() throws Exception {
        String token = "opaque-mcp-token";
        when(sessionService.get(token)).thenReturn(session(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void mustChangePassword_allowsAuthMe() throws Exception {
        String token = "opaque-mcp-me";
        when(sessionService.get(token)).thenReturn(session(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void legacyUserJwt_isRejected() throws Exception {
        // 硬切：切换前签发的用户 JWT（含 "." 但无 appId）不再接受
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWithBearer("a.b.c"), response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private SessionInfo session(boolean mustChangePassword) {
        Instant now = Instant.now();
        return new SessionInfo(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                RolePermissions.EMPLOYEE,
                mustChangePassword,
                0L,
                null,
                null,
                "test-device",
                "JUnit",
                "127.0.0.1",
                now,
                now,
                now.plusSeconds(3600),
                "hash",
                false);
    }
}
