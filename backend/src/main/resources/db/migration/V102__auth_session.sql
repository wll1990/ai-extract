-- 不透明会话：DB 为真相源，Redis 为主读/秒级撤销通道。
-- token 只存 SHA-256，不存明文。
CREATE TABLE auth_session (
    id                   UUID PRIMARY KEY,
    token_hash           VARCHAR(64)  NOT NULL,
    user_id              UUID         NOT NULL,
    company_id           UUID,
    role                 VARCHAR(32)  NOT NULL,
    epoch                BIGINT       NOT NULL DEFAULT 0,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    partner_app_id       UUID,
    external_user_id     VARCHAR(128),
    device_id            VARCHAR(64),
    user_agent           VARCHAR(512),
    ip                   VARCHAR(64),
    idle_ttl_seconds     INTEGER      NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    absolute_expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at           TIMESTAMP WITH TIME ZONE,
    revoked_reason       VARCHAR(64)
);

CREATE UNIQUE INDEX uk_auth_session_token_hash ON auth_session (token_hash);
CREATE INDEX idx_auth_session_user_active
    ON auth_session (user_id, created_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_auth_session_expire ON auth_session (absolute_expires_at);
CREATE INDEX idx_auth_session_partner
    ON auth_session (partner_app_id, external_user_id);
