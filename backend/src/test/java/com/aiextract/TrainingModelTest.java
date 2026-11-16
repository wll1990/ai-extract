package com.aiextract;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCertification;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 培训实体不变量测试 —— 枚举校验、状态判定与 @Builder.Default。
 *
 * <p>重点不是 getter/setter，而是三处会直接写坏数据库的默认值：
 * 状态列 NOT NULL、{@code prerequisites} 是 JSONB NOT NULL、计数器非空。
 * 曾经在评测侧出现过漏 {@code @Builder.Default} 导致 INSERT 显式写 NULL 的故障，
 * 这里用断言把同类问题挡在编译后第一道。</p>
 */
class TrainingModelTest {

    @Test
    void builderDefaults_areNotNullForEveryNotNullColumn() {
        TrainingPlan plan = TrainingPlan.builder().build();
        assertEquals(TrainingPlan.STATUS_DRAFT, plan.getStatus());
        assertEquals(1, plan.getCurrentVersion());

        TrainingTask task = TrainingTask.builder().build();
        assertEquals(1, task.getPhaseNo());
        assertNotNull(task.getPrerequisites(), "prerequisites 是 JSONB NOT NULL，必须默认 []");
        assertEquals("[]", task.getPrerequisites());
        assertEquals(Boolean.TRUE, task.getIsRequired());
        assertEquals(Boolean.TRUE, task.getAllowHints());
        assertEquals(1, task.getMaxAttempts());
        assertEquals(TrainingTask.DEADLINE_AUTO_SUBMIT, task.getDeadlinePolicy());

        TrainingAssignment assignment = TrainingAssignment.builder().build();
        assertEquals(TrainingAssignment.STATUS_NOT_STARTED, assignment.getStatus());
        assertEquals(0, assignment.getTotalTasks());
        assertEquals(0, assignment.getRequiredTotal());
        assertEquals(0, assignment.getCompletedTasks());
        assertEquals(0, assignment.getRequiredPassed());
        assertEquals(0, assignment.getPassedTasks());

        TrainingTaskRecord record = TrainingTaskRecord.builder().build();
        assertEquals(TrainingTaskRecord.STATUS_LOCKED, record.getStatus());

        TrainingAttempt attempt = TrainingAttempt.builder().build();
        assertEquals(TrainingAttempt.STATUS_IN_PROGRESS, attempt.getStatus());
        assertEquals(1, attempt.getAttemptNo());
        assertEquals(0, attempt.getGradingAttempts());

        TrainingCohort cohort = TrainingCohort.builder().build();
        assertEquals(TrainingCohort.STATUS_ACTIVE, cohort.getStatus());

        TrainingCertification cert = TrainingCertification.builder().build();
        assertEquals(TrainingCertification.STATUS_PENDING, cert.getStatus());
    }

    @Test
    void attempt_claimableCoversSubmittedAndGrading() {
        // 只覆盖 submitted 会让「置 grading 后进程崩溃」的行永久领不回来
        assertTrue(attempt(TrainingAttempt.STATUS_SUBMITTED).isClaimable());
        assertTrue(attempt(TrainingAttempt.STATUS_GRADING).isClaimable());
        assertFalse(attempt(TrainingAttempt.STATUS_IN_PROGRESS).isClaimable());
        assertFalse(attempt(TrainingAttempt.STATUS_FINALIZED).isClaimable());
        assertFalse(attempt(TrainingAttempt.STATUS_ERROR).isClaimable());
    }

    @Test
    void attempt_answerEditableOnlyInProgress() {
        assertTrue(attempt(TrainingAttempt.STATUS_IN_PROGRESS).isAnswerEditable());
        assertFalse(attempt(TrainingAttempt.STATUS_SUBMITTED).isAnswerEditable());
        assertFalse(attempt(TrainingAttempt.STATUS_FINALIZED).isAnswerEditable());
    }

    @Test
    void taskRecord_terminalDrivesCounterDeltas() {
        assertFalse(record(TrainingTaskRecord.STATUS_LOCKED).isTerminal());
        assertFalse(record(TrainingTaskRecord.STATUS_AVAILABLE).isTerminal());
        assertFalse(record(TrainingTaskRecord.STATUS_IN_PROGRESS).isTerminal());
        assertFalse(record(TrainingTaskRecord.STATUS_PENDING_REVIEW).isTerminal());
        assertTrue(record(TrainingTaskRecord.STATUS_PASSED).isTerminal());
        assertTrue(record(TrainingTaskRecord.STATUS_FAILED).isTerminal());
        assertTrue(record(TrainingTaskRecord.STATUS_WAIVED).isTerminal());
    }

    @Test
    void assignment_requiredAllPassedNeedsNonZeroTotal() {
        TrainingAssignment none = TrainingAssignment.builder()
                .requiredTotal(0).requiredPassed(0).build();
        assertFalse(none.isRequiredAllPassed(), "没有必修任务时不能误判为全部通过");

        TrainingAssignment partial = TrainingAssignment.builder()
                .requiredTotal(3).requiredPassed(2).build();
        assertFalse(partial.isRequiredAllPassed());

        TrainingAssignment done = TrainingAssignment.builder()
                .requiredTotal(3).requiredPassed(3).build();
        assertTrue(done.isRequiredAllPassed());
    }

    @Test
    void enumGuards_rejectUnknownValues() {
        assertTrue(TrainingPlan.isValidStatus("published"));
        assertFalse(TrainingPlan.isValidStatus("done"));

        assertTrue(TrainingTask.isValidType("exam"));
        assertFalse(TrainingTask.isValidType("video"));
        assertTrue(TrainingTask.isDeadlinePolicy("timeout_fail"));
        assertFalse(TrainingTask.isDeadlinePolicy("ignore"));

        assertTrue(TrainingAttempt.isValidStatus("error"));
        assertFalse(TrainingAttempt.isValidStatus("completed"));
        assertTrue(TrainingAttempt.isValidResult("undetermined"));
        assertFalse(TrainingAttempt.isValidResult("partial"));

        assertTrue(TrainingTaskRecord.isValidStatus("waived"));
        assertFalse(TrainingTaskRecord.isValidStatus("skipped"));

        assertTrue(TrainingCohort.isValidStatus("completed"));
        assertFalse(TrainingCohort.isValidStatus("draft"));

        assertTrue(TrainingAssignment.isValidStatus("pending_certification"));
        assertFalse(TrainingAssignment.isValidStatus("passed"));
    }

    @Test
    void examTaskRequiresNoHintsByConvention() {
        TrainingTask exam = TrainingTask.builder()
                .type(TrainingTask.TYPE_EXAM).allowHints(false)
                .examDatasetVersion(1).examDurationMinutes(60).build();
        assertTrue(exam.isExam());
        assertFalse(exam.isPractice());
        assertFalse(exam.isReading());
        assertFalse(exam.getAllowHints(), "考试任务不得允许提示（DDL 亦有 CHECK）");
    }

    // ==================== 辅助 ====================

    private TrainingAttempt attempt(String status) {
        return TrainingAttempt.builder().status(status).build();
    }

    private TrainingTaskRecord record(String status) {
        return TrainingTaskRecord.builder().status(status).build();
    }
}
