-- 向量化失败自动重试：审批后 embedding 失败时，新版不参与检索、旧版继续生效，
-- 由 ContentIndexRetryScheduler 按退避重试并在成功后完成旧版下线。
ALTER TABLE public.knowledge_entry
    ADD COLUMN IF NOT EXISTS embedding_retry_count int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS embedding_next_retry_at timestamp without time zone;

ALTER TABLE public.industry_case
    ADD COLUMN IF NOT EXISTS embedding_retry_count int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS embedding_next_retry_at timestamp without time zone;

CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_retry
    ON public.knowledge_entry (status, embedding_status, embedding_next_retry_at);
CREATE INDEX IF NOT EXISTS idx_case_embedding_retry
    ON public.industry_case (status, embedding_status, embedding_next_retry_at);
