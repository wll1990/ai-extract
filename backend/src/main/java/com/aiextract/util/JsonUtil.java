package com.aiextract.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JSONB 解析工具 — 统一项目中散落的 parseJsonList/parseJsonArray/parseIntroProfile 等重复方法。
 *
 * <p>所有方法 null-safe：输入 null/blank/"[]"/"{}" 均返回空集合。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-29
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    /** 解析 JSON 字符串数组 → List&lt;String&gt;。null/空/解析异常均返回空 list。 */
    @SuppressWarnings("unchecked")
    public static List<String> parseStringList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 解析 JSON 对象 → Map&lt;String, String&gt;。null/空/解析异常均返回空 map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 解析 JSON 字符串数组 → List&lt;T&gt;，通过 mapper 转换元素类型。null/空/解析异常均返回空 list。 */
    public static <T> List<T> parseList(String json, Function<String, T> mapper) {
        List<String> raw = parseStringList(json);
        if (raw.isEmpty()) return List.of();
        try {
            return raw.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(mapper)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }
}
