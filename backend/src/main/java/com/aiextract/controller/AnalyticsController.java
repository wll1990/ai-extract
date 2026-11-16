package com.aiextract.controller;

import com.aiextract.model.AnalyticsEvent;
import com.aiextract.repository.AnalyticsEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 前端埋点接收端 —— 接收用户行为事件并写入 analytics_event 表。
 *
 * <p>埋点失败不影响主流程，异常静默忽略。</p>
  * @author AI Extract Team
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsEventRepository analyticsEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 接收前端埋点事件。
     *
     * @param body 事件数据，包含 event_type 和 event_data（JSONB）
     */
    @PostMapping("/analytics/event")
    public ResponseEntity<Void> trackEvent(@RequestBody Map<String, Object> body) {
        Object eventType = body.get("event_type");
        if (!(eventType instanceof String type) || type.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            // event_data 列是 JSONB，必须写真正的 JSON 文本。
            // 此前用 Map.toString() 得到的是 {audience=external} 这种 Java 字面量，
            // Postgres 拒绝写入，异常被吞掉，接口照样返回 200 —— 非空 event_data 的埋点全部丢失。
            Object data = body.get("event_data");
            analyticsEventRepository.save(AnalyticsEvent.builder()
                .id(UUID.randomUUID())
                .eventType(type)
                .eventData(data == null ? null : objectMapper.writeValueAsString(data))
                .createdAt(LocalDateTime.now())
                .build());
        } catch (Exception e) {
            // 埋点失败不影响主流程；用 warn 留痕，避免静默丢数据后无从排查
            log.warn("埋点写入失败: type={}, err={}", type, e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
