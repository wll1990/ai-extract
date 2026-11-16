-- IM 桥接身份 + 发布失败原因
-- 目的：多桥部署时 /internal/im/{type}/active 只下发本桥负责的 bot，appSecret 不再全量下发
ALTER TABLE public.im_channel
    ADD COLUMN IF NOT EXISTS bridge_id varchar(64) NOT NULL DEFAULT 'default';

ALTER TABLE public.im_channel
    ADD COLUMN IF NOT EXISTS last_error varchar(500);

COMMENT ON COLUMN public.im_channel.bridge_id IS '负责该 bot 的传输桥标识（feishu-bot 实例）';
COMMENT ON COLUMN public.im_channel.last_error IS '最近一次发布/重连失败原因';

CREATE INDEX IF NOT EXISTS idx_im_channel_bridge_status
    ON public.im_channel (bridge_id, channel_type, status);
