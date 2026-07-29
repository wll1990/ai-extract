-- ============================================================
-- V10: 经验颗粒全文检索（BM25 近似）
-- ============================================================
-- 用途: 支持 Hybrid Search (Dense 向量 + Sparse 全文 → RRF 融合)
--       弥补纯向量搜索对产品名、编号、术语等精确匹配的短板
-- 原理: tsvector 按 'simple' 配置分词（中文按字切分），ts_rank 计算 BM25 近似分
--       权重: scene_tag(A) > scene_description(B) > expert_thought/standard_script(C)
-- 依赖: pg_trgm 扩展（PostgreSQL 自带，无需 CREATE EXTENSION）
-- ============================================================

ALTER TABLE experience_grain
    -- 全文检索向量列（GENERATED ALWAYS — 自动从文本字段派生，无需应用层维护）
    ADD COLUMN IF NOT EXISTS search_text tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', COALESCE(scene_tag,          '')), 'A') ||  -- 场景标签权重最高
        setweight(to_tsvector('simple', COALESCE(scene_description,  '')), 'B') ||  -- 场景描述
        setweight(to_tsvector('simple', COALESCE(expert_thought,     '')), 'C') ||  -- 销冠思路
        setweight(to_tsvector('simple', COALESCE(standard_script,    '')), 'C')     -- 标准话术
    ) STORED;

COMMENT ON COLUMN experience_grain.search_text IS '全文检索向量 — ts_rank BM25 近似排序，GIN 索引加速';

-- GIN 倒排索引 — 加速 @@ 匹配和 ts_rank 排序
CREATE INDEX IF NOT EXISTS idx_grain_fts ON experience_grain USING GIN(search_text);
COMMENT ON INDEX idx_grain_fts IS '全文检索 GIN 倒排索引 — 支持 @@ 匹配和 ts_rank 排序';
