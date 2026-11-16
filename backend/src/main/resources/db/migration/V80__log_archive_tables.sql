-- DATA-02 归档策略：主表只保留近 3 个月，历史数据搬到归档表（不删除历史）
CREATE TABLE IF NOT EXISTS public.token_usage_log_archive (
    LIKE public.token_usage_log INCLUDING ALL,
    archived_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.admin_audit_log_archive (
    LIKE public.admin_audit_log INCLUDING ALL,
    archived_at TIMESTAMP NOT NULL DEFAULT now()
);
