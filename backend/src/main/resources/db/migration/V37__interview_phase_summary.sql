-- ============================================================
-- V11: 访谈阶段摘要
-- ============================================================
-- 用途: 长访谈 token 优化 — 阶段完成时异步生成 AI 摘要
--       后续阶段用摘要替代全量历史消息，避免 1h 访谈 token 爆炸
-- 触发: InterviewService.markPhaseCompleteFlux() → @Async generatePhaseSummary()
-- 使用: InterviewService.buildMessagesList() 读摘要 + 当前阶段全量消息
-- 范围: sales 访谈和 expert 元访谈通用（按 sessionId 查，不区分类型）
-- ============================================================

CREATE TABLE IF NOT EXISTS interview_phase_summary (
    -- 主键
    id UUID PRIMARY KEY,
    -- 关联的访谈会话
    session_id UUID NOT NULL,
    -- 阶段标识: opening / storytelling / modeling / closing
    phase VARCHAR(20) NOT NULL,
    -- 阶段中文名: 开场定调 / 故事深描 / 模型提炼 / 收网确认
    phase_label VARCHAR(20) NOT NULL,
    -- AI 生成的本阶段关键信息摘要（3-5 句话）
    summary TEXT NOT NULL,
    -- 摘要生成时间
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 按会话查已完成的阶段摘要（buildMessagesList 用）
CREATE INDEX IF NOT EXISTS idx_phase_summary_session ON interview_phase_summary(session_id, phase);

COMMENT ON TABLE interview_phase_summary IS '访谈阶段摘要 — 阶段完成时异步生成，后续阶段替代全量历史减少 token';
COMMENT ON COLUMN interview_phase_summary.session_id IS '关联的访谈会话 ID';
COMMENT ON COLUMN interview_phase_summary.phase IS '阶段标识: opening / storytelling / modeling / closing';
COMMENT ON COLUMN interview_phase_summary.phase_label IS '阶段中文标签: 开场定调 / 故事深描 / 模型提炼 / 收网确认';
COMMENT ON COLUMN interview_phase_summary.summary IS 'AI 生成的本阶段已收集关键信息摘要';
COMMENT ON COLUMN interview_phase_summary.created_at IS '摘要生成时间';
