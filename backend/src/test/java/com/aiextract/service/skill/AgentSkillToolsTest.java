package com.aiextract.service.skill;

import com.aiextract.model.AgentSkill;
import com.aiextract.model.AgentSkillResource;
import com.aiextract.repository.AgentSkillResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 技能工具：开关、租户解析、续读、预算与路径校验。
 */
@ExtendWith(MockitoExtension.class)
class AgentSkillToolsTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();

    @Mock
    private AgentSkillRegistry registry;
    @Mock
    private AgentSkillResourceRepository resources;

    private AgentSkillTools tools;

    @BeforeEach
    void setUp() {
        tools = new AgentSkillTools(registry, resources, new ObjectMapper());
    }

    @Test
    void safePathOnlyAllowsPlainReferencesPath() {
        assertTrue(AgentSkillTools.safePath("references/faq.md"));
        assertTrue(AgentSkillTools.safePath("references/a/b.md"));
        assertFalse(AgentSkillTools.safePath("../references/faq.md"));
        assertFalse(AgentSkillTools.safePath("references/../secret.md"));
        assertFalse(AgentSkillTools.safePath("references/%2e%2e/secret.md"));
        assertFalse(AgentSkillTools.safePath("references\\faq.md"));
        assertFalse(AgentSkillTools.safePath("/etc/passwd"));
        assertFalse(AgentSkillTools.safePath("scripts/run.md"));
        assertFalse(AgentSkillTools.safePath(null));
    }

    @Test
    void toolsDoNothingWhenSkillsNotEnabled() {
        String result = tools.listSkills(context(false));
        assertEquals("技能不可用。", result);
    }

    @Test
    void listSkillsFormatsCatalog() {
        when(registry.catalog(COMPANY_ID)).thenReturn(List.of(
                new AgentSkillRegistry.CatalogEntry("price-objection", "价格异议", "拆解总拥有成本")));

        String result = tools.listSkills(context(true));

        assertTrue(result.contains("price-objection | 价格异议 | 拆解总拥有成本"));
    }

    @Test
    void useSkillRejectsUnknownKey() {
        when(registry.resolve(COMPANY_ID, "missing")).thenReturn(Optional.empty());

        String result = tools.useSkill("{\"skillKey\":\"missing\"}", context(true));

        assertEquals("技能不存在或不可用。", result);
    }

    @Test
    void useSkillReturnsBodyAndContinuationHint() {
        AgentSkill skill = skill("big-skill", "x".repeat(SkillBudget.MAX_BODY_CHARS + 100));
        when(registry.resolve(COMPANY_ID, "big-skill")).thenReturn(Optional.of(skill));
        when(resources.findMeta(skill.getId())).thenReturn(List.of());

        String first = tools.useSkill("{\"skillKey\":\"big-skill\"}", context(true));

        assertTrue(first.length() <= SkillBudget.MAX_BODY_CHARS + 200);
        assertTrue(first.contains("offset=" + SkillBudget.MAX_BODY_CHARS));
    }

    @Test
    void useSkillListsReferences() {
        AgentSkill skill = skill("with-refs", "步骤");
        when(registry.resolve(COMPANY_ID, "with-refs")).thenReturn(Optional.of(skill));
        AgentSkillResourceRepository.MetaRow row = mock(AgentSkillResourceRepository.MetaRow.class);
        when(row.getPath()).thenReturn("references/faq.md");
        when(resources.findMeta(skill.getId())).thenReturn(List.of(row));

        String result = tools.useSkill("{\"skillKey\":\"with-refs\"}", context(true));

        assertTrue(result.contains("参考资料：references/faq.md"));
    }

    @Test
    void useSkillStopsAfterBudgetCalls() {
        AgentSkill skill = skill("loop", "步骤");
        when(registry.resolve(COMPANY_ID, "loop")).thenReturn(Optional.of(skill));
        when(resources.findMeta(skill.getId())).thenReturn(List.of());
        ToolContext ctx = context(true);

        String last = "";
        for (int i = 0; i < SkillBudget.MAX_USE_CALLS + 1; i++) {
            last = tools.useSkill("{\"skillKey\":\"loop\"}", ctx);
        }

        assertTrue(last.contains("上限"));
    }

    @Test
    void readReferenceChunksAndRejectsBadPath() {
        AgentSkill skill = skill("refs", "步骤");
        when(registry.resolve(COMPANY_ID, "refs")).thenReturn(Optional.of(skill));
        AgentSkillResource resource = AgentSkillResource.builder()
                .id(UUID.randomUUID())
                .skillId(skill.getId())
                .path("references/long.md")
                .content("y".repeat(SkillBudget.MAX_REF_CHARS + 10))
                .build();
        when(resources.findBySkillIdAndPath(skill.getId(), "references/long.md"))
                .thenReturn(Optional.of(resource));

        String chunk = tools.readReference(
                "{\"skillKey\":\"refs\",\"path\":\"references/long.md\"}", context(true));
        String rejected = tools.readReference(
                "{\"skillKey\":\"refs\",\"path\":\"../etc/passwd\"}", context(true));

        assertTrue(chunk.contains("offset=" + SkillBudget.MAX_REF_CHARS));
        assertEquals("参考资料路径不合法。", rejected);
    }

    private static AgentSkill skill(String key, String body) {
        return AgentSkill.builder()
                .id(UUID.randomUUID())
                .skillKey(key)
                .name("技能-" + key)
                .description("说明")
                .bodyMd(body)
                .source(AgentSkill.SOURCE_TENANT)
                .companyId(COMPANY_ID)
                .version(1)
                .status(AgentSkill.STATUS_PUBLISHED)
                .tokenEstimate(1)
                .build();
    }

    private static ToolContext context(boolean enabled) {
        Map<String, Object> map = new HashMap<>();
        map.put("agentSkills", enabled);
        map.put("skillBudget", new SkillBudget());
        map.put("companyId", COMPANY_ID.toString());
        map.put("userId", UUID.randomUUID().toString());
        return new ToolContext(map);
    }
}
