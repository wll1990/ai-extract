-- ============================================================
-- V55__demo_request.sql — 门户「预约演示」留资
--
-- 公开提交（/public/demo-request），运营在后台读取。
-- 幂等：可重复执行。
-- ============================================================

CREATE TABLE IF NOT EXISTS public.demo_request (
    id            uuid PRIMARY KEY,
    company_name  character varying(200) NOT NULL,
    contact_name  character varying(100) NOT NULL,
    role          character varying(100),
    team_size     character varying(50),
    contact       character varying(200) NOT NULL,
    message       text,
    source        character varying(50) NOT NULL DEFAULT 'portal',
    status        character varying(20) NOT NULL DEFAULT 'new',
    created_at    timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_demo_request_status CHECK (status IN ('new', 'contacted', 'closed'))
);

CREATE INDEX IF NOT EXISTS idx_demo_request_created_at
    ON public.demo_request(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_demo_request_contact_created
    ON public.demo_request(contact, created_at DESC);
