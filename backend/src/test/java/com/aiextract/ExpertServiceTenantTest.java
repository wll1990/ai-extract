package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.ExpertGrainEditRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExpertSkill;
import com.aiextract.repository.ExpertDocumentRepository;
import com.aiextract.repository.ExpertGrainRepository;
import com.aiextract.repository.ExpertSkillRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.service.ExpertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 专家经验库多租户隔离 — A/B 企业互不可见，平台跨企业只看超管。
 */
class ExpertServiceTenantTest {

    private ExpertSkillRepository expertSkillRepository;
    private ExpertGrainRepository expertGrainRepository;
    private CompanyScopeService companyScopeService;
    private ExpertService service;

    private final UUID companyA = UUID.randomUUID();
    private final UUID companyB = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        expertSkillRepository = mock(ExpertSkillRepository.class);
        expertGrainRepository = mock(ExpertGrainRepository.class);
        companyScopeService = mock(CompanyScopeService.class);
        service = new ExpertService(
                expertSkillRepository,
                expertGrainRepository,
                mock(ExpertDocumentRepository.class),
                mock(ReportRepository.class),
                mock(ObjectMapper.class),
                mock(ChatClient.class),
                mock(WebClient.class),
                mock(PromptLoader.class),
                mock(DomainConfigLoader.class),
                companyScopeService,
                mock(com.aiextract.repository.InterviewSessionRepository.class),
                mock(com.aiextract.service.FileStorageService.class),
                mock(com.aiextract.service.FileParseService.class));
        TokenContext.set(userB, companyB);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    private ExpertSkill expertOfCompany(UUID companyId) {
        UUID id = UUID.randomUUID();
        return ExpertSkill.builder()
                .id(id).name("萃取师").companyId(companyId)
                .status("active").grainCount(0).build();
    }

    @Test
    void companyAdmin_cannotReadOtherCompanyExpert() {
        ExpertSkill other = expertOfCompany(companyA);
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        when(expertSkillRepository.findById(other.getId())).thenReturn(Optional.of(other));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getExpertDetail(other.getId().toString()));

        assertEquals(404, e.getErrorCode());
    }

    @Test
    void companyAdmin_cannotEditGrainOfOtherCompanyExpert() {
        ExpertSkill other = expertOfCompany(companyA);
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        when(expertSkillRepository.findById(other.getId())).thenReturn(Optional.of(other));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.editGrain(other.getId().toString(), UUID.randomUUID().toString(),
                        new ExpertGrainEditRequest()));

        assertEquals(404, e.getErrorCode());
        verify(expertGrainRepository, never()).findById(any());
    }

    @Test
    void companyAdmin_cannotDeleteOtherCompanyExpert() {
        ExpertSkill other = expertOfCompany(companyA);
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        when(expertSkillRepository.findById(other.getId())).thenReturn(Optional.of(other));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.deleteExpert(other.getId().toString()));

        assertEquals(404, e.getErrorCode());
        verify(expertSkillRepository, never()).delete(any());
    }

    @Test
    void companyList_isFilteredByCompany_notFindAll() {
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        when(expertSkillRepository.findByCompanyId(eq(companyB), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<?> page = service.getExperts(1, 20, null, null);

        assertTrue(page.isEmpty());
        verify(expertSkillRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void superAdmin_canReadAnyCompanyExpert() {
        ExpertSkill other = expertOfCompany(companyA);
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(true);
        when(expertSkillRepository.findById(other.getId())).thenReturn(Optional.of(other));
        when(expertGrainRepository.findByExpertId(other.getId())).thenReturn(java.util.List.of());

        service.getExpertDetail(other.getId().toString());

        verify(expertSkillRepository).findById(other.getId());
    }
}
