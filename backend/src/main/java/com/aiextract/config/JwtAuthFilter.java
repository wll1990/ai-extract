package com.aiextract.config;

import com.aiextract.common.TraceContext;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.util.JwtUtil;
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
 * JWT认证过滤器
 *
 * <p>在每个请求到达Controller之前，从Authorization请求头中提取JWT Token，
 * 验证有效性并设置Spring Security上下文。权限码通过 {@link RolePermissions} 映射表
 * 从 JWT 中的 role 转换为 authority 集合。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int BEARER_PREFIX_LENGTH = BEARER_PREFIX.length();

    /** HttpOnly 登录 Cookie（AuthController 下发） */
    private static final String TOKEN_COOKIE = "token";
    /** 非 HttpOnly 的 CSRF 随机值（双提交校验） */
    private static final String CSRF_COOKIE = "csrf_token";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    /** 本次认证是否来自 Cookie，用于决定是否强制 CSRF 校验 */
    private static final String ATTR_AUTH_FROM_COOKIE = "auth.fromCookie";

    /** 超管切换企业视角的请求头；仅 COMPANY_MANAGE 可用，非法值 fail-closed */
    private static final String ACTING_COMPANY_HEADER = "X-Acting-Company-Id";

    private final JwtUtil jwtUtil;
    private final PartnerJwtFilter partnerJwtFilter;
    private final TokenRevocationService tokenRevocationService;
    private final CompanyRepository companyRepository;

    /** log=仍接受 query token 但告警；enforce=仅 SSE 白名单路径接受 */
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
        // 优先复用前端/网关传入的 X-Trace-Id，实现全链路关联；没有则生成
        TraceContext.start(request.getHeader("X-Trace-Id"));
        response.setHeader("X-Trace-Id", TraceContext.get());

        try {
            // ── 1. 标准 JWT 验证（优先，覆盖 99% 流量） ──
            String token = extractToken(request);

            // Cookie 认证的写操作必须带 CSRF 头（双提交）；Bearer 调用不受影响
            if (token != null && Boolean.TRUE.equals(request.getAttribute(ATTR_AUTH_FROM_COOKIE))
                    && requiresCsrf(request) && !csrfValid(request)) {
                writeForbidden(response, "CSRF 校验失败，请刷新页面重试");
                return;
            }

            if (token != null) {
                try {
                    if (jwtUtil.validateToken(token)) {
                        UUID userId = jwtUtil.getUserIdFromToken(token);
                        String role = jwtUtil.getRoleFromToken(token);
                        UUID companyId = jwtUtil.getCompanyIdFromToken(token);
                        String jti = jwtUtil.getJtiFromToken(token);
                        long tokenVersion = jwtUtil.getTokenVersionFromToken(token);
                        // 高危变更即时失效：版本号不符或命中黑名单 → 401（前端据此重新登录）
                        // 注意：只在连接建立时检查一次，在途 SSE 长连接不受影响
                        if (tokenRevocationService.isBlocked(userId, jti, tokenVersion)) {
                            // 直接写响应体：sendError 会触发容器的 error dispatch，
                            // 再走一遍过滤器链时被 anyRequest().denyAll() 改写成 403
                            writeUnauthorized(response);
                            return;
                        }
                        // 管理员重置/首次登录：除改密与读取身份外一律拒绝
                        if (jwtUtil.mustChangePasswordFromToken(token)
                                && !isPasswordChangeExempt(request.getRequestURI())) {
                            writeForbidden(response, "首次登录请先修改密码");
                            return;
                        }
                        Set<String> permissions = jwtUtil.getPermissionsFromToken(token);
                        // 灰度期旧 token 无 permissions claim：先按角色映射补齐，再做视角校验
                        if (permissions.isEmpty()) {
                            permissions = RolePermissions.getPermissions(role);
                        }
                        // 企业视角 fail-closed：非法/无权限/企业不可用一律拒绝，不静默忽略
                        UUID actingCompanyId = null;
                        String actingHeader = request.getHeader(ACTING_COMPANY_HEADER);
                        if (actingHeader != null && !actingHeader.isBlank()) {
                            if (!permissions.contains(Permission.COMPANY_MANAGE)) {
                                writeForbidden(response, "无权切换企业视角");
                                return;
                            }
                            try {
                                actingCompanyId = UUID.fromString(actingHeader.trim());
                            } catch (IllegalArgumentException e) {
                                writeBadRequest(response, "企业视角参数格式不正确");
                                return;
                            }
                            var actingCompany = companyRepository.findById(actingCompanyId).orElse(null);
                            if (actingCompany == null) {
                                writeForbidden(response, "企业不存在，无法切换视角");
                                return;
                            }
                            if (!"active".equals(actingCompany.getStatus())) {
                                writeForbidden(response, "企业已归档，无法切换视角");
                                return;
                            }
                        }
                        setAuthentication(userId, role, permissions, token, companyId, actingCompanyId, request);
                        request.setAttribute("token", token);
                        log.trace("JWT认证成功, userId: {}, role: {}", userId, role);
                        filterChain.doFilter(request, response);
                        return;
                    }
                } catch (Exception e) {
                    log.debug("标准 JWT 校验失败 (将尝试合作方): {}", e.getMessage());
                    // 标准 JWT 失败 → 降级到合作方验证
                }
            }

            // ── 2. 合作方 JWT 验证（仅 ?token= + appId 字段） ──
            String paramToken = request.getParameter("token");
            if (paramToken != null && !paramToken.isBlank() && hasAppIdClaim(paramToken)) {
                try {
                    UUID userId = partnerJwtFilter.authenticate(paramToken);
                    setAuthentication(userId, "c_partner", RolePermissions.getPermissions(RolePermissions.C_PARTNER), paramToken, null, null, request);
                    filterChain.doFilter(request, response);
                    return;
                } catch (Exception e) {
                    log.warn("Partner JWT 验证失败: {}", e.getMessage());
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
                    return;
                }
            }
        } finally {
            TraceContext.clear();
            TokenContext.clear();
            CompanyScopeService.clearCache();
        }

        // ── 3. 无认证通过 ──
        filterChain.doFilter(request, response);
    }

    private void setAuthentication(UUID userId, String role, Set<String> permissions,
                                   String credentials, UUID companyId, UUID actingCompanyId,
                                   HttpServletRequest request) {
        // 向后兼容：灰度期旧 token 无 permissions claim，回退角色映射解析
        if (permissions.isEmpty()) {
            permissions = RolePermissions.getPermissions(role);
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(permissions.size() + 1);
        // 角色名仍作为 authority（供「身份迁移」类规则使用，见 SecurityConfig 注释）
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

    /**
     * 写入 401 响应（不走 sendError，避免 error dispatch 被 denyAll 改写为 403）。
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"登录已失效，请重新登录\"}");
    }

    /**
     * 检查 JWT payload 中是否包含 appId 字段（合作方 token 的特征）。
     * 不解签名，仅 Base64 解码 payload 段做快速判断。
     */
    private boolean hasAppIdClaim(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return false;
            byte[] payloadBytes = java.util.Base64.getUrlDecoder().decode(parts[1]);
            com.fasterxml.jackson.databind.JsonNode payload =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadBytes);
            return payload.has("appId") && !payload.get("appId").asText().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 提取 JWT token
     *
     * <p>优先级：Authorization header → 登录 Cookie → query param（仅 SSE/WebSocket 白名单路径）。
     * 带 Bearer 的调用不参与 CSRF 校验；只有 Cookie 认证的写操作要求双提交。</p>
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Authorization header — 适用于所有 API 调用场景
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX_LENGTH);
        }
        // 2. 登录 Cookie — 前端不再把 token 放进 localStorage
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (TOKEN_COOKIE.equals(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    request.setAttribute(ATTR_AUTH_FROM_COOKIE, Boolean.TRUE);
                    return cookie.getValue();
                }
            }
        }
        // 3. query param — 仅 SSE/WebSocket 等无法设 header 的路径
        String queryToken = request.getParameter("token");
        if (queryToken == null || queryToken.isBlank()) {
            return null;
        }
        if (isSseQueryTokenAllowed(request)) {
            return queryToken;
        }
        if ("log".equalsIgnoreCase(queryTokenMode)) {
            log.warn("非 SSE 路径使用 query token（灰度放行）：{} {}", request.getMethod(),
                    request.getRequestURI());
            return queryToken;
        }
        log.warn("拒绝非 SSE 路径的 query token：{} {}", request.getMethod(), request.getRequestURI());
        return null;
    }

    /** 强制改密状态下仍可访问的路径：改密、读取身份、导航 */
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

    /** 只有 Cookie 认证的写操作需要 CSRF；登录/注册/登出/公开回调天然豁免 */
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

    /** 双提交校验：请求头与 Cookie 中的随机值必须一致 */
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
