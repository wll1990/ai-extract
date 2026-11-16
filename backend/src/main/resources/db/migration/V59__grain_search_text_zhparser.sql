-- K21: search_text 原来用 'simple' 生成，BM25 查询用 zhparser_cfg，中文词元基本不相交，
-- Hybrid 实际退化为 Dense。统一改为 zhparser_cfg 生成。
ALTER TABLE experience_grain DROP COLUMN IF EXISTS search_text;

ALTER TABLE experience_grain ADD COLUMN search_text tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('zhparser_cfg', COALESCE(scene_tag, '')), 'A')
    || setweight(to_tsvector('zhparser_cfg', COALESCE(scene_description, '')), 'B')
    || setweight(to_tsvector('zhparser_cfg', COALESCE(expert_thought, '')), 'C')
    || setweight(to_tsvector('zhparser_cfg', COALESCE(standard_script, '')), 'C')
) STORED;

CREATE INDEX IF NOT EXISTS idx_grain_fts ON experience_grain USING gin (search_text);
