package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.CurrentUser;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 当前用户的会话/设备管理。
 *
 * <p>只操作自己的会话；管理员踢人走 {@link AdminSessionController}。</p>
 */
@RestController
@RequestMapping("/auth/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping
    public ApiResponse<List<SessionService.DeviceView>> list() {
        UUID userId = requireUser();
        return ApiResponse.success(sessionService.listDevices(userId, currentToken()));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> kick(@PathVariable UUID sessionId) {
        UUID userId = requireUser();
        sessionService.revokeSession(userId, sessionId);
        return ApiResponse.success();
    }

    /** 踢掉除当前设备外的全部会话 */
    @PostMapping("/revoke-all")
    public ApiResponse<Void> revokeOthers() {
        UUID userId = requireUser();
        sessionService.revokeOthers(userId, currentToken());
        return ApiResponse.success();
    }

    private UUID requireUser() {
        UUID userId = CurrentUser.id();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return userId;
    }

    private String currentToken() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() instanceof String token) {
            return token;
        }
        return null;
    }
}
