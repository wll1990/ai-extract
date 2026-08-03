package com.aiextract.common;

import org.springframework.data.domain.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分页响应统一组装工具 — 消除各 Controller 中重复的 7 行样板代码。
 *
 * <pre>{@code
 *   return ApiResponse.success(PageResponse.of(skillPage, page, size));
 * }</pre>
 */
public final class PageResponse {

    private PageResponse() {}

    /** 直接用 Page content */
    public static Map<String, Object> of(Page<?> page, int pageNum, int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", page.getContent());
        m.put("page", pageNum);
        m.put("size", size);
        m.put("total", page.getTotalElements());
        m.put("totalPages", page.getTotalPages());
        return m;
    }

    /** 自定义 content（例如追加了组织分身） */
    public static Map<String, Object> of(List<?> content, Page<?> page, int pageNum, int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", content);
        m.put("page", pageNum);
        m.put("size", size);
        m.put("total", page.getTotalElements());
        m.put("totalPages", page.getTotalPages());
        return m;
    }
}
