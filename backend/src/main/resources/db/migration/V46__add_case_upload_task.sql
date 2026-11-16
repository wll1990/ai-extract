-- =============================================
-- 案例批量上传任务（与 knowledge_upload_task 对称）
-- 追踪「文档上传 → AI 萃取案例」异步状态
-- =============================================

CREATE TABLE IF NOT EXISTS case_upload_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES company(id),
    file_name VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'extracting' CHECK (status IN ('extracting', 'done', 'failed')),
    produced_count INT NOT NULL DEFAULT 0,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_case_upload_task_company ON case_upload_task(company_id);
CREATE INDEX IF NOT EXISTS idx_case_upload_task_status ON case_upload_task(status);
