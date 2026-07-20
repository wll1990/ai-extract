COMMENT ON TABLE feedback_log IS '用户反馈记录——每次打分完整留存，支持按颗粒/场景/时间分析回答质量';

CREATE TABLE feedback_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    conversation_id UUID,
    message_id UUID,
    user_id UUID,
    grain_id UUID,
    rating VARCHAR(10) NOT NULL,
    query TEXT,
    ai_response VARCHAR(500),
    rag_score DOUBLE PRECISION,
    source VARCHAR(20) NOT NULL DEFAULT 'user',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN feedback_log.id IS '主键';
COMMENT ON COLUMN feedback_log.skill_id IS '所属分身ID';
COMMENT ON COLUMN feedback_log.conversation_id IS '所属对话ID';
COMMENT ON COLUMN feedback_log.message_id IS 'AI消息ID';
COMMENT ON COLUMN feedback_log.user_id IS '打分用户ID';
COMMENT ON COLUMN feedback_log.grain_id IS '关联的经验颗粒(NULL=无匹配时的打分)';
COMMENT ON COLUMN feedback_log.rating IS '评分: up=有帮助, down=没帮助';
COMMENT ON COLUMN feedback_log.query IS '用户当时的提问原文';
COMMENT ON COLUMN feedback_log.ai_response IS 'AI回答截取前500字';
COMMENT ON COLUMN feedback_log.rag_score IS '回答时的RAG平均匹配度';
COMMENT ON COLUMN feedback_log.source IS '来源: user=用户打分, backfill=存量迁移';

CREATE INDEX idx_fl_skill_time ON feedback_log(skill_id, created_at DESC);
CREATE INDEX idx_fl_grain ON feedback_log(grain_id) WHERE grain_id IS NOT NULL;
CREATE INDEX idx_fl_rating ON feedback_log(skill_id, rating);
