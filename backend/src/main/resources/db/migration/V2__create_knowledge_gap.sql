COMMENT ON TABLE knowledge_gap IS '知识缺口——用户提问后RAG检索无匹配颗粒时记录，用于发现分身知识盲区';

CREATE TABLE knowledge_gap (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    space_id UUID NOT NULL,
    query TEXT NOT NULL,
    scene_tag VARCHAR(100),
    attempted_query_count INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'open',
    resolved_by VARCHAR(100),
    resolved_at TIMESTAMP,
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN knowledge_gap.id IS '主键';
COMMENT ON COLUMN knowledge_gap.skill_id IS '所属分身ID';
COMMENT ON COLUMN knowledge_gap.space_id IS '所属空间ID';
COMMENT ON COLUMN knowledge_gap.query IS '用户提问原文';
COMMENT ON COLUMN knowledge_gap.scene_tag IS '系统推测的场景标签';
COMMENT ON COLUMN knowledge_gap.attempted_query_count IS '该场景累计出现次数';
COMMENT ON COLUMN knowledge_gap.status IS '状态: open/reviewing/resolved/ignored';
COMMENT ON COLUMN knowledge_gap.resolved_by IS '处理人';
COMMENT ON COLUMN knowledge_gap.resolved_at IS '处理时间';
COMMENT ON COLUMN knowledge_gap.note IS '管理员备注';

CREATE INDEX idx_kg_skill_status ON knowledge_gap(skill_id, status);
CREATE INDEX idx_kg_skill_time ON knowledge_gap(skill_id, created_at DESC);
CREATE INDEX idx_kg_space ON knowledge_gap(space_id);
