-- 仅开发/测试：清空销售复盘 + 企业 AI Key。生产禁止执行。
DROP TABLE IF EXISTS public.lead_activity;
DROP TABLE IF EXISTS public.lead_contact;
DROP TABLE IF EXISTS public.follow_up_task;
DROP TABLE IF EXISTS public.lead;
DROP TABLE IF EXISTS public.review_coach_note;
DROP TABLE IF EXISTS public.review_coach_request;
DROP TABLE IF EXISTS public.review_message;
DROP TABLE IF EXISTS public.review_report;
DROP TABLE IF EXISTS public.review_segment;
DROP TABLE IF EXISTS public.review_session;
DROP TABLE IF EXISTS public.company_ai_config;
ALTER TABLE public.token_usage_log DROP COLUMN IF EXISTS company_id;
