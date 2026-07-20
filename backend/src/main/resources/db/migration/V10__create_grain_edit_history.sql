COMMENT ON TABLE grain_edit_history IS '颗粒编辑历史——每次Admin修改颗粒时自动记录，支持版本回滚和审计追溯';

CREATE TABLE grain_edit_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    grain_id UUID NOT NULL,                              -- 被编辑的颗粒ID
    field_name VARCHAR(50) NOT NULL,                     -- 修改的字段名
    old_value TEXT,                                      -- 旧值
    new_value TEXT,                                      -- 新值

    edited_by VARCHAR(100),                              -- 修改人
    edit_note TEXT,                                      -- 修改原因

    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN grain_edit_history.id IS '主键';
COMMENT ON COLUMN grain_edit_history.grain_id IS '被编辑的颗粒ID';
COMMENT ON COLUMN grain_edit_history.field_name IS '修改的字段名(expertThought/standardScript/commonMistakes/applicableCondition/sceneTag/weight)';
COMMENT ON COLUMN grain_edit_history.old_value IS '修改前的内容';
COMMENT ON COLUMN grain_edit_history.new_value IS '修改后的内容';
COMMENT ON COLUMN grain_edit_history.edited_by IS '修改人';
COMMENT ON COLUMN grain_edit_history.edit_note IS '修改原因(Admin填写)';

CREATE INDEX idx_geh_grain ON grain_edit_history(grain_id, created_at DESC);
