package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.model.UserNotification;
import com.aiextract.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 站内信收件箱 —— 只读本人通知，userId 从 JWT 取。
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@RestController
@RequestMapping("/me/notifications")
@RequiredArgsConstructor
public class NotificationCenterController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<UserNotification> result = notificationService.listMine(unreadOnly, page, size);
        List<Map<String, Object>> content = result.getContent().stream().map(n -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId().toString());
            row.put("type", n.getType());
            row.put("title", n.getTitle());
            row.put("content", n.getContent());
            row.put("link", n.getLink());
            row.put("bizType", n.getBizType());
            row.put("bizId", n.getBizId() != null ? n.getBizId().toString() : null);
            row.put("read", n.isRead());
            row.put("readAt", n.getReadAt());
            row.put("createdAt", n.getCreatedAt());
            return row;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, result, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        return ApiResponse.success(Map.of("count", notificationService.unreadCount()));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable UUID id) {
        notificationService.markRead(id);
        return ApiResponse.success(Map.of("id", id.toString(), "read", true));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead() {
        return ApiResponse.success(Map.of("updated", notificationService.markAllRead()));
    }

    /** NT-04 删除单条通知（只能删本人） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        notificationService.deleteMine(id);
        return ApiResponse.success(null);
    }
}
