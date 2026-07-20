CREATE EXTENSION IF NOT EXISTS vector;

-- =============================================
-- 基础业务表
-- =============================================

CREATE TABLE company (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    logo_url VARCHAR(500),
    brand_color VARCHAR(7) DEFAULT '#1A2B4C',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE "user" (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES company(id),
    name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'employee' CHECK (role IN ('super_admin', 'space_owner', 'employee')),
    avatar_url VARCHAR(500),
    phone VARCHAR(20),
    account VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_user_company ON "user"(company_id);
CREATE INDEX idx_user_account ON "user"(account);

CREATE TABLE space (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES "user"(id),
    title VARCHAR(200) NOT NULL,
    description TEXT,
    tags JSONB DEFAULT '[]',
    is_public BOOLEAN DEFAULT false,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'paused', 'archived')),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_space_user ON space(user_id);

CREATE TABLE interview_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL REFERENCES space(id),
    topic VARCHAR(200),
    status VARCHAR(20) DEFAULT 'created' CHECK (status IN ('created', 'in_progress', 'paused', 'completed', 'abandoned', 'failed')),
    current_phase VARCHAR(20) DEFAULT 'opening' CHECK (current_phase IN ('opening', 'storytelling', 'modeling', 'closing')),
    collect_case_story BOOLEAN DEFAULT false,
    collect_steps BOOLEAN DEFAULT false,
    collect_decision BOOLEAN DEFAULT false,
    collect_mindset BOOLEAN DEFAULT false,
    collect_boundary BOOLEAN DEFAULT false,
    collect_checklist BOOLEAN DEFAULT false,
    invite_code VARCHAR(50),
    invited_by UUID REFERENCES "user"(id),
    expert_skill_id UUID,
    last_active_at TIMESTAMP DEFAULT NOW(),
    created_at TIMESTAMP DEFAULT NOW(),
    finished_at TIMESTAMP
);
CREATE INDEX idx_session_space ON interview_session(space_id);
CREATE INDEX idx_session_status ON interview_session(status);

CREATE TABLE interview_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    role VARCHAR(10) NOT NULL CHECK (role IN ('ai', 'user', 'system')),
    content TEXT NOT NULL,
    phase VARCHAR(20),
    depth INT DEFAULT 0,
    stage_status JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_message_session ON interview_message(session_id);
CREATE INDEX idx_message_created ON interview_message(session_id, created_at);

CREATE TABLE report (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL REFERENCES space(id),
    session_id UUID REFERENCES interview_session(id),
    title VARCHAR(200) NOT NULL,
    subtitle VARCHAR(500),
    content_json JSONB NOT NULL DEFAULT '{}',
    word_url VARCHAR(500),
    ppt_url VARCHAR(500),
    web_published BOOLEAN DEFAULT true,
    file_status VARCHAR(20) DEFAULT 'synced' CHECK (file_status IN ('synced', 'pending_regenerate')),
    rating DECIMAL(2,1) DEFAULT 4.5,
    view_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_report_space ON report(space_id);

CREATE TABLE experience_grain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL REFERENCES space(id),
    report_id UUID NOT NULL REFERENCES report(id) ON DELETE CASCADE,
    scene_tag VARCHAR(50),
    scene_description TEXT,
    judgment_signal TEXT,
    expert_thought TEXT,
    standard_script TEXT,
    common_mistakes TEXT,
    applicable_condition TEXT,
    embedding VECTOR(1536),
    helpful_count INT DEFAULT 0,
    unhelpful_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_grain_space ON experience_grain(space_id);
CREATE INDEX idx_grain_scene ON experience_grain(scene_tag);

CREATE TABLE skill (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL REFERENCES space(id) UNIQUE,
    model_name VARCHAR(100) DEFAULT 'deepseek-chat',
    model_config JSONB DEFAULT '{}',
    status VARCHAR(20) DEFAULT 'generating' CHECK (status IN ('generating', 'active', 'failed')),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE tool (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID REFERENCES space(id),
    report_id UUID REFERENCES report(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL CHECK (type IN ('poster', 'card', 'checklist', 'script', 'trainer_manual', 'assessment')),
    name VARCHAR(200) NOT NULL,
    file_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_tool_space ON tool(space_id);
CREATE INDEX idx_tool_type ON tool(type);

CREATE TABLE im_channel (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES company(id),
    channel_type VARCHAR(20) NOT NULL CHECK (channel_type IN ('feishu', 'wecom', 'wechat', 'dingtalk')),
    enabled BOOLEAN DEFAULT false,
    config JSONB NOT NULL DEFAULT '{}',
    linked_skills JSONB DEFAULT '[]',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- =============================================
-- 元萃取相关表（新增）
-- =============================================

CREATE TABLE expert_skill (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    source_type VARCHAR(20) DEFAULT 'interview' CHECK (source_type IN ('interview', 'document', 'hybrid')),
    style_tags JSONB DEFAULT '[]',
    industry_tags JSONB DEFAULT '[]',
    seniority VARCHAR(50),
    skill_file VARCHAR(500),
    report_id UUID REFERENCES report(id),
    grain_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'analyzing', 'extracting', 'active', 'failed')),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE expert_grain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expert_id UUID NOT NULL REFERENCES expert_skill(id) ON DELETE CASCADE,
    category VARCHAR(50) NOT NULL CHECK (category IN ('judgment_intuition', 'mental_model', 'failure_lesson', 'validation_method', 'metaphor_framework', 'rhythm_sense', 'typing_method')),
    source_type VARCHAR(20) DEFAULT 'interview' CHECK (source_type IN ('interview', 'document')),
    scene_description TEXT,
    knowledge_content TEXT NOT NULL,
    application_rule TEXT,
    priority INT DEFAULT 1 CHECK (priority BETWEEN 1 AND 5),
    consensus_type VARCHAR(20) DEFAULT 'single' CHECK (consensus_type IN ('single', 'consensus', 'conflict')),
    consensus_expert_ids JSONB DEFAULT '[]',
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'under_review', 'deprecated')),
    embedding VECTOR(1536),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_expert_grain_category ON expert_grain(category);
CREATE INDEX idx_expert_grain_expert ON expert_grain(expert_id);
CREATE INDEX idx_expert_grain_status ON expert_grain(status);
CREATE INDEX idx_expert_grain_source ON expert_grain(source_type);

CREATE TABLE expert_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expert_id UUID NOT NULL REFERENCES expert_skill(id) ON DELETE CASCADE,
    file_name VARCHAR(500) NOT NULL,
    file_url VARCHAR(500),
    file_type VARCHAR(50),
    file_size BIGINT,
    parsed_content TEXT,
    status VARCHAR(20) DEFAULT 'uploaded' CHECK (status IN ('uploaded', 'parsing', 'parsed', 'pending_manual', 'failed')),
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_expert_document_expert ON expert_document(expert_id);