package com.aiextract.service;

import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Report;
import com.aiextract.model.Skill;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步报告生成服务。
 *
 * <p>萃取完成后异步生成报告内容存入 report 表，
 * 避免阻塞 HTTP 请求线程和清洗调度器。</p>
  * @author AI Extract Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGenerationService {

    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ExtractionReportService extractionReportService;
    private final com.aiextract.repository.InterviewSessionRepository interviewRepository;
    private final ReportRepository reportRepository;
    private final ObjectMapper objectMapper;

    /** 自注入代理：确保 @Async 通过 AOP 代理生效，避免内部调用绕过异步 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ReportGenerationService self;

    /**
     * 萃取完成后异步生成报告。
     */
    @Async("embeddingExecutor")
    public void generateAsync(UUID skillId) {
        try {
            Skill skill = skillRepository.findById(skillId).orElse(null);
            if (skill == null) {

                return;

            }
            List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
            if (grains.isEmpty()) { return; }
            log.info("异步报告生成开始, skillId: {}, grains: {}", skillId, grains.size());
            String html = extractionReportService.generateHtml(skillId);
            if (html != null && !html.isBlank()) {
                saveOrUpdateReport(skill, grains.size(), html);
            }
            log.info("异步报告生成完成, skillId: {}", skillId);
        } catch (Exception e) {
            log.error("异步报告生成失败, skillId: {}", skillId, e);
        }
    }

    /** 保存或更新报告到 report 表 */
    private void saveOrUpdateReport(Skill skill, int grainCount, String html) {
        var existingPage = reportRepository.findBySpaceIdOrderByCreatedAtDesc(
                skill.getSpaceId(), org.springframework.data.domain.PageRequest.of(0, 1));
        Report report;
        String title = (skill.getOwnerName() != null ? skill.getOwnerName() : "未命名") + " · 经验萃取报告";
        String subtitle = grainCount + "个经验颗粒";

        if (existingPage.hasContent()) {
            report = existingPage.getContent().get(0);
            report.setTitle(title);
            report.setSubtitle(subtitle);
            report.setUpdatedAt(LocalDateTime.now());
        } else {
            report = Report.builder()
                .id(UUID.randomUUID())
                .spaceId(skill.getSpaceId())
                .title(title)
                .subtitle(subtitle)
                .shareEnabled(true)
                .viewCount(0)
                .rating(java.math.BigDecimal.valueOf(4.5))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        }

        // HTML 写文件
        writeReportHtml(report.getId(), html);
        report.setHtmlPath("./data/reports/" + report.getId() + ".html");

        // content_json 改为轻量统计
        try {
            report.setContentJson(objectMapper.writeValueAsString(Map.of("grainCount", grainCount)));
        } catch (Exception ignored) {
            report.setContentJson("{}");
        }
        reportRepository.save(report);
        log.info("报告已保存, skillId: {}, reportId: {}, htmlPath: {}", skill.getId(), report.getId(), report.getHtmlPath());
    }

    /** 写报告 HTML 到文件（供 saveOrUpdateReport 和 AdminAuditController 复用） */
    public static void writeReportHtml(UUID reportId, String html) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("./data/reports");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(reportId + ".html"), html,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("写入报告文件失败: " + reportId, e);
        }
    }

    /**
     * 访谈完成后生成报告 — 找到访谈对应的 skill 异步生成。
     */
    public UUID generateReport(UUID interviewId) {
        var session = interviewRepository.findById(interviewId).orElse(null);
        if (session == null) {
            log.warn("generateReport: 访谈不存在, interviewId: {}", interviewId);
            return null;
        }
        Skill skill = skillRepository.findBySpaceId(session.getSpaceId()).orElse(null);
        if (skill == null) {
            log.warn("generateReport: 访谈对应空间无 skill, interviewId: {}, spaceId: {}", interviewId, session.getSpaceId());
            return null;
        }
        /** 通过自注入代理调用，确保 @Async 生效，AI 调用不阻塞当前线程 */
        self.generateAsync(skill.getId());
        return skill.getId();
    }

}
