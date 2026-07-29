-- ============================================================
-- V33：注册码加 default_role + 种子企业默认注册码。
-- 1. company_register_code 表加 default_role 列
-- 2. 为种子默认企业创建注册码 DEFAULT01
-- ============================================================

-- 1. 注册码加默认角色
ALTER TABLE company_register_code
    ADD COLUMN IF NOT EXISTS default_role VARCHAR(20) DEFAULT 'employee';

COMMENT ON COLUMN company_register_code.default_role IS '此注册码创建的用户的默认角色：employee / company_admin';

-- 2. 为种子默认企业创建注册码（供登录使用）
INSERT INTO company_register_code (id, company_id, code, enabled, max_uses, used_count, created_at, default_role)
SELECT gen_random_uuid(), 'c0000000-0000-0000-0000-000000000001', 'DEFAULT01', true, 0, 0, NOW(), 'company_admin'
WHERE NOT EXISTS (SELECT 1 FROM company_register_code WHERE code = 'DEFAULT01');
