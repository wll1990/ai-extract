-- =============================================
-- 章节级进度 + 断点续传（T16）
-- 上传任务新增：文件路径（续传重解析）、章节总数、已处理章节数、失败原因
-- =============================================

ALTER TABLE knowledge_upload_task
    ADD COLUMN IF NOT EXISTS file_path VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS total_sections INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS processed_sections INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);

ALTER TABLE case_upload_task
    ADD COLUMN IF NOT EXISTS file_path VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS total_sections INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS processed_sections INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);
