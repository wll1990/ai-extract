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
 * <h3>演进路线</h3>
 * <pre>
 * Phase 1（当前）— 每5分钟全量扫 conversation_stats + feedback_log
 *   适用：分身 &lt; 10,000，每次 &lt; 200ms，零额外依赖
 *   瓶颈：分身数增长后全表扫描耗时线性增长
 *
 * Phase 2 — 异步增量自增 conversationCount + lastActiveAt
 *   doFinally 回调中直接 UPDATE skill SET conversation_count = conversation_count + 1
 *   userCount/satisfactionRate 仍保留定时聚合（去重和百分比需聚合窗口数据）
 *   适用：分身 &lt; 100,000
 *   瓶颈：userCount 去重和 30 天窗口衰减无法纯增量解决
 *
 * Phase 3 — Redis Sorted Set 滑动窗口 + 定时回写 PostgreSQL
 *   ZADD skill:conv:{skillId} {timestamp} {conversationId}   -- 自动去重
 *   ZADD skill:user:{skillId} {timestamp} {userId}            -- 自动去重
 *   ZCOUNT 计算 30 天内元素个数（O(log N)）
 *   定期 flush 快照到 skill 表做持久化兜底
 *   适用：任意规模
 *   成本：引入 Redis 依赖，需处理缓存穿透和降级
 * </pre>
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
