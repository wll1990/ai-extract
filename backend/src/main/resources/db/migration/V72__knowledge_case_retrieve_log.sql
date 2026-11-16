-- 知识/案例引用日志：分身回答命中知识或案例时写一行，
-- 供详情页「被引用」面板与废弃前影响面提示使用（最近 30 天）。
CREATE TABLE IF NOT EXISTS public.knowledge_case_retrieve_log (
    id UUID PRIMARY KEY,
    knowledge_id UUID,
    case_id UUID,
    skill_id UUID NOT NULL,
    conversation_id UUID,
    similarity DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_kc_retrieve_one_target CHECK (
        (knowledge_id IS NOT NULL AND case_id IS NULL)
        OR (knowledge_id IS NULL AND case_id IS NOT NULL)
    )
);

COMMENT ON TABLE public.knowledge_case_retrieve_log IS '知识/案例召回日志 — 分身回答引用溯源';

CREATE INDEX IF NOT EXISTS idx_kcrl_knowledge ON public.knowledge_case_retrieve_log (knowledge_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kcrl_case ON public.knowledge_case_retrieve_log (case_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kcrl_skill ON public.knowledge_case_retrieve_log (skill_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kcrl_created ON public.knowledge_case_retrieve_log (created_at);
