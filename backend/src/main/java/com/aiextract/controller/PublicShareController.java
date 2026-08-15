package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.GuestSessionResponse;
import com.aiextract.dto.ShareInfoResponse;
import com.aiextract.service.ShareService;
import com.aiextract.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 对外公开分享接口 — SecurityConfig 中 /public/** permitAll
 *
 * <p>落地信息无凭证可访问；游客发证幂等（有 C 端身份则滑动续期）。
 * 凭证返回纯 JSON token（前端存 localStorage + Bearer），不写 Cookie —
 * 与 B 端 HttpOnly Cookie 物理隔离，同浏览器双身份互不干扰。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Slf4j
@RestController
@RequestMapping("/public/share")
@RequiredArgsConstructor
public class PublicShareController {

    private final ShareService shareService;
    private final JwtUtil jwtUtil;

    /**
     * 分享落地页信息
     */
    @GetMapping("/{shareCode}")
    public ApiResponse<ShareInfoResponse> getShareInfo(@PathVariable String shareCode) {
        return ApiResponse.success(shareService.getShareInfo(shareCode, currentUserIdOrNull()));
    }

    /**
     * 游客发证（无身份 → 新建游客；已有 C 端身份 → 滑动续期重签）
     */
    @PostMapping("/{shareCode}/guest")
    public ApiResponse<GuestSessionResponse> createGuest(
            @PathVariable String shareCode, HttpServletRequest request) {
        return ApiResponse.success(
                shareService.createGuestSession(shareCode, clientIp(request), currentUserIdOrNull()));
    }

    /**
     * 按 skillId 创建/续期游客会话（平台端 PC 聊天页入口，无需分享码）。
     */
    @PostMapping("/skills/{skillId}/guest")
    public ApiResponse<GuestSessionResponse> createGuestBySkillId(
            @PathVariable UUID skillId, HttpServletRequest request) {
        return ApiResponse.success(
                shareService.createGuestSessionBySkillId(skillId, clientIp(request), currentUserIdOrNull()));
    }

    /**
     * permitAll 路径下认证可有可无：携带有效 token 则解出 userId，否则按匿名处理。
     * 匿名请求的 credentials 非 JWT（AnonymousAuthenticationToken），解析失败即返回 null。
     */
    private UUID currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getCredentials() == null) {
            return null;
        }
        try {
            return jwtUtil.getUserIdFromToken(auth.getCredentials().toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 取客户端真实 IP：X-Forwarded-For 首段（Nginx 反代场景）回退 remoteAddr */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
