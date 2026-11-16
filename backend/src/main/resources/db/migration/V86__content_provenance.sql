-- 内容溯源：知识/案例条目 -> 来源上传任务 / 来源文档
-- 目的：从一条已生效知识或案例，能追到「哪份文档 / 哪本书 / 哪个上传任务 / 哪个卡片版本」。
-- source 文本会带「《文档名/书名》 · 章节」，这里再存结构化字段用于详情页与原文下载。

ALTER TABLE knowledge_entry
    ADD COLUMN IF NOT EXISTS source_task_id UUID,
    ADD COLUMN IF NOT EXISTS source_document VARCHAR(255);

ALTER TABLE knowledge_entry
    ALTER COLUMN source TYPE VARCHAR(1000);

ALTER TABLE industry_case
    ADD COLUMN IF NOT EXISTS source_task_id UUID,
    ADD COLUMN IF NOT EXISTS source_document VARCHAR(255);

ALTER TABLE industry_case
    ALTER COLUMN source TYPE VARCHAR(1000);

CREATE INDEX IF NOT EXISTS idx_knowledge_entry_source_task
    ON knowledge_entry (source_task_id);

CREATE INDEX IF NOT EXISTS idx_industry_case_source_task
    ON industry_case (source_task_id);

COMMENT ON COLUMN knowledge_entry.source_task_id IS '来源上传任务 ID；手工录入/外部导入为空';
COMMENT ON COLUMN knowledge_entry.source_document IS '来源文档名或书名；手工录入为空';
COMMENT ON COLUMN industry_case.source_task_id IS '来源上传任务 ID；手工录入/外部导入为空';
COMMENT ON COLUMN industry_case.source_document IS '来源文档名或书名；手工录入为空';
