package com.aiextract.service;

import com.aiextract.dto.ReportDetailResponse;
import com.aiextract.dto.ReportListResponse;
import com.aiextract.dto.UpdateReportRequest;
import com.aiextract.common.ErrorMessages;
import com.aiextract.common.StatusConstants;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Report;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 报告服务
 *
 * <p>提供报告列表查询、详情查看、内容编辑和文件下载功能。
 * 编辑后支持仅更新Web版或触发重新生成Word/PPT。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int MAX_RATING = 5;
    private static final String FORMAT_PPT = "ppt";

    private final ReportRepository reportRepository;
    private final ReportGenerationService reportGenerationService;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final com.aiextract.repository.ExperienceGrainRepository grainRepository;
    private final com.aiextract.repository.SkillRepository skillRepository;
    private final com.aiextract.repository.InterviewSessionRepository sessionRepository;
    private final ExtractionReportService extractionReportService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.report.min-grains:10}")
    private int reportMinGrains;
    @org.springframework.beans.factory.annotation.Value("${app.report.min-scenes:3}")
    private int reportMinScenes;

    /**
     * 提交评分
     */
    @Transactional(rollbackFor = Exception.class)
    public void rateReport(String reportId, int rating) {
        Report report = reportRepository.findById(UUID.fromString(reportId))
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.REPORT_NOT_FOUND));
        if (rating <= 0 || rating > MAX_RATING)
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "评分范围1-5");
        report.setRating(java.math.BigDecimal.valueOf(rating));
        reportRepository.save(report);
    }

    /**
     * 同步清单状态（存储到report的content_json中）
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncChecklist(String reportId, Map<String, Object> checklist) {
        Report report = reportRepository.findById(UUID.fromString(reportId)).orElse(null);
        if (report == null) {

            return;

        }
        try {
            String currentJson = report.getContentJson();
            ObjectMapper mapper = objectMapper;
            Map<String, Object> content = mapper.readValue(currentJson, Map.class);
            content.put("checklistState", checklist);
            report.setContentJson(mapper.writeValueAsString(content));
            reportRepository.save(report);
        } catch (Exception e) {
            log.warn("同步清单失败, reportId: {}", reportId, e);
        }
    }

    /**
     * 获取报告列表
     *
     * @param spaceId 空间ID（可选）
     * @param keyword 搜索关键词（可选，匹配标题和副标题）
     * @param page    页码
     * @param size    每页条数
     * @return 报告分页列表
     */
    @Transactional(readOnly = true)
    public Page<ReportListResponse> getReports(String spaceId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Report> reportPage;

        if (keyword != null && !keyword.isEmpty()) {
            reportPage = reportRepository.searchFullText(keyword, pageable);
        } else if (spaceId != null && !spaceId.isEmpty()) {
            reportPage = reportRepository.findBySpaceIdOrderByCreatedAtDesc(
                    UUID.fromString(spaceId), pageable);
        } else {
            reportPage = reportRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        // 批量预加载 author names + scene tags，避免 N+1
        List<UUID> spaceIds = reportPage.getContent().stream()
                .map(Report::getSpaceId).distinct().toList();
        Map<UUID, String> authorNames = batchResolveAuthorNames(spaceIds);
        Map<UUID, List<String>> sceneTagsMap = batchResolveSceneTags(spaceIds);

        return reportPage.map(r -> toListResponse(r, authorNames, sceneTagsMap));
    }

    private Map<UUID, String> batchResolveAuthorNames(List<UUID> spaceIds) {
        if (spaceIds.isEmpty()) { return Map.of(); }
        List<Space> spaces = spaceRepository.findAllById(spaceIds);
        List<UUID> userIds = spaces.stream().map(Space::getUserId).distinct().toList();
        Map<UUID, String> userNames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
        return spaces.stream()
                .collect(Collectors.toMap(Space::getId,
                        s -> userNames.getOrDefault(s.getUserId(), "未知用户"),
                        (a, b) -> a));
    }

    private Map<UUID, List<String>> batchResolveSceneTags(List<UUID> spaceIds) {
        if (spaceIds.isEmpty()) { return Map.of(); }
        Map<UUID, List<String>> result = new LinkedHashMap<>();
        for (UUID sid : spaceIds) {
            result.put(sid, grainRepository.findTop5DistinctSceneTagsBySpaceId(sid));
        }
        return result;
    }

    /**
     * 获取报告详情
     *
     * <p>查询报告完整内容，同时更新浏览量+1。</p>
     *
     * @param reportId 报告ID
     * @return 报告详情
     * @throws BusinessException 如果报告不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportDetailResponse getReport(String reportId) {
        UUID id = UUID.fromString(reportId);
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("报告不存在, reportId: {}", reportId);
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.REPORT_NOT_FOUND);
                });

        // 更新浏览量
        report.setViewCount(report.getViewCount() + 1);
        reportRepository.save(report);

        return toDetailResponse(report);
    }

    /**
     * 编辑报告内容
     *
     * <p>更新content_json中的对应章节。
     * 如果regenerate=true，异步重新生成Word/PPT并将file_status置为synced；
     * 否则将file_status置为pending_regenerate。</p>
     *
     * @param reportId 报告ID
     * @param request  编辑请求
     * @return 更新后的报告详情
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportDetailResponse updateReport(String reportId, UpdateReportRequest request) {
        UUID id = UUID.fromString(reportId);
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("报告不存在, reportId: {}", reportId);
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.REPORT_NOT_FOUND);
                });

        // 更新章节内容（简化：直接替换content_json）
        if (request.getChapters() != null && !request.getChapters().isEmpty()) {
            report.setContentJson(formatChaptersToJson(request.getChapters()));
        }

        // 判断是否需要重新生成文件
        if (Boolean.TRUE.equals(request.getRegenerate())) {
            report.setFileStatus("synced");
            reportRepository.save(report);
            // 异步重新生成Word/PPT
            reportGenerationService.regenerateFilesAsync(report.getId());
            log.info("触发报告文件重新生成, reportId: {}", reportId);
        } else {
            report.setFileStatus("pending_regenerate");
            reportRepository.save(report);
            log.info("报告Web版已更新，文件标记为待生成, reportId: {}", reportId);
        }

        return toDetailResponse(report);
    }

    /**
     * 下载报告文件（Word或PPT）
     *
     * <p>如果file_status=pending_regenerate，触发重新生成后返回文件流。</p>
     *
     * @param reportId 报告ID
     * @param format   格式：word / ppt
     * @return 文件下载路径
     * @throws BusinessException 如果报告不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public String downloadReport(String reportId, String format) {
        UUID id = UUID.fromString(reportId);
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("报告不存在, reportId: {}", reportId);
                    return new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.REPORT_NOT_FOUND);
                });

        String fileUrl;
        if (FORMAT_PPT.equalsIgnoreCase(format)) {
            fileUrl = report.getPptUrl();
        } else {
            fileUrl = report.getWordUrl();
        }

        // 如果待生成，触发重新生成
        if (StatusConstants.FILE_PENDING_REGENERATE.equals(report.getFileStatus()) || fileUrl == null) {
            log.info("报告文件待生成，触发重新生成, reportId: {}, format: {}", reportId, format);
            fileUrl = reportGenerationService.regenerateFile(report.getId(), format);
            report.setFileStatus(StatusConstants.FILE_SYNCED);

            if (FORMAT_PPT.equalsIgnoreCase(format)) {
                report.setPptUrl(fileUrl);
            } else {
                report.setWordUrl(fileUrl);
            }
            reportRepository.save(report);
        }

        return fileUrl;
    }

    /**
     * 转换报告实体为列表响应
     */
    private ReportListResponse toListResponse(Report report,
            Map<UUID, String> authorNames, Map<UUID, List<String>> sceneTagsMap) {
        String authorName = authorNames.getOrDefault(report.getSpaceId(), "未知用户");
        List<String> sceneTags = sceneTagsMap.getOrDefault(report.getSpaceId(), List.of());
        return ReportListResponse.builder()
                .id(report.getId().toString())
                .spaceId(report.getSpaceId() != null ? report.getSpaceId().toString() : null)
                .title(report.getTitle())
                .subtitle(report.getSubtitle())
                .authorName(authorName)
                .sceneTags(sceneTags)
                .rating(report.getRating())
                .viewCount(report.getViewCount())
                .fileStatus(report.getFileStatus())
                .createdAt(report.getCreatedAt() != null ? report.getCreatedAt().toString() : null)
                .build();
    }

    /**
     * 转换报告实体为详情响应
     */
    private ReportDetailResponse toDetailResponse(Report report) {
        // 一次子查询拿 userName，一次查 skill，替代原来的 3 次串行查询
        String authorName = null;
        String skillId = null;
        String skillStatus = null;
        if (report.getSpaceId() != null) {
            authorName = userRepository.findNameBySpaceId(report.getSpaceId()).orElse(null);
            var skill = skillRepository.findBySpaceId(report.getSpaceId());
            if (skill.isPresent()) {
                skillId = skill.get().getId().toString();
                skillStatus = skill.get().getStatus();
            }
        }
        return ReportDetailResponse.builder()
                .id(report.getId().toString())
                .spaceId(report.getSpaceId() != null ? report.getSpaceId().toString() : null)
                .skillId(skillId)
                .skillStatus(skillStatus)
                .title(report.getTitle())
                .subtitle(report.getSubtitle())
                .contentJson(report.getContentJson())
                .wordUrl(report.getWordUrl())
                .pptUrl(report.getPptUrl())
                .webPublished(report.getWebPublished())
                .fileStatus(report.getFileStatus())
                .rating(report.getRating())
                .viewCount(report.getViewCount())
                .authorName(authorName)
                .createdAt(report.getCreatedAt() != null ? report.getCreatedAt().toString() : null)
                .updatedAt(report.getUpdatedAt() != null ? report.getUpdatedAt().toString() : null)
                .build();
    }

    /**
     * 按访谈 sessionId 检查报告就绪状态并返回 HTML。
     * sessionId → spaceId → Skill → 检查颗粒/场景数 → generateHtml。
     *
     * @return ReportHtmlResult(ready, html|grains|scenes)
     */
    @Transactional(readOnly = true)
    public ReportHtmlResult getReportHtmlBySession(UUID sessionId, UUID userId) {
        var session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return ReportHtmlResult.notReady(0, 0, reportMinGrains, reportMinScenes);

        // 属主校验：session → space → space.isOwnedBy(userId)
        var space = spaceRepository.findById(session.getSpaceId()).orElse(null);
        if (space == null || !space.isOwnedBy(userId))
            return ReportHtmlResult.notReady(0, 0, reportMinGrains, reportMinScenes);

        var skill = skillRepository.findBySpaceId(session.getSpaceId()).orElse(null);
        if (skill == null) return ReportHtmlResult.notReady(0, 0, reportMinGrains, reportMinScenes);

        long grains = grainRepository.countBySpaceIdAndStatus(session.getSpaceId(), "active");
        long scenes = grainRepository.countDistinctSceneTagsBySpaceIdAndStatus(session.getSpaceId(), "active");
        boolean ready = grains >= reportMinGrains && scenes >= reportMinScenes;

        if (!ready) return ReportHtmlResult.notReady(grains, scenes, reportMinGrains, reportMinScenes);

        String html = extractionReportService.generateHtml(skill.getId());
        return ReportHtmlResult.ready(html, grains, scenes);
    }

    public record ReportHtmlResult(boolean ready, String html, long grains, long scenes,
                                   long needGrains, long needScenes) {
        public static ReportHtmlResult notReady(long grains, long scenes, int minGrains, int minScenes) {
            return new ReportHtmlResult(false, null, grains, scenes,
                    Math.max(0, minGrains - grains), Math.max(0, minScenes - scenes));
        }
        public static ReportHtmlResult ready(String html, long grains, long scenes) {
            return new ReportHtmlResult(true, html, grains, scenes, 0, 0);
        }
    }

    /**
     * 将章节列表格式化为JSON字符串（使用Jackson保证合法JSON）
     */
    private String formatChaptersToJson(List<UpdateReportRequest.ChapterUpdate> chapters) {
        try {
            ObjectMapper mapper = objectMapper;
            List<Map<String, Object>> chapterMaps = chapters.stream().map(ch -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("order", ch.getOrder());
                map.put("content", ch.getContent());
                return map;
            }).collect(Collectors.toList());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("chapters", chapterMaps);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("格式化章节JSON失败", e);
            return "{\"chapters\":[]}";
        }
    }
}
