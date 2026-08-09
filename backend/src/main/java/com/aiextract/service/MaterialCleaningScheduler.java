package com.aiextract.service;

import com.aiextract.common.TraceContext;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 素材处理调度器（解析 → 清洗 → 向量化）
 *
 * <p>多节点安全：乐观锁 + Redis skill 锁保证同一人的素材串行处理。
 * 解析和清洗分别使用独立线程池，解析可适度并发，清洗少而精。</p>
  * @author AI Extract Team
 */
@Slf4j
@Component
public class MaterialCleaningScheduler {

    private final MaterialCleaningService cleaningService;
    private final SkillMaterialRepository materialRepository;
    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final DashScopeEmbeddingService embeddingService;
    private final ReportGenerationService reportGenerationService;
    private final StringRedisTemplate redisTemplate;

    private final Executor parseExecutor;
    private final Executor cleanExecutor;

    /** 自注入以支持内部 @Transactional 方法调用 */
    @Lazy @Autowired private MaterialCleaningScheduler self;

    private static final String STATUS_ANALYZED = MaterialCleaningService.STATUS_ANALYZED;
    private static final String STATUS_GENERATING = "generating";
    private static final String STATUS_REVIEWING = "reviewing";
    private static final int MAX_RETRY_COUNT = 3;
    private static final int LOCK_TIMEOUT_MINUTES = 5;
    private static final int SKILL_LOCK_TTL_SECONDS = 600;
    /** 10 分钟，足够最慢的清洗完成 */
    private final String workerId = initWorkerId();

    public MaterialCleaningScheduler(MaterialCleaningService cleaningService,
                                      SkillMaterialRepository materialRepository,
                                      SkillRepository skillRepository,
                                      ExperienceGrainRepository grainRepository,
                                      DashScopeEmbeddingService embeddingService,
                                      ReportGenerationService reportGenerationService,
                                      StringRedisTemplate redisTemplate,
                                      @Qualifier("parseExecutor") Executor parseExecutor,
                                      @Qualifier("cleanExecutor") Executor cleanExecutor) {
        this.cleaningService = cleaningService;
        this.materialRepository = materialRepository;
        this.skillRepository = skillRepository;
        this.grainRepository = grainRepository;
        this.embeddingService = embeddingService;
        this.reportGenerationService = reportGenerationService;
        this.redisTemplate = redisTemplate;
        this.parseExecutor = parseExecutor;
        this.cleanExecutor = cleanExecutor;
    }

    private String initWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + Thread.currentThread().getId();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void scanAndProcess() {
        scanAndParse();
        scanAndClean();
    }

    /** ==================== 解析阶段 ==================== */

    private void scanAndParse() {
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(LOCK_TIMEOUT_MINUTES);
        List<SkillMaterial> tasks = materialRepository.findPendingParseTasks(timeout);
        if (tasks.isEmpty()) { return; }
        log.info("发现 {} 个待解析素材", tasks.size());
        for (SkillMaterial task : tasks) {
            parseExecutor.execute(() -> self.tryParseOne(task.getId()));
        }
    }

    /** 短事务：尝试锁定素材 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean tryLockMaterial(UUID materialId) {
        return materialRepository.tryLock(materialId, workerId) > 0;
    }

    /** 短事务：释放锁 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void releaseMaterialLock(UUID materialId) {
        materialRepository.releaseLock(materialId);
    }

    /** 单素材解析（锁在短事务，HTTP 调用在事务外） */
    public void tryParseOne(UUID materialId) {
        if (!self.tryLockMaterial(materialId)) { return; }
        TraceContext.init(materialId);
        try {
            cleaningService.parseFile(materialId);
        } catch (Exception e) {
            log.error("文件解析失败, materialId: {}", materialId, e);
            self.incrementRetryOrReject(materialId, "解析失败: " + e.getMessage(), MaterialCleaningService.STATUS_PARSE_FAILED);
        } finally {
            self.releaseMaterialLock(materialId);
            TraceContext.clear();
        }
    }

