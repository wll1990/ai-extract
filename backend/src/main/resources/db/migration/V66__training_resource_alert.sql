-- 培训资源失效告警：已发布计划引用的知识/案例/分身下线或过期时，计划详情给出可处理提醒
CREATE TABLE IF NOT EXISTS public.training_resource_alert (
    id            uuid PRIMARY KEY,
    company_id    uuid         NOT NULL,
    plan_id       uuid         NOT NULL,
    plan_version  integer      NOT NULL,
    task_id       uuid         NOT NULL,
    resource_type varchar(20)  NOT NULL,
    resource_id   uuid         NOT NULL,
    reason        varchar(500) NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'open',
    created_at    timestamp    NOT NULL DEFAULT now(),
    resolved_at   timestamp
);

COMMENT ON TABLE public.training_resource_alert IS '培训计划引用资源失效告警；同一 task+resource 只保留一条 open';

CREATE UNIQUE INDEX IF NOT EXISTS uq_training_resource_alert_open
    ON public.training_resource_alert (task_id, resource_id)
    WHERE status = 'open';

CREATE INDEX IF NOT EXISTS idx_training_resource_alert_plan
    ON public.training_resource_alert (plan_id, status);

CREATE INDEX IF NOT EXISTS idx_training_resource_alert_company
    ON public.training_resource_alert (company_id, status);
