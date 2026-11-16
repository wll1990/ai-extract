-- 三库评审修复（2026-11-14）
-- 1) 知识/案例：记录废弃前状态，恢复时不再把「放弃的草稿」直接恢复成生效
ALTER TABLE public.knowledge_entry
    ADD COLUMN IF NOT EXISTS previous_status varchar(20);
ALTER TABLE public.industry_case
    ADD COLUMN IF NOT EXISTS previous_status varchar(20);

COMMENT ON COLUMN public.knowledge_entry.previous_status IS '进入 deprecated 前的状态；恢复时据此回到原状态（草稿回草稿，生效回生效）';
COMMENT ON COLUMN public.industry_case.previous_status IS '进入 deprecated 前的状态；恢复时据此回到原状态（草稿回草稿，生效回生效）';

-- 2) 评测题集：保存最近一次审核结论，退回理由不再丢失
ALTER TABLE public.evaluation_dataset
    ADD COLUMN IF NOT EXISTS last_review_reason text,
    ADD COLUMN IF NOT EXISTS reviewed_by uuid,
    ADD COLUMN IF NOT EXISTS reviewed_at timestamp without time zone;

COMMENT ON COLUMN public.evaluation_dataset.last_review_reason IS '最近一次审核（通过/退回）的说明，退回时必填';

-- 3) 评测题目：标记 AI 生成，便于出题人与人工题区分
ALTER TABLE public.evaluation_item
    ADD COLUMN IF NOT EXISTS generated boolean NOT NULL DEFAULT false;

-- 4) 出题任务：记录去重/丢弃数量，0 产出时能解释原因
ALTER TABLE public.evaluation_generation_job
    ADD COLUMN IF NOT EXISTS skipped_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rejected_count integer NOT NULL DEFAULT 0;

-- 5) 召回日志：组织分身调度没有单一 skillId，允许为空（引用聚合按知识/案例即可）
ALTER TABLE public.knowledge_case_retrieve_log
    ALTER COLUMN skill_id DROP NOT NULL;

-- 6) 上传任务：记录失败章节数（部分章节萃取失败不再静默）
ALTER TABLE public.knowledge_upload_task
    ADD COLUMN IF NOT EXISTS failed_sections integer NOT NULL DEFAULT 0;
