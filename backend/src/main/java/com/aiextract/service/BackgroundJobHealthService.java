package com.aiextract.service;

import com.aiextract.repository.ExpertSkillRepository;
import com.aiextract.repository.InterviewSessionRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.SkillMaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台任务健康检查 —— 统计最近窗口内各异步管道的失败数，
 * 供运维告警与值班页判断「是不是有任务在持续失败」。
 *
 * <p>只统计、不自动重试、不删除数据；失败任务仍由各模块的 retry 接口人工触发。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundJobHealthService {

    private final SkillMaterialRepository materialRepository;
    private final ReviewReportRepository reportRepository;
    private final ExpertSkillRepository expertSkillRepository;
    private final InterviewSessionRepository interviewSessionRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(int hours) {
        int window = Math.min(Math.max(hours, 1), 168);
        LocalDateTime after = LocalDateTime.now().minusHours(window);

        long materialFailed = materialRepository.countByStatusInAndUpdatedAtAfter(
                List.of("parse_failed", "cleaning_failed"), after);
        long reportFailed = reportRepository.countByStatusAndUpdatedAtAfter("failed", after);
        long expertFailed = expertSkillRepository.countByStatusAndUpdatedAtAfter("failed", after);
        long interviewFailed = interviewSessionRepository.countByExtractionStatusAndCreatedAtAfter(
                InterviewExtractionStateService.FAILED, after);

        long total = materialFailed + reportFailed + expertFailed + interviewFailed;

        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("material", materialFailed);
        jobs.put("reviewReport", reportFailed);
        jobs.put("expertExtraction", expertFailed);
        jobs.put("interviewExtraction", interviewFailed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowHours", window);
        result.put("since", after.toString());
        result.put("totalFailures", total);
        result.put("jobs", jobs);
        result.put("status", total == 0 ? "healthy" : (total >= 20 ? "degraded" : "warning"));
        return result;
    }
}
