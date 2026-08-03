package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.LoginRequest;
import com.aiextract.dto.LoginResponse;
import com.aiextract.dto.RegisterRequest;
import com.aiextract.dto.UserInfoResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CompanyRegisterCode;
import com.aiextract.repository.CompanyRegisterCodeRepository;
import com.aiextract.service.AuthService;
import com.aiextract.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
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
    private final CompanyRegisterCodeRepository registerCodeRepository;
    private final JwtUtil jwtUtil;

    @org.springframework.beans.factory.annotation.Value("${jwt.cookie-secure:false}")
    private boolean cookieSecure;

    @org.springframework.beans.factory.annotation.Value("${jwt.expiration}")
    private long jwtExpirationMs;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletResponse httpResponse) {
        LoginResponse response = authService.login(request);
        setTokenCookie(httpResponse, response.getToken());
        return ApiResponse.success(response);
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletResponse httpResponse,
                                               HttpServletRequest httpRequest) {
        // 安全校验：仅已认证的 super_admin 可注册 super_admin 角色
        if ("super_admin".equals(request.getRole())) {
            String token = getTokenFromSecurityContext(httpRequest);
            if (token == null) {
                return ApiResponse.error(403, "不允许自注册为超级管理员");
            }
            try {
                String requesterRole = jwtUtil.getRoleFromToken(token);
                if (!"super_admin".equals(requesterRole)) {
                    return ApiResponse.error(403, "无权限创建超级管理员");
                }
            } catch (Exception e) {
                return ApiResponse.error(403, "Token 无效");
            }
        }
        LoginResponse response = authService.register(request);
        // 仅自注册时设置 Cookie：管理员创建其他用户时已有自己的有效 token，不覆盖
        if (!hasValidToken(httpRequest)) {
            setTokenCookie(httpResponse, response.getToken());
        }
        return ApiResponse.success(response);
    }

    /** 检查请求是否携带有效 token（判断是否为已登录管理员操作） */
    private boolean hasValidToken(HttpServletRequest request) {
        // 1. Authorization header（API 调用场景）
        String authHeader = request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                jwtUtil.getUserIdFromToken(authHeader.substring(7));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        // 2. HttpOnly Cookie（页面操作场景）
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("token".equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                    try {
                        jwtUtil.getUserIdFromToken(c.getValue());
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
        }
        return false;
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

    /**
     * B端注册（使用企业注册码）。新员工扫码后注册，自动归入企业。
     */
    @PostMapping("/register/with-code")
    public ApiResponse<LoginResponse> registerWithCode(@RequestBody Map<String, Object> body,
                                                        HttpServletResponse httpResponse) {
        String companyCode = (String) body.get("companyCode");
        String account = (String) body.get("account");
        String password = (String) body.get("password");
        String name = (String) body.get("name");

        if (companyCode == null || account == null || password == null || name == null) {
            return ApiResponse.error(400, "请填写所有字段");
        }

        CompanyRegisterCode c = registerCodeRepository.findByCode(companyCode)
            .orElseThrow(() -> new BusinessException(400, "注册码无效"));
        if (Boolean.FALSE.equals(c.getEnabled())) {
            throw new BusinessException(400, "注册码已失效");
        }
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "注册码已过期");
        }
        if (c.getMaxUses() > 0 && c.getUsedCount() >= c.getMaxUses()) {
            throw new BusinessException(400, "注册码已达使用上限");
        }

        // 创建用户 — 使用注册码的 defaultRole
        String role = body.get("role") != null ? (String) body.get("role")
            : (c.getDefaultRole() != null ? c.getDefaultRole() : "employee");
        if (!com.aiextract.config.RolePermissions.REGISTRABLE_ROLES.contains(role)) {
            role = "employee";
        }
        RegisterRequest request = RegisterRequest.builder()
            .companyId(c.getCompanyId().toString())
            .account(account).password(password).name(name)
            .role(role).build();
        LoginResponse response = authService.register(request);

        // 消费注册码
        c.setUsedCount(c.getUsedCount() + 1);
        registerCodeRepository.save(c);

        setTokenCookie(httpResponse, response.getToken());
        return ApiResponse.success(response);
    }

    /** 退出登录 — 清除 HttpOnly Cookie */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        Cookie clear = new Cookie("token", "");
        clear.setHttpOnly(true);
        clear.setSecure(cookieSecure);
        clear.setPath("/");
        clear.setMaxAge(0);
        response.addCookie(clear);
        return ApiResponse.success();
    }

    /** 设置 HttpOnly Cookie */
    private void setTokenCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie("token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge((int) (jwtExpirationMs / 1000));
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
