package com.aiextract;

import com.aiextract.common.KnowledgeStatus;
import com.aiextract.service.KnowledgeCleaningService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 知识/案例清洗纯逻辑单元测试 —— 轻量归一化（「超标」不被改成「招标」）、过期状态派生。
 * 纯静态方法，无 Spring 上下文。
 */
class KnowledgeCleaningServiceTest {

    // ==================== 轻量归一化 ====================

    @Test
    void normalizeLight_doesNotCorrectTypos() {
        // 关键回归：经验管道的 TextNormalizer 会把「超标」误改为「招标」，知识管道必须保持原样
        assertEquals("价格超标了。", KnowledgeCleaningService.normalizeLight("价格超标了。"));
    }

    @Test
    void normalizeLight_collapsesBlankLines() {
        assertEquals("a\n\nb", KnowledgeCleaningService.normalizeLight("a\n\n\n\n\nb"));
    }

    @Test
    void normalizeLight_nullReturnsEmpty() {
        assertEquals("", KnowledgeCleaningService.normalizeLight(null));
    }

    // ==================== 过期状态派生 ====================

    @Test
    void deriveEffectiveStatus_activePastIsExpired() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        assertEquals(KnowledgeStatus.EXPIRED,
                KnowledgeStatus.deriveEffectiveStatus("active", now.minusDays(1), now));
    }

    @Test
    void deriveEffectiveStatus_activeFutureStaysActive() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        assertEquals("active",
                KnowledgeStatus.deriveEffectiveStatus("active", now.plusDays(1), now));
    }

    @Test
    void deriveEffectiveStatus_nonActiveUnchanged() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        assertEquals("pending",
                KnowledgeStatus.deriveEffectiveStatus("pending", now.minusDays(1), now));
        assertEquals("deprecated",
                KnowledgeStatus.deriveEffectiveStatus("deprecated", null, now));
    }
}
