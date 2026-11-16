-- ============================================================
-- sales-schema.sql — 销售复盘 + 企业 AI Key
--
-- 【部署】上线/新环境执行本文件，一条命令收敛到最终态：
--   psql -U <user> -d <database> -v ON_ERROR_STOP=1 -f scripts/sales-schema.sql
--
-- 【幂等】可重复执行：CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS /
--        CREATE INDEX IF NOT EXISTS。
--
-- 【重置】本文件不删数据。清空重建执行 scripts/sales-reset.sql（仅开发/测试）。
--
-- 前提：PostgreSQL 13+；company/user 表已存在；本库约定不建 FK，
--       引用完整性由 Service 校验并返回 404/409。
-- ============================================================

-- ============================================================
-- 一、企业 AI Key（W1）
-- ============================================================

CREATE TABLE IF NOT EXISTS public.company_ai_config (
    id                  uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id          uuid NOT NULL,
    provider            character varying(20) NOT NULL DEFAULT 'deepseek',
    base_url            character varying(300) NOT NULL,
    api_key_cipher      character varying(500) NOT NULL,
    key_last4           character varying(8) NOT NULL,
    model_name          character varying(100) NOT NULL DEFAULT 'deepseek-chat',
    status              character varying(20) NOT NULL DEFAULT 'enabled',
    fallback_enabled    boolean NOT NULL DEFAULT true,
    created_by          uuid,
    updated_by          uuid,
    created_at          timestamp without time zone NOT NULL DEFAULT now(),
    updated_at          timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT uq_cac_company_provider UNIQUE (company_id, provider),
    CONSTRAINT ck_cac_status CHECK (status IN ('enabled', 'disabled'))
);

-- 旧版本曾保留轮换前 Key 24 小时；现已改为审计记录废弃，不再落旧密文
ALTER TABLE public.company_ai_config
    DROP COLUMN IF EXISTS old_api_key_cipher,
    DROP COLUMN IF EXISTS old_key_expires_at;

COMMENT ON TABLE public.company_ai_config IS '企业 AI Key — AES-GCM 密文存储，未配置/停用按开关回退平台 Key';
COMMENT ON COLUMN public.company_ai_config.api_key_cipher IS 'PartnerCrypto AES-256-GCM 密文，禁止存明文';
COMMENT ON COLUMN public.company_ai_config.key_last4 IS '仅用于页面展示的末 4 位';

CREATE INDEX IF NOT EXISTS idx_cac_company
    ON public.company_ai_config(company_id);

-- Token 用量归属企业（平台 Key 与企业 Key 都记企业，便于成本归属）
ALTER TABLE public.token_usage_log
    ADD COLUMN IF NOT EXISTS company_id uuid;

CREATE INDEX IF NOT EXISTS idx_tul_company_date
    ON public.token_usage_log(company_id, usage_date);

-- ============================================================
-- 二、销售复盘（W2）
-- ============================================================

-- 1) 复盘会话
CREATE TABLE IF NOT EXISTS public.review_session (
    id                          uuid PRIMARY KEY,
    company_id                  uuid NOT NULL,
    reviewer_id                 uuid NOT NULL,
    coach_skill_id              uuid NOT NULL,
    coach_owner_id              uuid NOT NULL,
    coach_name                  character varying(100),
    coach_title                 character varying(200),
    customer_name               character varying(200),
    topic                       character varying(200),
    transcript                  text NOT NULL,
    transcript_chars            integer NOT NULL,
    source                      character varying(20) NOT NULL DEFAULT 'text',
    share_transcript_with_coach boolean NOT NULL DEFAULT false,
    status                      character varying(20) NOT NULL DEFAULT 'active',
    lead_id                     uuid,
    created_at                  timestamp without time zone NOT NULL DEFAULT now(),
    updated_at                  timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_rs_status CHECK (status IN ('active', 'report_ready', 'converted', 'closed')),
    CONSTRAINT ck_rs_chars CHECK (transcript_chars BETWEEN 1 AND 30000)
);

