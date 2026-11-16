-- KN-06 上传萃取结果先预览再采纳：
-- 萃取阶段只落 draft，管理端确认后按批采纳为 pending 知识，或整批拒绝。
CREATE TABLE IF NOT EXISTS public.knowledge_upload_draft (
    id          UUID PRIMARY KEY,
    task_id     UUID NOT NULL,
    section_no  INTEGER NOT NULL DEFAULT 0,
    title       VARCHAR(500) NOT NULL,
    content     TEXT NOT NULL,
    category    VARCHAR(50) NOT NULL,
    source      VARCHAR(500),
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kud_task_status
    ON public.knowledge_upload_draft (task_id, status);
