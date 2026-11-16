-- IM 通知绑定：B 端用户 ↔ 飞书/企微/钉钉身份。
--
-- 通过「平台生成 6 位码 → 用户给机器人发 绑定 码」完成，无需企业通讯录 API。
-- 一个用户在同一渠道只保留一条；推送用 bot_id + external_user_id/chat_id 定位。

CREATE TABLE IF NOT EXISTS im_user_binding (
    id               uuid PRIMARY KEY,
    company_id       uuid NOT NULL,
    channel_type     varchar(20) NOT NULL,
    bot_id           varchar(128) NOT NULL,
    user_id          uuid NOT NULL,
    external_user_id varchar(128) NOT NULL,
    chat_id          varchar(128),
    display_name     varchar(200),
    created_at       timestamp NOT NULL DEFAULT now(),
    last_seen_at     timestamp,
    CONSTRAINT uq_imb_channel_external UNIQUE (channel_type, external_user_id),
    CONSTRAINT uq_imb_company_channel_user UNIQUE (company_id, channel_type, user_id)
);

CREATE INDEX IF NOT EXISTS idx_imb_user ON im_user_binding (user_id);
CREATE INDEX IF NOT EXISTS idx_imb_company ON im_user_binding (company_id);

-- 一次性绑定码（10 分钟有效；过期行由创建时顺带清理）
CREATE TABLE IF NOT EXISTS im_bind_code (
    code        varchar(12) PRIMARY KEY,
    company_id  uuid NOT NULL,
    user_id     uuid NOT NULL,
    expires_at  timestamp NOT NULL,
    created_at  timestamp NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ibc_expires ON im_bind_code (expires_at);
