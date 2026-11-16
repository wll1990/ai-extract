-- OPS-03 对话写入幂等：客户端为每条消息生成 clientMessageId，
-- 同一会话内重复提交（重试/双击）只落一条 user + 一条 assistant。
ALTER TABLE public.skill_message
    ADD COLUMN IF NOT EXISTS client_message_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_skill_message_client
    ON public.skill_message (conversation_id, client_message_id, role)
    WHERE client_message_id IS NOT NULL;
