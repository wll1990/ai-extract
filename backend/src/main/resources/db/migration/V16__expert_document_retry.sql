-- V16__expert_document_retry_count.sql
-- 萃取师文档解析失败重试次数。解析异常不再置假数据，直接标 failed + 抛异常；
-- 调度器重扫时跳过已 failed 的文档，管理员可通过 /retry 手动重置。

ALTER TABLE expert_document ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0;

COMMENT ON COLUMN expert_document.retry_count IS '解析失败重试次数，上限3次';