CREATE INDEX IF NOT EXISTS idx_rs_company_reviewer
    ON public.review_session(company_id, reviewer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rs_company_coach
    ON public.review_session(company_id, coach_owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rs_coach_skill
    ON public.review_session(coach_skill_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rs_company_created
    ON public.review_session(company_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rs_company_status_created
    ON public.review_session(company_id, status, created_at DESC);

-- 2) 对话段落（报告引用 segmentId）
CREATE TABLE IF NOT EXISTS public.review_segment (
    id          uuid PRIMARY KEY,
    session_id  uuid NOT NULL REFERENCES public.review_session(id) ON DELETE CASCADE,
    seq         integer NOT NULL,
    speaker     character varying(20) NOT NULL DEFAULT 'unknown',
    text        text NOT NULL,
    char_count  integer NOT NULL,
    created_at  timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT uq_rseg_session_seq UNIQUE (session_id, seq),
    CONSTRAINT ck_rseg_speaker CHECK (speaker IN ('sales', 'customer', 'unknown'))
);

CREATE INDEX IF NOT EXISTS idx_rseg_session
    ON public.review_segment(session_id, seq);

-- 3) 报告（版本化 + 异步 job）
CREATE TABLE IF NOT EXISTS public.review_report (
    id               uuid PRIMARY KEY,
    company_id       uuid NOT NULL,
    session_id       uuid NOT NULL REFERENCES public.review_session(id) ON DELETE CASCADE,
    version          integer NOT NULL,
    status           character varying(20) NOT NULL DEFAULT 'queued',
    model_name       character varying(100),
    prompt_version   character varying(20) NOT NULL DEFAULT 'v1',
    summary          text,
    payload          jsonb,
    error_message    text,
    attempts         integer NOT NULL DEFAULT 0,
    lease_owner      character varying(100),
    lease_expires_at timestamp without time zone,
    generated_at     timestamp without time zone,
    created_by       uuid,
    created_at       timestamp without time zone NOT NULL DEFAULT now(),
    updated_at       timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT uq_rrp_session_version UNIQUE (session_id, version),
    CONSTRAINT ck_rrp_status CHECK (status IN ('queued', 'generating', 'ready', 'failed')),
    CONSTRAINT ck_rrp_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_rrp_claim
    ON public.review_report(status, lease_expires_at) WHERE status IN ('queued', 'generating');
CREATE INDEX IF NOT EXISTS idx_rrp_company_created
    ON public.review_report(company_id, created_at DESC);
-- 同一会话最多一个在途报告：并发点「生成」时由数据库兜底
CREATE UNIQUE INDEX IF NOT EXISTS uq_rrp_session_active
    ON public.review_report(session_id) WHERE status IN ('queued', 'generating');

-- 4) 追问消息
CREATE TABLE IF NOT EXISTS public.review_message (
    id          uuid PRIMARY KEY,
    session_id  uuid NOT NULL REFERENCES public.review_session(id) ON DELETE CASCADE,
    company_id  uuid NOT NULL,
    role        character varying(20) NOT NULL,
    content     text NOT NULL,
    created_at  timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_rm_role CHECK (role IN ('user', 'assistant'))
);

CREATE INDEX IF NOT EXISTS idx_rm_session
    ON public.review_message(session_id, created_at);

-- 4.1) 请教练补充：每个复盘最多一条请求
CREATE TABLE IF NOT EXISTS public.review_coach_request (
    id            uuid PRIMARY KEY,
    company_id    uuid NOT NULL,
    session_id    uuid NOT NULL REFERENCES public.review_session(id) ON DELETE CASCADE,
    coach_user_id uuid NOT NULL,
    requested_by  uuid NOT NULL,
    message       character varying(500),
    status        character varying(20) NOT NULL DEFAULT 'pending',
    requested_at  timestamp without time zone NOT NULL DEFAULT now(),
    completed_at  timestamp without time zone,
    created_at    timestamp without time zone NOT NULL DEFAULT now(),
    updated_at    timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT uq_rcr_session UNIQUE (session_id),
    CONSTRAINT ck_rcr_status CHECK (status IN ('pending', 'done', 'cancelled'))
);

CREATE INDEX IF NOT EXISTS idx_rcr_coach
    ON public.review_coach_request(company_id, coach_user_id, status, requested_at DESC);

-- 4.2) 教练补充点评：追加写
CREATE TABLE IF NOT EXISTS public.review_coach_note (
    id            uuid PRIMARY KEY,
    company_id    uuid NOT NULL,
    session_id    uuid NOT NULL REFERENCES public.review_session(id) ON DELETE CASCADE,
    coach_user_id uuid NOT NULL,
    content       text NOT NULL,
    created_at    timestamp without time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rcn_session
    ON public.review_coach_note(session_id, created_at);

-- 5) 跟进任务
CREATE TABLE IF NOT EXISTS public.follow_up_task (
    id           uuid PRIMARY KEY,
    company_id   uuid NOT NULL,
    lead_id      uuid,
    review_id    uuid,
    owner_id     uuid NOT NULL,
    title        character varying(200) NOT NULL,
    channel      character varying(20),
    content      text,
    due_at       timestamp without time zone,
    status       character varying(20) NOT NULL DEFAULT 'pending',
    completed_at timestamp without time zone,
    created_by   uuid NOT NULL,
    created_at   timestamp without time zone NOT NULL DEFAULT now(),
    updated_at   timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_task_status CHECK (status IN ('pending', 'done', 'cancelled'))
);

