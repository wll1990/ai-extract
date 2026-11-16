package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.TokenContext;
import com.aiextract.config.TokenRevocationService;
import com.aiextract.dto.LoginRequest;
import com.aiextract.dto.LoginResponse;
import com.aiextract.dto.RegisterRequest;
import com.aiextract.dto.UserInfoResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.AuthService;
import com.aiextract.service.RegistrationPolicy;
import com.aiextract.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;
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
    private final TokenRevocationService tokenRevocationService;
    private final com.aiextract.service.AuthRateLimiter authRateLimiter;
    private final com.aiextract.common.ClientIpResolver clientIpResolver;

    @org.springframework.beans.factory.annotation.Value("${jwt.cookie-secure:false}")
    private boolean cookieSecure;

    @org.springframework.beans.factory.annotation.Value("${jwt.expiration}")
    private long jwtExpirationMs;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletResponse httpResponse,
                                             HttpServletRequest httpRequest) {
        authRateLimiter.checkLogin(clientIpResolver.resolve(httpRequest), request.getAccount());
        LoginResponse response = authService.login(request);
        setTokenCookie(httpResponse, response.getToken());
        setCsrfCookie(httpResponse);
        return ApiResponse.success(response);
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletResponse httpResponse,
                                               HttpServletRequest httpRequest) {
        // 只限制匿名注册；管理员带 token 批量建号不受此限
        if (!hasValidToken(httpRequest)) {
            authRateLimiter.checkRegister(clientIpResolver.resolve(httpRequest));
        }
        UUID inviteCompanyId = null;
        if (request.getInviteToken() != null && !request.getInviteToken().isBlank()) {
            try {
                inviteCompanyId = jwtUtil.parseInviteToken(request.getInviteToken());
            } catch (Exception e) {
                throw new BusinessException(400, "邀请令牌无效或已过期，请刷新页面重试");
            }
        }

        String token = getTokenFromSecurityContext();
        String callerRole = null;
        UUID callerCompanyId = null;
        Set<String> callerPermissions = Set.of();
        if (token != null) {
            try {
                callerRole = jwtUtil.getRoleFromToken(token);
                callerCompanyId = jwtUtil.getCompanyIdFromToken(token);
                callerPermissions = jwtUtil.getPermissionsFromToken(token);
            } catch (Exception e) {
                callerRole = null;
                callerCompanyId = null;
                callerPermissions = Set.of();
            }
        }

        // 匿名注册：企业归属只认服务端签发的邀请令牌，忽略请求体 companyId
        UUID targetCompanyId;
        if (callerRole == null && inviteCompanyId != null) {
            targetCompanyId = inviteCompanyId;
            request.setCompanyId(inviteCompanyId.toString());
        } else {
            try {
                targetCompanyId = UUID.fromString(request.getCompanyId());
            } catch (Exception e) {
                return ApiResponse.error(400, "企业ID格式不正确");
            }
        }
        RegistrationPolicy.assertAssignable(request.getRole(), callerRole,
                callerCompanyId, targetCompanyId, callerPermissions,
                callerRole == null && inviteCompanyId != null);
        LoginResponse response = authService.register(request);
        // 仅自注册时设置 Cookie：管理员创建其他用户时已有自己的有效 token，不覆盖
        if (!hasValidToken(httpRequest)) {
            setTokenCookie(httpResponse, response.getToken());
            setCsrfCookie(httpResponse);
        }
        return ApiResponse.success(response);
    }

    /** 检查请求是否携带有效 Bearer token（判断是否为已登录管理员操作） */
    private boolean hasValidToken(HttpServletRequest request) {
        String authHeader = request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                jwtUtil.getUserIdFromToken(authHeader.substring(7));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /** 修改自己的密码：成功后返回新 token 并刷新 Cookie */
    @PostMapping("/change-password")
    public ApiResponse<Map<String, String>> changePassword(@RequestBody Map<String, String> body,
                                                           HttpServletResponse httpResponse) {
        UUID userId = TokenContext.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        String newToken = authService.changePassword(userId,
                body.get("oldPassword"), body.get("newPassword"));
        setTokenCookie(httpResponse, newToken);
        setCsrfCookie(httpResponse);
        return ApiResponse.success(Map.of("token", newToken));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> getCurrentUser() {
        String token = getTokenFromSecurityContext();
        if (token == null || token.isBlank()) {
            return ApiResponse.error(401, "未登录");
        }
        UUID userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            return ApiResponse.error(401, "token无效");
        }
        UserInfoResponse response = authService.getCurrentUser(userId);
        return ApiResponse.success(response);
    }

    /**
     * B端注册（使用企业注册码）。新员工扫码后注册，自动归入企业。
     */
    @PostMapping("/register/with-code")
    public ApiResponse<LoginResponse> registerWithCode(@RequestBody Map<String, Object> body,
                                                        HttpServletResponse httpResponse,
                                                        HttpServletRequest httpRequest) {
        authRateLimiter.checkRegister(clientIpResolver.resolve(httpRequest));
        String companyCode = (String) body.get("companyCode");
        String account = (String) body.get("account");
        String password = (String) body.get("password");
        String name = (String) body.get("name");

        if (companyCode == null || account == null || password == null || name == null) {
            return ApiResponse.error(400, "请填写所有字段");
        }

        // 校验 + 建号 + 原子消费注册码全部在 AuthService 的事务内完成
        LoginResponse response = authService.registerWithCode(companyCode, account, password, name);
        setTokenCookie(httpResponse, response.getToken());
        setCsrfCookie(httpResponse);
        return ApiResponse.success(response);
    }

    /** 退出登录 — 清除 HttpOnly Cookie 并撤销当前 token */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        Cookie clear = new Cookie("token", "");
        clear.setHttpOnly(true);
        clear.setSecure(cookieSecure);
        clear.setPath("/");
        clear.setMaxAge(0);
        response.addCookie(clear);

        Cookie csrfClear = new Cookie("csrf_token", "");
        csrfClear.setHttpOnly(false);
        csrfClear.setSecure(cookieSecure);
        csrfClear.setPath("/");
        csrfClear.setMaxAge(0);
        response.addCookie(csrfClear);

        // 撤销当前 token（登出后立即失效），匿名请求无 token 则跳过
        String token = getTokenFromSecurityContext();
        if (token != null) {
            try {
                String jti = jwtUtil.getJtiFromToken(token);
                long remaining = (jwtUtil.getExpirationFromToken(token).getTime()
                        - System.currentTimeMillis()) / 1000;
                if (remaining > 0) {
                    tokenRevocationService.revokeToken(jti, remaining);
                }
            } catch (Exception e) {
                log.warn("登出撤销 token 失败", e);
            }
        }
        return ApiResponse.success();
    }

    /** B端用户上传个人头像 */
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        UUID userId = TokenContext.get();
        String avatarUrl = authService.uploadAvatar(userId, file);
        return ApiResponse.success(Map.of("avatarUrl", avatarUrl));
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

    /** 下发 CSRF 双提交 Cookie：非 HttpOnly，前端读取后写回 X-CSRF-Token */
    private void setCsrfCookie(HttpServletResponse response) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        Cookie cookie = new Cookie("csrf_token", sb.toString());
        cookie.setHttpOnly(false);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge((int) (jwtExpirationMs / 1000));
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    /** 从 SecurityContext 取 token */
    private String getTokenFromSecurityContext() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getCredentials() instanceof String token && !token.isEmpty()) {
            return token;
        }
        return null;
    }
}
