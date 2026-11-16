-- 培训：发布时内容快照 + 轮次类型 + 资源告警版本
ALTER TABLE public.training_task
    ADD COLUMN IF NOT EXISTS resource_snapshot jsonb,
    ADD COLUMN IF NOT EXISTS snapshot_version int,
    ADD COLUMN IF NOT EXISTS snapshot_at timestamp without time zone;

ALTER TABLE public.training_task_record
    ADD COLUMN IF NOT EXISTS round_type varchar(20) NOT NULL DEFAULT 'initial';

UPDATE public.training_task_record
   SET round_type = 'remediation'
 WHERE round_no > 1 AND round_type = 'initial';

ALTER TABLE public.training_resource_alert
    ADD COLUMN IF NOT EXISTS resource_version int;

-- 存量已发布版本的阅读任务快照回填（只补空值，不改已有快照）
UPDATE public.training_task t
   SET resource_snapshot = jsonb_build_object(
           'schemaVersion', 1,
           'type', 'knowledge',
           'id', k.id,
           'title', k.title,
           'content', k.content,
           'source', k.source,
           'sourceDocument', k.source_document,
           'capturedAt', now()),
       snapshot_version = 1,
       snapshot_at = now()
  FROM public.knowledge_entry k
 WHERE t.resource_type = 'knowledge'
   AND t.resource_id = k.id
   AND t.resource_snapshot IS NULL
   AND t.type = 'reading';

UPDATE public.training_task t
   SET resource_snapshot = jsonb_build_object(
           'schemaVersion', 1,
           'type', 'case',
           'id', c.id,
           'title', c.title,
           'customerProfile', c.customer_profile::text,
           'funnelStage', c.funnel_stage,
           'sceneTag', c.scene_tag,
           'dialogueQuote', c.dialogue_quote::text,
           'objection', c.objection,
           'keyActions', c.key_actions,
           'scripts', c.scripts,
           'reusablePoints', c.reusable_points,
           'failurePoints', c.failure_points,
           'capturedAt', now()),
       snapshot_version = 1,
       snapshot_at = now()
  FROM public.industry_case c
 WHERE t.resource_type = 'case'
   AND t.resource_id = c.id
   AND t.resource_snapshot IS NULL
   AND t.type = 'reading';
