package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.Skill;
import com.aiextract.repository.ConversationStatsRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 个人工作台 — employee 可见，不走 /admin 权限域。
 *
 * @author AI Extract Team
 * @since 2026-07-29
 */
@RestController
@RequiredArgsConstructor
public class WorkbenchController {

    private final JwtUtil jwtUtil;
    private final ConversationStatsRepository convStatsRepository;
    private final SkillRepository skillRepository;

    private String getToken() {
        return (String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
    }

    @GetMapping("/workbench/mine")
    public ApiResponse<Map<String, Object>> mine() {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        Map<String, Object> data = new LinkedHashMap<>();

        // 本周对话数
        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();
        long weekConversations = convStatsRepository.myConversations(userId, weekStart);

        // 本月对话数
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long monthConversations = convStatsRepository.myConversations(userId, monthStart);

        // 今日对话数
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayConversations = convStatsRepository.myConversations(userId, todayStart);

        long todayPractice = convStatsRepository.myPracticeCount(userId, todayStart);

        data.put("todayConversations", todayConversations);
        data.put("weekConversations", weekConversations);
        data.put("monthConversations", monthConversations);
        data.put("todayPractice", todayPractice);

        // 最近使用分身
        List<Object[]> mySkills = convStatsRepository.mySkills(userId);
        List<Map<String, Object>> recentSkills = new ArrayList<>();
        if (!mySkills.isEmpty()) {
            List<UUID> skillIds = mySkills.stream().map(r -> (UUID) r[0]).distinct().toList();
            Map<UUID, Skill> skillMap = new HashMap<>();
            skillRepository.findAllById(skillIds).forEach(s -> skillMap.put(s.getId(), s));
            for (Object[] row : mySkills) {
                Skill sk = skillMap.get((UUID) row[0]);
                if (sk == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("skillId", sk.getId().toString());
                item.put("displayName", sk.getDisplayName() != null ? sk.getDisplayName() : sk.getOwnerName());
                item.put("ownerName", sk.getOwnerName());
                item.put("conversations", (Long) row[1]);
                item.put("lastActive", row[2] != null ? row[2].toString().substring(0, 10) : "");
                recentSkills.add(item);
            }
        }
        data.put("recentSkills", recentSkills);

        return ApiResponse.success(data);
    }
}
