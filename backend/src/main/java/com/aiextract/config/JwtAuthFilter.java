package com.aiextract.config;

import com.aiextract.common.TraceContext;
import com.aiextract.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final JwtUtil jwtUtil;
    private final PartnerJwtFilter partnerJwtFilter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 无条件生成 traceId（未认证请求也需要追踪）
        TraceContext.init(java.util.UUID.randomUUID());
        response.setHeader("X-Trace-Id", TraceContext.get());

        try {
            // ── 1. 标准 JWT 验证（优先，覆盖 99% 流量） ──
            String token = extractToken(request);

            if (token != null) {
                try {
                    if (jwtUtil.validateToken(token)) {
                        UUID userId = jwtUtil.getUserIdFromToken(token);
                        String role = jwtUtil.getRoleFromToken(token);
                        UUID companyId = jwtUtil.getCompanyIdFromToken(token);
                        setAuthentication(userId, role, token, companyId, request);
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
                    setAuthentication(userId, "c_partner", paramToken, null, request);
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

    private void setAuthentication(UUID userId, String role, String credentials,
                                   UUID companyId, HttpServletRequest request) {
        Set<String> permissions = RolePermissions.getPermissions(role);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(permissions.size() + 1);
        // 角色名本身也作为 authority（C 端 hasAuthority("c_guest") 等规则依赖此项）
        authorities.add(new SimpleGrantedAuthority(role));
        for (String perm : permissions) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(userId, credentials, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        TokenContext.set(userId, companyId);
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
     * <p>优先级：Authorization header → Cookie → query param。
     * API 调用优先走 Bearer token（不受中间代理 Cookie 转发影响），
     * 页面加载场景 Cookie 作为降级回退。</p>
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Authorization header — 适用于所有 API 调用场景
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX_LENGTH);
        }
        // 2. query param — SSE/WebSocket 等无法设 header 的场景
        return request.getParameter("token");
    }
}
