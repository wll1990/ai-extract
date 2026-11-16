-- 上传准入与书籍模式：内容质量门禁、二手来源标记、跳过原因
-- 1) 上传任务记录素材类型（internal=企业自有资料，book=外部书籍/课程）
ALTER TABLE knowledge_upload_task ADD COLUMN IF NOT EXISTS source_kind varchar(20) NOT NULL DEFAULT 'internal';
ALTER TABLE knowledge_upload_task ADD COLUMN IF NOT EXISTS skipped_count integer NOT NULL DEFAULT 0;
ALTER TABLE knowledge_upload_task ADD COLUMN IF NOT EXISTS guide_message text;

ALTER TABLE case_upload_task ADD COLUMN IF NOT EXISTS source_kind varchar(20) NOT NULL DEFAULT 'internal';
ALTER TABLE case_upload_task ADD COLUMN IF NOT EXISTS skipped_count integer NOT NULL DEFAULT 0;
ALTER TABLE case_upload_task ADD COLUMN IF NOT EXISTS guide_message text;

-- 2) 知识草稿记录质量分与问题，未达标的草稿不允许直接采纳
ALTER TABLE knowledge_upload_draft ADD COLUMN IF NOT EXISTS quality_score integer;
ALTER TABLE knowledge_upload_draft ADD COLUMN IF NOT EXISTS quality_issues text;
ALTER TABLE knowledge_upload_draft ADD COLUMN IF NOT EXISTS quality_warnings text;

-- 3) 案例记录来源性质（内部实战 / 书籍二手）与质量问题
ALTER TABLE industry_case ADD COLUMN IF NOT EXISTS origin varchar(20) NOT NULL DEFAULT 'internal';
ALTER TABLE industry_case ADD COLUMN IF NOT EXISTS quality_issues text;
ALTER TABLE industry_case ADD COLUMN IF NOT EXISTS quality_warnings text;
