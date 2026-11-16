-- =============================================
-- 支持 super_admin 上传公共知识/案例
-- 上传任务 company_id 允许 NULL（NULL=平台公共上传）
-- =============================================

ALTER TABLE knowledge_upload_task ALTER COLUMN company_id DROP NOT NULL;
ALTER TABLE case_upload_task ALTER COLUMN company_id DROP NOT NULL;
