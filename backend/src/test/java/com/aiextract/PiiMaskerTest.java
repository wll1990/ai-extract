package com.aiextract;

import com.aiextract.service.PiiMasker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传脱敏规则单测：结构化标识必须打码，业务数字不能被误伤，重复调用保持幂等。
 */
class PiiMaskerTest {

    @Test
    void mobile_isMasked() {
        assertEquals("客户电话 138****5678，请回拨。", PiiMasker.mask("客户电话 13800135678，请回拨。"));
    }

    @Test
    void multipleMobiles_allMasked() {
        String masked = PiiMasker.mask("对接人 13800138000，备用 13912345678");
        assertFalse(masked.contains("13800138000"));
        assertFalse(masked.contains("13912345678"));
        assertTrue(masked.contains("138****8000"));
    }

    @Test
    void idCard_isMasked_andNotTreatedAsBankCard() {
        String masked = PiiMasker.mask("身份证 110101199001011234 已核验");
        assertEquals("身份证 1101**********1234 已核验", masked);
    }

    @Test
    void bankCard_isMasked() {
        String masked = PiiMasker.mask("回款账户 6222020200112233445 已确认");
        assertFalse(masked.contains("6222020200112233445"));
        assertTrue(masked.contains("****3445"));
    }

    @Test
    void email_isMasked() {
        assertEquals("邮箱 z***@example.com", PiiMasker.mask("邮箱 zhangsan@example.com"));
    }

    @Test
    void landline_isMasked_keepAreaCode() {
        assertEquals("座机 010-****", PiiMasker.mask("座机 010-88886666"));
    }

    @Test
    void businessNumbers_areUntouched() {
        String text = "2026 年 11 月报价 500 万，折扣 0.85，毛利率 32%。";
        assertEquals(text, PiiMasker.mask(text));
    }

    @Test
    void idempotent() {
        String once = PiiMasker.mask("电话 13800138000，邮箱 zhangsan@example.com");
        assertEquals(once, PiiMasker.mask(once));
    }

    @Test
    void nullAndBlank_safe() {
        assertNull(PiiMasker.mask(null));
        assertEquals("", PiiMasker.mask(""));
        assertEquals("  ", PiiMasker.mask("  "));
    }

    @Test
    void personWithTitle_masked_evenInStandard() {
        assertEquals("某总说预算还没批下来。", PiiMasker.mask("张总说预算还没批下来。"));
        assertEquals("某经理要求再比一次价格。", PiiMasker.mask("李经理要求再比一次价格。"));
    }

    @Test
    void jobTitleAlone_notMasked() {
        // 「总经理」是岗位不是人名，不能被误伤
        assertEquals("总经理要求走内部审批。", PiiMasker.mask("总经理要求走内部审批。"));
    }

    @Test
    void standard_keepsCompanyProjectAndAmount() {
        String text = "上海某某有限公司的华东ERP升级项目，合同金额 500 万元。";
        assertEquals(text, PiiMasker.mask(text));
    }

    @Test
    void strict_masksCompanyProjectAndAmount() {
        String masked = PiiMasker.maskStrict("上海某某有限公司的华东ERP升级项目，合同金额 500 万元。");
        assertFalse(masked.contains("上海某某有限公司"));
        assertFalse(masked.contains("华东ERP升级"));
        assertFalse(masked.contains("500 万元"));
        assertTrue(masked.contains("某企业"));
        assertTrue(masked.contains("某项目"));
        assertTrue(masked.contains("某金额"));
    }

    @Test
    void strict_keepsPolicyThresholdAmounts() {
        String masked = PiiMasker.maskStrict("标准报价 50 万以上需财务与总经理双签。");
        assertTrue(masked.contains("50 万以上"), "政策阈值必须保留，否则规则不可用");
    }

    @Test
    void strict_masksGovernmentAgency() {
        String masked = PiiMasker.maskStrict("该项目由市财政局和区教育局联合审批。");
        assertFalse(masked.contains("财政局"));
        assertTrue(masked.contains("某政府部门"));
    }

    @Test
    void strict_masksEmailDomain() {
        String masked = PiiMasker.maskStrict("邮箱 zhangsan@example.com");
        assertTrue(masked.contains("z***@***.com"), masked);
        assertFalse(masked.contains("example.com"));
    }

    @Test
    void creditCode_businessNo_plate_masked() {
        String text = "统一社会信用代码 91310000MA1K35Q123，合同编号 HT-2026-8899，车牌 沪A12345。";
        String masked = PiiMasker.mask(text);
        assertFalse(masked.contains("91310000MA1K35Q123"));
        assertFalse(masked.contains("HT-2026-8899"));
        assertFalse(masked.contains("沪A12345"));
    }

    @Test
    void wechatAndQq_masked() {
        String masked = PiiMasker.mask("微信号 wxid_abc123xyz，QQ 88776655");
        assertFalse(masked.contains("wxid_abc123xyz"));
        assertFalse(masked.contains("88776655"));
    }

    @Test
    void address_masked_keepLabel() {
        assertEquals("地址 ****", PiiMasker.mask("地址：上海市浦东新区张江路 88 号 3 号楼"));
    }
}
