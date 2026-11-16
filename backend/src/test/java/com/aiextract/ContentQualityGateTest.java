package com.aiextract;

import com.aiextract.service.ContentQualityGate;
import com.aiextract.service.ContentQualityGate.QualityReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容准入质量门禁单元测试：知识卡片/案例的阻断项、书籍模式更严标准、审批前最小校验。
 */
class ContentQualityGateTest {

    private static final String GOOD_CONTENT =
            "客户说预算没批时，先确认谁签字、谁使用、谁反对，再按清单准备对比材料。"
                    + "适用前提是客户已经进入内部评审；如果连需求都没确认，先补需求调研。";

    @Test
    void knowledge_validCard_passes() {
        QualityReport r = ContentQualityGate.checkKnowledge(
                "制造业客户说预算未批时先确认决策链", GOOD_CONTENT, "methodology", null, false);

        assertTrue(r.pass(), r.issueText());
        assertTrue(r.score() >= 80, "有数字/场景/边界，分数应偏高");
    }

    @Test
    void knowledge_tinyContent_blocked() {
        QualityReport r = ContentQualityGate.checkKnowledge("BANT", "四问法", "methodology", null, false);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("正文过短"));
    }

    @Test
    void knowledge_chapterOnlyTitle_blocked() {
        QualityReport r = ContentQualityGate.checkKnowledge(
                "第三章 异议处理", GOOD_CONTENT, "methodology", null, false);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("章节名"));
    }

    @Test
    void knowledge_bookWithoutSource_blocked() {
        QualityReport r = ContentQualityGate.checkKnowledge(
                "客户说预算未批时先确认决策链", GOOD_CONTENT, "methodology", null, true);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("出处"));
    }

    @Test
    void knowledge_bookTooLong_blocked() {
        String longText = "观点".repeat(400);
        QualityReport r = ContentQualityGate.checkKnowledge(
                "客户说预算未批时先确认决策链", longText, "methodology", "《销售巨人》· 第3章", true);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("正文过长"));
    }

    @Test
    void knowledge_bookCopyrightPage_blocked() {
        QualityReport r = ContentQualityGate.checkKnowledge(
                "图书在版编目数据说明", "ISBN 978-7-111-00000-0 定价 59 元 版权所有 侵权必究。"
                        + "本书由机械工业出版社出版发行，推荐序由某某撰写。",
                "methodology", "《销售巨人》", true);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("版权页") || r.issueText().contains("无效信息"));
    }

    @Test
    void case_validStory_passes() {
        QualityReport r = ContentQualityGate.checkCase(
                "制造业采购总监嫌贵，拆分总拥有成本后签约",
                "win", "价格比竞品高 20%",
                "{\"industry\":\"制造业\",\"scale\":\"500人\",\"role\":\"采购总监\"}",
                "拆解实施周期与售后成本", "我们不比单价，比三年总成本",
                "[\"你们比竞品贵\"]", "内部复盘", false);

        assertTrue(r.pass(), r.issueText());
    }

    @Test
    void case_emptyShell_blocked() {
        QualityReport r = ContentQualityGate.checkCase(
                "某客户案例", "win", null, null, null, null, null, null, false);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("核心异议"));
    }

    @Test
    void case_placeholderTitle_blocked() {
        QualityReport r = ContentQualityGate.checkCase(
                "案例一", "win", "价格异议", null, "做了对比", null, null, null, false);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("占位名"));
    }

    @Test
    void case_bookWithoutSource_blocked() {
        QualityReport r = ContentQualityGate.checkCase(
                "制造业采购总监嫌贵，拆分总拥有成本后签约", "win", "价格异议",
                null, "做了对比", null, null, null, true);

        assertFalse(r.pass());
        assertTrue(r.issueText().contains("出处"));
    }

    @Test
    void approval_legacyShortContent_allowed() {
        assertTrue(ContentQualityGate.approvalBlockersKnowledge(
                "BANT", "四问法", "methodology").isEmpty());
    }

    @Test
    void approval_giantContent_blocked() {
        List<String> blockers = ContentQualityGate.approvalBlockersKnowledge(
                "价格政策", "文".repeat(2000), "methodology");

        assertEquals(1, blockers.size());
        assertTrue(blockers.get(0).contains("拆分成多条"));
    }

    @Test
    void approval_caseWithStoredIssues_blocked() {
        List<String> blockers = ContentQualityGate.approvalBlockersCase(
                "某客户案例", "win", "缺少核心异议与关键动作，无法作为案例检索");

        assertFalse(blockers.isEmpty());
        assertTrue(blockers.get(0).contains("核心异议"));
    }
}
