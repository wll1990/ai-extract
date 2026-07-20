COMMENT ON TABLE analytics_event IS '前端埋点事件——用户行为追踪(推荐点击/模式切换/对话结束等)，30天清理';

CREATE TABLE analytics_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID,
    conversation_id UUID,
    user_id UUID,
    event_type VARCHAR(50) NOT NULL,
    event_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN analytics_event.id IS '主键';
COMMENT ON COLUMN analytics_event.skill_id IS '关联分身ID';
COMMENT ON COLUMN analytics_event.conversation_id IS '关联对话ID';
COMMENT ON COLUMN analytics_event.user_id IS '用户ID';
COMMENT ON COLUMN analytics_event.event_type IS '事件类型: recommendation_show/click, mode_switch, conversation_end';
COMMENT ON COLUMN analytics_event.event_data IS '事件数据(JSONB)';

CREATE INDEX idx_ae_skill_time ON analytics_event(skill_id, created_at DESC);
CREATE INDEX idx_ae_type ON analytics_event(event_type);
