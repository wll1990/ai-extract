package com.aiextract.service.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 目录注入：kill switch、渐进披露边界、不可信标记。
 */
@ExtendWith(MockitoExtension.class)
class AgentSkillPromptInjectorTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();

    @Mock
    private AgentSkillRegistry registry;

    private AgentSkillPromptInjector injector;

    @BeforeEach
    void setUp() {
        injector = new AgentSkillPromptInjector(registry);
    }

    @Test
    void disabledReturnsPromptUnchanged() {
        assertEquals("system", injector.append("system", COMPANY_ID, false));
    }

    @Test
    void emptyCatalogReturnsPromptUnchanged() {
        when(registry.catalog(COMPANY_ID)).thenReturn(List.of());
        assertEquals("system", injector.append("system", COMPANY_ID, true));
    }

    @Test
    void catalogIsMarkedUntrustedAndShowsKeyNameDescription() {
        when(registry.catalog(COMPANY_ID)).thenReturn(List.of(
                new AgentSkillRegistry.CatalogEntry("price-objection", "价格异议", "拆解总拥有成本")));

        String prompt = injector.append("system", COMPANY_ID, true);

        assertTrue(prompt.startsWith("system"));
        assertTrue(prompt.contains("<available_skills untrusted=\"true\">"));
        assertTrue(prompt.contains("- price-objection | 价格异议 | 拆解总拥有成本"));
    }

    @Test
    void catalogStripsTagsAndTruncatesLongDescription() {
        String longDescription = "说明".repeat(100);
        when(registry.catalog(COMPANY_ID)).thenReturn(List.of(
                new AgentSkillRegistry.CatalogEntry("k", "<b>名称</b>", longDescription)));

        String block = injector.formatCatalog(registry.catalog(COMPANY_ID));

        assertFalse(block.contains("<b>"));
        assertTrue(block.contains("…"));
    }

    @Test
    void catalogCapsItemCount() {
        List<AgentSkillRegistry.CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entries.add(new AgentSkillRegistry.CatalogEntry("skill-" + i, "技能" + i, "说明" + i));
        }
        String block = injector.formatCatalog(entries);

        assertTrue(block.contains("共 40 个，仅展示前"));
        assertTrue(countOccurrences(block, "\n- skill-") <= 20);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
