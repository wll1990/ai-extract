-- SA-08 教练台账任务化：已读标记、回复内容、回复时间
ALTER TABLE public.review_coach_request
    ADD COLUMN IF NOT EXISTS read_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS replied_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reply_content TEXT;
