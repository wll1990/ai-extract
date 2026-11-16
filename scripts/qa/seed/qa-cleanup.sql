-- ============================================================
-- qa-cleanup.sql — 按 runId 清理 QA 测试数据（生产只读安全）
--
-- 用法：
--   psql ... -v ON_ERROR_STOP=1 -v qa_run_id=20261112-2100 -f qa-cleanup.sql
--
-- 只删除名称/标题带 "QA-<runId>-" 的对象及其子数据；
-- 不删除 qa-seed.sql 的固定种子（种子名称不含 runId）。
-- 结束时校验残留为 0，否则抛异常。
-- ============================================================

BEGIN;

\set ON_ERROR_STOP on

-- 安全/越权回归用例的固定探针数据（REG-SEC-08/09），每次清理都删掉
DELETE FROM public.candidate_grain
 WHERE id = '0c000000-0000-0000-0000-000000000011';
DELETE FROM public.auto_insight
 WHERE id = '0c000000-0000-0000-0000-000000000010';
DELETE FROM public.skill_conversation
 WHERE id = '0c000000-0000-0000-0000-000000000001';

CREATE TEMP TABLE qa_spaces ON COMMIT DROP AS
  SELECT id FROM public.space
   WHERE title LIKE 'QA-' || :'qa_run_id' || '-%';

CREATE TEMP TABLE qa_skills ON COMMIT DROP AS
  SELECT id FROM public.skill
   WHERE space_id IN (SELECT id FROM qa_spaces)
      OR display_name LIKE 'QA-' || :'qa_run_id' || '-%';

-- 固定 QA 分身上传的素材（按文件名前缀识别）
CREATE TEMP TABLE qa_materials ON COMMIT DROP AS
  SELECT id FROM public.skill_material
   WHERE skill_id IN (SELECT id FROM qa_skills)
      OR file_name LIKE 'QA-' || :'qa_run_id' || '-%';

-- 分身对话
DELETE FROM public.skill_message
 WHERE conversation_id IN (SELECT id FROM public.skill_conversation WHERE skill_id IN (SELECT id FROM qa_skills));
DELETE FROM public.skill_conversation WHERE skill_id IN (SELECT id FROM qa_skills);
DELETE FROM public.conversation_stats WHERE skill_id IN (SELECT id FROM qa_skills);
DELETE FROM public.feedback_log WHERE skill_id IN (SELECT id FROM qa_skills);
DELETE FROM public.grain_retrieve_log
 WHERE grain_id IN (SELECT id FROM public.experience_grain WHERE space_id IN (SELECT id FROM qa_spaces));
DELETE FROM public.grain_edit_history
 WHERE grain_id IN (SELECT id FROM public.experience_grain WHERE space_id IN (SELECT id FROM qa_spaces));

-- 素材 / 颗粒 / 报告 / 访谈
DELETE FROM public.skill_material WHERE id IN (SELECT id FROM qa_materials);
DELETE FROM public.candidate_grain WHERE skill_id IN (SELECT id FROM qa_skills);
DELETE FROM public.knowledge_gap WHERE skill_id IN (SELECT id FROM qa_skills);
DELETE FROM public.experience_grain
 WHERE space_id IN (SELECT id FROM qa_spaces)
    OR source_material_id IN (SELECT id FROM qa_materials);
DELETE FROM public.report WHERE space_id IN (SELECT id FROM qa_spaces);
DELETE FROM public.interview_message
 WHERE session_id IN (SELECT id FROM public.interview_session WHERE space_id IN (SELECT id FROM qa_spaces));
DELETE FROM public.interview_session WHERE space_id IN (SELECT id FROM qa_spaces);

-- 知识 / 案例
DELETE FROM public.knowledge_entry WHERE title LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public.industry_case WHERE title LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public.knowledge_upload_task WHERE file_name LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public.case_upload_task WHERE file_name LIKE 'QA-' || :'qa_run_id' || '-%';

