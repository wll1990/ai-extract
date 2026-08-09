package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.CLoginRequest;
import com.aiextract.dto.CRegisterRequest;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.service.CAuthService;
import com.aiextract.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * C 端认证接口 — 与 B 端 /auth/* 完全独立
 *
 * <p>权限（SecurityConfig）：login=permitAll；register=hasRole(C_GUEST)（游客升级语义）；
 * me=authenticated。
 * login 和 registerNew 设 c_token HttpOnly Cookie —— 供前端中间件统一检查登录态；
 * 真正 API 鉴权仍走 Authorization: Bearer  header（JwtAuthFilter）。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Slf4j
@RestController
@RequestMapping("/c/auth")
@RequiredArgsConstructor
@SuppressWarnings("PMD.ClassNamingShouldBeCamelRule")
public class CAuthController {

    private final CAuthService cAuthService;
    private final JwtUtil jwtUtil;

    @Value("${jwt.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${app.share.c-user-token-ttl-days:30}")
    private int cUserTokenTtlDays;

    private UUID extractUserId() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return jwtUtil.getUserIdFromToken(token);
    }

    /**
     * C 端登录（账号+密码，平台级，无企业 ID）。
     * 设 c_token HttpOnly Cookie 供前端中间件检查登录态。
     */
    @PostMapping("/login")
    public ApiResponse<GuestSessionResponse> login(@Valid @RequestBody CLoginRequest request,
                                                    HttpServletResponse httpResponse) {
        GuestSessionResponse response = cAuthService.login(request);
        setTokenCookie(httpResponse, response.getToken());
        return ApiResponse.success(response);
    }

    /**
     * 游客升级注册（原地 UPDATE，userId 不变，会话历史自动继承）。
     * 升级后的新 token（c_user 角色）覆盖旧 Cookie。
     */
    @PostMapping("/register")
    public ApiResponse<GuestSessionResponse> register(@Valid @RequestBody CRegisterRequest request,
                                                       HttpServletResponse httpResponse) {
        GuestSessionResponse response = cAuthService.register(extractUserId(), request);
        setTokenCookie(httpResponse, response.getToken());
        return ApiResponse.success(response);
    }

    /**
     * C 端独立注册（非游客升级，source='platform'），支持上传头像。
     * 注册即登录，设 c_token Cookie。
     */
    @PostMapping(value = "/register/new", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<GuestSessionResponse> registerNew(
            @RequestPart("account") String account,
            @RequestPart("password") String password,
            @RequestPart(value = "nickname", required = false) String nickname,
            @RequestPart(value = "avatar", required = false) MultipartFile avatar,
            HttpServletResponse httpResponse) {
        GuestSessionResponse response = cAuthService.registerNew(account, password, nickname, avatar);
        setTokenCookie(httpResponse, response.getToken());
        return ApiResponse.success(response);
    }

    /**
     * 当前 C 端身份探测（B 端 token 会 404 — 前端按无 C 端身份处理）
     */
    @GetMapping("/me")
    public ApiResponse<GuestSessionResponse> me() {
        return ApiResponse.success(cAuthService.me(extractUserId()));
    }

    /** 设 c_token HttpOnly Cookie — 与 B 端 token Cookie 命名隔离，避免互相覆盖 */
    private void setTokenCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie("c_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        cookie.setMaxAge(cUserTokenTtlDays * 86400);
        response.addCookie(cookie);
    }
}
