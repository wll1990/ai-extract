-- 知识/案例：版本链根、来源类型、消费回流与企业维度
-- 1) 版本链根：同一 root 下只允许一个 active，避免分叉后两套口径同时生效
ALTER TABLE public.knowledge_entry ADD COLUMN IF NOT EXISTS root_id uuid;
UPDATE public.knowledge_entry SET root_id = id WHERE root_id IS NULL;
ALTER TABLE public.knowledge_entry ALTER COLUMN root_id SET NOT NULL;

ALTER TABLE public.industry_case ADD COLUMN IF NOT EXISTS root_id uuid;
UPDATE public.industry_case SET root_id = id WHERE root_id IS NULL;
ALTER TABLE public.industry_case ALTER COLUMN root_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_root ON public.knowledge_entry (root_id);
CREATE INDEX IF NOT EXISTS idx_case_root ON public.industry_case (root_id);

-- 2) 知识来源类型：书籍卡片与实战知识在编辑/审批时的准入口径不同
ALTER TABLE public.knowledge_entry
    ADD COLUMN IF NOT EXISTS origin varchar(20) NOT NULL DEFAULT 'internal',
    ADD COLUMN IF NOT EXISTS book_mode boolean NOT NULL DEFAULT false;

-- 3) 引用日志：企业维度 + 使用场景（聊天/培训/评测），用于消费回流
ALTER TABLE public.knowledge_case_retrieve_log
    ADD COLUMN IF NOT EXISTS company_id uuid,
    ADD COLUMN IF NOT EXISTS usage_type varchar(32) NOT NULL DEFAULT 'skill_chat',
    ADD COLUMN IF NOT EXISTS usage_ref_id uuid;

CREATE INDEX IF NOT EXISTS idx_kcrl_company_usage
    ON public.knowledge_case_retrieve_log (company_id, usage_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kcrl_usage_ref
    ON public.knowledge_case_retrieve_log (usage_ref_id);

-- 4) 会话统计：企业维度 + 知识/案例命中计数
ALTER TABLE public.conversation_stats
    ADD COLUMN IF NOT EXISTS company_id uuid,
    ADD COLUMN IF NOT EXISTS kc_knowledge_count int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS kc_case_count int NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_cs_company_created
    ON public.conversation_stats (company_id, created_at DESC);

-- 5) 助手消息持久化知识/案例参考（刷新历史后仍可追溯）
ALTER TABLE public.skill_message ADD COLUMN IF NOT EXISTS source_refs jsonb;

-- 6) 生效时间参与检索
CREATE INDEX IF NOT EXISTS idx_knowledge_effective
    ON public.knowledge_entry (status, effective_at, expire_at);
CREATE INDEX IF NOT EXISTS idx_industry_case_effective
    ON public.industry_case (status, effective_at, expire_at);
