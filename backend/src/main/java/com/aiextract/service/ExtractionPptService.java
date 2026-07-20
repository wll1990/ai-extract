package com.aiextract.service;

import com.aiextract.model.ExperienceGrain;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ExtractionPptService {

    private static final String TEMPLATE_PATH = "templates/extraction-report.pptx";
    private static final Color DARK_BLUE = new Color(0x1A, 0x2B, 0x4C);
    private static final Color GOLD = new Color(0xC8, 0xA4, 0x5C);
    private static final Color BRAND_BLUE = new Color(0x16, 0x5D, 0xFF);
    private static final Color DARK_GRAY = new Color(0x4E, 0x59, 0x69);
    private static final Color FAQ_RED = new Color(0xC0, 0x39, 0x2B);

    public byte[] generate(List<ExperienceGrain> grains, Map<String, Object> data, String ownerName) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TEMPLATE_PATH)) {
            if (is == null) {
                log.warn("PPT模板未找到，使用动态生成");
                return generateFallback(grains, data, ownerName);
            }

            XMLSlideShow ppt = new XMLSlideShow(is);
            java.util.List<XSLFSlide> slides = ppt.getSlides();

            fillSlide(slides.get(0), Map.of(
                    "REPORT_TITLE", ownerName + " · 销冠技能萃取报告",
                    "REPORT_SUBTITLE", nn(data.get("oneliner")),
                    "REPORT_META", "颗粒数: " + grains.size() + "    " + nn(data.get("subIndustry"))
            ));

            // Slide 1: Case summary
            fillSlide(slides.get(1), buildCaseSummary(data));

            // Slide 2: Content - strategies
            fillSlide(slides.get(2), buildStrategySlide(data));

            // Slide 3: Two-column - tactics + donts
            fillSlide(slides.get(3), buildTacticsSlide(data));

            // Slide 4: FAQ
            fillSlide(slides.get(4), buildFaqSlide(data));

            // Slide 5: Grains table
            fillSlide(slides.get(5), buildGrainsSlide(grains));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            ppt.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("PPT生成失败", e);
            return new byte[0];
        }
    }

    private byte[] generateFallback(List<ExperienceGrain> grains, Map<String, Object> data, String ownerName) {
        // 降级：动态生成简化版（保持向前兼容）
        ExtractionPptServiceFallback fallback = new ExtractionPptServiceFallback();
        return fallback.generate(grains, data, ownerName);
    }

    private void fillSlide(XSLFSlide slide, Map<String, String> replacements) {
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextBox box) {
                for (XSLFTextParagraph p : box.getTextParagraphs()) {
                    for (XSLFTextRun r : p.getTextRuns()) {
                        String text = r.getRawText();
                        if (text != null && replacements.containsKey(text)) {
                            r.setText(replacements.get(text));
                        }
                    }
                }
            }
            // 清除占位用的矩形（带颜色的 placeholder shape）
            if (shape instanceof XSLFAutoShape auto) {
                // 保留非占位元素
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildCaseSummary(Map<String, Object> data) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("SECTION_TITLE", "一、案例摘要");

        Map<String, Object> cs = (Map<String, Object>) data.get("caseSummary");
        StringBuilder content = new StringBuilder();
        if (cs != null) {
            if (cs.get("dealTarget") != null) {
                content.append("成交标的：").append(cs.get("dealTarget")).append("\n\n");
            }
            if (cs.get("customerProfile") != null) {
                content.append("客户特征：").append(cs.get("customerProfile")).append("\n\n");
            }
            if (cs.get("businessValue") != null) {
                content.append("业务价值：").append(cs.get("businessValue"));
            }
        }
        m.put("SECTION_CONTENT", content.toString());
        m.put("PAGE_NUM", "2");
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildStrategySlide(Map<String, Object> data) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("SLIDE_TITLE", "二、核心方法论 — 心法");
        m.put("PAGE_NUM", "3");

        List<Map<String, Object>> strategies = (List<Map<String, Object>>) data.get("strategies");
        if (strategies != null) {
            for (int i = 0; i < Math.min(strategies.size(), 5); i++) {
                Map<String, Object> s = strategies.get(i);
                String key = "BULLET_" + (i + 1);
                String val = "<b>" + nn(s.get("name")) + "</b>：" + nn(s.get("principle"));
                m.put(key, val);
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildTacticsSlide(Map<String, Object> data) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("SLIDE_TITLE", "秘招 · 踩坑提醒");
        m.put("LEFT_HEADER", "🔧 秘招（具体动作）");
        m.put("RIGHT_HEADER", "⚠ 踩坑提醒");
        m.put("PAGE_NUM", "4");

        List<Map<String, Object>> tactics = (List<Map<String, Object>>) data.get("tactics");
        if (tactics != null) {
            StringBuilder left = new StringBuilder();
            for (int i = 0; i < Math.min(tactics.size(), 6); i++) {
                Map<String, Object> t = tactics.get(i);
                left.append("<b>").append(i + 1).append(". ").append(nn(t.get("name"))).append("</b>\n");
                left.append(nn(t.get("method"))).append("\n\n");
            }
            m.put("LEFT_CONTENT", left.toString());
        }

        List<String> donts = (List<String>) data.get("donts");
        if (donts != null) {
            StringBuilder right = new StringBuilder();
            for (int i = 0; i < Math.min(donts.size(), 8); i++) {
                right.append("• ").append(donts.get(i)).append("\n");
            }
            m.put("RIGHT_CONTENT", right.toString());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildFaqSlide(Map<String, Object> data) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("SLIDE_TITLE", "三、常见异议处理");
        m.put("PAGE_NUM", "5");

        List<Map<String, Object>> faq = (List<Map<String, Object>>) data.get("faq");
        if (faq != null) {
            for (int i = 0; i < Math.min(faq.size(), 4); i++) {
                Map<String, Object> f = faq.get(i);
                int n = i + 1;
                m.put("FAQ_Q_" + n, "❓ " + nn(f.get("question")));
                m.put("FAQ_A_" + n, "💬 " + trunc(nn(f.get("answer")), 120));
            }
        }
        return m;
    }

    private Map<String, String> buildGrainsSlide(List<ExperienceGrain> grains) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("SLIDE_TITLE", "附录：技能颗粒明细（共" + grains.size() + "条）");
        m.put("PAGE_NUM", "6");

        m.put("TABLE_HEADER", "  场景标签                         场景描述 / 标准话术");

        for (int i = 0; i < Math.min(grains.size(), 8); i++) {
            ExperienceGrain g = grains.get(i);
            String key = "TABLE_ROW_" + (i + 1);
            String val = "  【" + nn(g.getSceneTag()) + "】  "
                    + trunc(nn(g.getSceneDescription()) + " | " + nn(g.getStandardScript()), 100);
            m.put(key, val);
        }
        return m;
    }

    private String nn(Object obj) { return obj != null ? obj.toString() : ""; }

    private String trunc(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max) + "...";
    }

    /** 降级：无模板时动态生成 */
    private static class ExtractionPptServiceFallback {
        byte[] generate(List<ExperienceGrain> grains, Map<String, Object> data, String ownerName) {
            XMLSlideShow ppt = new XMLSlideShow();
            ppt.setPageSize(new java.awt.Dimension(960, 540));
            // 快速创建几页
            XSLFSlide s = ppt.createSlide();
            XSLFTextBox box = s.createTextBox();
            box.setAnchor(new Rectangle(50, 200, 860, 100));
            XSLFTextParagraph p = box.addNewTextParagraph();
            p.setTextAlign(XSLFTextParagraph.TextAlign.CENTER);
            XSLFTextRun r = p.addNewTextRun();
            r.setText(ownerName + " · 萃取报告");
            r.setFontSize(28.0);
            r.setFontColor(DARK_BLUE);
            r.setFontFamily("PingFang SC");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try { ppt.write(out); ppt.close(); } catch (Exception ignored) {}
            return out.toByteArray();
        }
    }
}
