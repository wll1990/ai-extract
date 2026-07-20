-- V15__create_extraction_drop_log.sql
-- 萃取管道淘汰明细：让"颗粒为什么没出来"可查。
-- 三个记录点：chunk 去重丢弃 / 对抗验证拒绝 / 验证异常整批放行（fail-open）。

CREATE TABLE extraction_drop_log (
    id UUID PRIMARY KEY,
    material_id UUID NOT NULL,
    space_id UUID NOT NULL,
    stage VARCHAR(30) NOT NULL CHECK (stage IN ('dedup','verification','verification_skipped')),
    chunk_index INT,                -- dedup: 被丢 chunk 序号
    content_preview TEXT,           -- 被丢内容摘要（chunk 前500字 / 候选"场景|思考|话术"截断）
    collided_grain_id UUID,         -- dedup: 撞上的存量颗粒
    similarity NUMERIC(4,3),        -- dedup: Jaccard 相似度
    detail JSONB,                   -- verification: AI打分 {specificity,composite,verdict}; skipped: {batchSize,reason}
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_drop_log_material ON extraction_drop_log(material_id);
CREATE INDEX idx_drop_log_space_stage ON extraction_drop_log(space_id, stage);

COMMENT ON TABLE extraction_drop_log IS '萃取管道淘汰明细：chunk去重丢弃/对抗验证拒绝/验证跳过，用于排查颗粒缺失';
