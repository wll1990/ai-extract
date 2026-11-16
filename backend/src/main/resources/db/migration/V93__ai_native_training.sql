-- AI Native 培训：新增 drill（场景微练）任务类型与 AI 生成计划留痕。
--
-- drill 与 exam 共用「题面快照 + 参考答案」的执行与评分基础设施，但题面来自
-- AI 根据知识/案例生成的场景卡（resource_snapshot），不依赖评测题集，也不限时。

ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_type;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_type
    CHECK (type IN ('reading', 'practice', 'exam', 'drill'));

-- 微练必须带生成好的场景快照，否则学员打开只有空题面
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_drill_snapshot;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_drill_snapshot
    CHECK (type <> 'drill' OR resource_snapshot IS NOT NULL);

ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_type;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_type
    CHECK (type IN ('practice', 'exam', 'drill'));

ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_drill_snapshot;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_drill_snapshot
    CHECK (type <> 'drill' OR question_snapshot IS NOT NULL);

-- AI 生成计划留痕：为什么给这批人推这些任务，必须可解释、可追溯
ALTER TABLE public.training_plan
    ADD COLUMN IF NOT EXISTS goal text;
ALTER TABLE public.training_plan
    ADD COLUMN IF NOT EXISTS generated_by_ai boolean NOT NULL DEFAULT false;
ALTER TABLE public.training_plan
    ADD COLUMN IF NOT EXISTS generation_note text;
