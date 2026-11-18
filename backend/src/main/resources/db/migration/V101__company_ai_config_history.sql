-- 企业 AI Key 配置变更历史。
-- 只记录元数据（厂商/模型/Base URL/Key 末 4 位/回退开关/操作人），不记录明文或密文 Key。
CREATE TABLE IF NOT EXISTS company_ai_config_history (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    action varchar(20) NOT NULL,
    provider varchar(20),
    model_name varchar(100),
    base_url varchar(300),
    key_last4 varchar(8),
    fallback_enabled boolean NOT NULL DEFAULT true,
    actor_id uuid,
    actor_name varchar(100),
    created_at timestamp without time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_company_ai_config_history_company_time
    ON company_ai_config_history (company_id, created_at DESC);

COMMENT ON TABLE company_ai_config_history IS '企业 AI Key 配置变更历史：created/updated/rotated/enabled/disabled';
COMMENT ON COLUMN company_ai_config_history.key_last4 IS '仅末 4 位，不保存任何可还原 Key 的信息';
