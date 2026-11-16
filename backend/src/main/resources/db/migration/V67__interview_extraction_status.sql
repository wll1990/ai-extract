-- 访谈萃取状态：让「颗粒是否已产出」可见、可重试
ALTER TABLE public.interview_session
    ADD COLUMN IF NOT EXISTS extraction_status varchar(20) NOT NULL DEFAULT 'pending';

ALTER TABLE public.interview_session
    ADD COLUMN IF NOT EXISTS extraction_error text;

ALTER TABLE public.interview_session
    ADD COLUMN IF NOT EXISTS extracted_at timestamp;

COMMENT ON COLUMN public.interview_session.extraction_status IS 'pending/extracting/done/failed；访谈完成后异步萃取颗粒的状态';
COMMENT ON COLUMN public.interview_session.extraction_error IS '最近一次萃取失败原因';
COMMENT ON COLUMN public.interview_session.extracted_at IS '最近一次萃取成功时间';

-- 存量：已完成访谈默认视为已处理（历史数据无法精确判断，避免前端全部显示未萃取）
UPDATE public.interview_session
SET extraction_status = CASE WHEN status = 'completed' THEN 'done' ELSE 'pending' END
WHERE extraction_status = 'pending';
