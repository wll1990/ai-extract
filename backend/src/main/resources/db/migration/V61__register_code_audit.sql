-- 注册码使用审计：记录最后一个使用者的 userId 与使用时间
ALTER TABLE public.company_register_code
    ADD COLUMN IF NOT EXISTS last_used_by uuid;

ALTER TABLE public.company_register_code
    ADD COLUMN IF NOT EXISTS last_used_at timestamp without time zone;

COMMENT ON COLUMN public.company_register_code.last_used_by IS '最近一次使用该注册码注册的用户ID';
COMMENT ON COLUMN public.company_register_code.last_used_at IS '最近一次使用该注册码的时间';
