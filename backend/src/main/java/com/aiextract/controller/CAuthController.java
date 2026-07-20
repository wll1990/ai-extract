package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.CLoginRequest;
import com.aiextract.dto.CRegisterRequest;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.service.CAuthService;
import com.aiextract.util.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * C 端认证接口 — 与 B 端 /auth/* 完全独立
 *
 * <p>权限（SecurityConfig）：login=permitAll；register=hasRole(C_GUEST)（游客升级语义）；
 * me=authenticated。全部纯 JSON token，不写 Cookie。</p>
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

    private UUID extractUserId() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return jwtUtil.getUserIdFromToken(token);
    }

    /**
     * C 端登录（账号+密码，平台级，无企业 ID）
     */
    @PostMapping("/login")
    public ApiResponse<GuestSessionResponse> login(@Valid @RequestBody CLoginRequest request) {
        return ApiResponse.success(cAuthService.login(request));
    }

    /**
     * 游客升级注册（原地 UPDATE，userId 不变，会话历史自动继承）
     */
    @PostMapping("/register")
    public ApiResponse<GuestSessionResponse> register(@Valid @RequestBody CRegisterRequest request) {
        return ApiResponse.success(cAuthService.register(extractUserId(), request));
    }

    /**
     * 当前 C 端身份探测（B 端 token 会 404 — 前端按无 C 端身份处理）
     */
    @GetMapping("/me")
    public ApiResponse<GuestSessionResponse> me() {
        return ApiResponse.success(cAuthService.me(extractUserId()));
    }
}
