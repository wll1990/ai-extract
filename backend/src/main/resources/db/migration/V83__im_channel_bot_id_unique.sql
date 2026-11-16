-- bot_id 是所有渠道的全局路由键（ImInboundService 用 findByBotId 做 O(1) 路由），
-- 此前只有代码约定，没有数据库约束：两个企业绑定同一个 appId 会静默串号。
-- 空值表示「未完成发布」的行（publishing/failed 复用），不参与唯一性。
CREATE UNIQUE INDEX IF NOT EXISTS uk_im_channel_bot_id
    ON public.im_channel (bot_id)
    WHERE bot_id IS NOT NULL;

COMMENT ON COLUMN public.im_channel.bot_id IS
    '平台 bot 全局路由键：飞书 app_id / 企业微信 corpId:agentId / 钉钉 appKey';
