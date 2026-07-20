ALTER TABLE skill_material ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0;
COMMENT ON COLUMN skill_material.retry_count IS '解析/清洗失败重试次数，上限3次';
