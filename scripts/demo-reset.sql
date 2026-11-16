-- ============================================================
-- demo-reset.sql — 只清理演示企业域，不触碰其它租户
--
-- 用法：psql -d aiextract -f scripts/demo-reset.sql
-- 幂等：可重复执行。
-- ============================================================

BEGIN;

-- 演示企业固定 ID
CREATE TEMP TABLE demo_company ON COMMIT DROP AS
  SELECT 'd0000000-0000-0000-0000-000000000001'::uuid AS id;

CREATE TEMP TABLE demo_sessions ON COMMIT DROP AS
  SELECT id FROM public.review_session WHERE company_id = (SELECT id FROM demo_company);

CREATE TEMP TABLE demo_spaces ON COMMIT DROP AS
  SELECT id FROM public.space WHERE user_id IN (
    SELECT id FROM public."user" WHERE company_id = (SELECT id FROM demo_company)
  );

-- 个人分身 company_id 为空，必须按空间归属识别，否则会漏删
CREATE TEMP TABLE demo_skills ON COMMIT DROP AS
  SELECT id FROM public.skill
   WHERE company_id = (SELECT id FROM demo_company)
      OR space_id IN (SELECT id FROM demo_spaces);

-- 培训域（子表在前）
DELETE FROM public.training_certification WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.training_review
 WHERE attempt_id IN (
   SELECT id FROM public.training_attempt
    WHERE assignment_id IN (SELECT id FROM public.training_assignment WHERE company_id = (SELECT id FROM demo_company))
 );
DELETE FROM public.training_attempt
 WHERE assignment_id IN (SELECT id FROM public.training_assignment WHERE company_id = (SELECT id FROM demo_company));
DELETE FROM public.training_task_record
 WHERE assignment_id IN (SELECT id FROM public.training_assignment WHERE company_id = (SELECT id FROM demo_company));
DELETE FROM public.training_assignment WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.training_cohort WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.training_task
 WHERE plan_id IN (SELECT id FROM public.training_plan WHERE company_id = (SELECT id FROM demo_company));
DELETE FROM public.training_plan_version
 WHERE plan_id IN (SELECT id FROM public.training_plan WHERE company_id = (SELECT id FROM demo_company));
DELETE FROM public.training_plan WHERE company_id = (SELECT id FROM demo_company);

-- 销售复盘域
DELETE FROM public.review_coach_note WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.review_coach_request WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.review_message WHERE session_id IN (SELECT id FROM demo_sessions);
DELETE FROM public.review_report WHERE session_id IN (SELECT id FROM demo_sessions);
DELETE FROM public.review_segment WHERE session_id IN (SELECT id FROM demo_sessions);
DELETE FROM public.review_session WHERE company_id = (SELECT id FROM demo_company);

-- 线索与任务
DELETE FROM public.lead_activity WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.lead_contact WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.follow_up_task WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.lead WHERE company_id = (SELECT id FROM demo_company);

-- 站内信
DELETE FROM public.user_notification WHERE company_id = (SELECT id FROM demo_company);

-- 分身对话
DELETE FROM public.skill_message
 WHERE conversation_id IN (SELECT id FROM public.skill_conversation WHERE skill_id IN (SELECT id FROM demo_skills));
DELETE FROM public.skill_conversation WHERE skill_id IN (SELECT id FROM demo_skills);

-- 经验域
DELETE FROM public.conversation_stats WHERE skill_id IN (SELECT id FROM demo_skills);
DELETE FROM public.feedback_log WHERE skill_id IN (SELECT id FROM demo_skills);
DELETE FROM public.experience_grain WHERE space_id IN (SELECT id FROM demo_spaces);
DELETE FROM public.report WHERE space_id IN (SELECT id FROM demo_spaces);
DELETE FROM public.interview_message
 WHERE session_id IN (SELECT id FROM public.interview_session WHERE space_id IN (SELECT id FROM demo_spaces));
DELETE FROM public.interview_session WHERE space_id IN (SELECT id FROM demo_spaces);
DELETE FROM public.skill_material WHERE skill_id IN (SELECT id FROM demo_skills);

-- 空间 / 分身 / 用户 / 企业
DELETE FROM public.skill WHERE id IN (SELECT id FROM demo_skills);
DELETE FROM public.space WHERE id IN (SELECT id FROM demo_spaces);
DELETE FROM public."user" WHERE company_id = (SELECT id FROM demo_company);
DELETE FROM public.company WHERE id = (SELECT id FROM demo_company);

COMMIT;