-- 评测
DELETE FROM public.evaluation_review
 WHERE run_item_id IN (
   SELECT ri.id FROM public.evaluation_run_item ri
    JOIN public.evaluation_run r ON r.id = ri.run_id
   WHERE r.company_id = '0a000000-0000-0000-0000-000000000001'
     AND r.id IN (SELECT id FROM public.evaluation_run WHERE dataset_id IN (
       SELECT id FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%')));
DELETE FROM public.evaluation_report
 WHERE run_id IN (SELECT id FROM public.evaluation_run WHERE dataset_id IN (
   SELECT id FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%'));
DELETE FROM public.evaluation_run_item
 WHERE run_id IN (SELECT id FROM public.evaluation_run WHERE dataset_id IN (
   SELECT id FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%'));
DELETE FROM public.evaluation_run
 WHERE dataset_id IN (SELECT id FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.evaluation_generation_job
 WHERE dataset_id IN (SELECT id FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.evaluation_item
 WHERE dataset_id IN (SELECT id FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.evaluation_dataset_version
 WHERE dataset_id IN (SELECT id FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.evaluation_dataset WHERE name LIKE 'QA-' || :'qa_run_id' || '-%';

-- 销售复盘 / 线索 / 任务
DELETE FROM public.review_coach_note
 WHERE session_id IN (SELECT id FROM public.review_session WHERE customer_name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.review_coach_request
 WHERE session_id IN (SELECT id FROM public.review_session WHERE customer_name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.review_message
 WHERE session_id IN (SELECT id FROM public.review_session WHERE customer_name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.review_report
 WHERE session_id IN (SELECT id FROM public.review_session WHERE customer_name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.review_segment
 WHERE session_id IN (SELECT id FROM public.review_session WHERE customer_name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.follow_up_task WHERE title LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public.lead_activity
 WHERE lead_id IN (SELECT id FROM public.lead WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.lead_contact
 WHERE lead_id IN (SELECT id FROM public.lead WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.lead WHERE name LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public.review_session WHERE customer_name LIKE 'QA-' || :'qa_run_id' || '-%';

-- 培训（只清理 runId 命名计划及其子数据）
DELETE FROM public.training_certification
 WHERE assignment_id IN (SELECT id FROM public.training_assignment WHERE cohort_id IN (
   SELECT id FROM public.training_cohort WHERE plan_id IN (
     SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%')));
DELETE FROM public.training_review
 WHERE attempt_id IN (SELECT id FROM public.training_attempt WHERE assignment_id IN (
   SELECT id FROM public.training_assignment WHERE cohort_id IN (
     SELECT id FROM public.training_cohort WHERE plan_id IN (
       SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%'))));
DELETE FROM public.training_attempt WHERE assignment_id IN (
   SELECT id FROM public.training_assignment WHERE cohort_id IN (
     SELECT id FROM public.training_cohort WHERE plan_id IN (
       SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%')));
DELETE FROM public.training_task_record WHERE assignment_id IN (
   SELECT id FROM public.training_assignment WHERE cohort_id IN (
     SELECT id FROM public.training_cohort WHERE plan_id IN (
       SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%')));
DELETE FROM public.training_assignment WHERE cohort_id IN (
   SELECT id FROM public.training_cohort WHERE plan_id IN (
     SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%'));
DELETE FROM public.training_cohort WHERE plan_id IN (
   SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.training_task WHERE plan_id IN (
   SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.training_plan_version WHERE plan_id IN (
   SELECT id FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%');
DELETE FROM public.training_plan WHERE name LIKE 'QA-' || :'qa_run_id' || '-%';

-- 分享 → 访客会话（QA 种子分身上的游客，按 source_share_id 精确归属）
DELETE FROM public.skill_message
 WHERE conversation_id IN (
   SELECT c.id FROM public.skill_conversation c
     JOIN public."user" u ON u.id = c.user_id
    WHERE c.skill_id = '0a000000-0000-0000-0000-000000000032'
      AND u.source = 'share');
DELETE FROM public.skill_conversation c
 USING public."user" u
 WHERE c.user_id = u.id
   AND c.skill_id = '0a000000-0000-0000-0000-000000000032'
   AND u.source = 'share';
DELETE FROM public."user"
 WHERE source = 'share'
   AND source_share_id IN (
     SELECT id FROM public.skill_share
      WHERE skill_id = '0a000000-0000-0000-0000-000000000032');

-- 通知 / 留资
DELETE FROM public.user_notification WHERE title LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public.demo_request WHERE company_name LIKE 'QA-' || :'qa_run_id' || '-%';

-- 分身本身
DELETE FROM public.skill_share WHERE skill_id IN (SELECT id FROM qa_skills);
DELETE FROM public.experience_grain WHERE space_id IN (SELECT id FROM qa_spaces);
DELETE FROM public.skill WHERE id IN (SELECT id FROM qa_skills);
DELETE FROM public.space WHERE id IN (SELECT id FROM qa_spaces);
-- 兜底：按 runId 前缀再扫一遍（临时表是快照，异步改名/晚创建的对象可能漏网）
DELETE FROM public.skill WHERE display_name LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public.space WHERE title LIKE 'QA-' || :'qa_run_id' || '-%';
DELETE FROM public."user"
 WHERE company_id = '0a000000-0000-0000-0000-000000000001'
   AND (account LIKE 'QA-' || :'qa_run_id' || '-%'
        OR account LIKE 'REG-' || :'qa_run_id' || '-%');

-- QA 租户里测试生成的注册码（保留固定种子码 QATEST01）
DELETE FROM public.company_register_code
 WHERE company_id = '0a000000-0000-0000-0000-000000000001'
   AND code <> 'QATEST01';

-- 残留校验
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM public.skill WHERE id IN (SELECT id FROM qa_skills);
  IF n > 0 THEN RAISE EXCEPTION 'cleanup residual: skill=%', n; END IF;
  SELECT count(*) INTO n FROM public.space WHERE id IN (SELECT id FROM qa_spaces);
  IF n > 0 THEN RAISE EXCEPTION 'cleanup residual: space=%', n; END IF;
  SELECT count(*) INTO n FROM public.skill_material WHERE id IN (SELECT id FROM qa_materials);
  IF n > 0 THEN RAISE EXCEPTION 'cleanup residual: skill_material=%', n; END IF;
  SELECT count(*) INTO n FROM public.experience_grain WHERE space_id IN (SELECT id FROM qa_spaces);
  IF n > 0 THEN RAISE EXCEPTION 'cleanup residual: experience_grain=%', n; END IF;
  SELECT count(*) INTO n FROM public.report WHERE space_id IN (SELECT id FROM qa_spaces);
  IF n > 0 THEN RAISE EXCEPTION 'cleanup residual: report=%', n; END IF;
END $$;

COMMIT;
