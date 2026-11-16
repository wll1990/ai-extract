-- 复盘报告开始生成时间：前端展示「排队多久 / 生成多久」，不依赖 updated_at 猜测。
ALTER TABLE public.review_report ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;

COMMENT ON COLUMN public.review_report.started_at IS 'Worker 首次领取并开始生成的时间';

-- 存量 generating 报告用 updated_at 近似回填，避免前端显示空白。
UPDATE public.review_report
   SET started_at = updated_at
 WHERE started_at IS NULL AND status IN ('generating', 'ready', 'failed');
