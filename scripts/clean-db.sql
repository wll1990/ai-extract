-- ============================================
-- 数据库清理：保留初始数据，清空其他所有表
-- 用法：psql -U $PG_USER -d $PG_DATABASE -f scripts/clean-db.sql
-- ============================================

BEGIN;

-- 1. 保留 seed 数据的表 — DELETE 排除种子行
DELETE FROM company_register_code WHERE code != 'DEFAULT01';
DELETE FROM "user" WHERE id != '00000000-0000-0000-0000-000000000001';
DELETE FROM company WHERE id != 'c0000000-0000-0000-0000-000000000001';

-- 2. 全部清空的表（TRUNCATE CASCADE 级联清理外键）
TRUNCATE TABLE answer_correction CASCADE;
TRUNCATE TABLE interview_phase_summary CASCADE;
TRUNCATE TABLE interview_invite_code CASCADE;
TRUNCATE TABLE skill_share CASCADE;
TRUNCATE TABLE grain_retrieve_log CASCADE;
TRUNCATE TABLE feedback_log CASCADE;
TRUNCATE TABLE grain_edit_history CASCADE;
TRUNCATE TABLE candidate_grain CASCADE;
TRUNCATE TABLE auto_insight CASCADE;
TRUNCATE TABLE extraction_drop_log CASCADE;
TRUNCATE TABLE skill_message CASCADE;
TRUNCATE TABLE skill_conversation CASCADE;
TRUNCATE TABLE interview_message CASCADE;
TRUNCATE TABLE interview_session CASCADE;
TRUNCATE TABLE skill_acceptance_question CASCADE;
TRUNCATE TABLE skill_acceptance CASCADE;
TRUNCATE TABLE skill_evaluation CASCADE;
TRUNCATE TABLE report_history CASCADE;
TRUNCATE TABLE report CASCADE;
TRUNCATE TABLE skill_material CASCADE;
TRUNCATE TABLE skill CASCADE;
TRUNCATE TABLE skill_profile CASCADE;
TRUNCATE TABLE experience_grain CASCADE;
TRUNCATE TABLE expert_grain CASCADE;
TRUNCATE TABLE expert_document CASCADE;
TRUNCATE TABLE expert_skill CASCADE;
TRUNCATE TABLE organization_skill CASCADE;
TRUNCATE TABLE space CASCADE;
TRUNCATE TABLE im_channel CASCADE;
TRUNCATE TABLE knowledge_gap CASCADE;
TRUNCATE TABLE token_usage_log CASCADE;
TRUNCATE TABLE analytics_event CASCADE;
TRUNCATE TABLE admin_audit_log CASCADE;
TRUNCATE TABLE partner_app CASCADE;
TRUNCATE TABLE conversation_stats CASCADE;
TRUNCATE TABLE tool CASCADE;

COMMIT;
