COMMENT ON TABLE admin_audit_log IS '管理员操作审计——所有Admin写操作记录(Phase 3开始写入)';

CREATE TABLE admin_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id UUID,
    detail JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN admin_audit_log.id IS '主键';
COMMENT ON COLUMN admin_audit_log.admin_id IS '操作人ID';
COMMENT ON COLUMN admin_audit_log.action IS '操作类型: edit_grain/deprecate_grain/create_grain/resolve_gap/edit_domain/edit_prompt';
COMMENT ON COLUMN admin_audit_log.target_type IS '操作对象类型: grain/gap/prompt/domain';
COMMENT ON COLUMN admin_audit_log.target_id IS '操作对象ID';
COMMENT ON COLUMN admin_audit_log.detail IS '操作详情(JSONB): 字段名+新旧值';

CREATE INDEX idx_aal_admin_time ON admin_audit_log(admin_id, created_at DESC);
CREATE INDEX idx_aal_target ON admin_audit_log(target_type, target_id);
