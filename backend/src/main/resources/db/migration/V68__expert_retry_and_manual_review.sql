-- 专家萃取状态机：重试上限 + 失败原因 + 人工补录（manual_review）
ALTER TABLE public.expert_skill
    ADD COLUMN IF NOT EXISTS retry_count integer NOT NULL DEFAULT 0;

ALTER TABLE public.expert_skill
    ADD COLUMN IF NOT EXISTS failure_reason text;

COMMENT ON COLUMN public.expert_skill.retry_count IS '自动分析失败重试次数；达到上限 3 后停留 failed';
COMMENT ON COLUMN public.expert_skill.failure_reason IS '最近一次失败原因（供管理端展示与排查）';
