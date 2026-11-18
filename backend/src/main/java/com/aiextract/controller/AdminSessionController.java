package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.CurrentUser;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * 管理员踢用户全部会话。权限码 USER_MANAGE + 同企业校验（超管除外）。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminSessionController {

    private final SessionService sessionService;
    private final UserRepository userRepository;

    @DeleteMapping("/{userId}/sessions")
    public ApiResponse<Void> kickAll(@PathVariable UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        boolean superAdmin = CompanyScopeService.isSuperAdminStatic();
        if (!superAdmin && !Objects.equals(CurrentUser.homeCompanyId(), target.getCompanyId())) {
            // 跨租户不泄露存在性
            throw new BusinessException(404, "用户不存在");
        }
        sessionService.revokeAll(userId);
        return ApiResponse.success();
    }
}
