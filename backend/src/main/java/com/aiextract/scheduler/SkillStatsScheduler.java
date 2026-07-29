package com.aiextract.scheduler;

import com.aiextract.model.OrganizationSkill;
import com.aiextract.model.Skill;
import com.aiextract.repository.ConversationStatsRepository;
import com.aiextract.repository.FeedbackLogRepository;
import com.aiextract.repository.OrganizationSkillRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
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
    private final OrganizationSkillRepository orgSkillRepository;
    private final ConversationStatsRepository conversationStatsRepository;
    private final FeedbackLogRepository feedbackLogRepository;
    private final com.aiextract.service.SkillService skillService;
    private final com.aiextract.repository.ExperienceGrainRepository grainRepository;

    /** 防重入：true 表示当前有任务在执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 防重入：推荐问题存量补齐专用 */
    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);

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
        if (!skills.isEmpty()) {
            refreshIndividualStats(skills);
        }
        refreshOrgSkillStats();
    }

    private void refreshIndividualStats(List<Skill> skills) {

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

    private void refreshOrgSkillStats() {
        List<OrganizationSkill> orgSkills = orgSkillRepository.findByStatusIn(
                List.of("published"));
        if (orgSkills.isEmpty()) return;

        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        LocalDateTime now = LocalDateTime.now();
        int updated = 0;

        for (OrganizationSkill org : orgSkills) {
            List<UUID> memberIds = JsonUtil.parseList(org.getMemberSkillIds(), UUID::fromString);
            if (memberIds.isEmpty()) continue;

            // 1. 跨成员聚合 conversation stats — 去重 userCount
            List<Object[]> rawStats = conversationStatsRepository.orgBatchStatsRaw(
                    memberIds, thirtyDaysAgo, now);
            long totalConvs = 0;
            Set<UUID> allUsers = new HashSet<>();
            LocalDateTime maxLastActive = null;
            for (Object[] row : rawStats) {
                totalConvs += num(row[1]);
                if (row[2] != null) allUsers.add((UUID) row[2]);
                if (row[3] != null) {
                    LocalDateTime t = (LocalDateTime) row[3];
                    if (maxLastActive == null || t.isAfter(maxLastActive)) maxLastActive = t;
                }
            }

            // 2. 跨成员满意度加权平均
            List<Object[]> satStats = feedbackLogRepository.batchSatisfactionStats(memberIds);
            long totalUp = 0, totalAll = 0;
            for (Object[] row : satStats) {
                totalUp += num(row[1]);
                totalAll += num(row[2]);
            }
            int satRate = totalAll > 0 ? (int) Math.round((double) totalUp / totalAll * 100) : 0;

            // 3. Dirty check + UPDATE
            boolean changed = false;
            if (!Objects.equals(org.getConversationCount(), (int) totalConvs)) {
                org.setConversationCount((int) totalConvs); changed = true;
            }
            if (!Objects.equals(org.getUserCount(), allUsers.size())) {
                org.setUserCount(allUsers.size()); changed = true;
            }
            if (!Objects.equals(org.getSatisfactionRate(), satRate)) {
                org.setSatisfactionRate(satRate); changed = true;
            }
            if (!Objects.equals(org.getLastActiveAt(), maxLastActive)) {
                org.setLastActiveAt(maxLastActive); changed = true;
            }
            if (changed) updated++;
        }

        if (updated > 0) {
            log.info("刷新组织分身互动统计完成: {} 个有变更, 共 {} 个", updated, orgSkills.size());
        }
    }

    private static long num(Object o) {
        return o != null ? ((Number) o).longValue() : 0L;
    }

    // ═══════════════════════════════════════════════════════════
    // 一次性存量补齐 — 仅手动触发
    // ═══════════════════════════════════════════════════════════

    /**
     * 存量推荐问题补齐：查所有 published 且 recommended_questions IS NULL 的 Skill，
     * 逐个调 @Async generateRecommendedQuestions 生成。
     *
     * <p>仅手动触发（无 @Scheduled），AtomicBoolean 防重入。
     * 每次最多处理 50 个分身，避免 AI 调用雪崩。</p>
     */
    public void backfillRecommendedQuestions() {
        if (!backfillRunning.compareAndSet(false, true)) {
            log.info("推荐问题存量补齐正在执行中，跳过");
            return;
        }
        try {
            List<Skill> skills = skillRepository.findByStatus("published");
            if (skills.isEmpty()) {
                log.info("无已发布分身，跳过推荐问题补齐");
                return;
            }

            int count = 0;
            for (Skill skill : skills) {
                try {
                    // generateRecommendedQuestions 内部已幂等，无需重复判断
                    skillService.generateRecommendedQuestions(skill.getId());
                    count++;
                    if (count >= 50) {
                        log.info("推荐问题补齐已达单次上限 50，剩余 {} 个下次继续",
                                skills.size() - count);
                        break;
                    }
                } catch (Exception e) {
                    log.warn("推荐问题补齐失败 skillId={}: {}", skill.getId(), e.getMessage());
                }
            }
            log.info("推荐问题存量补齐完成: 触发 {} 个, 共扫描 {} 个已发布分身", count, skills.size());
        } catch (Exception e) {
            log.error("推荐问题存量补齐异常", e);
        } finally {
            backfillRunning.set(false);
        }
    }

    // ==================== P1-1: weight 根据反馈自动调权 ====================

    /**
     * 每 30 分钟根据 helpful/unhelpful 反馈自动调整颗粒权重。
     * <p>公式: newWeight = baseWeight × (1 + helpfulBonus - unhelpfulPenalty)
     *   <ul>
     *     <li>helpfulBonus = min(0.5, helpfulRatio × 0.5)</li>
     *     <li>unhelpfulPenalty = min(0.4, unhelpfulRatio × 0.3)</li>
     *   </ul>
     * 最终 clamp 到 [0.1, 2.0]。
     */
    @Transactional(rollbackFor = Exception.class)
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 */30 * * * ?")
    public void updateGrainWeights() {
        try {
            var grains = grainRepository.findAll().stream()
                .filter(g -> "active".equals(g.getStatus()))
                .filter(g -> g.getHelpfulCount() > 0 || g.getUnhelpfulCount() > 0)
                .toList();
            if (grains.isEmpty()) return;

            int updated = 0;
            for (var g : grains) {
                int total = g.getHelpfulCount() + g.getUnhelpfulCount();
                if (total == 0) continue;
                double baseWeight = g.getWeight() != null ? g.getWeight() : 1.0;
                double helpfulRatio = (double) g.getHelpfulCount() / total;
                double unhelpfulRatio = (double) g.getUnhelpfulCount() / total;
                double helpfulBonus = Math.min(0.5, helpfulRatio * 0.5);
                double unhelpfulPenalty = Math.min(0.4, unhelpfulRatio * 0.3);
                double newWeight = Math.max(0.1, Math.min(2.0,
                    baseWeight * (1.0 + helpfulBonus - unhelpfulPenalty)));
                if (Math.abs(newWeight - baseWeight) > 0.01) {
                    g.setWeight(round2(newWeight));
                    grainRepository.save(g);
                    updated++;
                }
            }
            if (updated > 0) {
                log.info("颗粒权重自动调整完成: {}个颗粒更新, 共扫描{}个有反馈颗粒",
                    updated, grains.size());
            }
        } catch (Exception e) {
            log.error("颗粒权重自动调整失败", e);
        }
    }

    // ==================== P2-1: 低质颗粒自动废弃 ====================

    /**
     * 每小时检查：unhelpfulCount >= 10 且 helpfulCount < 3 的低质颗粒自动废弃。
     */
    @Transactional(rollbackFor = Exception.class)
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 * * * ?")
    public void autoDeprecateGrains() {
        try {
            var grains = grainRepository.findAll().stream()
                .filter(g -> "active".equals(g.getStatus()))
                .filter(g -> g.getUnhelpfulCount() >= 10 && g.getHelpfulCount() < 3)
                .toList();
            if (grains.isEmpty()) return;
            for (var g : grains) {
                g.setStatus("deprecated");
                grainRepository.save(g);
            }
            log.info("低质颗粒自动废弃: {} 个", grains.size());
        } catch (Exception e) {
            log.error("低质颗粒自动废弃失败", e);
        }
    }

    // ==================== P2-3: qualityScore 随反馈动态更新 ====================

    /**
     * 每 30 分钟根据用户反馈重新计算 qualityScore。
     * newScore = originalScore × 0.6 + feedbackScore × 0.4
     */
    @Transactional(rollbackFor = Exception.class)
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 * * * ?")
    public void updateQualityScores() {
        try {
            var grains = grainRepository.findAll().stream()
                .filter(g -> "active".equals(g.getStatus()))
                .filter(g -> g.getQualityScore() != null)
                .filter(g -> g.getHelpfulCount() + g.getUnhelpfulCount() >= 5)
                .toList();
            if (grains.isEmpty()) return;
            int updated = 0;
            for (var g : grains) {
                double originalScore = g.getQualityScore();
                int total = g.getHelpfulCount() + g.getUnhelpfulCount();
                double feedbackScore = (double) g.getHelpfulCount() / total * 5.0;
                double newScore = Math.min(5.0, Math.max(0.0,
                    originalScore * 0.6 + feedbackScore * 0.4));
                if (Math.abs(newScore - originalScore) > 0.1) {
                    g.setQualityScore(round2(newScore));
                    grainRepository.save(g);
                    updated++;
                }
            }
            if (updated > 0) {
                log.info("qualityScore 动态更新完成: {}个颗粒", updated);
            }
        } catch (Exception e) {
            log.error("qualityScore 动态更新失败", e);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

}
