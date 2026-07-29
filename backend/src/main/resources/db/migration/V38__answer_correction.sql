-- ============================================================
-- V12: 回答矫正记录
-- ============================================================
-- 用途: Admin 标记 AI 错误回答并提交正确答案，联动颗粒权重衰减
-- 触发: POST /api/v1/admin/insights/corrections
-- 影响: 关联颗粒 weight × 0.7 衰减 + 写审计日志
-- ============================================================

CREATE TABLE IF NOT EXISTS answer_correction (
    -- 主键
    id UUID PRIMARY KEY,
    -- 关联的分身（用于权限校验和统计）
    skill_id UUID NOT NULL,
    -- 关联的会话（可空 — admin 可能离线矫正）
    conversation_id UUID,
    -- 被矫正的具体 AI 消息
    message_id UUID,
    -- 用户当时问的问题
    original_query TEXT,
    -- AI 的错误回答
    bad_response TEXT,
    -- Admin 给出的正确答案
    corrected_response TEXT,
    -- 涉及的颗粒 ID 列表（JSON 数组: ["uuid1","uuid2"]）
    grain_ids JSONB,
    -- 操作人
    corrected_by VARCHAR(100),
    -- 矫正时间
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 按分身查矫正历史（运营分析用）
CREATE INDEX IF NOT EXISTS idx_correction_skill ON answer_correction(skill_id);

COMMENT ON TABLE answer_correction IS '回答矫正记录 — Admin 标记 AI 错误回答并联动颗粒权重衰减';
COMMENT ON COLUMN answer_correction.skill_id IS '关联的分身 ID';
COMMENT ON COLUMN answer_correction.conversation_id IS '关联的会话 ID（可空 — 离线矫正）';
COMMENT ON COLUMN answer_correction.message_id IS '被矫正的 AI 消息 ID';
COMMENT ON COLUMN answer_correction.original_query IS '用户当时问的问题';
COMMENT ON COLUMN answer_correction.bad_response IS 'AI 的错误回答';
COMMENT ON COLUMN answer_correction.corrected_response IS 'Admin 给出的正确答案';
COMMENT ON COLUMN answer_correction.grain_ids IS '涉及的颗粒 ID 列表（JSONB 数组）— 矫正后这些颗粒 weight × 0.7';
COMMENT ON COLUMN answer_correction.corrected_by IS '操作人标识';
COMMENT ON COLUMN answer_correction.created_at IS '矫正时间';
