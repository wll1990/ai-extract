package com.aiextract.service;

import com.aiextract.dto.ReportDetailResponse;
import com.aiextract.dto.ReportListResponse;
import com.aiextract.common.ErrorMessages;
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
import java.util.ArrayList;
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

    private final ReportRepository reportRepository;
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
     * 获取报告列表
     *
     * @param spaceId 空间ID（可选）
     * @param keyword 搜索关键词（可选）
     * @param tag     场景标签过滤（可选）
     * @param sort    排序：rating | createdAt | viewCount
     * @param page    页码
     * @param size    每页条数
     * @return 报告分页列表
     */
    @Transactional(readOnly = true)
    public Page<ReportListResponse> getReports(String spaceId, String keyword, String tag, String sort, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Report> reportPage;

        if (tag != null && !tag.isEmpty()) {
            reportPage = reportRepository.findBySceneTag(tag, pageable);
        } else if (keyword != null && !keyword.isEmpty()) {
            reportPage = reportRepository.searchFullText(keyword, pageable);
        } else if (spaceId != null && !spaceId.isEmpty()) {
            reportPage = reportRepository.findBySpaceIdOrderByCreatedAtDesc(
                    UUID.fromString(spaceId), pageable);
        } else if ("rating".equals(sort)) {
            reportPage = reportRepository.findAllByOrderByRatingDesc(pageable);
        } else if ("viewCount".equals(sort)) {
            reportPage = reportRepository.findAllByOrderByViewCountDesc(pageable);
        } else {
            reportPage = reportRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        // 批量预加载 author names + scene tags
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
        // 一次查询取所有 space 的 scene tags
        List<Object[]> rows = grainRepository.findDistinctSceneTagsBySpaceIdIn(spaceIds);
        for (Object[] row : rows) {
            UUID sid = (UUID) row[0];
            String tag = (String) row[1];
            result.computeIfAbsent(sid, k -> new ArrayList<>()).add(tag);
        }
        // 截断到 top 5
        result.replaceAll((k, v) -> v.size() > 5 ? v.subList(0, 5) : v);
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
                .shareCode(report.getShareCode())
                .hasHtml(report.getHtmlPath() != null && !report.getHtmlPath().isEmpty())
                .createdAt(report.getCreatedAt() != null ? report.getCreatedAt().toString() : null)
                .build();
    }

    /**
     * 转换报告实体为详情响应
     */
    private ReportDetailResponse toDetailResponse(Report report) {
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
                .shareCode(report.getShareCode())
                .hasHtml(report.getHtmlPath() != null && !report.getHtmlPath().isEmpty())
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

}
