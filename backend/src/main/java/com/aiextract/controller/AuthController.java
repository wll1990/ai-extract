package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.LoginRequest;
import com.aiextract.dto.LoginResponse;
import com.aiextract.dto.RegisterRequest;
import com.aiextract.dto.UserInfoResponse;
import com.aiextract.service.AuthService;
import com.aiextract.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 认证控制器
 *
 * <p>提供登录、注册和获取当前用户信息三个接口。
 * JWT 通过 HttpOnly Cookie 下发，防止 XSS 窃取。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletResponse httpResponse) {
        LoginResponse response = authService.login(request);
        setTokenCookie(httpResponse, response.getToken());
        return ApiResponse.success(response);
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        LoginResponse response = authService.register(request);
        // 不设置 Cookie：管理员创建其他用户时不应替换自己的 token
        return ApiResponse.success(response);
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> getCurrentUser(HttpServletRequest request) {
        String token = getTokenFromSecurityContext(request);
        if (token == null) {
            return ApiResponse.error(401, "未登录");
        }
        UUID userId = jwtUtil.getUserIdFromToken(token);
        UserInfoResponse response = authService.getCurrentUser(userId);
        return ApiResponse.success(response);
    }

    /** 设置 HttpOnly Cookie */
    private void setTokenCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie("token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        // 本地 HTTP，生产应 true
        cookie.setPath("/");
        cookie.setMaxAge(86400);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    /** 从 SecurityContext 或 Cookie 取 token */
    private String getTokenFromSecurityContext(HttpServletRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getCredentials() instanceof String token && !token.isEmpty()) {
            return token;
        }
        // fallback: 直接从 Cookie 取（未登录场景 SecurityContext 为空）
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("token".equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
