-- ============================================================
-- evaluation-schema.sql — 评测中心全量建表（P0 题集管理 + P1 评测运行，最终态）
--
-- 【部署】上线/新环境只需执行本文件，一条命令收敛到最终态：
--   psql -U <user> -d <database> -v ON_ERROR_STOP=1 -f scripts/evaluation-schema.sql
--
-- 【幂等】本文件可重复执行：
--   · 建表用 CREATE TABLE IF NOT EXISTS
--   · 补列用 ALTER TABLE ... ADD COLUMN IF NOT EXISTS
--   · 唯一/枚举约束用 DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT
--   · 索引用 CREATE INDEX IF NOT EXISTS
--   因此对「已存在的旧环境」执行会自动补齐缺失的列/约束/索引，不会报错。
--   ⚠️ 幂等只覆盖本文件定义过的对象；已删列/改类型请走 evaluation-reset.sql 重建。
--
-- 【重置】本文件不删数据。需要清空重建请执行 scripts/evaluation-reset.sql（仅开发/测试）。
--
-- 前提：PostgreSQL 13+（gen_random_uuid 内置）；jsonb 内置；company/skill/user 表已存在
--
-- 【外键决策 D1】本库 public schema 现有外键数为 0，全局约定为「应用层校验 + Service 显式 409」，
--   故本模块 8 张表均不建 FK。引用完整性由 EvaluationRunService（创建时校验 dataset/skill 归属）
--   与 DatasetService.deleteItem（删题前查 evaluation_run_item 引用）保证。
--
-- 【CHECK 决策 D4】计划「通用约定」要求状态列 varchar + CHECK；库内另 24 张表均有，
--   本模块 8 张表全部补齐（共 26 条），不再需要单独的补齐脚本。
-- ============================================================

-- ============================================================
-- 一、P0 题集管理（4 表）
-- ============================================================

-- 题集：评测/培训共用的「版本化题集」主表；company_id NULL = 平台公共模板
CREATE TABLE IF NOT EXISTS public.evaluation_dataset (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid                                            , -- 所属企业ID，NULL=平台公共模板
    name character varying(255) NOT NULL                       , -- 题集名
    description text                                           , -- 描述
    purpose character varying(20) NOT NULL                     , -- 用途：regression/acceptance/training
    status character varying(20) NOT NULL DEFAULT 'draft'      , -- 状态：draft/in_review/approved/published/retired
    current_version integer NOT NULL DEFAULT 1                 , -- 当前版本号
    item_count integer NOT NULL DEFAULT 0                      , -- 最新发布版题数（冗余加速列表）
    created_by uuid                                            , -- 创建人ID
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.evaluation_dataset IS '评测中心题集 — 评测/培训共用的版本化题集';

CREATE INDEX IF NOT EXISTS idx_ds_company_status ON public.evaluation_dataset(company_id, status);
CREATE INDEX IF NOT EXISTS idx_ds_purpose ON public.evaluation_dataset(purpose);

-- 题集版本：发布即冻结；后续编辑复制新 draft 版本，不覆盖已发布
CREATE TABLE IF NOT EXISTS public.evaluation_dataset_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    dataset_id uuid NOT NULL                                   , -- 所属题集ID
    version integer NOT NULL                                   , -- 版本号
    status character varying(20) NOT NULL DEFAULT 'draft'      , -- 状态：draft/published/retired
    item_count integer NOT NULL DEFAULT 0                      , -- 冻结时快照题数
    frozen_at timestamp without time zone                      , -- 发布时间
    created_by uuid                                            , -- 创建人ID
    created_at timestamp without time zone NOT NULL DEFAULT now()  -- 创建时间
);
COMMENT ON TABLE public.evaluation_dataset_version IS '题集版本 — 发布即冻结，UNIQUE(dataset_id,version)';

CREATE INDEX IF NOT EXISTS idx_ds_version_ds ON public.evaluation_dataset_version(dataset_id);