ALTER TABLE public.follow_up_task
    ADD COLUMN IF NOT EXISTS due_reminded_at     timestamp without time zone,
    ADD COLUMN IF NOT EXISTS overdue_notified_at timestamp without time zone,
    ADD COLUMN IF NOT EXISTS escalated_at        timestamp without time zone,
    ADD COLUMN IF NOT EXISTS escalated_by        uuid;

CREATE INDEX IF NOT EXISTS idx_task_company_owner
    ON public.follow_up_task(company_id, owner_id, status, due_at);
CREATE INDEX IF NOT EXISTS idx_task_review
    ON public.follow_up_task(review_id);
CREATE INDEX IF NOT EXISTS idx_task_pending_due
    ON public.follow_up_task(due_at) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_task_owner_pending
    ON public.follow_up_task(company_id, owner_id, due_at) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_task_company_lead_due
    ON public.follow_up_task(company_id, lead_id, due_at DESC NULLS LAST);
CREATE UNIQUE INDEX IF NOT EXISTS uq_task_review_title
    ON public.follow_up_task(review_id, title) WHERE review_id IS NOT NULL;

-- 6) 线索（含查重列）
CREATE TABLE IF NOT EXISTS public.lead (
    id                    uuid PRIMARY KEY,
    company_id            uuid NOT NULL,
    owner_id              uuid NOT NULL,
    name                  character varying(200) NOT NULL,
    name_normalized       character varying(200) NOT NULL,
    contact_name          character varying(100),
    contact_phone         character varying(50),
    contact_phone_digits  character varying(30),
    need_summary          text,
    source                character varying(20) NOT NULL,
    source_review_id      uuid,
    status                character varying(20) NOT NULL DEFAULT 'new',
    next_follow_up_at     timestamp without time zone,
    created_by            uuid NOT NULL,
    created_at            timestamp without time zone NOT NULL DEFAULT now(),
    updated_at            timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_lead_status CHECK (status IN ('new', 'following', 'converted', 'invalid'))
);

ALTER TABLE public.lead
    ADD COLUMN IF NOT EXISTS last_activity_at timestamp without time zone;

UPDATE public.lead
   SET last_activity_at = COALESCE(last_activity_at, updated_at, created_at)
 WHERE last_activity_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_lead_dedupe_name
    ON public.lead(company_id, name_normalized);
CREATE INDEX IF NOT EXISTS idx_lead_dedupe_phone
    ON public.lead(company_id, contact_phone_digits) WHERE contact_phone_digits IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_lead_owner
    ON public.lead(company_id, owner_id, status, next_follow_up_at);
