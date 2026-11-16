-- 线索活动类型补齐：负责人分配与任务收口此前没有对应类型，
-- 导致「设负责人」在 DB CHECK 处直接失败（500），任务完成/放弃也无法回写时间线。
ALTER TABLE public.lead_activity DROP CONSTRAINT IF EXISTS ck_lead_activity_type;

ALTER TABLE public.lead_activity
    ADD CONSTRAINT ck_lead_activity_type CHECK (type IN (
        'created','status_changed','field_updated','note',
        'contact_added','contact_updated','contact_removed',
        'task_created','task_transferred','task_escalated',
        'task_completed','task_cancelled',
        'owner_changed'
    ));
