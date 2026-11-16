-- 员工颗粒纠错：一线只能提交建议，不直接改颗粒；管理员审核通过后才落库并重算向量。
CREATE TABLE IF NOT EXISTS public.grain_correction_suggestion (
    id UUID PRIMARY KEY,
    grain_id UUID NOT NULL,
    skill_id UUID NOT NULL,
    company_id UUID NOT NULL,
    suggested_by UUID NOT NULL,
    reason TEXT NOT NULL,
    scene_description TEXT,
    expert_thought TEXT,
    standard_script TEXT,
    common_mistakes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    reviewed_by UUID,
    reviewed_at TIMESTAMP,
    review_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_grain_correction_status CHECK (status IN ('pending', 'approved', 'rejected'))
);

COMMENT ON TABLE public.grain_correction_suggestion IS '颗粒纠错建议 — 员工提交，管理员审核';

CREATE INDEX IF NOT EXISTS idx_gcs_company_status
    ON public.grain_correction_suggestion (company_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gcs_grain
    ON public.grain_correction_suggestion (grain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gcs_skill
    ON public.grain_correction_suggestion (skill_id, created_at DESC);
