package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.PracticeKcSettingView;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.Skill;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.CompanySettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 对练知识/案例检索开关（公司级，实时生效）。
 *
 * <p>读取不做缓存：每次对练请求按 {@code (company_id, setting_key)} 点查，管理员保存后
 * 下一轮对练立即按新值执行。缺失或值非法时回退全局默认值，默认关闭以保持既有行为。</p>
 *
 * <p>开关只决定「是否发起知识/案例检索」，不改变知识/案例的企业隔离规则
 * （范围由 {@link KcScopeResolver} 决定）。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeKcSettingService {

    /** 企业设置键：对练是否检索知识/案例 */
    public static final String KEY_PRACTICE_KC_ENABLED = "practice.kc_enabled";

    private final CompanySettingRepository settingRepository;
    private final CompanyScopeService companyScopeService;
    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.practice.kc-default-enabled:false}")
    private boolean defaultEnabled;

    /**
     * 对练链路入口：按分身所属企业解析。
     *
     * <p>分身无企业归属（C 端专家）时回退调用者企业；两者都没有则用全局默认值，
     * 不产生额外查询。</p>
     */
    public boolean isEnabledForSkill(Skill skill, UUID callerCompanyId) {
        UUID companyId = companyScopeService.resolveSkillCompanyId(skill);
        if (companyId == null) {
            companyId = callerCompanyId;
        }
        if (companyId == null) {
            return defaultEnabled;
        }
        return isEnabled(companyId);
    }

    /** 指定企业的开关；无配置或值非法时返回默认值。 */
    public boolean isEnabled(UUID companyId) {
        if (companyId == null) {
            return defaultEnabled;
        }
        return settingRepository.findByCompanyIdAndSettingKey(companyId, KEY_PRACTICE_KC_ENABLED)
                .map(row -> parseBoolean(row.getSettingValue(), companyId))
                .orElse(defaultEnabled);
    }

    public PracticeKcSettingView get(UUID companyId) {
        if (companyId == null) {
            return new PracticeKcSettingView(defaultEnabled, defaultEnabled, null, null);
        }
        return settingRepository.findByCompanyIdAndSettingKey(companyId, KEY_PRACTICE_KC_ENABLED)
                .map(row -> new PracticeKcSettingView(
                        parseBoolean(row.getSettingValue(), companyId), defaultEnabled,
                        row.getUpdatedBy(), row.getUpdatedAt()))
                .orElseGet(() -> new PracticeKcSettingView(defaultEnabled, defaultEnabled, null, null));
    }

    /** 保存开关；审计记录操作人与新值。 */
    @Transactional(rollbackFor = Exception.class)
    public PracticeKcSettingView update(UUID companyId, boolean enabled, UUID updatedBy) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        // 原子 upsert：并发首次保存由 (company_id, setting_key) 唯一约束收敛
        settingRepository.upsert(UUID.randomUUID(), companyId, KEY_PRACTICE_KC_ENABLED,
                Boolean.toString(enabled), updatedBy, now);
        writeAudit(companyId, enabled, updatedBy);
        return new PracticeKcSettingView(enabled, defaultEnabled, updatedBy, now);
    }

    private boolean parseBoolean(String raw, UUID companyId) {
        if (raw == null) {
            return defaultEnabled;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        log.warn("公司设置值非法，回退默认值 companyId={} key={} value={}",
                companyId, KEY_PRACTICE_KC_ENABLED, raw);
        return defaultEnabled;
    }

    private void writeAudit(UUID companyId, boolean enabled, UUID updatedBy) {
        UUID adminId = updatedBy != null ? updatedBy : TokenContext.get();
        if (adminId == null) {
            log.warn("对练检索开关变更缺少操作人，跳过审计 companyId={}", companyId);
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("companyId", companyId.toString());
            detail.put("key", KEY_PRACTICE_KC_ENABLED);
            detail.put("enabled", enabled);
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(adminId)
                    .action("practice_kc_config_update")
                    .targetType("company_setting")
                    .targetId(companyId)
                    .detail(objectMapper.writeValueAsString(detail))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("对练检索开关审计写入失败 companyId={}", companyId, e);
        }
    }
}
