-- ============================================================
-- evaluation-reset.sql — 评测模块重置（仅开发/测试环境）
--
-- ⚠️ 会删除评测模块 8 张表及其**全部数据**（题集/题目/运行/复核/报告/生成任务）。
--    不碰 company/user/skill 等其他表。
-- 用途：本地或测试环境需要从空白重建时使用；生产环境禁止执行。
--
-- 用法（在仓库根目录）：psql -U <user> -d <database> -v ON_ERROR_STOP=1 -f scripts/evaluation-reset.sql
-- 说明：删表后立即用 \ir 重建到最终态（\ir 相对本脚本所在目录解析，与当前工作目录无关）
-- ============================================================

DROP TABLE IF EXISTS public.evaluation_review;
DROP TABLE IF EXISTS public.evaluation_report;
DROP TABLE IF EXISTS public.evaluation_run_item;
DROP TABLE IF EXISTS public.evaluation_run;
DROP TABLE IF EXISTS public.evaluation_generation_job;
DROP TABLE IF EXISTS public.evaluation_item;
DROP TABLE IF EXISTS public.evaluation_dataset_version;
DROP TABLE IF EXISTS public.evaluation_dataset;

\ir evaluation-schema.sql
