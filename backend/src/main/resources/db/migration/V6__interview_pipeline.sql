-- V6__interview_pipeline.sql
-- 访谈管道打通：领域隔离 + 颗粒溯源 + 萃取师来源追溯

-- 1. InterviewSession 存储领域标识
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS domain VARCHAR(64);
COMMENT ON COLUMN interview_session.domain IS '领域ID，如 sales.b2b_enterprise / finance.secondary_market';

-- 2. ExperienceGrain 颗粒来源追溯
ALTER TABLE experience_grain ADD COLUMN IF NOT EXISTS source_type VARCHAR(20) DEFAULT 'file_upload';
ALTER TABLE experience_grain ADD COLUMN IF NOT EXISTS source_interview_id UUID;
COMMENT ON COLUMN experience_grain.source_type IS '颗粒来源: file_upload | interview';
COMMENT ON COLUMN experience_grain.source_interview_id IS '关联 interview_session.id，访谈产出的颗粒可追溯到具体会话';

-- 3. ExpertSkill 支持 interview 来源 + 领域隔离
ALTER TABLE expert_skill ADD COLUMN IF NOT EXISTS domain VARCHAR(64);
ALTER TABLE expert_skill ADD COLUMN IF NOT EXISTS source_session_id UUID;
ALTER TABLE expert_skill ADD COLUMN IF NOT EXISTS source_content TEXT;
COMMENT ON COLUMN expert_skill.domain IS '领域ID，隔离不同域的萃取师经验';
COMMENT ON COLUMN expert_skill.source_session_id IS '元访谈 session ID，来源为 interview 时关联';
COMMENT ON COLUMN expert_skill.source_content IS '元访谈转录文本，供分析管道处理';

-- 4. ExpertGrain 领域隔离
ALTER TABLE expert_grain ADD COLUMN IF NOT EXISTS domain VARCHAR(64);
COMMENT ON COLUMN expert_grain.domain IS '领域ID，继承自 ExpertSkill.domain';