-- 题目：单题；retrieval_expected 用于研发回归，expected_points 用于验收/考试
CREATE TABLE IF NOT EXISTS public.evaluation_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid                                            , -- 所属企业ID（冗余自 dataset）
    dataset_id uuid NOT NULL                                   , -- 所属题集ID
    version integer NOT NULL                                   , -- 所属版本号
    question text NOT NULL                                     , -- 题干/输入
    context text                                               , -- 固定上下文（可比较性）
    expected_points jsonb                                      , -- 预期要点（金标）
    forbidden_points jsonb                                     , -- 禁止项（红线）
    citation_requirement jsonb                                 , -- 引用要求
    scene_tag character varying(50)                            , -- 适用场景
    gold_basis text                                            , -- 金标依据/出处
    scoring_rule jsonb                                         , -- 评分规则（rubric 版本快照）
    retrieval_expected jsonb                                   , -- 检索期望（回归用途）
    fact_key character varying(64)                             , -- 事实标识：素材句子归一化后的 SHA-256（去重主键，手工题为 NULL）
    fact_quote character varying(200)                          , -- 事实原文：被吸附的素材句子（截断 200 字）
    order_no integer NOT NULL DEFAULT 0                        , -- 题序
    status character varying(20) NOT NULL DEFAULT 'active'     , -- 状态：active/deprecated
    created_by uuid                                            , -- 创建人ID
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.evaluation_item IS '评测题目 — 单题，含预期要点/禁止项/检索期望/评分规则';

CREATE INDEX IF NOT EXISTS idx_item_ds_ver ON public.evaluation_item(dataset_id, version);

-- 旧环境补列（幂等）：事实级去重（docs/40）
ALTER TABLE public.evaluation_item ADD COLUMN IF NOT EXISTS fact_key   character varying(64);
ALTER TABLE public.evaluation_item ADD COLUMN IF NOT EXISTS fact_quote character varying(200);
COMMENT ON COLUMN public.evaluation_item.fact_key   IS '事实标识：素材句子归一化后的 SHA-256，去重主键（手工题为 NULL）';
COMMENT ON COLUMN public.evaluation_item.fact_quote IS '事实原文：被吸附的素材句子（截断 200 字），用于 prompt 避让与人工核对';

-- 覆盖「题集+版本 → 已有事实标识」查询；partial 索引跳过手工题
CREATE INDEX IF NOT EXISTS idx_item_ds_fact
    ON public.evaluation_item(dataset_id, version, fact_key)
    WHERE fact_key IS NOT NULL;

