package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.controller.SkillMaterialController;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.MaterialAuditService;
import com.aiextract.service.FileStorageService;
import com.aiextract.service.MaterialCleaningScheduler;
import com.aiextract.service.MaterialCleaningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 素材入口租户隔离回归 —— P0-1。
 *
 * <p>company_admin 持有 MATERIAL_MANAGE 时也不能跨企业读写素材；
 * employee 仍只能操作自己空间下的分身。</p>
 */
@ExtendWith(MockitoExtension.class)
class SkillMaterialControllerTest {

    private static final String TOKEN = "token";

    @Mock private SkillMaterialRepository materialRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private MaterialCleaningService cleaningService;
    @Mock private MaterialCleaningScheduler cleaningScheduler;
    @Mock private MaterialAuditService auditService;
    @Mock private UserRepository userRepository;
    @Mock private SpaceRepository spaceRepository;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private FileStorageService fileStorageService;

    private SkillMaterialController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillMaterialController(materialRepository, skillRepository, grainRepository,
                cleaningService, cleaningScheduler, auditService, userRepository, spaceRepository,
                companyScopeService, new ObjectMapper(), fileStorageService);
        TokenContext.set(UUID.randomUUID(), null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID(), TOKEN,
                        List.of(new SimpleGrantedAuthority(RolePermissions.COMPANY_ADMIN))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TokenContext.clear();
    }

    @Test
    void companyAdmin_otherCompanySkillList_throws403() {
        UUID skillId = UUID.randomUUID();
        doThrow(new BusinessException(403, "无权访问其他企业的分身"))
                .when(companyScopeService).assertSkillOwnership(skillId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.listMaterials(skillId, 1, 20, null));
        assertEquals(403, e.getErrorCode());
    }

    @Test
    void companyAdmin_ownCompanySkillList_passes() {
        UUID skillId = UUID.randomUUID();
        when(materialRepository.findBySkillIdExcludingType(any(), any(), any()))
                .thenReturn(Page.empty());

        assertDoesNotThrow(() -> controller.listMaterials(skillId, 1, 20, null));
        verify(companyScopeService).assertSkillOwnership(skillId);
    }

    @Test
    void companyAdmin_otherCompanySpaceUpload_throws403BeforeWrite() {
        UUID spaceId = UUID.randomUUID();
        doThrow(new BusinessException(403, "无权访问其他企业的空间"))
                .when(companyScopeService).assertSpaceOwnership(spaceId);
        MockMultipartFile file = new MockMultipartFile(
                "files", "a.txt", "text/plain", "hello".getBytes());

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.upload(List.of(file), spaceId, null, null, null));
        assertEquals(403, e.getErrorCode());
        verify(cleaningService, never()).uploadMaterialToSpace(any(), any(), any(), any(), any(), any());
    }

    @Test
    void employee_otherSpaceSkill_throws403() {
        UUID currentUserId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        TokenContext.set(currentUserId, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, TOKEN,
                        List.of(new SimpleGrantedAuthority(RolePermissions.EMPLOYEE))));
        when(skillRepository.findById(skillId))
                .thenReturn(Optional.of(Skill.builder().id(skillId).spaceId(spaceId).status("published").build()));
        when(spaceRepository.findById(spaceId))
                .thenReturn(Optional.of(space(spaceId, ownerId)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.listMaterials(skillId, 1, 20, null));
        assertEquals(403, e.getErrorCode());
    }

    private Space space(UUID id, UUID userId) {
        return Space.builder()
                .id(id).userId(userId).title("space")
                .isPublic(false).status("active")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }
}
