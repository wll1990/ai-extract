-- =============================================
-- 知识库 + 案例库 + 上传任务（《补全方案》第一块）
-- 经验/知识/案例三条管道隔离，本迁移只建新表，不改销冠流程
-- =============================================

-- 知识库条目：事实型知识（产品/竞品/价格/合规/术语/方法论）
-- company_id NULL = 平台公共知识（所有租户可见、模板化授权）
CREATE TABLE IF NOT EXISTS knowledge_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID REFERENCES company(id),
    source_type VARCHAR(20) NOT NULL CHECK (source_type IN ('public', 'platform', 'deidentified', 'tenant')),
    category VARCHAR(20) NOT NULL CHECK (category IN ('compliance', 'price', 'product', 'policy', 'competitor', 'terminology', 'methodology', 'industry_intro')),
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    effective_at TIMESTAMP,
    expire_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('draft', 'pending', 'active', 'deprecated')),
    source VARCHAR(500),
    created_by UUID,
    embedding vector(1024),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_knowledge_company ON knowledge_entry(company_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_category ON knowledge_entry(category);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_type ON knowledge_entry(source_type);
CREATE INDEX IF NOT EXISTS idx_knowledge_status ON knowledge_entry(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_hnsw ON knowledge_entry USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='200');

-- 行业案例库：过程型案例（客户画像/推进过程/结果/可复用点）
-- company_id NULL = 平台公共案例
CREATE TABLE IF NOT EXISTS industry_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID REFERENCES company(id),
    title VARCHAR(255) NOT NULL,
    customer_profile JSONB,
    funnel_stage VARCHAR(20),
    scene_tag VARCHAR(50),
    dialogue_quote JSONB,
    objection TEXT,
    emotion_change TEXT,
    key_actions TEXT,
    scripts TEXT,
    advance_nodes TEXT,
    result VARCHAR(10) CHECK (result IN ('win', 'loss', 'delay')),
    reusable_points TEXT,
    failure_points TEXT,
    source VARCHAR(500),
    contributor_id UUID,
    confidence DOUBLE PRECISION,
    version INT NOT NULL DEFAULT 1,
    effective_at TIMESTAMP,
    expire_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('draft', 'pending', 'active', 'deprecated')),
    embedding vector(1024),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_case_company ON industry_case(company_id);
CREATE INDEX IF NOT EXISTS idx_case_funnel ON industry_case(funnel_stage);
CREATE INDEX IF NOT EXISTS idx_case_scene ON industry_case(scene_tag);
CREATE INDEX IF NOT EXISTS idx_case_result ON industry_case(result);
CREATE INDEX IF NOT EXISTS idx_case_status ON industry_case(status);
CREATE INDEX IF NOT EXISTS idx_case_embedding_hnsw ON industry_case USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='200');

-- 知识批量上传任务：追踪「文档上传 → AI 萃取」异步状态
CREATE TABLE IF NOT EXISTS knowledge_upload_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES company(id),
    file_name VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'extracting' CHECK (status IN ('extracting', 'done', 'failed')),
    produced_count INT NOT NULL DEFAULT 0,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_upload_task_company ON knowledge_upload_task(company_id);
CREATE INDEX IF NOT EXISTS idx_upload_task_status ON knowledge_upload_task(status);
