COMMENT ON TABLE grain_retrieve_log IS 'RAG检索日志——每次语义检索命中记录，用于分析检索质量和场景覆盖，30天自动清理';

CREATE TABLE grain_retrieve_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    original_query TEXT,
    rewritten_query TEXT,
    grain_id UUID NOT NULL,
    scene_tag VARCHAR(100),
    similarity DOUBLE PRECISION NOT NULL,
    tier VARCHAR(10),
    position INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN grain_retrieve_log.id IS '主键';
COMMENT ON COLUMN grain_retrieve_log.skill_id IS '所属分身ID';
COMMENT ON COLUMN grain_retrieve_log.conversation_id IS '所属对话ID';
COMMENT ON COLUMN grain_retrieve_log.original_query IS '用户原始提问';
COMMENT ON COLUMN grain_retrieve_log.rewritten_query IS 'LLM改写后的查询';
COMMENT ON COLUMN grain_retrieve_log.grain_id IS '命中的颗粒ID';
COMMENT ON COLUMN grain_retrieve_log.scene_tag IS '颗粒的场景标签';
COMMENT ON COLUMN grain_retrieve_log.similarity IS '余弦相似度(0~1)';
COMMENT ON COLUMN grain_retrieve_log.tier IS '分层标记: high=高匹配, ref=参考, NULL=低匹配';
COMMENT ON COLUMN grain_retrieve_log.position IS '排名(1-based)';

CREATE INDEX idx_grl_skill_time ON grain_retrieve_log(skill_id, created_at DESC);
CREATE INDEX idx_grl_grain ON grain_retrieve_log(grain_id);
CREATE INDEX idx_grl_conv ON grain_retrieve_log(conversation_id);
