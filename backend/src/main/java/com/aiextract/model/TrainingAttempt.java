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
 * 培训尝试实体 —— 一次陪练或一次考试提交。
 *
 * <p>执行态与业务结论严格分离：{@code status} 只表示流程走到哪，
 * {@code result} 才表示通过/未通过/未判定，人工复核可改后者、不能改前者。</p>
 *
 * <p>状态机：in_progress → submitted → grading → finalized / pending_review → finalized；
 * 评分失败落 {@link #STATUS_ERROR}（必须带 errorMessage），可重试但不能无限重试
 * （{@code gradingAttempts} 计数）。租约三字段与 evaluation_run_item 同语义，
 * 领取必须同时覆盖 submitted 与 grading，否则「置 grading 后进程崩溃」会永久卡死。</p>
 *
 * <p>JSONB 字段（String 存 JSON）：`questionSnapshot` 考试题面 + 金标（禁止下发学员）；
 * `answers` 答题草稿/最终答案；`score` 评分明细（含 confidence/needReview）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_attempt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingAttempt {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分配 ID */
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    /** 任务 ID */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** 对应进度行 ID */
    @Column(name = "task_record_id", nullable = false)
    private UUID taskRecordId;

    /** 类型：practice/exam */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    /** 第几次尝试（重考建新行，旧证据不覆盖） */
    @Column(name = "attempt_no", nullable = false)
    @Builder.Default
    private Integer attemptNo = 1;

    /** 状态：in_progress/submitted/grading/pending_review/finalized/error */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_IN_PROGRESS;

    /** 业务结论：pass/fail/undetermined（独立字段） */
    @Column(name = "result", length = 20)
    private String result;

    /** 陪练绑定的分身 ID */
    @Column(name = "skill_id")
    private UUID skillId;

    /** 陪练对话 ID（复用 skill_conversation） */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /** 考试冻结题面（含金标，禁止下发学员） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_snapshot", columnDefinition = "JSONB")
    private String questionSnapshot;

    /** 答题草稿/最终答案 {"<itemId>":"<text>"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", columnDefinition = "JSONB")
    private String answers;

    /** 评分结果（含 confidence/needReview） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score", columnDefinition = "JSONB")
    private String score;

    /** 评分失败原因（status=error 必填） */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 系统终止原因：auto_submit / timeout_fail / system_error（正常提交为 null） */
    @Column(name = "termination_reason", length = 20)
    private String terminationReason;

    /** 已领取评分次数（防无限重试） */
    @Column(name = "grading_attempts", nullable = false)
    @Builder.Default
    private Integer gradingAttempts = 0;

    /** 开始时间 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 提交时间 */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** 服务端判定截止时间 = min(开始+时长, 分配截止) */
    @Column(name = "deadline")
    private LocalDateTime deadline;

    /** 结束时间 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 评分租约持有者 */
    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    /** 租约到期时间 */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    /** 心跳时间 */
    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 类型常量 ==========

    public static final String TYPE_PRACTICE = "practice";
    public static final String TYPE_EXAM = "exam";
    public static final String TYPE_DRILL = "drill";

    // ========== 状态常量 ==========

    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_GRADING = "grading";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_FINALIZED = "finalized";
    public static final String STATUS_ERROR = "error";

    // ========== 结论常量 ==========

    public static final String RESULT_PASS = "pass";
    public static final String RESULT_FAIL = "fail";
    public static final String RESULT_UNDETERMINED = "undetermined";

    // ========== 系统终止原因 ==========

    public static final String TERMINATION_AUTO_SUBMIT = "auto_submit";
    public static final String TERMINATION_TIMEOUT_FAIL = "timeout_fail";
    public static final String TERMINATION_SYSTEM_ERROR = "system_error";
    public static final String TERMINATION_ASSIGNMENT_CLOSED = "assignment_closed";

    private static final Set<String> STATUSES = Set.of(
            STATUS_IN_PROGRESS, STATUS_SUBMITTED, STATUS_GRADING,
            STATUS_PENDING_REVIEW, STATUS_FINALIZED, STATUS_ERROR);

    private static final Set<String> RESULTS = Set.of(
            RESULT_PASS, RESULT_FAIL, RESULT_UNDETERMINED);

    /** 判断状态是否合法。 */
    public static boolean isValidStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /** 判断结论是否合法。 */
    public static boolean isValidResult(String result) {
        return result != null && RESULTS.contains(result);
    }

    /** 是否已出结论（不能再改答案） */
    public boolean isTerminal() {
        return STATUS_FINALIZED.equals(status);
    }

    /** 是否允许继续作答（仅进行中） */
    public boolean isAnswerEditable() {
        return STATUS_IN_PROGRESS.equals(status);
    }

    /** 是否可被评分 worker 领取（两种态都要覆盖） */
    public boolean isClaimable() {
        return STATUS_SUBMITTED.equals(status) || STATUS_GRADING.equals(status);
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
