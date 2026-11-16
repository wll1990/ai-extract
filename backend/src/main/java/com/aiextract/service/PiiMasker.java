package com.aiextract.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上传资料结构化脱敏 —— 在文本进入 AI 抽取和落库之前，对可直接定位到个人的标识打码。
 *
 * <p>覆盖：手机号、身份证号、银行卡号、固定电话、邮箱。规则是确定性的，幂等，
 * 不依赖模型判断，避免「模型说脱敏了其实没脱」。</p>
 *
 * <p>不处理姓名/公司名：中文人名与品牌名、产品名高度混淆（「华为」「张江」都是常用词），
 * 正则替换会误伤业务事实。这部分靠提示词约束（不保留真实姓名、用角色称呼）和租户自查，
 * 后续要做得更彻底需要接 NER 模型，不在本层硬做。</p>
 *
 * <p>注意：文件原件仍保存在对象存储（断点续传需要），脱敏作用于解析出的文本、
 * 送模型的上下文和落库内容，不作用于原始文件。</p>
 */
public final class PiiMasker {

    /** 身份证：18 位（末位可为 X）或 15 位，前后不能紧邻字母数字 */
    private static final Pattern ID_CARD =
            Pattern.compile("(?<![0-9A-Za-z])(\\d{17}[0-9Xx]|\\d{15})(?![0-9A-Za-z])");

