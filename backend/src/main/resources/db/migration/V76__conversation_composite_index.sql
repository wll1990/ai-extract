-- 会话列表按 skill + user 过滤并按 updated_at 排序，单列索引会回表排序。
CREATE INDEX IF NOT EXISTS idx_skill_conv_skill_user_updated
    ON public.skill_conversation USING btree (skill_id, user_id, updated_at DESC);
