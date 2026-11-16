-- 培训收尾：资源告警支持题集类型与版本定位。
--
-- resource_version 在 V90 已加，这里保持幂等；新增按企业/状态/类型的扫描索引。

ALTER TABLE public.training_resource_alert
    ADD COLUMN IF NOT EXISTS resource_version int;

CREATE INDEX IF NOT EXISTS idx_tra_company_status_type
    ON public.training_resource_alert (company_id, status, resource_type);