    /** ==================== 清洗阶段 ==================== */

    private void scanAndClean() {
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(LOCK_TIMEOUT_MINUTES);
        List<SkillMaterial> tasks = materialRepository.findPendingCleaningTasks(timeout);
        if (tasks.isEmpty()) { return; }
        log.info("发现 {} 个待清洗素材", tasks.size());
        for (SkillMaterial task : tasks) {
            cleanExecutor.execute(() -> self.tryCleanOne(task.getId()));
        }
    }

    /**
     * 抢 skill 级 Redis 锁。同一 skill 下同时只允许 1 个素材清洗。
     * 返回 true 表示抢到锁，可以继续。
     */
    private boolean tryAcquireSkillLock(UUID skillId) {
        String key = "skill:clean:" + skillId;
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, workerId, Duration.ofSeconds(SKILL_LOCK_TTL_SECONDS));
        return Boolean.TRUE.equals(ok);
    }

    /** 释放 skill 级锁 */
    private void releaseSkillLock(UUID skillId) {
        redisTemplate.delete("skill:clean:" + skillId);
    }

    /** 触发报告生成的最小活跃颗粒数（配置：app.report.min-grains） */
    @Value("${app.report.min-grains:10}")
    private int reportMinGrains;
    /** 触发报告生成的最小场景覆盖数（配置：app.report.min-scenes） */
    @Value("${app.report.min-scenes:3}")
    private int reportMinScenes;

    /** 标记 skill 报告为脏（仅当满足最低颗粒+场景门槛） */
    private void markReportDirty(UUID skillId) {
        skillRepository.findById(skillId).ifPresent(skill -> {
            UUID spaceId = skill.getSpaceId();
            long grainCount = grainRepository.countBySpaceIdAndStatus(spaceId, "active");
            if (grainCount < reportMinGrains) {
                log.info("报告标脏跳过(skillId={}): 活跃颗粒{}条, 需≥{}", skillId, grainCount, reportMinGrains);
                return;
            }
            long sceneCount = grainRepository.countDistinctSceneTagsBySpaceIdAndStatus(spaceId, "active");
            if (sceneCount < reportMinScenes) {
                log.info("报告标脏跳过(skillId={}): 场景覆盖{}个, 需≥{}", skillId, sceneCount, reportMinScenes);
                return;
            }
            redisTemplate.opsForSet().add("report:dirty:skills", skillId.toString());
            log.info("报告标脏(skillId={}): 颗粒{}条, 场景{}个", skillId, grainCount, sceneCount);
        });
    }

    /** 短事务：标记素材为已萃取（仅当管道正常走完即 status=analyzed，防止 rejected/failed 被覆盖） */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void markMaterialExtracted(UUID materialId) {
        materialRepository.findById(materialId).ifPresent(m -> {
            if (!STATUS_ANALYZED.equals(m.getStatus())) { return; }
            m.setStatus("extracted");
            materialRepository.save(m);
        });
    }

    /** 短事务：保存颗粒 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void saveGrains(UUID materialId, List<MaterialCleaningService.GrainCandidate> candidates) {
        SkillMaterial material = materialRepository.findById(materialId).orElseThrow();
        UUID spaceId = skillRepository.findById(material.getSkillId())
                .map(Skill::getSpaceId).orElse(null);
        if (spaceId == null || candidates.isEmpty()) { return; }
        for (MaterialCleaningService.GrainCandidate c : candidates) {
            ExperienceGrain grain = ExperienceGrain.builder()
                    .id(UUID.randomUUID()).spaceId(spaceId).sourceMaterialId(materialId)
                    .sceneTag(c.sceneTag()).sceneDescription(c.insight().sceneDescription())
                    .expertThought(c.insight().expertThought()).standardScript(c.insight().standardScript())
                    .commonMistakes(c.insight().commonMistakes()).applicableCondition(c.insight().applicableCondition())
                    .qualityScore(c.qualityScore()).difficultyLevel(c.difficultyLevel())
                    .verificationNotes(c.verificationNotes()).status("active")
                    .helpfulCount(0).unhelpfulCount(0)
                    .weight(c.qualityScore() != null
                        ? Math.max(0.1, Math.min(2.0, c.qualityScore() / 5.0 * 2.0))
                        : null) // P0-4: weight 根据 qualityScore 初始化
                    .createdAt(LocalDateTime.now())
                    .build();
            grainRepository.save(grain);
        }
    }

    /**
     * 统一素材处理管道：清洗 → 颗粒落库 → 向量化 → 终态标记 → skill 状态推进 → 报告标脏。
     *
     * <p>无锁、无事务：AI 调用在事务外，锁由调用方负责（调度器抢锁后调用；
     * 访谈素材由 InterviewTranscriptExtractor 独占驱动，扫描已排除，无竞争无需锁）。
     * 异常向上抛，由调用方决定失败语义（调度器计重试，访谈路径标 failed）。</p>
     *
     * @return 本次产出的颗粒候选数
     */
    public int processMaterial(UUID materialId) {
        List<MaterialCleaningService.GrainCandidate> candidates = cleaningService.clean(materialId);

        if (!candidates.isEmpty()) {
            self.saveGrains(materialId, candidates);
            embedGrains(materialId, candidates);
            // P2-2: 嵌入后语义去重检查（委托 Service 执行数据操作）
            cleaningService.deduplicateByEmbedding(materialId);
        }

        self.markMaterialExtracted(materialId);

        if (!candidates.isEmpty()) {
            materialRepository.findById(materialId).ifPresent(material -> {
                UUID skillId = material.getSkillId();
                skillRepository.findById(skillId).ifPresent(skill -> {
                    if (STATUS_GENERATING.equals(skill.getStatus())) {
                        skill.setStatus(STATUS_REVIEWING);
                        skillRepository.save(skill);
                        log.info("Skill 状态已更新, skillId: {}, generating→reviewing", skill.getId());
                    }
                });
                log.info("清洗完成, materialId: {}, 生成颗粒: {}", materialId, candidates.size());
                /** 标记报告脏，由独立定时器合并生成 */
                markReportDirty(skillId);
            });
        }
        return candidates.size();
    }

    /** 批量向量化素材颗粒（一次 embedding API 调用；失败不阻断萃取，仅告警） */
    public void embedGrains(UUID materialId, List<MaterialCleaningService.GrainCandidate> candidates) {
        try {
            List<ExperienceGrain> freshGrains = grainRepository.findBySourceMaterialId(materialId);
            // 预建 sceneTag→grain 映射，O(1) 查找替代 N+1 stream
            Map<String, ExperienceGrain> byTag = new LinkedHashMap<>();
            for (ExperienceGrain g : freshGrains) {
                String tag = g.getSceneTag();
                if (byTag.containsKey(tag)) {
                    log.warn("同场景多颗粒 tag={} grainId={} 只embed第一条", tag, g.getId());
                }
                byTag.putIfAbsent(tag, g);
            }
            List<ExperienceGrain> toEmbed = new java.util.ArrayList<>();
            List<String> texts = new java.util.ArrayList<>();
            for (MaterialCleaningService.GrainCandidate c : candidates) {
                ExperienceGrain grain = byTag.get(c.sceneTag());
                if (grain == null) continue;
                toEmbed.add(grain);
                texts.add(embeddingService.grainToText(grain));
            }
            if (!toEmbed.isEmpty()) {
                List<float[]> vectors = embeddingService.embedBatch(texts);
                embeddingService.saveEmbeddings(toEmbed, vectors);
                log.info("颗粒向量化完成, materialId: {}, embedded: {}/{}",
                        materialId, toEmbed.size(), candidates.size());
            }
        } catch (Exception e) {
            log.warn("批量向量生成失败, materialId: {}, grainCount: {}, reason: {}",
                    materialId, candidates.size(), e.getMessage());
        }
    }

    /** 单素材清洗（material 锁 + skill 锁，业务管道统一走 processMaterial） */
    public void tryCleanOne(UUID materialId) {
        /** 1. material 级乐观锁（多节点互斥） */
        if (!self.tryLockMaterial(materialId)) { return; }

        SkillMaterial material = materialRepository.findById(materialId).orElse(null);
        if (material == null) {

            return;

        }
        UUID skillId = material.getSkillId();

        /** 2. skill 级 Redis 锁（同一人的素材串行） */
        if (!tryAcquireSkillLock(skillId)) {
            self.releaseMaterialLock(materialId);
            return;
        }

        TraceContext.init(materialId);
        try {
            processMaterial(materialId);
        } catch (Exception e) {
            log.error("素材清洗失败, materialId: {}", materialId, e);
            self.incrementRetryOrReject(materialId, "清洗失败: " + e.getMessage(), MaterialCleaningService.STATUS_CLEANING_FAILED);
        } finally {
            releaseSkillLock(skillId);
            self.releaseMaterialLock(materialId);
            TraceContext.clear();
        }
    }

    /** ==================== 重试机制 ==================== */

    /** 短事务：递加重试计数，达上限则标记 rejected，否则设为 retryStatus */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void incrementRetryOrReject(UUID materialId, String reason, String retryStatus) {
        materialRepository.findById(materialId).ifPresent(m -> {
            int count = m.getRetryCount() != null ? m.getRetryCount() + 1 : 1;
            m.setRetryCount(count);
            if (count >= MAX_RETRY_COUNT) {
                m.setStatus(MaterialCleaningService.STATUS_REJECTED);
                m.setAnalysisNotes((m.getAnalysisNotes() != null ? m.getAnalysisNotes() + "; " : "") + "已达最大重试次数(" + MAX_RETRY_COUNT + "): " + reason);
                log.warn("素材已达最大重试次数, materialId: {}, reason: {}", materialId, reason);
            } else {
                m.setStatus(retryStatus);
                m.setAnalysisNotes(reason);
            }
            materialRepository.save(m);
        });
    }

    /** 短事务：重置重试计数（管理员手动重试） */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void resetRetryCount(UUID materialId) {
        materialRepository.findById(materialId).ifPresent(m -> {
            m.setRetryCount(0);
            m.setStatus("uploaded");
            m.setAnalysisNotes("管理员手动重试");
            materialRepository.save(m);
        });
    }

    /** ==================== 报告防抖 ==================== */

    /** 每 2 分钟扫脏 skill 列表，批量生成报告 */
    @Scheduled(fixedDelay = 120_000)
    public void generateDirtyReports() {
        String key = "report:dirty:skills";

        /** 补偿扫描：查所有 reviewing 状态有颗粒无报告的 skill */
        try {
            List<UUID> staleSkillIds = skillRepository.findReviewingSkillsMissingReport();
            for (UUID skillId : staleSkillIds) {
                redisTemplate.opsForSet().add(key, skillId.toString());
            }
            if (!staleSkillIds.isEmpty()) {
                log.info("补偿扫描: 发现 {} 个 skill 报告需要更新", staleSkillIds.size());
            }
        } catch (Exception e) {
            log.warn("补偿扫描异常: {}", e.getMessage());
        }

        while (true) {
            String skillId = redisTemplate.opsForSet().pop(key);
            if (skillId == null) {

                break;

            }
            try {
                log.info("合并生成报告, skillId: {}", skillId);
                reportGenerationService.generateAsync(UUID.fromString(skillId));
            } catch (Exception e) {
                log.warn("报告生成失败, skillId: {}", skillId, e.getMessage());
            }
        }
    }

    /** ==================== util ==================== */

}
