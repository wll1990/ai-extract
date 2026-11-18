-- 三方 H5 接入：按合作方配置 CSP 白名单、code TTL、会话 TTL 与配额。
ALTER TABLE partner_app
    ADD COLUMN code_ttl_seconds              INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN session_idle_ttl_seconds      INTEGER NOT NULL DEFAULT 7200,
    ADD COLUMN session_absolute_ttl_seconds  INTEGER NOT NULL DEFAULT 43200;
