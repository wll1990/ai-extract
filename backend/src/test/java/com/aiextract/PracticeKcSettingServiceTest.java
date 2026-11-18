package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.dto.PracticeKcSettingView;
import com.aiextract.model.CompanySetting;
import com.aiextract.model.Skill;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.CompanySettingRepository;
import com.aiextract.service.PracticeKcSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对练知识/案例开关：缺失回退默认、非法值回退、按分身企业解析、实时读写。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PracticeKcSettingServiceTest {

    @Mock private CompanySettingRepository settingRepository;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private AdminAuditLogRepository auditLogRepository;

    private PracticeKcSettingService service;
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PracticeKcSettingService(settingRepository, companyScopeService,
                auditLogRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "defaultEnabled", false);
    }

    @Test
    void missingSetting_usesDefaultFalse() {
        when(settingRepository.findByCompanyIdAndSettingKey(companyId,
                PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED)).thenReturn(Optional.empty());

        assertFalse(service.isEnabled(companyId));
    }

    @Test
    void missingSetting_withDefaultTrue_returnsTrue() {
        ReflectionTestUtils.setField(service, "defaultEnabled", true);
        when(settingRepository.findByCompanyIdAndSettingKey(companyId,
                PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED)).thenReturn(Optional.empty());

        assertTrue(service.isEnabled(companyId));
    }

    @Test
    void storedValues_parsed_caseInsensitive() {
        when(settingRepository.findByCompanyIdAndSettingKey(companyId,
                PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED))
                .thenReturn(Optional.of(row("TRUE")))
                .thenReturn(Optional.of(row("false")))
                .thenReturn(Optional.of(row("not-a-boolean")));

        assertTrue(service.isEnabled(companyId));
        assertFalse(service.isEnabled(companyId));
        assertFalse(service.isEnabled(companyId));
    }

    @Test
    void isEnabledForSkill_prefersSkillCompanyOverCaller() {
        UUID skillCompany = UUID.randomUUID();
        UUID callerCompany = UUID.randomUUID();
        Skill skill = Skill.builder().id(UUID.randomUUID()).companyId(skillCompany).build();
        when(companyScopeService.resolveSkillCompanyId(skill)).thenReturn(skillCompany);
        when(settingRepository.findByCompanyIdAndSettingKey(skillCompany,
                PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED)).thenReturn(Optional.of(row("true")));

        assertTrue(service.isEnabledForSkill(skill, callerCompany));

        verify(settingRepository, never()).findByCompanyIdAndSettingKey(eq(callerCompany), any());
    }

    @Test
    void isEnabledForSkill_fallsBackToCallerThenDefault() {
        Skill skill = Skill.builder().id(UUID.randomUUID()).build();
        UUID callerCompany = UUID.randomUUID();
        when(companyScopeService.resolveSkillCompanyId(skill)).thenReturn(null);
        when(settingRepository.findByCompanyIdAndSettingKey(callerCompany,
                PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED)).thenReturn(Optional.of(row("true")));

        assertTrue(service.isEnabledForSkill(skill, callerCompany));
        // 两者都没有企业归属 → 默认 false，且不查库
        assertFalse(service.isEnabledForSkill(skill, null));
        verify(settingRepository, never()).findByCompanyIdAndSettingKey(eq((UUID) null), any());
    }

    @Test
    void update_upsertsAndWritesAudit() {
        UUID adminId = UUID.randomUUID();
        when(settingRepository.upsert(any(), eq(companyId),
                eq(PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED), eq("true"), eq(adminId), any()))
                .thenReturn(1);

        PracticeKcSettingView view = service.update(companyId, true, adminId);

        assertTrue(view.enabled());
        verify(settingRepository).upsert(any(), eq(companyId),
                eq(PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED), eq("true"), eq(adminId), any());
        verify(auditLogRepository).save(any());
    }

    private CompanySetting row(String value) {
        return CompanySetting.builder()
                .id(UUID.randomUUID()).companyId(companyId)
                .settingKey(PracticeKcSettingService.KEY_PRACTICE_KC_ENABLED)
                .settingValue(value).updatedAt(LocalDateTime.now())
                .build();
    }
}
