-- 内容智能收尾：异步任务结果、AI 草稿审核元数据、消费回流去重。

-- 异步生成任务：结果引用（草稿 ID / 派生内容）与更新时间
ALTER TABLE content_generation_job
    ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone;
UPDATE content_generation_job
   SET updated_at = created_at
 WHERE updated_at IS NULL;
ALTER TABLE content_generation_job
    ADD COLUMN IF NOT EXISTS result jsonb;

-- 同一企业同一去重键只允许一个在跑任务（并发点击/多实例保护）；
-- failed 任务不占用，可重试；done 由业务层继续按日拦截。
CREATE UNIQUE INDEX IF NOT EXISTS uq_cgj_running_dedupe
    ON content_generation_job (company_id, kind, dedupe_key)
    WHERE status = 'running' AND dedupe_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cgj_kind_status_created
    ON content_generation_job (kind, status, created_at DESC);

-- 人工审核元数据（采纳率、审核时长、丢弃理由）
ALTER TABLE knowledge_entry
    ADD COLUMN IF NOT EXISTS reviewed_by uuid;
ALTER TABLE knowledge_entry
    ADD COLUMN IF NOT EXISTS reviewed_at timestamp without time zone;
ALTER TABLE knowledge_entry
    ADD COLUMN IF NOT EXISTS review_note text;

ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS reviewed_by uuid;
ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS reviewed_at timestamp without time zone;
ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS review_note text;

ALTER TABLE evaluation_item
    ADD COLUMN IF NOT EXISTS reviewed_by uuid;
ALTER TABLE evaluation_item
    ADD COLUMN IF NOT EXISTS reviewed_at timestamp without time zone;
ALTER TABLE evaluation_item
    ADD COLUMN IF NOT EXISTS review_note text;

-- AI 草稿审核队列（只扫带 generation_job_id 的行）
CREATE INDEX IF NOT EXISTS idx_knowledge_ai_drafts
    ON knowledge_entry (company_id, status, created_at DESC)
    WHERE generation_job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_case_ai_drafts
    ON industry_case (company_id, status, created_at DESC)
    WHERE generation_job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_item_ai_drafts
    ON evaluation_item (company_id, status, created_at DESC)
    WHERE generation_job_id IS NOT NULL;

-- 消费回流去重：评测按 runItem+条目、培训阅读按 assignment+task+user+day
ALTER TABLE knowledge_case_retrieve_log
    ADD COLUMN IF NOT EXISTS dedupe_key varchar(200);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kcrl_dedupe
    ON knowledge_case_retrieve_log (dedupe_key)
    WHERE dedupe_key IS NOT NULL;
