package com.aiextract.service.precheck;

import com.aiextract.config.DomainConfig.AcceptanceConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 素材准入：参考资料识别 + 长文本关键词密度回归。
 */
class ContentAnalyzerTest {

    private final ContentAnalyzer analyzer = new ContentAnalyzer();

    private AcceptanceConfig config() {
        AcceptanceConfig c = new AcceptanceConfig();
        c.setMinTextLength(50);
        c.setChineseRatioMin(0.70);
        c.setSalesKeywordDensityMin(0.005);
        c.setFirstPersonRatioMin(0.01);
        c.setTheoreticalRatioMax(0.30);
        c.setMarketingRatioMax(0.15);
        c.setSalesKeywords(List.of("报价,价格,折扣,预算,客户,销售,成交,合同,采购,谈判,异议,需求,方案,竞品"));
        return c;
    }

    @Test
    void contractTemplate_isRejectedAsReferenceDocument() {
        String text = "甲方：某某科技有限公司\n乙方：某某信息技术有限公司\n"
                + "本合同一式两份，双方签字盖章后生效。\n"
                + "第三条 违约责任：任何一方违约，应赔偿对方全部损失。\n"
                + "合同编号：HT-2026-001。";

        AcceptanceResult r = analyzer.checkAcceptance(text, config());

        assertFalse(r.passed());
        assertEquals("REFERENCE_DOCUMENT", r.rejectCode());
        assertTrue(r.rejectReason().contains("知识库"), r.rejectReason());
    }

    @Test
    void policyDocument_isRejected() {
        String text = "销售管理办法\n第一条 为规范销售行为，特制定本办法。\n"
                + "第二条 销售人员应在报价前完成客户资信审核。\n"
                + "第三条 违反本制度者按公司规定处理。\n"
                + "第四条 本办法自发布之日起施行。";

        AcceptanceResult r = analyzer.checkAcceptance(text, config());

        assertFalse(r.passed());
        assertEquals("REFERENCE_DOCUMENT", r.rejectCode());
    }

    @Test
    void productSpec_isRejected() {
        String text = "产品规格说明书\n目录\n一、技术参数\n二、型号说明\n三、修订记录\n"
                + "本产品支持多种型号，具体技术参数见下表。用户手册随设备一并交付。"
                + "版本历史：V1.0 初始发布，V2.0 更新技术指标。";

        AcceptanceResult r = analyzer.checkAcceptance(text, config());

        assertFalse(r.passed());
        assertEquals("REFERENCE_DOCUMENT", r.rejectCode());
    }

    @Test
    void orgChart_isRejected() {
        String text = "公司组织架构与部门职责说明。\n"
                + "销售部部门职责：负责客户开发与合同签订。\n"
                + "各岗位职责详见岗位说明书；员工手册与薪酬制度另行发布。";

        AcceptanceResult r = analyzer.checkAcceptance(text, config());

        assertFalse(r.passed());
        assertEquals("REFERENCE_DOCUMENT", r.rejectCode());
    }

    @Test
    void realDialogueMentioningContractTemplate_isNotRejected() {
        String text = "客户：合同模板我发您，付款节点按上次的改一下。\n"
                + "销售：好的，我把协议模板和报价一起发您，预算和折扣再确认。\n"
                + "客户：甲方审批还要两天，合同先别盖章。\n"
                + "销售：明白，我同步乙方这边，等您采购流程走完再签。";

        AcceptanceResult r = analyzer.checkAcceptance(text, config());

        assertTrue(r.passed(), "真实对话提到合同模板不应被当成参考资料：" + r.rejectReason());
    }

    @Test
    void personalNarrativeMentioningProductSpecs_isNotRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("我跟客户讲这款产品的技术参数时，会先问他现在用的是什么型号。\n");
            sb.append("我发现客户更关心参数背后的效果，所以我不背规格，而是讲我们上次怎么帮他解决问题。\n");
        }

        AcceptanceResult r = analyzer.checkAcceptance(sb.toString(), config());

        assertTrue(r.passed(), "销售个人叙事提到产品参数不应按产品说明书拒绝：" + r.rejectReason());
    }

    @Test
    void realDialogueMentioningContract_isNotRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append("客户：合同里付款节点能不能调整？报价能不能再降一点？\n");
            sb.append("我：我先确认甲方的审批流程，再和乙方这边核对交付时间。\n");
            sb.append("客户：预算还在审批，价格和折扣要再谈。\n");
            sb.append("我：理解，我把方案和竞品对比整理给您，本周内给答复。\n");
        }

        AcceptanceResult r = analyzer.checkAcceptance(sb.toString(), config());

        assertTrue(r.passed(), r.rejectReason());
    }

    @Test
    void longTranscript_passesKeywordDensity() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 20_000) {
            sb.append("客户：这个报价太高了，预算不够，能不能给个折扣？\n");
            sb.append("我：我先了解一下您的采购流程，再和您确认方案和交付节点。\n");
            sb.append("客户：我们还在和竞品对比，合同条款也要再看。\n");
            sb.append("我：没问题，我把价格拆分和异议处理思路整理给您。\n");
        }

        AcceptanceResult r = analyzer.checkAcceptance(sb.toString(), config());

        assertTrue(r.passed(), "2 万字真实对话不应被关键词密度误杀：" + r.rejectReason());
    }
}
