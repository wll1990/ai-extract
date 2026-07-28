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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * JWT认证过滤器
 *
 * <p>在每个请求到达Controller之前，从Authorization请求头中提取JWT Token，
 * 验证有效性并设置Spring Security上下文。</p>
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

    /**
     * 过滤器执行逻辑
     *
     * <p>从请求头提取Token，验证并设置认证信息到SecurityContext。
     * 如果Token无效或不存在，不阻塞请求（交由SecurityConfig处理）。</p>
     *
     * @param request     HTTP请求
     * @param response    HTTP响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet异常
     * @throws IOException      IO异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 无条件生成 traceId（未认证请求也需要追踪）
        TraceContext.init(java.util.UUID.randomUUID());
        response.setHeader("X-Trace-Id", TraceContext.get());

        // ── 合作方 JWT 验证（URL ?token= 参数） ──
        String partnerToken = request.getParameter("token");
        if (partnerToken != null && !partnerToken.isBlank()) {
            try {
                UUID userId = partnerJwtFilter.authenticate(partnerToken);
                setAuthentication(userId, "c_partner", partnerToken, request);
                filterChain.doFilter(request, response);
                return;
            } catch (Exception e) {
                log.warn("Partner JWT 验证失败: {}", e.getMessage());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
                return;
            } finally {
                TraceContext.clear();
                TokenContext.clear();
            }
        }

        String token = extractToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            TraceContext.clear();
            TokenContext.clear();
            return;
        }

        try {
            if (jwtUtil.validateToken(token)) {
                UUID userId = jwtUtil.getUserIdFromToken(token);
                String role = jwtUtil.getRoleFromToken(token);

                setAuthentication(userId, role, token, request);
                request.setAttribute("token", token);
                log.trace("JWT认证成功, userId: {}, role: {}", userId, role);
            } else {
                // Token 存在但无效（过期/伪造），清除客户端 cookie
                clearTokenCookie(response);
            }
        } catch (Exception e) {
            log.warn("JWT Token解析失败: {}", e.getMessage());
            clearTokenCookie(response);
        }

        filterChain.doFilter(request, response);
        TraceContext.clear();
        TokenContext.clear();
    }

    private void setAuthentication(UUID userId, String role, String credentials, HttpServletRequest request) {
        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
            new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(userId, credentials, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        TokenContext.set(userId);
    }

    /** 清除客户端过期 token cookie */
    private void clearTokenCookie(HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        jakarta.servlet.http.Cookie expired = new jakarta.servlet.http.Cookie("token", "");
        expired.setHttpOnly(true);
        expired.setPath("/");
        expired.setMaxAge(0);
        response.addCookie(expired);
    }

    /**
     * 提取 JWT token
     *
     * <p>优先级：Authorization header → Cookie → query param。
     * API 调用优先走 Bearer token（不受中间代理 Cookie 转发影响），
     * 页面加载场景 Cookie 作为降级回退。</p>
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Authorization header — 优先级最高，适用于所有 API 调用场景
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX_LENGTH);
        }
        // 2. HttpOnly Cookie — 页面加载 / 跨代理场景的降级回退
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if ("token".equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                    return c.getValue();
                }
            }
        }
        // 3. query param — SSE/WebSocket 等无法设 header 的场景
        return request.getParameter("token");
    }
}
