-- ============================================================
-- V26：访谈邀请码表。
-- B端管理员生成邀请码，已有账号的员工扫码后直接进入访谈。
-- 不绑定 space，space 由扫码登录者自己决定。
-- ============================================================

CREATE TABLE IF NOT EXISTS interview_invite_code (
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

COMMENT ON TABLE interview_invite_code IS '访谈邀请码，不绑定space，扫码登录的员工自己决定space';
COMMENT ON COLUMN interview_invite_code.max_uses IS '最大使用次数，0=不限';
COMMENT ON COLUMN interview_invite_code.used_count IS '已使用次数';

CREATE INDEX idx_iicode_code ON interview_invite_code(code);
