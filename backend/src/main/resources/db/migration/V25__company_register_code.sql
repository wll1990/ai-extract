-- ============================================================
-- V25：企业注册码表。
-- B端/Partner管理员生成注册码，新员工扫码注册自动归入企业。
-- ============================================================

CREATE TABLE IF NOT EXISTS company_register_code (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    code VARCHAR(20) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    max_uses INT DEFAULT 0,        -- 0=不限次数
    used_count INT DEFAULT 0,
    created_by UUID,
    created_at TIMESTAMP DEFAULT now(),
    expires_at TIMESTAMP
);

COMMENT ON TABLE company_register_code IS '企业注册码，管理员生成后新员工扫码注册自动归入企业';
COMMENT ON COLUMN company_register_code.max_uses IS '最大使用次数，0=不限';
COMMENT ON COLUMN company_register_code.used_count IS '已使用次数';

CREATE INDEX idx_ccode_code ON company_register_code(code);
