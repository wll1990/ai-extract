package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.controller.AdminKnowledgeController;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.KnowledgeUploadTaskRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.KnowledgeCaseService;
import com.aiextract.service.KnowledgeCleaningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminKnowledgeController 单元测试 — standaloneSetup + MockMvc（mock Service/Repository），
 * 验证路由 + PageResponse 形状 + @Valid 入参拒绝。无 Spring 上下文，绕开安全过滤器。
 */
@ExtendWith(MockitoExtension.class)
class AdminKnowledgeControllerTest {

    @Mock
    private KnowledgeEntryRepository knowledgeRepository;
    @Mock
    private KnowledgeUploadTaskRepository uploadTaskRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private KnowledgeCaseService knowledgeCaseService;
    @Mock
    private KnowledgeCleaningService knowledgeCleaningService;
    @Mock
    private CompanyScopeService companyScopeService;
    @Mock
    private com.aiextract.service.KnowledgeCaseRetriever knowledgeCaseRetriever;
    @Mock
    private com.aiextract.repository.CompanyRepository companyRepository;
    @Mock
    private com.aiextract.service.ContentImportService contentImportService;
    @Mock
    private com.aiextract.config.PromptLoader promptLoader;
    @Mock
    private com.aiextract.service.ContentDeletionService contentDeletionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminKnowledgeController controller = new AdminKnowledgeController(
                knowledgeRepository, uploadTaskRepository, userRepository, knowledgeCaseService,
                knowledgeCleaningService, companyScopeService, knowledgeCaseRetriever, companyRepository,
                contentImportService, promptLoader, contentDeletionService);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
    }

    @Test
    void listKnowledge_returnsPageResponse() throws Exception {
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        when(knowledgeRepository.search(anyBoolean(), any(), any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/knowledge").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    void createKnowledge_valid_returnsEntity() throws Exception {
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        KnowledgeEntry saved = KnowledgeEntry.builder()
                .id(UUID.randomUUID())
                .companyId(UUID.randomUUID())
                .sourceType("public")
                .category("methodology")
                .title("BANT")
                .content("四问法")
                .version(1)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(knowledgeCaseService.createKnowledge(any(), any())).thenReturn(saved);

        mockMvc.perform(post("/admin/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"BANT\",\"category\":\"methodology\",\"sourceType\":\"public\",\"content\":\"四问法\",\"expireAt\":\"2026-12-01T10:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("BANT"))
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    @Test
    void createKnowledge_blankTitle_rejected() throws Exception {
        mockMvc.perform(post("/admin/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"category\":\"methodology\",\"sourceType\":\"public\",\"content\":\"四问法\",\"expireAt\":\"2026-12-01T10:00:00\"}"))
                .andExpect(status().isBadRequest());
    }
}
