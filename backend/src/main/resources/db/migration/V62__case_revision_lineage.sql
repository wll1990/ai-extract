-- 案例版本化：AI 整理 / 人工修订产生新版本时，记录被取代的旧版本 ID
ALTER TABLE public.industry_case
    ADD COLUMN IF NOT EXISTS supersedes_id uuid;

COMMENT ON COLUMN public.industry_case.supersedes_id IS '被本版本取代的旧案例ID（AI 整理/人工修订产生）';

CREATE INDEX IF NOT EXISTS idx_industry_case_supersedes
    ON public.industry_case (supersedes_id);
