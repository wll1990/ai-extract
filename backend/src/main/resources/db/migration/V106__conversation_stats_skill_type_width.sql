-- skill_type 存储 individual / organization / enterprise，
-- 原 VARCHAR(10) 装不下 organization（12 字符），组织分身对话统计一直写入失败。
ALTER TABLE conversation_stats
    ALTER COLUMN skill_type TYPE VARCHAR(20);
