-- ============================================================
-- V32：组织分身分享支持。
-- skill_share.skill_id → nullable，新增 org_skill_id 列，
-- CHECK 约束确保 skill_id 和 org_skill_id 互斥（二选一）。
-- ============================================================

ALTER TABLE skill_share ALTER COLUMN skill_id DROP NOT NULL;
ALTER TABLE skill_share ADD COLUMN IF NOT EXISTS org_skill_id UUID;
ALTER TABLE skill_share ADD CONSTRAINT chk_share_target
    CHECK ((skill_id IS NOT NULL AND org_skill_id IS NULL)
        OR (skill_id IS NULL AND org_skill_id IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_skill_share_org_skill_channel
    ON skill_share(org_skill_id, channel);
