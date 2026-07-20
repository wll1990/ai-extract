-- V17__auto_insight_and_candidate_grain.sql
-- 自动发现引擎：洞察记录 + 候选颗粒 + knowledge_gap 向量化
--
-- auto_insight: AI 从数据中自动发现的规律/异常
-- candidate_grain: AI 生成的候选技能颗粒，管理员审核通过后入库
-- knowledge_gap.embedding: pgvector 向量，用于缺口聚类

-- ============================================================
-- 1. knowledge_gap 加 embedding 列（复用 DashScope text-embedding-v4）
-- ============================================================
ALTER TABLE knowledge_gap ADD COLUMN IF NOT EXISTS embedding VECTOR(1024);
COMMENT ON COLUMN knowledge_gap.embedding IS '缺口文本的向量表示，用于 pgvector 余弦聚类';

-- ============================================================
-- 2. auto_insight 表
-- ============================================================
CREATE TABLE IF NOT EXISTS auto_insight (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID,                              -- NULL = 跨分身聚合洞察
    type VARCHAR(50) NOT NULL,                  -- gap_burst / satisfaction_drop / hit_rate_drop / new_pattern / inactive
    title VARCHAR(500) NOT NULL,
    description TEXT,
    severity VARCHAR(20) NOT NULL DEFAULT 'info', -- critical / warning / info
    evidence JSONB NOT NULL DEFAULT '{}',        -- 数据依据：涉及对话/颗粒/反馈 ID、统计数据
    candidate_grain_id UUID,                     -- 关联的候选颗粒（可为空，洞察不一定产生候选颗粒）
    status VARCHAR(20) NOT NULL DEFAULT 'active', -- active / resolved / ignored
    resolved_by UUID,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE auto_insight IS 'AI 自动发现的洞察——从数据中识别规律/异常/新场景';
COMMENT ON COLUMN auto_insight.type IS '洞察类型：gap_burst=缺口爆发, satisfaction_drop=满意率骤降, hit_rate_drop=命中率下降, new_pattern=发现新高频场景, inactive=分身不活跃';
COMMENT ON COLUMN auto_insight.severity IS '严重程度：critical=需立即处理, warning=建议关注, info=仅供参考';
COMMENT ON COLUMN auto_insight.evidence IS 'JSONB 数据依据：{positive_samples, negative_samples, satisfaction_delta, source_conv_ids, source_grain_ids, source_gap_ids}';
COMMENT ON COLUMN auto_insight.candidate_grain_id IS '关联的 AI 生成的候选颗粒（为 NULL 表示该洞察未产生候选颗粒）';

CREATE INDEX IF NOT EXISTS idx_auto_insight_skill ON auto_insight(skill_id);
CREATE INDEX IF NOT EXISTS idx_auto_insight_status ON auto_insight(status);
CREATE INDEX IF NOT EXISTS idx_auto_insight_severity ON auto_insight(severity);
CREATE INDEX IF NOT EXISTS idx_auto_insight_created ON auto_insight(created_at DESC);

-- ============================================================
-- 3. candidate_grain 表
-- ============================================================
CREATE TABLE IF NOT EXISTS candidate_grain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID,                              -- NULL = 跨分身通用颗粒
    scene_tag VARCHAR(50) NOT NULL,
    scene_description TEXT,
    expert_thought TEXT NOT NULL,
    standard_script TEXT,
    common_mistakes TEXT,
    applicable_condition TEXT,
    source_insight_id UUID NOT NULL,            -- 关联的洞察记录
    source_evidence JSONB NOT NULL DEFAULT '{}', -- 数据依据
    status VARCHAR(20) NOT NULL DEFAULT 'pending_review', -- pending_review / approved / rejected
    reviewed_by UUID,
    reviewed_at TIMESTAMP,
    note TEXT,                                  -- 审核备注
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE candidate_grain IS 'AI 自动生成的候选技能颗粒——管理员审核通过后写入 experience_grain';
COMMENT ON COLUMN candidate_grain.scene_tag IS '场景标签，如 报价-ROI锚定';
COMMENT ON COLUMN candidate_grain.expert_thought IS 'AI 发现的销售策略/思考方式';
COMMENT ON COLUMN candidate_grain.standard_script IS 'AI 生成的推荐话术';
COMMENT ON COLUMN candidate_grain.common_mistakes IS 'AI 识别的常见话术错误';
COMMENT ON COLUMN candidate_grain.applicable_condition IS '适用此颗粒的场景条件';
COMMENT ON COLUMN candidate_grain.source_insight_id IS '产生此候选颗粒的洞察记录 ID';
COMMENT ON COLUMN candidate_grain.source_evidence IS 'JSONB 数据依据：{positive_samples, negative_samples, satisfaction_delta, source_conv_ids, source_grain_ids}';
COMMENT ON COLUMN candidate_grain.status IS '审核状态：pending_review=待审核, approved=已通过(已写入experience_grain), rejected=已拒绝';

CREATE INDEX IF NOT EXISTS idx_candidate_grain_skill ON candidate_grain(skill_id);
CREATE INDEX IF NOT EXISTS idx_candidate_grain_status ON candidate_grain(status);
CREATE INDEX IF NOT EXISTS idx_candidate_grain_insight ON candidate_grain(source_insight_id);
CREATE INDEX IF NOT EXISTS idx_candidate_grain_created ON candidate_grain(created_at DESC);
