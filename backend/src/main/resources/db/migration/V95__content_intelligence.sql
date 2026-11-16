-- AI Native 内容智能：草稿证据链、生成任务留痕。
--
-- AI 只出草稿：知识/案例/考题都保留 evidence（来源引用）与 generation_job_id，
-- 审核台据此展示「为什么生成这条」，也用于统计采纳率。

ALTER TABLE knowledge_entry
    ADD COLUMN IF NOT EXISTS evidence jsonb;
ALTER TABLE knowledge_entry
    ADD COLUMN IF NOT EXISTS generation_job_id uuid;

ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS evidence jsonb;
ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS generation_job_id uuid;
ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS source_conversation_id uuid;

ALTER TABLE evaluation_item
    ADD COLUMN IF NOT EXISTS evidence jsonb;
ALTER TABLE evaluation_item
    ADD COLUMN IF NOT EXISTS generation_job_id uuid;

CREATE TABLE IF NOT EXISTS content_generation_job (
    id             uuid PRIMARY KEY,
    company_id     uuid NOT NULL,
    kind           varchar(20) NOT NULL,   -- knowledge / case / eval_item / derive
    source_type    varchar(30) NOT NULL,   -- knowledge_gap / training_failure / eval_issue / conversation / manual
    source_ref     varchar(128),
    status         varchar(20) NOT NULL,   -- running / done / failed
    produced_count integer NOT NULL DEFAULT 0,
    detail         jsonb,
    error_message  text,
    requested_by   uuid,
    model          varchar(100),
    created_at     timestamp NOT NULL DEFAULT now(),
    finished_at    timestamp
);

CREATE INDEX IF NOT EXISTS idx_cgj_company_created
    ON content_generation_job (company_id, created_at DESC);

-- 同一来源重复生成同一内容时用于去重（标题/问题哈希）
ALTER TABLE content_generation_job
    ADD COLUMN IF NOT EXISTS dedupe_key varchar(200);
CREATE INDEX IF NOT EXISTS idx_cgj_dedupe
    ON content_generation_job (company_id, kind, dedupe_key);
