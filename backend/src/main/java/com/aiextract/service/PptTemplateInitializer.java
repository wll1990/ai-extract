package com.aiextract.service;

import org.apache.poi.xslf.usermodel.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.awt.*;
import java.io.*;

/**
 * PPT 模板初始化 — 首次启动时生成专业母版 .pptx 到 classpath
  * @author AI Extract Team
 */
@Service
public class PptTemplateInitializer {

    private static final Color DARK_BLUE = new Color(0x1A, 0x2B, 0x4C);
    private static final Color GOLD = new Color(0xC8, 0xA4, 0x5C);
    private static final Color BRAND_BLUE = new Color(0x16, 0x5D, 0xFF);
    private static final Color WHITE = Color.WHITE;
    private static final Color LIGHT_GRAY = new Color(0xE5, 0xE7, 0xEB);
    private static final Color DARK_GRAY = new Color(0x4E, 0x59, 0x69);

    private static final String TEMPLATE_PATH = "templates/extraction-report.pptx";
    private static final int SLIDE_W = 960;
    private static final int SLIDE_H = 540;

    /**
     * 应用启动时自动生成模板（如果不存在）
     */
    @PostConstruct
    public void init() throws Exception {
        File file = new File("target/classes/" + TEMPLATE_PATH);
        if (file.exists()) { return; }
        file.getParentFile().mkdirs();
        generateTemplate(file);
    }

    private void generateTemplate(File file) throws Exception {
        XMLSlideShow ppt = new XMLSlideShow();
        ppt.setPageSize(new java.awt.Dimension(SLIDE_W, SLIDE_H));

        // Slide Master
        XSLFSlideMaster master = ppt.getSlideMasters().get(0);

        // Slide 0: Cover template (dark background)
        createCoverSlide(ppt);

        // Slide 1: Section divider template
        createSectionSlide(ppt, "");

        // Slide 2: Content with bullet points
        createContentSlide(ppt, "");

        // Slide 3: Two-column content
        createTwoColumnSlide(ppt, "", "");

        // Slide 4: FAQ template
        createFaqSlide(ppt, "");

        // Slide 5: Table/grains template
        createGrainsSlide(ppt, "");

        try (FileOutputStream fos = new FileOutputStream(file)) {
            ppt.write(fos);
        }
        ppt.close();
    }

    // ========== Slide templates ==========

    private void createCoverSlide(XMLSlideShow ppt) {
        XSLFSlide slide = ppt.createSlide();

        // Full dark background
        addRect(slide, 0, 0, SLIDE_W, SLIDE_H, DARK_BLUE);

        // Top gold line
        addRect(slide, 0, 0, SLIDE_W, 3, GOLD);

        // Title area - placeholder text replaced at runtime
        addCenteredText(slide, "REPORT_TITLE", 36, WHITE, 60, 140, 840, 80);

        // Gold divider
        addRect(slide, 390, 235, 180, 2, GOLD);

        // Subtitle
        addCenteredText(slide, "REPORT_SUBTITLE", 18, new Color(0xC9, 0xCD, 0xD4), 60, 255, 840, 50);

        // Meta info
        addCenteredText(slide, "REPORT_META", 13, GOLD, 60, 330, 840, 30);

        // Footer
        addCenteredText(slide, "AI 萃取引擎自动生成 · 仅供内部培训使用", 10,
                new Color(0x86, 0x90, 0x9C), 60, 490, 840, 20);

        // Bottom gold line
        addRect(slide, 0, SLIDE_H - 3, SLIDE_W, 3, GOLD);
    }

    private void createSectionSlide(XMLSlideShow ppt, String title) {
        XSLFSlide slide = ppt.createSlide();

        // Left blue accent bar
        addRect(slide, 0, 0, 6, SLIDE_H, BRAND_BLUE);

        // Section number placeholder
        addText(slide, "SECTION_NUM", 14, BRAND_BLUE, 40, 60, 60, 25);

        // Section title
        addText(slide, "SECTION_TITLE", 28, DARK_BLUE, 40, 90, 860, 45);

        // Gold underline
        addRect(slide, 40, 140, 80, 3, GOLD);

        // Content area
        addText(slide, "SECTION_CONTENT", 15, DARK_GRAY, 40, 170, 860, 300);

        // Page number
        addText(slide, "PAGE_NUM", 10, new Color(0xC9, 0xCD, 0xD4), 900, 510, 40, 20);
    }

    private void createContentSlide(XMLSlideShow ppt, String title) {
        XSLFSlide slide = ppt.createSlide();

        // Top blue bar
        addRect(slide, 0, 0, SLIDE_W, 4, BRAND_BLUE);

        // Title
        addText(slide, "SLIDE_TITLE", 22, DARK_BLUE, 50, 30, 860, 40);

        // Gold underline
        addRect(slide, 50, 75, 60, 2, GOLD);

        // Bullet items
        addText(slide, "BULLET_1", 14, DARK_GRAY, 80, 110, 800, 30);
        addText(slide, "BULLET_2", 14, DARK_GRAY, 80, 160, 800, 30);
        addText(slide, "BULLET_3", 14, DARK_GRAY, 80, 210, 800, 30);
        addText(slide, "BULLET_4", 14, DARK_GRAY, 80, 260, 800, 30);
        addText(slide, "BULLET_5", 14, DARK_GRAY, 80, 310, 800, 30);

        // Page number
        addText(slide, "PAGE_NUM", 10, new Color(0xC9, 0xCD, 0xD4), 900, 510, 40, 20);
    }

