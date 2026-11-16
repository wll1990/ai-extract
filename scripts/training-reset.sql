-- ============================================================
-- training-reset.sql — 培训模块重置（仅开发/测试环境）
--
-- ⚠️ 会删除培训模块 10 张表及其**全部数据**（计划/任务/批次/分配/进度/尝试/复核/认证）。
--    不碰 company/user/skill/evaluation_* 等其他表。
-- 用途：本地或测试环境需要从空白重建时使用；生产环境禁止执行。
--
-- 用法（在仓库根目录）：psql -U <user> -d <database> -v ON_ERROR_STOP=1 -f scripts/training-reset.sql
-- 说明：删表后立即用 \ir 重建到最终态（\ir 相对本脚本所在目录解析，与当前工作目录无关）
-- ============================================================

DROP TABLE IF EXISTS public.user_notification;
DROP TABLE IF EXISTS public.training_certification;
DROP TABLE IF EXISTS public.training_review;
DROP TABLE IF EXISTS public.training_attempt;
DROP TABLE IF EXISTS public.training_task_record;
DROP TABLE IF EXISTS public.training_assignment;
DROP TABLE IF EXISTS public.training_cohort;
DROP TABLE IF EXISTS public.training_task;
DROP TABLE IF EXISTS public.training_plan_version;
DROP TABLE IF EXISTS public.training_plan;

-- export_job 为评测/培训共用表：本期培训占用中，随培训模块一并重置。
-- 若评测侧已开始产生导出任务，请单独确认后再执行本文件。
DROP TABLE IF EXISTS public.export_job;

\ir training-schema.sql
