-- AI 草稿审核结论：区分「审核通过后被新版取代/下架」与「审核时被丢弃」。
-- approved / rejected；NULL 表示尚未审核（指标里计入待审）。

ALTER TABLE knowledge_entry
    ADD COLUMN IF NOT EXISTS review_outcome varchar(20);

ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS review_outcome varchar(20);

ALTER TABLE evaluation_item
    ADD COLUMN IF NOT EXISTS review_outcome varchar(20);

CREATE INDEX IF NOT EXISTS idx_knowledge_review_outcome
    ON knowledge_entry (company_id, review_outcome)
    WHERE generation_job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_case_review_outcome
    ON industry_case (company_id, review_outcome)
    WHERE generation_job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_item_review_outcome
    ON evaluation_item (company_id, review_outcome)
    WHERE generation_job_id IS NOT NULL;
