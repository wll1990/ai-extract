-- ============================================================
-- V28：space.user_id 去掉外键约束。
-- 参照 skill_conversation.user_id — 无外键，B端 user.id 和 C端 app_user.id 都存在同一字段。
-- 不做类型区分，靠 JWT role 决定行为。
-- ============================================================

ALTER TABLE space DROP CONSTRAINT IF EXISTS fk_space_user;

COMMENT ON COLUMN space.user_id IS '空间所有者ID。B端存user.id，C端存app_user.id。无外键约束。';
