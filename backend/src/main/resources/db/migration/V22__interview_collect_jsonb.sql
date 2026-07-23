-- V20: 6 个 collect_* boolean 列收拢为 JSONB collect_status
-- 销冠访谈的 6 模块采集状态，元萃取不用
-- 2026-07-23

ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS collect_status JSONB DEFAULT '{}';

UPDATE interview_session SET collect_status = jsonb_build_object(
    'caseStory', CASE WHEN collect_case_story THEN 'collected' ELSE 'pending' END,
    'steps',    CASE WHEN collect_steps      THEN 'collected' ELSE 'pending' END,
    'decision', CASE WHEN collect_decision   THEN 'collected' ELSE 'pending' END,
    'mindset',  CASE WHEN collect_mindset    THEN 'collected' ELSE 'pending' END,
    'boundary', CASE WHEN collect_boundary   THEN 'collected' ELSE 'pending' END,
    'checklist',CASE WHEN collect_checklist  THEN 'collected' ELSE 'pending' END
);

ALTER TABLE interview_session DROP COLUMN IF EXISTS collect_case_story;
ALTER TABLE interview_session DROP COLUMN IF EXISTS collect_steps;
ALTER TABLE interview_session DROP COLUMN IF EXISTS collect_decision;
ALTER TABLE interview_session DROP COLUMN IF EXISTS collect_mindset;
ALTER TABLE interview_session DROP COLUMN IF EXISTS collect_boundary;
ALTER TABLE interview_session DROP COLUMN IF EXISTS collect_checklist;
