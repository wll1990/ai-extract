package com.aiextract.config;

import com.aiextract.common.TraceContext;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.service.session.SessionInfo;
import com.aiextract.service.session.SessionService;
import com.aiextract.service.session.SessionUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话认证过滤器 —— 不透明 token（Redis 主读 + DB 兜底）。
 *
 * <p>token 只是随机索引，服务端只存 SHA-256；会话失效由 Redis epoch / DB revoked_at 决定。
 * 合作方 JWT（?token= + appId）作为独立认证域保留到三方 code exchange 上线。</p>
 *
 * <p>CSRF 双提交、强制改密白名单、超管切企业视角等规则与原 JWT 版本保持一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int BEARER_PREFIX_LENGTH = BEARER_PREFIX.length();

    private static final String TOKEN_HEADER = "token";
    private static final String TOKEN_COOKIE = "token";
    private static final String CSRF_COOKIE = "csrf_token";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String ATTR_AUTH_FROM_COOKIE = "auth.fromCookie";
    private static final String ACTING_COMPANY_HEADER = "X-Acting-Company-Id";

    /** 视角校验失败时已写响应，调用方直接 return；null 才是合法值 */
    private static final UUID ACTING_REJECTED = new UUID(0L, 0L);

    private final SessionService sessionService;
    private final CompanyRepository companyRepository;

    @Value("${app.auth.query-token.mode:enforce}")
    private String queryTokenMode;

    private static final List<Pattern> SSE_QUERY_TOKEN_PATHS = List.of(
            Pattern.compile(".*/skills/[^/]+/chat$"),
            Pattern.compile(".*/skills/[^/]+/practice/respond$"),
            Pattern.compile(".*/skills/[^/]+/practice/evaluate$"),
            Pattern.compile(".*/skills/enterprise/chat$"),
            Pattern.compile(".*/interviews/[^/]+/(chat|resume|mark-phase-complete|supplement)$"),
            Pattern.compile(".*/me/training/attempts/[^/]+/(messages|hints)$"),
            Pattern.compile(".*/admin/skills/[^/]+/(practice-scenario|auto-demo)$"));

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TraceContext.start(request.getHeader("X-Trace-Id"));
        response.setHeader("X-Trace-Id", TraceContext.get());

        try {
            String token = extractToken(request);

            // Cookie 认证的写操作必须带 CSRF 头（双提交）；Bearer/header 调用不受影响
            if (token != null && Boolean.TRUE.equals(request.getAttribute(ATTR_AUTH_FROM_COOKIE))
                    && requiresCsrf(request) && !csrfValid(request)) {
                writeForbidden(response, "CSRF 校验失败，请刷新页面重试");
                return;
            }

            if (token != null && !token.isBlank()) {
                // 硬切：切换前的用户 JWT / 合作方自签 JWT 都不再接受。
                // 三方接入改为 code exchange，拿到的是不含 "." 的不透明 token。
                if (looksLikeJwt(token)) {
                    writeUnauthorized(response, "登录已失效，请重新登录");
                    return;
                }

                SessionInfo info;
                try {
                    info = sessionService.get(token);
                } catch (SessionUnavailableException e) {
                    log.error("会话存储不可用", e);
                    writeUnavailable(response);
                    return;
                }

                if (info != null) {
                    if (info.mustChangePassword() && !isPasswordChangeExempt(request.getRequestURI())) {
                        writeForbidden(response, "首次登录请先修改密码");
                        return;
                    }

                    Set<String> permissions = RolePermissions.getPermissions(info.role());
                    UUID actingCompanyId = resolveActingCompany(request, response, permissions);
                    if (ACTING_REJECTED.equals(actingCompanyId)) {
                        return;
                    }

                    setAuthentication(info.userId(), info.role(), permissions, token,
                            info.companyId(), actingCompanyId, request);
                    request.setAttribute("session", info);
                    request.setAttribute("token", token);
                    if (info.degraded()) {
                        request.setAttribute("auth.degraded", Boolean.TRUE);
                    }
                    log.trace("会话认证成功, userId: {}, role: {}", info.userId(), info.role());
                    filterChain.doFilter(request, response);
                    return;
                }

                // token 存在但无效/过期：不建立认证上下文，交给 SecurityConfig 决定 401/放行
                log.debug("会话无效或已过期");
            }

            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
            TokenContext.clear();
            CompanyScopeService.clearCache();
        }
    }

    private UUID resolveActingCompany(HttpServletRequest request, HttpServletResponse response,
                                      Set<String> permissions) throws IOException {
        String actingHeader = request.getHeader(ACTING_COMPANY_HEADER);
        if (actingHeader == null || actingHeader.isBlank()) {
            return null;
        }
        if (!permissions.contains(Permission.COMPANY_MANAGE)) {
            writeForbidden(response, "无权切换企业视角");
            return ACTING_REJECTED;
        }
        UUID actingCompanyId;
        try {
            actingCompanyId = UUID.fromString(actingHeader.trim());
        } catch (IllegalArgumentException e) {
            writeBadRequest(response, "企业视角参数格式不正确");
            return ACTING_REJECTED;
        }
        var company = companyRepository.findById(actingCompanyId).orElse(null);
        if (company == null) {
            writeForbidden(response, "企业不存在，无法切换视角");
            return ACTING_REJECTED;
        }
        if (!"active".equals(company.getStatus())) {
            writeForbidden(response, "企业已归档，无法切换视角");
            return ACTING_REJECTED;
        }
        return actingCompanyId;
    }

    private void setAuthentication(UUID userId, String role, Set<String> permissions,
                                   String credentials, UUID companyId, UUID actingCompanyId,
                                   HttpServletRequest request) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(permissions.size() + 1);
        authorities.add(new SimpleGrantedAuthority(role));
        for (String perm : permissions) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, credentials, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        TokenContext.set(userId, companyId, actingCompanyId);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\"}");
    }

    private void writeBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":400,\"message\":\"" + message + "\"}");
    }

    private void writeUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":503,\"message\":\"认证服务暂不可用，请稍后重试\"}");
    }

    private boolean looksLikeJwt(String token) {
        return token.indexOf('.') > 0 && token.split("\\.").length == 3;
    }

    private String extractToken(HttpServletRequest request) {
        // 1. 新主路径：token header
        String headerToken = request.getHeader(TOKEN_HEADER);
        if (headerToken != null && !headerToken.isBlank()) {
            return headerToken.trim();
        }
        // 2. Authorization: Bearer — 兼容现有前端/脚本
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX_LENGTH);
        }
        // 3. HttpOnly Cookie
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (TOKEN_COOKIE.equals(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    request.setAttribute(ATTR_AUTH_FROM_COOKIE, Boolean.TRUE);
                    return cookie.getValue();
                }
            }
        }
        // 4. query param — 仅 SSE/WebSocket 等无法设 header 的路径
        String queryToken = request.getParameter("token");
        if (queryToken == null || queryToken.isBlank()) {
            return null;
        }
        if (isSseQueryTokenAllowed(request)) {
            return queryToken;
        }
        if ("log".equalsIgnoreCase(queryTokenMode)) {
            log.warn("非 SSE 路径使用 query token（灰度放行）：{} {}",
                    request.getMethod(), request.getRequestURI());
            return queryToken;
        }
        log.warn("拒绝非 SSE 路径的 query token：{} {}", request.getMethod(), request.getRequestURI());
        return null;
    }

    private boolean isPasswordChangeExempt(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.endsWith("/auth/change-password")
                || uri.endsWith("/auth/me")
                || uri.endsWith("/auth/logout")
                || uri.endsWith("/c/auth/me")
                || uri.endsWith("/config/nav")
                || uri.endsWith("/config/nav/badges");
    }

    private boolean requiresCsrf(HttpServletRequest request) {
        String method = request.getMethod();
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        return !(uri.equals("/api/v1/auth/login") || uri.equals("/auth/login")
                || uri.startsWith("/api/v1/auth/register") || uri.startsWith("/auth/register")
                || uri.equals("/api/v1/auth/logout") || uri.equals("/auth/logout")
                || uri.startsWith("/api/v1/c/auth/") || uri.startsWith("/c/auth/")
                || uri.startsWith("/api/v1/public/") || uri.startsWith("/public/")
                || uri.startsWith("/api/v1/internal/im/") || uri.startsWith("/internal/im/")
                || (uri.contains("/im/") && uri.endsWith("/callback"))
                || uri.endsWith("/analytics/event")
                || uri.endsWith("/stt/recognize"));
    }

    private boolean csrfValid(HttpServletRequest request) {
        String header = request.getHeader(CSRF_HEADER);
        String cookieValue = null;
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (CSRF_COOKIE.equals(cookie.getName())) {
                    cookieValue = cookie.getValue();
                    break;
                }
            }
        }
        if (header == null || header.isBlank() || cookieValue == null || cookieValue.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                header.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                cookieValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean isSseQueryTokenAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (Pattern pattern : SSE_QUERY_TOKEN_PATHS) {
            if (pattern.matcher(uri).matches()) {
                return true;
            }
        }
        return false;
    }
}
