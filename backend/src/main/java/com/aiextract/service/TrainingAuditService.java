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
 * 培训敏感操作审计 —— 认证/撤销/补训/延期/复核/分配/导出统一入口。
 *
 * <p>审计与业务写在**同一事务**：审计失败就让业务回滚，避免「操作成功但无痕迹」。
 * 只有详情序列化失败会降级为最小 JSON，不阻断业务。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingAuditService {

    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 记录一条培训审计。
     *
     * @param action     动作，如 training.certify
     * @param targetType 对象类型，如 training_assignment
     * @param targetId   对象 ID
     * @param detail     变更详情（old/new/reason 等）
     */
    public void record(String action, String targetType, UUID targetId, Map<String, Object> detail) {
        UUID adminId = TokenContext.get();
        if (adminId == null) {
            log.warn("审计缺少操作人，跳过 action={} target={}:{}", action, targetType, targetId);
            return;
        }
        String json;
        try {
            json = detail == null ? null : objectMapper.writeValueAsString(detail);
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
