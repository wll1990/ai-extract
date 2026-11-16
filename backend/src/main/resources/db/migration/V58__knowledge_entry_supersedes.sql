-- 知识版本链：AI 整理生成的新版先保持 draft，旧版继续 active 供检索；
-- 审批通过时根据 supersedes_id 下线旧版，避免审批前知识空窗（K12）。
ALTER TABLE knowledge_entry ADD COLUMN IF NOT EXISTS supersedes_id UUID;
