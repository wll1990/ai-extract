-- ============================================================
-- V33：名片页支持 — intro_profile + recommended_questions。
-- opening_message 保持不变用于向后兼容。
-- ============================================================

ALTER TABLE skill ADD COLUMN IF NOT EXISTS intro_profile JSONB;
ALTER TABLE skill ADD COLUMN IF NOT EXISTS recommended_questions JSONB;
ALTER TABLE organization_skill ADD COLUMN IF NOT EXISTS intro_profile JSONB;
