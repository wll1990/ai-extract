-- ============================================================
-- auth-revoke-schema.sql — 鉴权撤销：用户安全版本号（S-F6/S-F7）
--
-- 【部署】与 evaluation-schema.sql / training-schema.sql 一样单文件执行：
--   psql -U <user> -d <database> -v ON_ERROR_STOP=1 -f scripts/auth-revoke-schema.sql
--
-- 【幂等】ADD COLUMN IF NOT EXISTS，可重复执行。
--
-- 【作用】改角色/停用账号时 token_version + 1，已签发 JWT 里的 ver claim 与之不符即失效，
--   解决「权限收回后旧 token 仍生效最长 24h」的问题。
--   默认 0，对改造前签发的 token（无 ver claim，按 0 处理）保持兼容。
-- ============================================================

ALTER TABLE public."user"
    ADD COLUMN IF NOT EXISTS token_version bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN public."user".token_version IS '安全版本号：改角色/停用账号时 +1，旧 JWT 立即失效；0 表示从未变更';
