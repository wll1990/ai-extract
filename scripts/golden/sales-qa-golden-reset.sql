-- 销售问答黄金集 —— 仅开发环境的定向清理。
--
-- 用法：
--   psql -d aiextract -v confirm_golden_reset=yes -f scripts/golden/sales-qa-golden-reset.sql
--
-- 只删除 dataset e0000000-0000-0000-0000-000000000001 及其运行/评审/报告；
-- 不 DROP 表、不 TRUNCATE、不触碰其它数据集。生产环境不要执行。

\set ON_ERROR_STOP on

\if :{?confirm_golden_reset}
\else
  \echo '拒绝执行：需要 -v confirm_golden_reset=yes'
  SELECT 1 / 0;
\endif

SELECT (:'confirm_golden_reset' = 'yes') AS _confirmed \gset

\if :_confirmed
\else
  \echo '拒绝执行：需要 -v confirm_golden_reset=yes'
  SELECT 1 / 0;
\endif

BEGIN;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM evaluation_dataset
              WHERE id = 'e0000000-0000-0000-0000-000000000001'
                AND company_id IS NOT NULL) THEN
    RAISE EXCEPTION 'e000… 属于企业数据，拒绝 reset';
  END IF;
END $$;

DELETE FROM evaluation_review
 WHERE run_item_id IN (
   SELECT ri.id FROM evaluation_run_item ri
    WHERE ri.run_id IN (
      SELECT id FROM evaluation_run
       WHERE dataset_id = 'e0000000-0000-0000-0000-000000000001'));

DELETE FROM evaluation_run_item
 WHERE run_id IN (
   SELECT id FROM evaluation_run
    WHERE dataset_id = 'e0000000-0000-0000-0000-000000000001');

DELETE FROM evaluation_report
 WHERE run_id IN (
   SELECT id FROM evaluation_run
    WHERE dataset_id = 'e0000000-0000-0000-0000-000000000001');

DELETE FROM evaluation_run
 WHERE dataset_id = 'e0000000-0000-0000-0000-000000000001';

DELETE FROM evaluation_generation_job
 WHERE dataset_id = 'e0000000-0000-0000-0000-000000000001';

DELETE FROM evaluation_item
 WHERE dataset_id = 'e0000000-0000-0000-0000-000000000001';

DELETE FROM evaluation_dataset_version
 WHERE dataset_id = 'e0000000-0000-0000-0000-000000000001';

DELETE FROM evaluation_dataset
 WHERE id = 'e0000000-0000-0000-0000-000000000001'
   AND company_id IS NULL;

COMMIT;

\echo 'golden set reset done'
