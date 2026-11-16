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
 * 销售复盘 schema 的真库约束测试。
 *
 * <p>先执行 <code>bash scripts/test-db-setup.sh</code>，再设置 TEST_PG_URL 运行；
 * 未设置时整类跳过。每个用例在独立事务中执行并回滚。</p>
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
class SalesSchemaDbTest {

    private Connection connection;
    private UUID companyId;
    private UUID sessionId;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TEST_PG_URL");
        String user = System.getenv("TEST_PG_USER");
        String password = System.getenv("TEST_PG_PASSWORD");
        connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);
        companyId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        insertSession(sessionId, companyId, 10);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
    }

    @Test
    void reportActiveUnique_blocksTwoQueuedSameSession() throws Exception {
        insertReport(sessionId, companyId, 1, "queued");

        SQLException duplicate = assertThrows(SQLException.class,
                () -> insertReport(sessionId, companyId, 2, "queued"));
        assertEquals("23505", duplicate.getSQLState(), "同一会话最多一个在途报告");
    }

    @Test
    void reportActiveUnique_allowsNewAfterReady() throws Exception {
        UUID first = insertReport(sessionId, companyId, 1, "queued");
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE review_report SET status='ready' WHERE id=?")) {
            ps.setObject(1, first);
            ps.executeUpdate();
        }
        insertReport(sessionId, companyId, 2, "queued");
        assertTrue(true);
    }

    @Test
    void companyAiConfig_uniqueCompanyProvider() throws Exception {
        insertCompanyConfig(companyId);

        SQLException duplicate = assertThrows(SQLException.class,
                () -> insertCompanyConfig(companyId));
        assertEquals("23505", duplicate.getSQLState());
    }

    @Test
    void segment_uniqueSessionSeq() throws Exception {
        insertSegment(sessionId, 1);

        SQLException duplicate = assertThrows(SQLException.class,
                () -> insertSegment(sessionId, 1));
        assertEquals("23505", duplicate.getSQLState());
    }

    @Test
    void transcriptChars_overLimitRejected() {
        SQLException e = assertThrows(SQLException.class,
                () -> insertSession(UUID.randomUUID(), companyId, 30001));
        assertEquals("23514", e.getSQLState(), "transcript_chars 必须 1..30000");
    }

    @Test
    void leadDedupeIndexesExist() throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT indexname FROM pg_indexes WHERE tablename='lead'")) {
            ResultSet rs = ps.executeQuery();
            boolean name = false;
            boolean phone = false;
            boolean sourceReview = false;
            while (rs.next()) {
                String idx = rs.getString(1);
                name |= "idx_lead_dedupe_name".equals(idx);
                phone |= "idx_lead_dedupe_phone".equals(idx);
                sourceReview |= "uq_lead_source_review".equals(idx);
            }
            assertTrue(name && phone, "线索查重索引必须存在");
            assertTrue(sourceReview, "一个复盘只能转出一条线索");
        }
    }

    @Test
    void lead_sourceReview_isUnique() throws Exception {
        UUID session = UUID.randomUUID();
        insertLeadFromReview(session);
        SQLException duplicate = assertThrows(SQLException.class,
                () -> insertLeadFromReview(session));
        assertEquals("23505", duplicate.getSQLState(), "同一复盘不能转出两条线索");
    }

    @Test
    void leadContact_onlyOnePrimaryPerLead() throws Exception {
        UUID leadId = insertLead();
        insertContact(leadId, true);

        SQLException duplicate = assertThrows(SQLException.class,
                () -> insertContact(leadId, true));
        assertEquals("23505", duplicate.getSQLState(), "一个线索最多一个主联系人");
    }

    @Test
    void leadActivity_rejectsUnknownType() throws Exception {
        UUID leadId = insertLead();
        SQLException e = assertThrows(SQLException.class,
                () -> insertActivity(leadId, "unknown_type"));
        assertEquals("23514", e.getSQLState());
    }

    @Test
    void taskReminderIndexExists() throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT indexname FROM pg_indexes WHERE tablename='follow_up_task'")) {
            ResultSet rs = ps.executeQuery();
            boolean pendingDue = false;
            while (rs.next()) {
                pendingDue |= "idx_task_pending_due".equals(rs.getString(1));
            }
            assertTrue(pendingDue, "任务到期扫描索引必须存在");
        }
    }

    private void insertLeadFromReview(UUID sourceReviewId) throws SQLException {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lead
                  (id, company_id, owner_id, name, name_normalized, source, source_review_id,
                   status, created_by, created_at, updated_at, last_activity_at)
                VALUES (?, ?, ?, '测试线索', '测试线索', 'review', ?, 'new', ?, ?, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, companyId);
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, sourceReviewId);
            ps.setObject(5, UUID.randomUUID());
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            ps.setTimestamp(8, now);
            ps.executeUpdate();
        }
    }

    private UUID insertLead() throws SQLException {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lead
                  (id, company_id, owner_id, name, name_normalized, source, status,
                   created_by, created_at, updated_at, last_activity_at)
                VALUES (?, ?, ?, '测试线索', '测试线索', 'review', 'new', ?, ?, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, companyId);
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, UUID.randomUUID());
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertContact(UUID leadId, boolean primary) throws SQLException {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lead_contact
                  (id, company_id, lead_id, name, role, is_primary, created_by, created_at, updated_at)
                VALUES (?, ?, ?, '王总', 'other', ?, ?, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, companyId);
            ps.setObject(3, leadId);
            ps.setBoolean(4, primary);
            ps.setObject(5, UUID.randomUUID());
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            ps.executeUpdate();
        }
    }

    private void insertActivity(UUID leadId, String type) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lead_activity (id, company_id, lead_id, type, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, companyId);
            ps.setObject(3, leadId);
            ps.setString(4, type);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    }

    private void insertSession(UUID id, UUID company, int chars) throws SQLException {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO review_session
                  (id, company_id, reviewer_id, coach_skill_id, coach_owner_id,
                   transcript, transcript_chars, source, share_transcript_with_coach,
                   status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'text', false, 'active', ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, company);
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, UUID.randomUUID());
            ps.setObject(5, UUID.randomUUID());
            ps.setString(6, "客户：你好");
            ps.setInt(7, chars);
            ps.setTimestamp(8, now);
            ps.setTimestamp(9, now);
            ps.executeUpdate();
        }
    }

    private UUID insertReport(UUID session, UUID company, int version, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO review_report
                  (id, company_id, session_id, version, status, prompt_version,
                   attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'v1', 0, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, company);
            ps.setObject(3, session);
            ps.setInt(4, version);
            ps.setString(5, status);
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertCompanyConfig(UUID company) throws SQLException {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO company_ai_config
                  (id, company_id, provider, base_url, api_key_cipher, key_last4,
                   model_name, status, fallback_enabled, created_at, updated_at)
                VALUES (?, ?, 'deepseek', 'https://api.deepseek.com', 'cipher', '5678',
                        'deepseek-chat', 'enabled', true, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, company);
            ps.setTimestamp(3, now);
            ps.setTimestamp(4, now);
            ps.executeUpdate();
        }
    }

    private void insertSegment(UUID session, int seq) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO review_segment
                  (id, session_id, seq, speaker, text, char_count, created_at)
                VALUES (?, ?, ?, 'customer', '你好', 2, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, session);
            ps.setInt(3, seq);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    }
}
