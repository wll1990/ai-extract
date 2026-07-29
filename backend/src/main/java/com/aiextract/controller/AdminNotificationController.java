package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.FeedbackLogRepository;
import com.aiextract.repository.KnowledgeGapRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin 通知中心 —— 阈值告警 + 红点计数。
  * @author AI Extract Team
 */
@Slf4j
@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private static final int GAP_ALERT_THRESHOLD = 10;
    private static final int SATISFACTION_ALERT_THRESHOLD = 70;

    private final KnowledgeGapRepository knowledgeGapRepository;
    private final FeedbackLogRepository feedbackLogRepository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final CompanyScopeService companyScopeService;

    /**
     * 单个分身的通知详情 —— 含阈值告警。
     */
    @GetMapping("/skills/{skillId}")
    public ApiResponse<Map<String, Object>> getSkillNotifications(@PathVariable String skillId) {
        UUID id = UUID.fromString(skillId);
        companyScopeService.assertSkillOwnership(id);
        var skill = skillRepository.findById(id).orElse(null);
        if (skill == null) {

            return ApiResponse.error(404, "分身不存在");

        }

        // ① 缺口告警
        long openGaps = knowledgeGapRepository.countBySkillIdAndStatus(id, "open");

        // ② 低质量颗粒告警：👎 ≥ 5
        long lowQualityCount = grainRepository.countBySpaceIdAndUnhelpfulCountGreaterThanEqual(
            skill.getSpaceId(), 5);

        // ③ 满意率告警：从 feedback_log 计算最近 7 天 👍 率
        List<Object[]> satStats = feedbackLogRepository.satisfactionStats(id);
        double satRate = 100;
        if (!satStats.isEmpty() && satStats.get(0)[1] != null) {
            long up = satStats.get(0)[0] != null ? (Long) satStats.get(0)[0] : 0;
            long total = (Long) satStats.get(0)[1];
            satRate = total > 0 ? (double) up / total * 100 : 100;
        }

        int totalAlerts = 0;
        if (openGaps >= GAP_ALERT_THRESHOLD) {

            totalAlerts++;

        }
        if (lowQualityCount > 0) {

            totalAlerts++;

        }
        if (satRate < SATISFACTION_ALERT_THRESHOLD) {

            totalAlerts++;

        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openGaps", openGaps);
        result.put("gapAlert", openGaps >= 10);
        result.put("lowQualityGrains", lowQualityCount);
        result.put("lowQualityAlert", lowQualityCount > 0);
        result.put("satisfactionRate", Math.round(satRate * 10.0) / 10.0);
        result.put("satisfactionAlert", satRate < 70);
        result.put("totalAlerts", totalAlerts);
        return ApiResponse.success(result);
    }
}
