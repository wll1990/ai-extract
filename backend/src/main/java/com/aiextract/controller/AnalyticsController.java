package com.aiextract.controller;

import com.aiextract.model.AnalyticsEvent;
import com.aiextract.repository.AnalyticsEventRepository;
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

    /**
     * 接收前端埋点事件。
     *
     * @param body 事件数据，包含 event_type 和 event_data（JSONB）
     */
    @PostMapping("/api/v1/analytics/event")
    public ResponseEntity<Void> trackEvent(@RequestBody Map<String, Object> body) {
        try {
            analyticsEventRepository.save(AnalyticsEvent.builder()
                .id(UUID.randomUUID())
                .eventType((String) body.get("event_type"))
                .eventData(body.get("event_data") != null ? body.get("event_data").toString() : null)
                .createdAt(LocalDateTime.now())
                .build());
        } catch (Exception e) {
            // 埋点失败不影响主流程，静默忽略
            log.debug("埋点写入失败: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