    /** 手机号：1[3-9] 开头 11 位，前后不能是数字 */
    private static final Pattern MOBILE =
            Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /** 银行卡：16–19 位连续数字（身份证已先处理，不会重复命中） */
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)(\\d{16,19})(?!\\d)");

    /** 固定电话：区号 + 7–8 位，允许连字符或空格 */
    private static final Pattern LANDLINE =
            Pattern.compile("(?<!\\d)(0\\d{2,3})[- ]?(\\d{7,8})(?!\\d)");

    /** 邮箱：保留首字符与域名 */
    private static final Pattern EMAIL =
            Pattern.compile("(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-])([A-Za-z0-9._%+-]*)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})(?![A-Za-z0-9.-])");

    /** 统一社会信用代码 / 纳税人识别号：18 位大写字母数字 */
    private static final Pattern CREDIT_CODE =
            Pattern.compile("(统一社会信用代码|纳税人识别号|税号)\\s*[:：]?\\s*([0-9A-HJ-NPQRTUWXY]{15,20})");

    /** 合同号/招标号/订单号等业务编号 */
    private static final Pattern BUSINESS_NO =
            Pattern.compile("(合同|招标|投标|订单|标书|采购单|项目)(编号|号)\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9\\-_/]{4,})");

    /** 车牌号 */
    private static final Pattern LICENSE_PLATE =
            Pattern.compile("(?<![A-Za-z0-9])([京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5,6})(?![A-Za-z0-9])");

    /** 微信号 */
    private static final Pattern WECHAT_ID =
            Pattern.compile("(?i)wxid_[A-Za-z0-9_-]{6,}");

    /** QQ/微信号（纯数字） */
    private static final Pattern QQ =
            Pattern.compile("(?i)(QQ|微信)(号)?\\s*[:：]?\\s*(\\d{5,12})");

    /** 详细地址 */
    private static final Pattern ADDRESS =
            Pattern.compile("(地址|住址|办公地址|收货地址)\\s*[:：]?\\s*([^\\n，。；;]{6,40})");

    /**
     * 常见姓氏（按人口排序取前 ~90），用于识别「张总」「李经理」这类称呼。
     * 刻意不用全量姓氏：生僻姓（如「解」）出现在正常动词里（「拆解总成本」）会造成误伤。
     */
    private static final String SURNAMES =
            "李王张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤";

    /** 「张总」「李经理」「王主任」：保留职务，隐去姓氏 */
    private static final Pattern PERSON_WITH_TITLE =
            Pattern.compile("(?<![副总的])([" + SURNAMES + "])(总经理|总监|经理|主任|处长|科长|局长|部长|老师|医生|律师|总)(?![司部])");

    /** 公司/机构名（按后缀识别，最多保留 20 字前缀） */
    private static final Pattern ORG_NAME =
            Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9（）()]{2,20}?)(股份有限公司|有限责任公司|有限公司|集团有限公司|集团|控股|银行|证券|保险|医院|大学|学院|研究院|研究所|事务所|工厂|厂)(?=[，。、；,.;\\s]|$|的)");

    /** 政府机关（按常见机构名识别） */
    private static final Pattern GOVERNMENT =
            Pattern.compile("[\\u4e00-\\u9fa5]{0,12}(发改委|财政局|税务局|公安局|法院|检察院|卫健委|教育局|住建局|市场监管局|生态环境局|人社局|民政局|交通局|水利局|农业农村局|商务局|文旅局|应急管理局|统计局|审计局|国资委|人民银行|银保监局|证监局)");

    /** 项目名：具名项目/工程（不跨越「的」和标点，避免吞掉前面的公司名） */
    private static final Pattern PROJECT_NAME =
            Pattern.compile("[^\\s，。、；：:;的]{2,12}(项目|工程)(?=[，。、；,.;\\s]|$)");

    /** 金额：数字 + 单位（用于对外脱敏，政策阈值会跳过） */
    private static final Pattern AMOUNT =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(亿元|亿|万元|万|元)");

    private PiiMasker() {
    }

    /**
     * 标准脱敏（企业内部使用）：结构化标识 + 联系人称呼 + 地址。
     * 客户公司名、项目名、金额保留——企业内部案例必须知道是哪家客户、多大单。
     */
    public static String mask(String text) {
        return maskInternal(text, false);
    }

    /**
     * 强脱敏（对外共享/公共库/书籍）：在标准之上，隐去公司名、政府机关、项目名，
     * 并把具体交易金额改为「某金额」（政策阈值如「50 万以上」保留）。
     */
    public static String maskStrict(String text) {
        return maskInternal(text, true);
    }

    private static String maskInternal(String text, boolean strict) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        result = replaceAll(result, ID_CARD, PiiMasker::maskIdCard);
        result = replaceAll(result, MOBILE, PiiMasker::maskMobile);
        result = replaceAll(result, BANK_CARD, PiiMasker::maskBankCard);
        result = replaceAll(result, LANDLINE, PiiMasker::maskLandline);
        result = replaceAll(result, EMAIL, strict ? PiiMasker::maskEmailStrict : PiiMasker::maskEmail);
        result = replaceGroup(result, CREDIT_CODE, m -> m.group(1) + " ****");
        result = replaceGroup(result, BUSINESS_NO, m -> m.group(1) + m.group(2) + " ****");
        result = replaceAll(result, LICENSE_PLATE, v -> maskPlate(v));
        result = replaceAll(result, WECHAT_ID, v -> "wxid_****");
        result = replaceGroup(result, QQ, m -> m.group(1) + (m.group(2) == null ? "" : m.group(2)) + " ****");
        result = replaceGroup(result, ADDRESS, m -> m.group(1) + " ****");
        result = replaceGroup(result, PERSON_WITH_TITLE, m -> "某" + m.group(2));

        if (strict) {
            result = replaceGroup(result, ORG_NAME, PiiMasker::maskOrgName);
            result = replaceAll(result, GOVERNMENT, v -> "某政府部门");
            result = replaceAll(result, PROJECT_NAME, v -> "某项目");
            result = replaceAmounts(result);
        }
        return result;
    }

    private static String replaceAll(String text, Pattern pattern, java.util.function.Function<String, String> masker) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(masker.apply(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 按分组替换（需要保留前缀/区号等场景） */
    private static String replaceGroup(String text, Pattern pattern,
                                       java.util.function.Function<Matcher, String> replacer) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 京A****：保留省份简称与发牌机关字母 */
    private static String maskPlate(String value) {
        return value.length() <= 2 ? "****" : value.substring(0, 2) + "****";
    }

    /** 对外共享时连邮箱域名一起打码，只保留顶级域 */
    private static String maskEmailStrict(String value) {
        Matcher m = EMAIL.matcher(value);
        if (!m.matches()) {
            return "***";
        }
        String domain = m.group(3);
        int dot = domain.lastIndexOf('.');
        String tld = dot >= 0 ? domain.substring(dot + 1) : "com";
        return m.group(1) + "***@***." + tld;
    }

    /** 公司/机构名：保留机构类型，隐去名称；泛称（我们/该公司等）不处理 */
    private static String maskOrgName(Matcher m) {
        String prefix = m.group(1);
        if (prefix.matches("^(我们|我司|本|贵|该|这家|那家|一家|多家|客户|对方|你们|他们).*")) {
            return m.group();
        }
        String suffix = m.group(2);
        if (suffix.contains("银行")) {
            return "某银行";
        }
        if (suffix.contains("证券")) {
            return "某证券公司";
        }
        if (suffix.contains("保险")) {
            return "某保险公司";
        }
        if (suffix.contains("医院")) {
            return "某医院";
        }
        if (suffix.contains("大学") || suffix.contains("学院")) {
            return "某院校";
        }
        if (suffix.contains("研究院") || suffix.contains("研究所")) {
            return "某研究机构";
        }
        if (suffix.contains("事务所")) {
            return "某事务所";
        }
        if (suffix.endsWith("厂")) {
            return "某工厂";
        }
        return "某企业";
    }

    /**
     * 金额脱敏：具体交易金额改为「某金额」；带「以上/以下/以内/起/上限/阈值」的
     * 政策口径保留，避免把「50 万以上需双签」这类规则一起抹掉。
     */
    private static String replaceAmounts(String text) {
        Matcher m = AMOUNT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String after = text.substring(m.end(), Math.min(text.length(), m.end() + 4));
            boolean policy = after.startsWith("以下") || after.startsWith("以上")
                    || after.startsWith("以内") || after.startsWith("起")
                    || after.startsWith("为限") || after.startsWith("上限")
                    || after.startsWith("阈值") || after.startsWith("以内");
            m.appendReplacement(sb, Matcher.quoteReplacement(policy ? m.group() : "某金额"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 110101********1234：保留前 4 位与后 4 位 */
    private static String maskIdCard(String value) {
        if (value.length() < 8) {
            return "****";
        }
        return value.substring(0, 4) + "**********" + value.substring(value.length() - 4);
    }

    /** 138****5678 */
    private static String maskMobile(String value) {
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    /** ****1234 */
    private static String maskBankCard(String value) {
        return "****" + value.substring(value.length() - 4);
    }

    /** 010-****：保留区号 */
    private static String maskLandline(String value) {
        Matcher m = LANDLINE.matcher(value);
        if (m.matches()) {
            return m.group(1) + "-****";
        }
        return "****";
    }

    /** a***@example.com */
    private static String maskEmail(String value) {
        Matcher m = EMAIL.matcher(value);
        if (m.matches()) {
            return m.group(1) + "***@" + m.group(3);
        }
        return "***";
    }
}
