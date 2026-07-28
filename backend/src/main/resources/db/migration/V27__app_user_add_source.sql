-- ============================================================
-- V27：app_user 增加 source（用户来源）和 company_id（所属企业）。
-- 将原来靠 status='partner' 区分改为 source 字段区分。
-- ============================================================
-- source 取值：
--   'share'    — 从分享链接来的（/s/{code}）
--   'platform' — 自己到 platform 注册的
--   'partner'  — 合作方嵌入自动创建的
-- company_id：
--   仅 source='partner' 时有值，= PartnerApp.app_id（即 Company UUID）
--   用于合作方管理员按企业维度查询本公司所有 partner 用户的分身
-- ============================================================

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS source VARCHAR(10) DEFAULT 'share';
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS company_id UUID;

COMMENT ON COLUMN app_user.source IS '用户来源: share=分享链接, platform=平台注册, partner=合作方嵌入';
COMMENT ON COLUMN app_user.company_id IS '所属企业UUID(仅partner用户有值)=PartnerApp.app_id';

-- 迁移旧数据：status='partner' → source='partner', status 回归 'registered'
UPDATE app_user SET source = 'partner', status = 'registered' WHERE status = 'partner';

-- 索引：合作方管理员按企业查用户
CREATE INDEX IF NOT EXISTS idx_app_user_company ON app_user(company_id) WHERE company_id IS NOT NULL;
