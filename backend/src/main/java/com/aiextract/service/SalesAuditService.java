package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.repository.AdminAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 销售 + 企业 AI 配置的敏感操作审计。
 *
 * <p>与 {@link TrainingAuditService} 同构；两个域独立演进，不互相依赖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesAuditService {

    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void record(String action, String targetType, UUID targetId, Map<String, Object> detail) {
        UUID adminId = TokenContext.get();
        if (adminId == null) {
            log.warn("审计缺少操作人，跳过 action={} target={}:{}", action, targetType, targetId);
            return;
        }
        // 超管切换企业视角时同时记录归属，便于追责「谁在谁的数据里做了什么」
        Map<String, Object> effectiveDetail = detail == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(detail);
        UUID actingCompanyId = TokenContext.getActingCompanyId();
        if (actingCompanyId != null) {
            effectiveDetail.put("actingCompanyId", actingCompanyId.toString());
            UUID homeCompanyId = TokenContext.getHomeCompanyId();
            if (homeCompanyId != null) {
                effectiveDetail.put("homeCompanyId", homeCompanyId.toString());
            }
        }
        String json;
        try {
            json = effectiveDetail.isEmpty() ? null : objectMapper.writeValueAsString(effectiveDetail);
        } catch (Exception e) {
            log.warn("审计详情序列化失败，降级为最小记录 action={}", action, e);
            json = "{\"serialize_error\":true}";
        }
        auditLogRepository.save(AdminAuditLog.builder()
                .id(UUID.randomUUID())
                .adminId(adminId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(json)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
