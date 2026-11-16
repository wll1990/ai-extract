-- V50：用户安全版本号（鉴权撤销 S-F6/S-F7）
-- 当前 application.yml 中 flyway.enabled=false，本文件不会自动执行；
-- 立即可用的建表入口是 scripts/auth-revoke-schema.sql（同一语句，幂等）。
-- 带 IF NOT EXISTS，将来 Flyway 重新启用时对已执行过脚本的库也不会失败。

ALTER TABLE public."user"
    ADD COLUMN IF NOT EXISTS token_version bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN public."user".token_version IS '安全版本号：改角色/停用账号时 +1，旧 JWT 立即失效；0 表示从未变更';
