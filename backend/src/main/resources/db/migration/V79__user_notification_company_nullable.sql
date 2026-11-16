-- NT-01 C 端用户没有企业归属，站内信需要允许 company_id 为空
ALTER TABLE public.user_notification
    ALTER COLUMN company_id DROP NOT NULL;
