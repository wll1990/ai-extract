-- 公共内容溯源：企业提交到公共库后，记录来源企业（仅展示，不参与权限）
ALTER TABLE public.knowledge_entry
    ADD COLUMN IF NOT EXISTS source_company_id uuid;

ALTER TABLE public.industry_case
    ADD COLUMN IF NOT EXISTS source_company_id uuid;

COMMENT ON COLUMN public.knowledge_entry.source_company_id IS '提交到公共库的来源企业；公共自建内容为空';
COMMENT ON COLUMN public.industry_case.source_company_id IS '提交到公共库的来源企业；公共自建内容为空';

CREATE INDEX IF NOT EXISTS idx_knowledge_entry_source_company
    ON public.knowledge_entry (source_company_id);

CREATE INDEX IF NOT EXISTS idx_industry_case_source_company
    ON public.industry_case (source_company_id);
