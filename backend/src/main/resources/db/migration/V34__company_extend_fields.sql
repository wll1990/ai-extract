-- ============================================================
-- V32：company 表扩展字段 — 管理员企业合作管理基础数据。
-- 新增联系人/电话/邮箱/地址/行业/规模/备注/状态 8 列。
-- ============================================================

ALTER TABLE company
    ADD COLUMN IF NOT EXISTS contact_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(30),
    ADD COLUMN IF NOT EXISTS contact_email VARCHAR(200),
    ADD COLUMN IF NOT EXISTS address VARCHAR(500),
    ADD COLUMN IF NOT EXISTS industry VARCHAR(100),
    ADD COLUMN IF NOT EXISTS scale VARCHAR(50),
    ADD COLUMN IF NOT EXISTS notes TEXT,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'active';

COMMENT ON COLUMN company.contact_name IS '联系人';
COMMENT ON COLUMN company.contact_phone IS '联系电话';
COMMENT ON COLUMN company.contact_email IS '联系邮箱';
COMMENT ON COLUMN company.address IS '企业地址';
COMMENT ON COLUMN company.industry IS '所属行业';
COMMENT ON COLUMN company.scale IS '企业规模';
COMMENT ON COLUMN company.notes IS '备注';
COMMENT ON COLUMN company.status IS '状态：active=合作中, archived=已归档';