CREATE INDEX IF NOT EXISTS idx_lead_activity_order
    ON public.lead(company_id, last_activity_at DESC);
CREATE INDEX IF NOT EXISTS idx_lead_company_created
    ON public.lead(company_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_lead_owner_activity
    ON public.lead(company_id, owner_id, last_activity_at DESC);
CREATE INDEX IF NOT EXISTS idx_lead_company_activity
    ON public.lead(company_id, last_activity_at DESC);
-- 一个复盘最多转出一条线索；并发转线索的最终兜底（P1-2）。
-- 历史库若已有重复数据，不阻断部署：跳过索引并告警，由应用层行锁兜底。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.lead
         WHERE source_review_id IS NOT NULL
         GROUP BY source_review_id HAVING count(*) > 1
    ) THEN
        RAISE WARNING 'lead.source_review_id 存在重复数据，跳过 uq_lead_source_review';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_source_review
            ON public.lead(source_review_id) WHERE source_review_id IS NOT NULL;
    END IF;
END $$;

-- 7) 线索联系人（一个线索最多一个主联系人）
CREATE TABLE IF NOT EXISTS public.lead_contact (
    id            uuid PRIMARY KEY,
    company_id    uuid NOT NULL,
    lead_id       uuid NOT NULL,
    name          character varying(100) NOT NULL,
    phone         character varying(50),
    phone_digits  character varying(30),
    role          character varying(30),
    is_primary    boolean NOT NULL DEFAULT false,
    note          character varying(500),
    created_by    uuid NOT NULL,
    created_at    timestamp without time zone NOT NULL DEFAULT now(),
    updated_at    timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_lead_contact_role CHECK (
        role IS NULL OR role IN ('decision_maker','influencer','user','gatekeeper','other')
    )
);

CREATE INDEX IF NOT EXISTS idx_lead_contact_lead
    ON public.lead_contact(company_id, lead_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_contact_primary
    ON public.lead_contact(lead_id) WHERE is_primary;

INSERT INTO public.lead_contact
    (id, company_id, lead_id, name, phone, phone_digits, role, is_primary,
     created_by, created_at, updated_at)
SELECT gen_random_uuid(), l.company_id, l.id, l.contact_name, l.contact_phone,
       l.contact_phone_digits, 'other', true, l.created_by, l.created_at, l.updated_at
  FROM public.lead l
 WHERE l.contact_name IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM public.lead_contact c WHERE c.lead_id = l.id);

-- 8) 线索活动时间线
CREATE TABLE IF NOT EXISTS public.lead_activity (
    id           uuid PRIMARY KEY,
    company_id   uuid NOT NULL,
    lead_id      uuid NOT NULL,
    type         character varying(40) NOT NULL,
    content      text,
    actor_id     uuid,
    source_type  character varying(30),
    source_id    uuid,
    occurred_at  timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_lead_activity_type CHECK (type IN (
        'created','status_changed','field_updated','note',
        'contact_added','contact_updated','contact_removed',
        'task_created','task_transferred','task_escalated'
    ))
);

CREATE INDEX IF NOT EXISTS idx_lead_activity_lead
    ON public.lead_activity(company_id, lead_id, occurred_at DESC);

-- 9) 存量任务回填 lead_id
UPDATE public.follow_up_task t
   SET lead_id = s.lead_id
  FROM public.review_session s
 WHERE t.review_id = s.id
   AND t.lead_id IS NULL
   AND s.lead_id IS NOT NULL;

-- 10) 站内信兜底（培训脚本已建则跳过；销售提醒复用同一张表）
CREATE TABLE IF NOT EXISTS public.user_notification (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    type character varying(40) NOT NULL,
    title character varying(255) NOT NULL,
    content text,
    link character varying(500),
    biz_type character varying(40),
    biz_id uuid,
    dedupe_key character varying(200),
    read_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_un_dedupe
    ON public.user_notification(dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_un_user_unread
    ON public.user_notification(user_id, read_at, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_un_company_created
    ON public.user_notification(company_id, created_at DESC);
