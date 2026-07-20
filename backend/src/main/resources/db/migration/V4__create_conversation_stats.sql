COMMENT ON TABLE conversation_stats IS '对话统计——每次AI回复一条记录，是飞轮所有报表的单一数据源';

CREATE TABLE conversation_stats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    user_id UUID,
    mode VARCHAR(20) NOT NULL,
    rag_high_count INT NOT NULL DEFAULT 0,
    rag_ref_count INT NOT NULL DEFAULT 0,
    rag_none_count INT NOT NULL DEFAULT 0,
    rag_avg_similarity DOUBLE PRECISION,
    feedback_up INT NOT NULL DEFAULT 0,
    feedback_down INT NOT NULL DEFAULT 0,
    error_type VARCHAR(20),
    is_test BOOLEAN NOT NULL DEFAULT FALSE,
    llm_duration_ms INT,
    total_duration_ms INT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN conversation_stats.id IS '主键';
COMMENT ON COLUMN conversation_stats.skill_id IS '所属分身ID';
COMMENT ON COLUMN conversation_stats.conversation_id IS '对话ID(多轮对话中可重复)';
COMMENT ON COLUMN conversation_stats.user_id IS '用户ID';
COMMENT ON COLUMN conversation_stats.mode IS '对话模式: qa/discuss/talk/practice/enterprise';
COMMENT ON COLUMN conversation_stats.rag_high_count IS '高匹配颗粒数(similarity≥阈值的颗粒)';
COMMENT ON COLUMN conversation_stats.rag_ref_count IS '参考匹配颗粒数';
COMMENT ON COLUMN conversation_stats.rag_none_count IS '无匹配次数(RAG返回空结果)';
COMMENT ON COLUMN conversation_stats.rag_avg_similarity IS '本轮RAG平均相似度';
COMMENT ON COLUMN conversation_stats.error_type IS '异常类型: NULL=正常, timeout, error, cancelled';
COMMENT ON COLUMN conversation_stats.is_test IS '是否Admin测试对话';
COMMENT ON COLUMN conversation_stats.llm_duration_ms IS 'LLM生成耗时(毫秒)';
COMMENT ON COLUMN conversation_stats.total_duration_ms IS '端到端总耗时(毫秒)';

CREATE INDEX idx_cs_skill_time ON conversation_stats(skill_id, created_at DESC);
CREATE INDEX idx_cs_conv ON conversation_stats(conversation_id);
CREATE INDEX idx_cs_skill_mode ON conversation_stats(skill_id, mode);
