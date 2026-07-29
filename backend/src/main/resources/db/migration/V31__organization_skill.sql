-- ============================================================
-- V31：组织/综合分身 + conversation_stats 语义统一。
-- 1. 新建 organization_skill 表（独立实体，不复用 Skill）
-- 2. conversation_stats 加 skill_type 列，统一三种管道语义
-- 3. skill 表加 org_type 列
-- ============================================================

-- 1. 组织分身表
CREATE TABLE IF NOT EXISTS organization_skill (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    member_skill_ids JSONB NOT NULL DEFAULT '[]',
    avatar_url VARCHAR(500),
    opening_message TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    domain VARCHAR(20) DEFAULT 'sales',

    -- 统计（SkillStatsScheduler 每5分钟聚合写入）
    conversation_count INTEGER DEFAULT 0,
    user_count INTEGER DEFAULT 0,
    satisfaction_rate INTEGER DEFAULT 0,
    last_active_at TIMESTAMP,

    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_org_skill_company ON organization_skill(company_id);
CREATE INDEX IF NOT EXISTS idx_org_skill_status ON organization_skill(status);

-- 2. conversation_stats — 统一三种管道语义
-- individual = 个人分身, organization = 组织分身, enterprise = 企业调度
ALTER TABLE conversation_stats
    ADD COLUMN IF NOT EXISTS skill_type VARCHAR(10) DEFAULT 'individual';

UPDATE conversation_stats SET skill_type = 'individual' WHERE skill_type IS NULL;

-- 3. skill — 分身类型标记
ALTER TABLE skill
    ADD COLUMN IF NOT EXISTS org_type VARCHAR(20) DEFAULT 'individual';

UPDATE skill SET org_type = 'individual' WHERE org_type IS NULL;
