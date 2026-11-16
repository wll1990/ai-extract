package com.aiextract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 培训 schema 的真库约束测试。
 *
 * <p>不使用 Testcontainers（当前环境 Docker 不可用），改为连接本机独立库：
 * 先执行 <code>bash scripts/test-db-setup.sh</code>，再设置
 * <code>TEST_PG_URL / TEST_PG_USER / TEST_PG_PASSWORD</code> 运行本类。
 * 未设置 TEST_PG_URL 时整类自动跳过，默认测试不受影响。</p>
 *
 * <p>每个用例在独立事务中执行并回滚，不污染测试库。</p>
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
class TrainingSchemaDbTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TEST_PG_URL");
        String user = System.getenv("TEST_PG_USER");
        String password = System.getenv("TEST_PG_PASSWORD");
        connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
    }

    // ==================== 补训轮次 ====================

    @Test
    void roundUnique_allowsTwoRoundsRejectsDuplicate() throws Exception {
        UUID assignmentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        insertRecord(assignmentId, taskId, 1, null, null);
        insertRecord(assignmentId, taskId, 2, UUID.randomUUID(), "补训：异议处理不达标");

        SQLException duplicate = assertThrows(SQLException.class,
                () -> insertRecord(assignmentId, taskId, 2, UUID.randomUUID(), "重复轮次"));
        assertEquals("23505", duplicate.getSQLState(), "同一分配+任务+轮次必须唯一");
    }

    @Test
    void remeidationRound_requiresSourceAndReason() {
        UUID assignmentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SQLException noSource = assertThrows(SQLException.class,
                () -> insertRecord(assignmentId, taskId, 2, null, "缺来源"));
        assertEquals("23514", noSource.getSQLState());

        rollbackQuietly();

        SQLException noReason = assertThrows(SQLException.class,
                () -> insertRecord(assignmentId, taskId, 2, UUID.randomUUID(), "  "));
        assertEquals("23514", noReason.getSQLState());
    }

    // ==================== 认证：部分唯一 + 撤销 ====================

    @Test
    void certification_onlyOneConfirmedButHistoryAllowed() throws Exception {
        UUID assignmentId = UUID.randomUUID();
        insertCertification(assignmentId, "confirmed");
        SQLException duplicateConfirmed = assertThrows(SQLException.class,
                () -> insertCertification(assignmentId, "confirmed"));
        assertEquals("23505", duplicateConfirmed.getSQLState());

        // 约束冲突后事务已中止：回滚重来，验证「撤销 → 重新认证」的历史形态
        rollbackQuietly();
        UUID first = insertCertification(assignmentId, "confirmed");
        revokeCertification(first);
        insertCertification(assignmentId, "confirmed");

        assertEquals(2, countCertifications(assignmentId), "一条撤销历史 + 一条新确认");
        assertEquals(1, countConfirmed(assignmentId), "同一时刻只能有一条生效认证");
    }

    @Test
    void revokedCertification_requiresReasonAndOperator() {
        UUID assignmentId = UUID.randomUUID();
        SQLException noFields = assertThrows(SQLException.class,
                () -> insertCertificationWithRevocation(assignmentId, "revoked", false));
        assertEquals("23514", noFields.getSQLState(), "revoked 必须有撤销人和理由");
    }

    // ==================== 计数与站内信 ====================

    @Test
    void counters_cannotExceedCompleted() {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO training_assignment(id, company_id, cohort_id, learner_id, status,"
                            + " total_tasks, required_total, completed_tasks, required_passed, passed_tasks)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.randomUUID());
                ps.setObject(3, UUID.randomUUID());
                ps.setObject(4, UUID.randomUUID());
                ps.setString(5, "in_progress");
                ps.setInt(6, 1);
                ps.setInt(7, 1);
                ps.setInt(8, 0);
                ps.setInt(9, 0);
                ps.setInt(10, 1); // passed > completed
                ps.executeUpdate();
            }
        });
        assertEquals("23514", ex.getSQLState());
    }

    @Test
    void notification_dedupeKeyIsUnique() throws Exception {
        String dedupe = "training:test:" + UUID.randomUUID();
        insertNotification(dedupe);
        SQLException ex = assertThrows(SQLException.class, () -> insertNotification(dedupe));
        assertEquals("23505", ex.getSQLState());
    }

    // ==================== 导出领取 ====================

    @Test
    void exportJob_skipLockedGivesOnlyOneClaimer() throws Exception {
        UUID jobId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO export_job(id, company_id, source_type, source_id, status)"
                        + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, jobId);
            ps.setObject(2, UUID.randomUUID());
            ps.setString(3, "training_cohort");
            ps.setObject(4, UUID.randomUUID());
            ps.setString(5, "running");
            ps.executeUpdate();
        }

        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT id FROM export_job WHERE status='running' AND id=? FOR UPDATE SKIP LOCKED")) {
            lock.setObject(1, jobId);
            try (ResultSet rs = lock.executeQuery()) {
                assertTrue(rs.next(), "第一个事务应能锁到任务");
            }
        }

        try (Connection second = DriverManager.getConnection(
                System.getenv("TEST_PG_URL"),
                System.getenv("TEST_PG_USER"),
                System.getenv("TEST_PG_PASSWORD"))) {
            second.setAutoCommit(false);
            try (PreparedStatement lock = second.prepareStatement(
                    "SELECT id FROM export_job WHERE status='running' AND id=? FOR UPDATE SKIP LOCKED")) {
                lock.setObject(1, jobId);
                try (ResultSet rs = lock.executeQuery()) {
                    assertTrue(!rs.next(), "被锁定的任务不能被第二个事务领取");
                }
            }
            second.rollback();
        }
    }

    // ==================== 索引 ====================

    @Test
    void p3IndexesExist() throws Exception {
        String[] indexes = {
                "uq_ttr_assignment_task_round", "idx_ttr_task_round", "idx_ttr_remediation_of",
                "uq_tcf_assignment_confirmed", "idx_tcf_assignment_history",
                "idx_ta_cohort_created", "uq_un_dedupe", "idx_un_user_unread",
                "idx_ej_claim", "idx_tat_pending_review_age"
        };
        for (String index : indexes) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname=?")) {
                ps.setString(1, index);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "缺少索引 " + index);
                }
            }
        }
    }

    // ==================== helpers ====================

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // ignore
        }
    }

    private void insertRecord(UUID assignmentId, UUID taskId, int roundNo,
                              UUID remediationOf, String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO training_task_record(id, assignment_id, task_id, status,"
                        + " round_no, remediation_of_record_id, remediation_reason, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, assignmentId);
            ps.setObject(3, taskId);
            ps.setString(4, "available");
            ps.setInt(5, roundNo);
            ps.setObject(6, remediationOf);
            ps.setString(7, reason);
            ps.setTimestamp(8, now);
            ps.setTimestamp(9, now);
            ps.executeUpdate();
        }
    }

    private UUID insertCertification(UUID assignmentId, String status) throws SQLException {
        return insertCertificationWithRevocation(assignmentId, status, true);
    }

    private UUID insertCertificationWithRevocation(UUID assignmentId, String status,
                                                    boolean withRevocationFields) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO training_certification(id, company_id, assignment_id, learner_id, status,"
                        + " confirmed_by, confirmed_at, revoked_by, revoked_at, revoke_reason, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            boolean confirmed = "confirmed".equals(status);
            boolean revoked = "revoked".equals(status) && withRevocationFields;
            ps.setObject(1, id);
            ps.setObject(2, UUID.randomUUID());
            ps.setObject(3, assignmentId);
            ps.setObject(4, UUID.randomUUID());
            ps.setString(5, status);
            ps.setObject(6, confirmed ? UUID.randomUUID() : null);
            ps.setTimestamp(7, confirmed ? now : null);
            ps.setObject(8, revoked ? UUID.randomUUID() : null);
            ps.setTimestamp(9, revoked ? now : null);
            ps.setString(10, revoked ? "认证后发现合规项未掌握" : null);
            ps.setTimestamp(11, now);
            ps.setTimestamp(12, now);
            ps.executeUpdate();
        }
        return id;
    }

    private void revokeCertification(UUID certificationId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE training_certification SET status='revoked', revoked_by=?, revoked_at=now(),"
                        + " revoke_reason=? WHERE id=?")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, "认证后发现合规项未掌握");
            ps.setObject(3, certificationId);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private int countCertifications(UUID assignmentId) throws SQLException {
        return countCertificationsByStatus(assignmentId, null);
    }

    private int countConfirmed(UUID assignmentId) throws SQLException {
        return countCertificationsByStatus(assignmentId, "confirmed");
    }

    private int countCertificationsByStatus(UUID assignmentId, String status) throws SQLException {
        String sql = "SELECT count(*) FROM training_certification WHERE assignment_id=?"
                + (status == null ? "" : " AND status=?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, assignmentId);
            if (status != null) {
                ps.setString(2, status);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertNotification(String dedupeKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO user_notification(id, company_id, user_id, type, title, dedupe_key, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, UUID.randomUUID());
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, "training_pending_review");
            ps.setString(5, "有待复核尝试");
            ps.setString(6, dedupeKey);
            ps.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    }
}
