package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 培训任务实体 —— 计划版本内的一个任务，按阶段（phase）分组。
 *
 * <p>三种类型：reading（阅读知识/案例）、practice（分身陪练）、exam（文本考试）。
 * 类型与资源的对应关系由数据库 CHECK 兜底：阅读必须有知识/案例资源，陪练必须绑分身，
 * 考试必须有题集版本、考试时长，且禁止提示。</p>
 *
 * <p>JSONB 字段（String 存 JSON）：`prerequisites` 前置任务 id 数组；
 * `pass_criteria` 通过条件，形如 `{"minScore":80,"minPointCoverage":0.7}`。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingTask {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属计划 ID */
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** 所属计划版本 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 阶段序号（从 1 开始） */
    @Column(name = "phase_no", nullable = false)
    @Builder.Default
    private Integer phaseNo = 1;

    /** 阶段名（如「第一周：产品知识」） */
    @Column(name = "phase_name", length = 100)
    private String phaseName;

    /** 类型：reading/practice/exam */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    /** 任务标题 */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 任务说明 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 是否必修（认证门槛：必修全过才可认证） */
    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean isRequired = true;

    /** 阶段内顺序 */
    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    /** 前置任务 id 数组（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prerequisites", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private String prerequisites = EMPTY_PREREQUISITES;

    /** 资源类型：knowledge/case/skill */
    @Column(name = "resource_type", length = 20)
    private String resourceType;

    /** 资源 ID（practice 时=分身 skill_id） */
    @Column(name = "resource_id")
    private UUID resourceId;

    /** 陪练场景标签 */
    @Column(name = "scene_tag", length = 50)
    private String sceneTag;

    /** 是否允许提示（考试必须 false，服务端拒绝提示） */
    @Column(name = "allow_hints", nullable = false)
    @Builder.Default
    private Boolean allowHints = true;

    /** 考试题集 ID */
    @Column(name = "exam_dataset_id")
    private UUID examDatasetId;

    /** 考试题集版本（冻结） */
    @Column(name = "exam_dataset_version")
    private Integer examDatasetVersion;

    /** 抽题数；NULL=全量 */
    @Column(name = "exam_item_count")
    private Integer examItemCount;

    /** 考试时长（分钟），exam 必填 */
    @Column(name = "exam_duration_minutes")
    private Integer examDurationMinutes;

    /** 通过条件（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pass_criteria", columnDefinition = "JSONB")
    private String passCriteria;

    /** 总尝试次数（含首次） */
    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 1;

    /** 逾期策略：auto_submit/timeout_fail */
    @Column(name = "deadline_policy", nullable = false, length = 20)
    @Builder.Default
    private String deadlinePolicy = DEADLINE_AUTO_SUBMIT;

    /** 发布时冻结的阅读内容（JSONB），保证资源后续下线不影响已分配培训 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_snapshot", columnDefinition = "jsonb")
    private String resourceSnapshot;

    @Column(name = "snapshot_version")
    private Integer snapshotVersion;

    @Column(name = "snapshot_at")
    private LocalDateTime snapshotAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 类型常量 ==========

    public static final String TYPE_READING = "reading";
    public static final String TYPE_PRACTICE = "practice";
    public static final String TYPE_EXAM = "exam";
    /** 场景微练：AI 根据知识/案例生成的 1-3 问场景卡，文本作答后即时评分 */
    public static final String TYPE_DRILL = "drill";

    private static final Set<String> TYPES = Set.of(
            TYPE_READING, TYPE_PRACTICE, TYPE_EXAM, TYPE_DRILL);

    // ========== 资源类型常量 ==========

    public static final String RESOURCE_KNOWLEDGE = "knowledge";
    public static final String RESOURCE_CASE = "case";
    public static final String RESOURCE_SKILL = "skill";
    /** 考试题集：resourceId=examDatasetId，resourceVersion=examDatasetVersion */
    public static final String RESOURCE_EXAM_DATASET = "exam_dataset";

    // ========== 逾期策略常量 ==========

    public static final String DEADLINE_AUTO_SUBMIT = "auto_submit";
    public static final String DEADLINE_TIMEOUT_FAIL = "timeout_fail";

    private static final Set<String> DEADLINE_POLICIES = Set.of(DEADLINE_AUTO_SUBMIT, DEADLINE_TIMEOUT_FAIL);

    /** 空前置数组的字面量，与 DDL 默认值保持一致 */
    public static final String EMPTY_PREREQUISITES = "[]";

    /** 单场考试题数上限：评分按题串行调用模型，题数失控会拖垮 worker 与成本 */
    public static final int MAX_EXAM_ITEMS = 100;

    /** 判断任务类型是否合法。 */
    public static boolean isValidType(String type) {
        return type != null && TYPES.contains(type);
    }

    /** 判断逾期策略是否合法。 */
    public static boolean isDeadlinePolicy(String policy) {
        return policy != null && DEADLINE_POLICIES.contains(policy);
    }

    /** 考试型任务 */
    public boolean isExam() {
        return TYPE_EXAM.equals(type);
    }

    /** 陪练型任务 */
    public boolean isPractice() {
        return TYPE_PRACTICE.equals(type);
    }

    /** 阅读型任务 */
    public boolean isReading() {
        return TYPE_READING.equals(type);
    }

    /** 场景微练 */
    public boolean isDrill() {
        return TYPE_DRILL.equals(type);
    }

    /** 需要作答并参与 AI 评分（考试/微练） */
    public boolean isGradedText() {
        return isExam() || isDrill();
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
