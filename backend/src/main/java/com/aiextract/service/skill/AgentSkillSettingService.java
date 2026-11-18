package com.aiextract.service.skill;

import com.aiextract.config.TokenContext;
import com.aiextract.repository.CompanySettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 企业级技能开关。与既有企业设置一致：每次点查、不缓存，保存后下一轮立即生效。
 */
@Service
@RequiredArgsConstructor
public class AgentSkillSettingService {

    public static final String KEY_ENABLED = "skill.enabled";

    private final CompanySettingRepository settingRepository;

    /** companyId 为空（C 端/游客）视为启用。 */
    public boolean isEnabled(UUID companyId) {
        if (companyId == null) {
            return true;
        }
        return settingRepository.findByCompanyIdAndSettingKey(companyId, KEY_ENABLED)
                .map(s -> !"false".equalsIgnoreCase(s.getSettingValue()))
                .orElse(true);
    }

    public void setEnabled(UUID companyId, boolean enabled) {
        UUID id = UUID.randomUUID();
        settingRepository.upsert(id, companyId, KEY_ENABLED, Boolean.toString(enabled),
                TokenContext.get(), LocalDateTime.now());
    }
}
