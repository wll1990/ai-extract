-- ============================================================
-- V54：补齐 IM / 外部渠道列
--
-- 这些列此前只在生产库手工执行，未进入 Flyway；空库迁移后
-- Hibernate validate 会因缺列启动失败。全部使用 IF NOT EXISTS，可重复执行。
-- ============================================================

ALTER TABLE public.im_channel
    ADD COLUMN IF NOT EXISTS bot_id varchar(128),
    ADD COLUMN IF NOT EXISTS secrets jsonb,
    ADD COLUMN IF NOT EXISTS skill_id uuid,
    ADD COLUMN IF NOT EXISTS status varchar(20);

ALTER TABLE public.skill_conversation
    ADD COLUMN IF NOT EXISTS channel varchar(20),
    ADD COLUMN IF NOT EXISTS external_chat_id varchar(128),
    ADD COLUMN IF NOT EXISTS external_user_id varchar(128),
    ADD COLUMN IF NOT EXISTS external_user_name varchar(128);

ALTER TABLE public.skill_message
    ADD COLUMN IF NOT EXISTS channel varchar(20),
    ADD COLUMN IF NOT EXISTS external_user_name varchar(128);
