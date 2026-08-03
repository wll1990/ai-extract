-- 报告分享：share_code + share_enabled + html_path
-- 注：旧列 word_url、ppt_url、file_status、web_published 已从 Java 实体移除，
-- 但暂不 DROP COLUMN（向后兼容旧数据，后续 V4 清理）
ALTER TABLE public.report ADD COLUMN IF NOT EXISTS share_code VARCHAR(16);
ALTER TABLE public.report ADD COLUMN IF NOT EXISTS share_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE public.report ADD COLUMN IF NOT EXISTS html_path VARCHAR(500);
CREATE UNIQUE INDEX IF NOT EXISTS idx_report_share_code ON public.report(share_code);
