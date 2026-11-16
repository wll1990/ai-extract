-- 知识/案例索引状态：区分「审核已通过」与「向量已入库」
-- 背景：审批通过后 embedding 在事务提交后异步写入，失败只打日志，
--      管理端无法看到「已生效但检索不到」的状态，也无法单条重建。

ALTER TABLE public.knowledge_entry
    ADD COLUMN IF NOT EXISTS embedding_status varchar(20) NOT NULL DEFAULT 'pending';

ALTER TABLE public.knowledge_entry
    ADD COLUMN IF NOT EXISTS indexed_at timestamp;

ALTER TABLE public.knowledge_entry
    ADD COLUMN IF NOT EXISTS index_error text;

ALTER TABLE public.industry_case
    ADD COLUMN IF NOT EXISTS embedding_status varchar(20) NOT NULL DEFAULT 'pending';

ALTER TABLE public.industry_case
    ADD COLUMN IF NOT EXISTS indexed_at timestamp;

ALTER TABLE public.industry_case
    ADD COLUMN IF NOT EXISTS index_error text;

COMMENT ON COLUMN public.knowledge_entry.embedding_status IS 'pending/indexing/indexed/failed';
COMMENT ON COLUMN public.knowledge_entry.indexed_at IS '最近一次向量写入成功时间';
COMMENT ON COLUMN public.knowledge_entry.index_error IS '最近一次向量写入失败原因';
COMMENT ON COLUMN public.industry_case.embedding_status IS 'pending/indexing/indexed/failed';
COMMENT ON COLUMN public.industry_case.indexed_at IS '最近一次向量写入成功时间';
COMMENT ON COLUMN public.industry_case.index_error IS '最近一次向量写入失败原因';

-- 存量回填：已有向量的视为已索引
UPDATE public.knowledge_entry
SET embedding_status = CASE WHEN embedding IS NULL THEN 'pending' ELSE 'indexed' END,
    indexed_at = CASE WHEN embedding IS NULL THEN NULL ELSE updated_at END
WHERE embedding_status = 'pending';

UPDATE public.industry_case
SET embedding_status = CASE WHEN embedding IS NULL THEN 'pending' ELSE 'indexed' END,
    indexed_at = CASE WHEN embedding IS NULL THEN NULL ELSE updated_at END
WHERE embedding_status = 'pending';

-- 重建索引时按「失败/待索引 + 生效中」筛选
CREATE INDEX IF NOT EXISTS idx_knowledge_entry_embedding_status
    ON public.knowledge_entry (embedding_status, status);

CREATE INDEX IF NOT EXISTS idx_industry_case_embedding_status
    ON public.industry_case (embedding_status, status);
