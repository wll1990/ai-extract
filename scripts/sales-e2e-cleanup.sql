-- 清理 E2E 全链路测试数据（仅开发/测试库）
-- 命名约定：customer_name LIKE 'E2E-链路-%'

BEGIN;

CREATE TEMP TABLE e2e_sessions AS
  SELECT id FROM public.review_session WHERE customer_name LIKE 'E2E-链路-%';
CREATE TEMP TABLE e2e_leads AS
  SELECT id FROM public.lead
   WHERE source_review_id IN (SELECT id FROM e2e_sessions)
      OR name LIKE 'E2E-链路-%';
CREATE TEMP TABLE e2e_reports AS
  SELECT id FROM public.review_report WHERE session_id IN (SELECT id FROM e2e_sessions);
CREATE TEMP TABLE e2e_coach_requests AS
  SELECT id FROM public.review_coach_request WHERE session_id IN (SELECT id FROM e2e_sessions);
CREATE TEMP TABLE e2e_coach_notes AS
  SELECT id FROM public.review_coach_note WHERE session_id IN (SELECT id FROM e2e_sessions);

DELETE FROM public.review_coach_note WHERE session_id IN (SELECT id FROM e2e_sessions);
DELETE FROM public.review_coach_request WHERE session_id IN (SELECT id FROM e2e_sessions);
DELETE FROM public.review_message WHERE session_id IN (SELECT id FROM e2e_sessions);
DELETE FROM public.review_report WHERE session_id IN (SELECT id FROM e2e_sessions);
DELETE FROM public.review_segment WHERE session_id IN (SELECT id FROM e2e_sessions);
DELETE FROM public.lead_activity WHERE lead_id IN (SELECT id FROM e2e_leads);
DELETE FROM public.lead_contact WHERE lead_id IN (SELECT id FROM e2e_leads);
CREATE TEMP TABLE e2e_tasks AS
  SELECT id FROM public.follow_up_task
   WHERE review_id IN (SELECT id FROM e2e_sessions)
      OR lead_id IN (SELECT id FROM e2e_leads);
DELETE FROM public.follow_up_task WHERE id IN (SELECT id FROM e2e_tasks);
DELETE FROM public.review_session WHERE id IN (SELECT id FROM e2e_sessions);
DELETE FROM public.lead WHERE id IN (SELECT id FROM e2e_leads);

DELETE FROM public.user_notification
 WHERE (biz_type = 'review_session' AND biz_id IN (SELECT id FROM e2e_sessions))
    OR (biz_type = 'review_report' AND biz_id IN (SELECT id FROM e2e_reports))
    OR (biz_type = 'review_coach_request' AND biz_id IN (SELECT id FROM e2e_coach_requests))
    OR (biz_type = 'review_coach_note' AND biz_id IN (SELECT id FROM e2e_coach_notes))
    OR (biz_type = 'follow_up_task' AND biz_id IN (SELECT id FROM e2e_tasks));

COMMIT;
