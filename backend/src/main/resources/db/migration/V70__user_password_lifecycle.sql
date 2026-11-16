-- 账号安全：首次登录/管理员重置后强制改密
ALTER TABLE public."user"
    ADD COLUMN IF NOT EXISTS must_change_password boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN public."user".must_change_password IS 'true=下次登录必须修改密码（管理员重置/首次登录）';
