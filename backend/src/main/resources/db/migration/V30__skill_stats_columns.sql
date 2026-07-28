-- ============================================================
-- V30：分身互动统计字段（SkillStatsScheduler 定时聚合写入）。
-- API 路径直接读列，零 GROUP BY 开销。
-- ============================================================

ALTER TABLE skill
    ADD COLUMN IF NOT EXISTS conversation_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS user_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS satisfaction_rate INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMP;

-- PostgreSQL 的 ALTER TABLE ADD COLUMN DEFAULT 只对新 INSERT 的行生效，
-- 已有行仍然是 NULL。显式填充默认值。
UPDATE skill SET conversation_count = 0 WHERE conversation_count IS NULL;
UPDATE skill SET user_count = 0 WHERE user_count IS NULL;
UPDATE skill SET satisfaction_rate = 0 WHERE satisfaction_rate IS NULL;
