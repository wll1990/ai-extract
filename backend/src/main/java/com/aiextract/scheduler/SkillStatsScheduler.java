package com.aiextract.scheduler;

import com.aiextract.model.Skill;
import com.aiextract.repository.ConversationStatsRepository;
import com.aiextract.repository.FeedbackLogRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分身互动统计定时聚合 — 每5分钟从 conversation_stats + feedback_log
 * 批量计算并写入 skill 表字段，API 路径直接读列，零 GROUP BY 开销。
 *
 * <p>防重入：AtomicBoolean 保证同一时刻只有一个任务在执行。</p>
 * <p>数据口径：使用 batchStatsOverviewExcludeTest 排除 is_test=true 的管理员测试数据。</p>
 *
 * <p>TODO: 当分身数量超过10000时，改为分页批量处理（每批500），避免一次性加载过多实体到内存。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillStatsScheduler {

    private final SkillRepository skillRepository;
    private final ConversationStatsRepository conversationStatsRepository;
    private final FeedbackLogRepository feedbackLogRepository;

    /** 防重入：true 表示当前有任务在执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 每5分钟执行一次 */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 */5 * * * ?")
    public void refreshSkillStats() {
        if (!running.compareAndSet(false, true)) {
            log.debug("上一次统计聚合尚未完成，跳过本次执行");
            return;
        }
        try {
            doRefresh();
        } catch (Exception e) {
            log.error("刷新分身互动统计失败", e);
        } finally {
            running.set(false);
        }
    }

    private void doRefresh() {
        List<Skill> skills = skillRepository.findByStatusIn(
                List.of("published", "reviewing"));
        if (skills.isEmpty()) return;

        List<UUID> skillIds = skills.stream().map(Skill::getId).toList();
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        LocalDateTime now = LocalDateTime.now();

        // ① 批量 conversation stats — 使用 excludeTest 版本
        List<Object[]> batchStats = conversationStatsRepository.batchStatsOverviewExcludeTest(
                skillIds, thirtyDaysAgo, now);
        Map<UUID, Long> convMap = new HashMap<>();
        Map<UUID, Long> userMap = new HashMap<>();
        Map<UUID, LocalDateTime> lastActiveMap = new HashMap<>();
        for (Object[] row : batchStats) {
            UUID sid = (UUID) row[0];
            convMap.put(sid, num(row[1]));
            userMap.put(sid, num(row[2]));
            if (row[6] != null) lastActiveMap.put(sid, (LocalDateTime) row[6]);
        }

        // ② 批量 satisfaction stats
        List<Object[]> batchSat = feedbackLogRepository.batchSatisfactionStats(skillIds);
        Map<UUID, Integer> satMap = new HashMap<>();
        for (Object[] row : batchSat) {
            UUID sid = (UUID) row[0];
            long up = num(row[1]);
            long total = num(row[2]);
            satMap.put(sid, total > 0 ? (int) Math.round((double) up / total * 100) : 0);
        }

        // ③ 写入 skill 字段 — 只 UPDATE 有变更的行
        int updated = 0;
        for (Skill skill : skills) {
            Long conv = convMap.getOrDefault(skill.getId(), 0L);
            Long users = userMap.getOrDefault(skill.getId(), 0L);
            Integer sat = satMap.getOrDefault(skill.getId(), 0);
            LocalDateTime lastActive = lastActiveMap.get(skill.getId());

            boolean changed = false;
            if (!Objects.equals(skill.getConversationCount(), conv.intValue())) {
                skill.setConversationCount(conv.intValue()); changed = true;
            }
            if (!Objects.equals(skill.getUserCount(), users.intValue())) {
                skill.setUserCount(users.intValue()); changed = true;
            }
            if (!Objects.equals(skill.getSatisfactionRate(), sat)) {
                skill.setSatisfactionRate(sat); changed = true;
            }
            if (!Objects.equals(skill.getLastActiveAt(), lastActive)) {
                skill.setLastActiveAt(lastActive); changed = true;
            }
            if (changed) updated++;
        }
        // JPA 自动 flush，只 UPDATE 有变更的行

        if (updated > 0) {
            log.info("刷新分身互动统计完成: {} 个分身有变更, 共 {} 个", updated, skills.size());
        }
    }

    private static long num(Object o) {
        return o != null ? ((Number) o).longValue() : 0L;
    }
}