    private void createTwoColumnSlide(XMLSlideShow ppt, String title, String subtitle) {
        XSLFSlide slide = ppt.createSlide();

        // Top blue bar
        addRect(slide, 0, 0, SLIDE_W, 4, BRAND_BLUE);

        // Title
        addText(slide, "SLIDE_TITLE", 22, DARK_BLUE, 50, 30, 860, 40);

        // Left column header
        addText(slide, "LEFT_HEADER", 16, BRAND_BLUE, 50, 100, 420, 30);
        addRect(slide, 50, 133, 40, 2, BRAND_BLUE);
        addText(slide, "LEFT_CONTENT", 13, DARK_GRAY, 50, 150, 420, 320);

        // Vertical divider
        addRect(slide, 478, 100, 1, 370, LIGHT_GRAY);

        // Right column header
        addText(slide, "RIGHT_HEADER", 16, GOLD, 500, 100, 420, 30);
        addRect(slide, 500, 133, 40, 2, GOLD);
        addText(slide, "RIGHT_CONTENT", 13, DARK_GRAY, 500, 150, 420, 320);

        // Page number
        addText(slide, "PAGE_NUM", 10, new Color(0xC9, 0xCD, 0xD4), 900, 510, 40, 20);
    }

    private void createFaqSlide(XMLSlideShow ppt, String title) {
        XSLFSlide slide = ppt.createSlide();

        // Top blue bar
        addRect(slide, 0, 0, SLIDE_W, 4, BRAND_BLUE);

        // Title
        addText(slide, "SLIDE_TITLE", 22, DARK_BLUE, 50, 30, 860, 40);

        // FAQ items — Q in red, A in dark gray
        addText(slide, "FAQ_Q_1", 14, new Color(0xC0, 0x39, 0x2B), 60, 100, 840, 25);
        addText(slide, "FAQ_A_1", 13, DARK_GRAY, 60, 130, 840, 40);

        addText(slide, "FAQ_Q_2", 14, new Color(0xC0, 0x39, 0x2B), 60, 180, 840, 25);
        addText(slide, "FAQ_A_2", 13, DARK_GRAY, 60, 210, 840, 40);

        addText(slide, "FAQ_Q_3", 14, new Color(0xC0, 0x39, 0x2B), 60, 260, 840, 25);
        addText(slide, "FAQ_A_3", 13, DARK_GRAY, 60, 290, 840, 40);

        addText(slide, "FAQ_Q_4", 14, new Color(0xC0, 0x39, 0x2B), 60, 340, 840, 25);
        addText(slide, "FAQ_A_4", 13, DARK_GRAY, 60, 370, 840, 40);

        // Page number
        addText(slide, "PAGE_NUM", 10, new Color(0xC9, 0xCD, 0xD4), 900, 510, 40, 20);
    }

    private void createGrainsSlide(XMLSlideShow ppt, String title) {
        XSLFSlide slide = ppt.createSlide();

        // Top blue bar
        addRect(slide, 0, 0, SLIDE_W, 4, BRAND_BLUE);

        // Title
        addText(slide, "SLIDE_TITLE", 22, DARK_BLUE, 50, 30, 860, 40);

        // Table header row
        addText(slide, "TABLE_HEADER", 11, WHITE, 40, 90, 880, 22);
        addRect(slide, 40, 90, 880, 22, DARK_BLUE);

        // Table rows
        String[] rows = {"TABLE_ROW_1", "TABLE_ROW_2", "TABLE_ROW_3", "TABLE_ROW_4",
                "TABLE_ROW_5", "TABLE_ROW_6", "TABLE_ROW_7", "TABLE_ROW_8"};
        for (int i = 0; i < rows.length; i++) {
            Color bg = i % 2 == 0 ? new Color(0xF7, 0xF8, 0xFA) : WHITE;
            addRect(slide, 40, 113 + i * 24, 880, 24, bg);
            addText(slide, rows[i], 10, DARK_GRAY, 50, 114 + i * 24, 860, 22);
        }

        // Page number
        addText(slide, "PAGE_NUM", 10, new Color(0xC9, 0xCD, 0xD4), 900, 510, 40, 20);
    }

    // ========== Helpers ==========

    private void addRect(XSLFSlide slide, int x, int y, int w, int h, Color color) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        shape.setAnchor(new Rectangle(x, y, w, h));
        shape.setFillColor(color);
        shape.setLineColor(null);
    }

    private void addText(XSLFSlide slide, String text, int fontSize, Color color, int x, int y, int w, int h) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle(x, y, w, h));
        XSLFTextParagraph p = box.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text != null ? text : "");
        r.setFontSize((double) fontSize);
        r.setFontColor(color);
        r.setFontFamily("PingFang SC");
    }

    private void addCenteredText(XSLFSlide slide, String text, int fontSize, Color color, int x, int y, int w, int h) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle(x, y, w, h));
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(XSLFTextParagraph.TextAlign.CENTER);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text != null ? text : "");
        r.setFontSize((double) fontSize);
        r.setFontColor(color);
        r.setFontFamily("PingFang SC");
    }
}
