-- 企业总调度（/skills/enterprise/chat）没有具体分身，缺口应按企业/空间记录。
-- 原来 skill_id NOT NULL 导致 insert 异常被吞，企业级缺口全部丢失。
ALTER TABLE knowledge_gap ALTER COLUMN skill_id DROP NOT NULL;