-- AI 生成候选任务（异步轮询）
CREATE TABLE IF NOT EXISTS public.evaluation_generation_job (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    dataset_id uuid NOT NULL                                   , -- 所属题集ID
    source_type character varying(20) NOT NULL                 , -- 来源类型：knowledge/case
    status character varying(20) NOT NULL DEFAULT 'running'    , -- 状态：running/done/failed
    produced_count integer NOT NULL DEFAULT 0                  , -- 产出候选数
    error_message character varying(1000)                      , -- 失败原因
    created_by uuid                                            , -- 创建人ID
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.evaluation_generation_job IS 'AI 生成候选任务 — 从知识/案例生成题目候选（异步轮询）';

-- 同一题集同一时间只允许一个 running 任务（防双击重复出题），DB 级原子兜底
CREATE UNIQUE INDEX IF NOT EXISTS uq_gen_job_running
    ON public.evaluation_generation_job(dataset_id) WHERE status = 'running';

-- ============================================================
-- 二、P1 评测运行（4 表）
-- ============================================================

-- 运行批次：题集冻结版本 × 分身；执行态与审核态分离
CREATE TABLE IF NOT EXISTS public.evaluation_run (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid NOT NULL                                   , -- 所属企业ID（企业隔离）
    dataset_id uuid NOT NULL                                   , -- 题集ID
    dataset_version integer NOT NULL                           , -- 绑定的冻结版本号
    skill_id uuid NOT NULL                                     , -- 被评测分身ID
    model_config jsonb                                         , -- 模型/温度等配置快照
    skill_snapshot jsonb                                       , -- 分身画像/提示词快照（可复现性）
    purpose character varying(20) NOT NULL                     , -- 用途：regression/acceptance
    status character varying(20) NOT NULL DEFAULT 'queued'     , -- 执行态：queued/running/completed/partial_failed/failed/cancel_requested/cancelled
    review_status character varying(20) NOT NULL DEFAULT 'not_ready', -- 审核态：not_ready/pending_review/finalized
    started_at timestamp without time zone                     , -- 开始执行时间（报告耗时起点）
    finished_at timestamp without time zone                    , -- 结束时间（报告耗时终点）
    total_items integer NOT NULL DEFAULT 0                     , -- 总题数
    completed_items integer NOT NULL DEFAULT 0                 , -- 执行完成题数
    error_items integer NOT NULL DEFAULT 0                     , -- 执行错误题数
    passed_items integer NOT NULL DEFAULT 0                    , -- 通过题数
    failed_items integer NOT NULL DEFAULT 0                    , -- 未通过题数
    undetermined_items integer NOT NULL DEFAULT 0              , -- 未判定题数
    score_avg numeric(6,2)                                     , -- 平均分（0-100）
    score_detail jsonb                                         , -- 聚合分明细
    usage_tokens bigint NOT NULL DEFAULT 0                     , -- 评测消耗 token（单独计量）
    usage_cost numeric(12,6) NOT NULL DEFAULT 0                , -- 评测消耗费用
    idempotency_key character varying(100)                     , -- 幂等键（防双击/重放）
    created_by uuid                                            , -- 创建人ID
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.evaluation_run IS '评测运行批次 — 题集版本×分身的执行记录，执行态与审核态分离';
COMMENT ON COLUMN public.evaluation_run.idempotency_key IS '幂等键：同企业内唯一，防前端双击/重放建重复批次';
COMMENT ON COLUMN public.evaluation_run.started_at IS '开始执行时间：worker 首次领取时写入，报告耗时起点';
COMMENT ON COLUMN public.evaluation_run.finished_at IS '结束时间：批次进入终态时写入，报告耗时终点';
COMMENT ON COLUMN public.evaluation_run.usage_tokens IS '评测专用用量，不计入分身对话/业务活跃度统计';

-- 旧环境补列（幂等）
ALTER TABLE public.evaluation_run ADD COLUMN IF NOT EXISTS started_at  timestamp without time zone;
ALTER TABLE public.evaluation_run ADD COLUMN IF NOT EXISTS finished_at timestamp without time zone;
ALTER TABLE public.evaluation_run ADD COLUMN IF NOT EXISTS idempotency_key character varying(100);

CREATE INDEX IF NOT EXISTS idx_run_company_status ON public.evaluation_run(company_id, status);
CREATE INDEX IF NOT EXISTS idx_run_skill ON public.evaluation_run(skill_id);
CREATE INDEX IF NOT EXISTS idx_run_dataset ON public.evaluation_run(dataset_id, dataset_version);

-- 幂等键：同企业内唯一；NULL 不参与
CREATE UNIQUE INDEX IF NOT EXISTS uq_run_idempotency
    ON public.evaluation_run(company_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- 单题执行：一题一次尝试一行；执行态 / 业务结论 / 重试三维分离
CREATE TABLE IF NOT EXISTS public.evaluation_run_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    run_id uuid NOT NULL                                       , -- 所属批次ID
    item_id uuid NOT NULL                                      , -- 题目ID
    item_version integer NOT NULL                              , -- 题目版本快照
    status character varying(20) NOT NULL DEFAULT 'pending'    , -- 执行态：pending/generating/scoring/completed/error
    verdict character varying(20)                              , -- 业务结论：pass/fail/undetermined（人工可改）
    ai_answer text                                             , -- 分身回答原文
    retrieval_evidence jsonb                                   , -- 检索命中证据
    rule_check_result jsonb                                    , -- 规则检查（禁止项/引用要求）
    ai_score jsonb                                             , -- LLM 评分原分（含 confidence）
    error_message text                                         , -- 执行错误原因
    attempt_no integer NOT NULL DEFAULT 1                      , -- 重试次数（新尝试不覆盖原记录）
    lease_owner character varying(100)                         , -- 领取 worker 标识（租约）
    lease_expires_at timestamp without time zone               , -- 租约到期时间（超时可重新领取）
    heartbeat_at timestamp without time zone                   , -- 心跳时间（中断恢复判据）
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.evaluation_run_item IS '评测单题执行 — 一次尝试一行，重试新增 attempt_no 不覆盖证据';
COMMENT ON COLUMN public.evaluation_run_item.verdict IS '业务结论，与执行态 status 独立，人工复核可改';

CREATE INDEX IF NOT EXISTS idx_run_item_run ON public.evaluation_run_item(run_id, status);
-- 引用保护：删题前 existsByItemId 走本索引，避免全表扫
CREATE INDEX IF NOT EXISTS idx_run_item_item ON public.evaluation_run_item(item_id);
-- worker 原子领取：只扫描可领取行。
-- ⚠️ 领取 SQL 必须是 status IN ('pending','generating') AND (lease_expires_at IS NULL OR lease_expires_at < now())：
--    只捞 pending 会让进程中断后停在 generating 的行永久卡死，违反「租约超时可恢复」。
CREATE INDEX IF NOT EXISTS idx_run_item_claim
    ON public.evaluation_run_item(status, lease_expires_at)
    WHERE status IN ('pending', 'generating');
-- 重试幂等：同批次同题同尝试号唯一
CREATE UNIQUE INDEX IF NOT EXISTS uq_run_item_attempt
    ON public.evaluation_run_item(run_id, item_id, attempt_no);

-- 人工复核：追加式，保留 AI 原分
CREATE TABLE IF NOT EXISTS public.evaluation_review (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    run_item_id uuid NOT NULL                                  , -- 单题执行ID
    reviewer_id uuid NOT NULL                                  , -- 复核人ID
    action character varying(20) NOT NULL                      , -- 动作：adjust/undetermined/confirm
    reason text NOT NULL                                       , -- 理由（必填）
    original_score jsonb                                       , -- AI 原分（不可丢）
    adjusted_score jsonb                                       , -- 人工改分
    created_at timestamp without time zone NOT NULL DEFAULT now()  -- 创建时间
);
COMMENT ON TABLE public.evaluation_review IS '评测人工复核 — 追加式，保留 AI 原分与必填理由';

CREATE INDEX IF NOT EXISTS idx_review_run_item ON public.evaluation_review(run_item_id);

-- 验收报告：版本化，修订产生新版本不覆盖历史
CREATE TABLE IF NOT EXISTS public.evaluation_report (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    run_id uuid NOT NULL                                       , -- 所属批次ID
    version integer NOT NULL DEFAULT 1                         , -- 报告版本号
    status character varying(20) NOT NULL DEFAULT 'draft'      , -- 状态：draft/finalized
    summary jsonb                                              , -- 汇总：总题/成功/通过/红线失败/未判定/耗时/用量
    content text                                               , -- 报告正文（冻结时生成）
    finalized_by uuid                                          , -- 定稿人ID
    finalized_at timestamp without time zone                   , -- 定稿时间
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.evaluation_report IS '评测验收报告 — 版本化，修订产生新版本不覆盖历史';

CREATE INDEX IF NOT EXISTS idx_report_run ON public.evaluation_report(run_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_report_run_version
    ON public.evaluation_report(run_id, version);

-- ============================================================
-- 三、约束（枚举 + 数值域，共 26 条）
--    DROP IF EXISTS + ADD 保证可重复执行；新增取值必须同时改这里和实体常量
-- ============================================================

-- ---- P0：evaluation_dataset ----
ALTER TABLE public.evaluation_dataset DROP CONSTRAINT IF EXISTS chk_ds_status;
ALTER TABLE public.evaluation_dataset ADD CONSTRAINT chk_ds_status CHECK (status IN
    ('draft', 'in_review', 'approved', 'published', 'retired'));
ALTER TABLE public.evaluation_dataset DROP CONSTRAINT IF EXISTS chk_ds_purpose;
ALTER TABLE public.evaluation_dataset ADD CONSTRAINT chk_ds_purpose CHECK (purpose IN
    ('regression', 'acceptance', 'training'));
ALTER TABLE public.evaluation_dataset DROP CONSTRAINT IF EXISTS chk_ds_current_version;
ALTER TABLE public.evaluation_dataset ADD CONSTRAINT chk_ds_current_version CHECK (current_version >= 1);
ALTER TABLE public.evaluation_dataset DROP CONSTRAINT IF EXISTS chk_ds_item_count;
ALTER TABLE public.evaluation_dataset ADD CONSTRAINT chk_ds_item_count CHECK (item_count >= 0);

-- ---- P0：evaluation_dataset_version ----
ALTER TABLE public.evaluation_dataset_version DROP CONSTRAINT IF EXISTS chk_ds_ver_status;
ALTER TABLE public.evaluation_dataset_version ADD CONSTRAINT chk_ds_ver_status CHECK (status IN
    ('draft', 'published', 'retired'));
ALTER TABLE public.evaluation_dataset_version DROP CONSTRAINT IF EXISTS chk_ds_ver_version;
ALTER TABLE public.evaluation_dataset_version ADD CONSTRAINT chk_ds_ver_version CHECK (version >= 1);
ALTER TABLE public.evaluation_dataset_version DROP CONSTRAINT IF EXISTS chk_ds_ver_item_count;
ALTER TABLE public.evaluation_dataset_version ADD CONSTRAINT chk_ds_ver_item_count CHECK (item_count >= 0);
-- 版本唯一：原脚本用裸 ADD CONSTRAINT（重复执行会报错），此处改为幂等写法
ALTER TABLE public.evaluation_dataset_version DROP CONSTRAINT IF EXISTS uq_ds_version;
ALTER TABLE public.evaluation_dataset_version ADD CONSTRAINT uq_ds_version UNIQUE (dataset_id, version);

-- ---- P0：evaluation_item ----
ALTER TABLE public.evaluation_item DROP CONSTRAINT IF EXISTS chk_item_status;
ALTER TABLE public.evaluation_item ADD CONSTRAINT chk_item_status CHECK (status IN ('active', 'deprecated'));
ALTER TABLE public.evaluation_item DROP CONSTRAINT IF EXISTS chk_item_version;
ALTER TABLE public.evaluation_item ADD CONSTRAINT chk_item_version CHECK (version >= 1);
ALTER TABLE public.evaluation_item DROP CONSTRAINT IF EXISTS chk_item_order_no;
ALTER TABLE public.evaluation_item ADD CONSTRAINT chk_item_order_no CHECK (order_no >= 0);

-- ---- P0：evaluation_generation_job ----
ALTER TABLE public.evaluation_generation_job DROP CONSTRAINT IF EXISTS chk_gen_job_status;
ALTER TABLE public.evaluation_generation_job ADD CONSTRAINT chk_gen_job_status CHECK (status IN
    ('running', 'done', 'failed'));
ALTER TABLE public.evaluation_generation_job DROP CONSTRAINT IF EXISTS chk_gen_job_source_type;
ALTER TABLE public.evaluation_generation_job ADD CONSTRAINT chk_gen_job_source_type CHECK (source_type IN
    ('knowledge', 'case'));
ALTER TABLE public.evaluation_generation_job DROP CONSTRAINT IF EXISTS chk_gen_job_produced_count;
ALTER TABLE public.evaluation_generation_job ADD CONSTRAINT chk_gen_job_produced_count CHECK (produced_count >= 0);

-- ---- P1：evaluation_run ----
ALTER TABLE public.evaluation_run DROP CONSTRAINT IF EXISTS chk_run_status;
ALTER TABLE public.evaluation_run ADD CONSTRAINT chk_run_status CHECK (status IN
    ('queued', 'running', 'completed', 'partial_failed', 'failed', 'cancel_requested', 'cancelled'));
ALTER TABLE public.evaluation_run DROP CONSTRAINT IF EXISTS chk_run_review_status;
ALTER TABLE public.evaluation_run ADD CONSTRAINT chk_run_review_status CHECK (review_status IN
    ('not_ready', 'pending_review', 'finalized'));
ALTER TABLE public.evaluation_run DROP CONSTRAINT IF EXISTS chk_run_purpose;
ALTER TABLE public.evaluation_run ADD CONSTRAINT chk_run_purpose CHECK (purpose IN ('regression', 'acceptance'));
ALTER TABLE public.evaluation_run DROP CONSTRAINT IF EXISTS chk_run_counters;
ALTER TABLE public.evaluation_run ADD CONSTRAINT chk_run_counters CHECK (
    total_items >= 0 AND completed_items >= 0 AND error_items >= 0
    AND passed_items >= 0 AND failed_items >= 0 AND undetermined_items >= 0);
ALTER TABLE public.evaluation_run DROP CONSTRAINT IF EXISTS chk_run_score_avg;
ALTER TABLE public.evaluation_run ADD CONSTRAINT chk_run_score_avg CHECK (
    score_avg IS NULL OR (score_avg >= 0 AND score_avg <= 100));
ALTER TABLE public.evaluation_run DROP CONSTRAINT IF EXISTS chk_run_usage;
ALTER TABLE public.evaluation_run ADD CONSTRAINT chk_run_usage CHECK (usage_tokens >= 0 AND usage_cost >= 0);

-- ---- P1：evaluation_run_item ----
ALTER TABLE public.evaluation_run_item DROP CONSTRAINT IF EXISTS chk_run_item_status;
ALTER TABLE public.evaluation_run_item ADD CONSTRAINT chk_run_item_status CHECK (status IN
    ('pending', 'generating', 'scoring', 'completed', 'error'));
ALTER TABLE public.evaluation_run_item DROP CONSTRAINT IF EXISTS chk_run_item_verdict;
ALTER TABLE public.evaluation_run_item ADD CONSTRAINT chk_run_item_verdict CHECK (
    verdict IS NULL OR verdict IN ('pass', 'fail', 'undetermined'));
ALTER TABLE public.evaluation_run_item DROP CONSTRAINT IF EXISTS chk_run_item_attempt_no;
ALTER TABLE public.evaluation_run_item ADD CONSTRAINT chk_run_item_attempt_no CHECK (attempt_no >= 1);

-- ---- P1：evaluation_review ----
ALTER TABLE public.evaluation_review DROP CONSTRAINT IF EXISTS chk_review_action;
ALTER TABLE public.evaluation_review ADD CONSTRAINT chk_review_action CHECK (action IN
    ('adjust', 'undetermined', 'confirm'));
ALTER TABLE public.evaluation_review DROP CONSTRAINT IF EXISTS chk_review_reason;
ALTER TABLE public.evaluation_review ADD CONSTRAINT chk_review_reason CHECK (length(btrim(reason)) > 0);

-- ---- P1：evaluation_report ----
ALTER TABLE public.evaluation_report DROP CONSTRAINT IF EXISTS chk_report_status;
ALTER TABLE public.evaluation_report ADD CONSTRAINT chk_report_status CHECK (status IN ('draft', 'finalized'));
ALTER TABLE public.evaluation_report DROP CONSTRAINT IF EXISTS chk_report_version;
ALTER TABLE public.evaluation_report ADD CONSTRAINT chk_report_version CHECK (version >= 1);

-- ============================================================
-- 四、部署后自检（可手动执行，期望值写在注释里）
-- ============================================================
-- 表：期望 8 行
--   SELECT count(*) FROM information_schema.tables
--   WHERE table_name LIKE 'evaluation%' AND table_schema='public';
-- 约束：期望 27 行（26 条 CHECK + 1 条 uq_ds_version UNIQUE）
--   SELECT conrelid::regclass AS tbl, conname, contype FROM pg_constraint
--   WHERE conrelid::regclass::text LIKE 'evaluation%' AND contype IN ('c','u') ORDER BY 1,2;
-- 索引：期望 11 行
--   SELECT indexname FROM pg_indexes
--   WHERE tablename IN ('evaluation_run','evaluation_run_item','evaluation_review','evaluation_report');
