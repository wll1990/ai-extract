-- 案例上传：记录提取失败章节数。
-- 与知识上传的 failed_sections 对齐：单章模型调用失败不再静默，任务状态仍可 done，
-- 但页面必须提示「部分章节未提取」，避免用户误以为整篇都处理完了。
ALTER TABLE case_upload_task
    ADD COLUMN IF NOT EXISTS failed_sections INTEGER NOT NULL DEFAULT 0;
