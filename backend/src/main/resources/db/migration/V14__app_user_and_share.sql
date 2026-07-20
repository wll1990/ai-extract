-- V14__app_user_and_share.sql
-- C 端双用户体系 + 分身对外分享：
--   app_user    — C 端用户（平台级，无租户）。游客 status='guest'（无账号密码），
--                 注册后原地升级为 'registered'（UUID 不变，会话历史自动继承）。
--   skill_share — 分身分享链接。enabled 为共享开关；对外可聊 = skill.status='published' AND enabled=true。
-- 不修改任何现有表；skill_conversation.user_id 为无外键扁平列，可直接存 app_user.id。

CREATE TABLE IF NOT EXISTS app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nickname VARCHAR(50) NOT NULL,
    account VARCHAR(100) UNIQUE,
    password_hash VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'guest' CHECK (status IN ('guest', 'registered')),
    source_share_id UUID,
    last_active_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_app_user_source ON app_user(source_share_id);

COMMENT ON TABLE app_user IS 'C端用户（平台级，与企业 user 表完全独立）';
COMMENT ON COLUMN app_user.nickname IS '昵称，游客自动生成"访客xxxx"，注册时可改';
COMMENT ON COLUMN app_user.account IS '登录账号，游客为 NULL，注册后平台全局唯一';
COMMENT ON COLUMN app_user.password_hash IS 'BCrypt 密码哈希，游客为 NULL';
COMMENT ON COLUMN app_user.status IS '状态: guest=游客（未设账号密码） / registered=已注册';
COMMENT ON COLUMN app_user.source_share_id IS '来源分享ID（skill_share.id），用于转化归因';

CREATE TABLE IF NOT EXISTS skill_share (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    company_id UUID NOT NULL,
    share_code VARCHAR(16) NOT NULL UNIQUE,
    channel VARCHAR(50) NOT NULL DEFAULT 'default',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_skill_share_skill ON skill_share(skill_id);

COMMENT ON TABLE skill_share IS '分身对外分享链接（一 skill 可多渠道多码）';
COMMENT ON COLUMN skill_share.company_id IS '分身所属企业（建码时经 skill→space→user 解析冗余，归因用）';
COMMENT ON COLUMN skill_share.share_code IS '短码，URL 形如 /s/{share_code}，base62 随机 10 位';
COMMENT ON COLUMN skill_share.channel IS '渠道标识，本期恒为 default，二期按渠道发码统计转化';
COMMENT ON COLUMN skill_share.enabled IS '共享开关：关闭后分享链接立即失效，企业内部使用不受影响';
