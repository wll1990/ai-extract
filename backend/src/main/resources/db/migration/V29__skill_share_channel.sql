-- ============================================================
-- V29：skill_share channel 改名 + company_id 改 nullable。
-- channel 取值：
--   'public'   — 对外分享 /s/{code}，任何人可访问
--   'internal' — 对内分享 /i/{code}，本公司员工或平台登录用户可访问
-- company_id 允许 NULL — C 端分身分享时无企业归属
-- ============================================================

UPDATE skill_share SET channel = 'public' WHERE channel = 'default';

ALTER TABLE skill_share ALTER COLUMN company_id DROP NOT NULL;

COMMENT ON COLUMN skill_share.channel IS 'public=对外分享, internal=对内分享';
COMMENT ON COLUMN skill_share.company_id IS '企业归属(C端分身分享时为null)';
