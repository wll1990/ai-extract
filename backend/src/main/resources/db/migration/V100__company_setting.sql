-- 企业级在线开关：key-value 扩展表，供「实时生效」的业务配置使用。
-- 首个用途：practice.kc_enabled（对练是否检索知识/案例）。
-- 值统一按文本存储，由各开关的 Service 解析（布尔用 "true"/"false"）。
CREATE TABLE IF NOT EXISTS public.company_setting (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES public.company(id) ON DELETE CASCADE,
    setting_key VARCHAR(64) NOT NULL,
    setting_value TEXT NOT NULL,
    updated_by UUID,
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_setting UNIQUE (company_id, setting_key)
);

CREATE INDEX IF NOT EXISTS idx_company_setting_company
    ON public.company_setting (company_id);

COMMENT ON TABLE public.company_setting IS '企业级在线开关/配置，读取不缓存，保存后下一请求即生效';
