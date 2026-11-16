-- ============================================================
-- knowledge-case-schema.sql — 知识库 + 案例库 + 上传任务 建表（最终态）
--
-- 对应迁移：
--   V45__add_knowledge_case_tables.sql
--   V46__add_case_upload_task.sql
--   V47__upload_task_company_nullable.sql
--   V48__upload_task_progress_resume.sql
--
-- 用法：psql -U <user> -d <database> -f scripts/knowledge-case-schema.sql
-- 前提：已存在 public.company 表（id uuid 主键）；PostgreSQL 13+（gen_random_uuid 内置）
-- ⚠️ 本脚本为「DROP + 重建」：每次执行都会删除下面 4 张表及其数据后重建，
--    仅用于重置/重建知识库+案例库 schema，勿在已有数据的生产库上直接跑。
-- ============================================================

-- ============================================================
-- 清理：删除已有表及数据（仅这 4 张本功能表，不碰 company/user 等既有表）
-- ============================================================
DROP TABLE IF EXISTS public.knowledge_upload_task;
DROP TABLE IF EXISTS public.case_upload_task;
DROP TABLE IF EXISTS public.knowledge_entry;
DROP TABLE IF EXISTS public.industry_case;

-- ============================================================
-- 知识库条目：事实型知识（产品/竞品/价格/合规/术语/方法论）
-- company_id NULL = 平台公共知识（所有租户可见、模板化授权）
-- ============================================================
CREATE TABLE IF NOT EXISTS public.knowledge_entry (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid                                            , -- 所属企业ID，NULL=平台公共知识
    source_type character varying(20) NOT NULL                 , -- 来源类型：public/platform/deidentified/tenant
    category character varying(20) NOT NULL                    , -- 分类：compliance/price/product/policy/competitor/terminology/methodology/industry_intro
    title character varying(255) NOT NULL                      , -- 标题
    content text NOT NULL                                      , -- 知识正文（事实型内容）
    version integer NOT NULL DEFAULT 1                         , -- 版本号（suggest-revision 时 +1）
    effective_at timestamp without time zone                   , -- 生效时间（可选）
    expire_at timestamp without time zone                      , -- 失效时间（派生过期态，审批必填）
    status character varying(20) NOT NULL DEFAULT 'pending'    , -- 状态：draft/pending/active/deprecated
    source character varying(500)                              , -- 出处（版权标注 / 章节链）
    created_by uuid                                            , -- 创建人ID
    embedding vector(1024)                                     , -- 语义向量（DashScope 1024 维，审批时写入）
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.knowledge_entry IS '知识库条目 — 事实型知识（产品/竞品/价格/合规/术语/方法论）';

CREATE INDEX IF NOT EXISTS idx_knowledge_company ON public.knowledge_entry(company_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_category ON public.knowledge_entry(category);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_type ON public.knowledge_entry(source_type);
CREATE INDEX IF NOT EXISTS idx_knowledge_status ON public.knowledge_entry(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_hnsw ON public.knowledge_entry USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='200');

-- ============================================================
-- 行业案例库：过程型案例（客户画像/推进过程/结果/可复用点）
-- company_id NULL = 平台公共案例
-- ============================================================
CREATE TABLE IF NOT EXISTS public.industry_case (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid                                            , -- 所属企业ID，NULL=平台公共案例
    title character varying(255) NOT NULL                      , -- 标题
    customer_profile jsonb                                     , -- 客户画像（JSONB）
    funnel_stage character varying(20)                         , -- 漏斗阶段：线索/首触/需求/方案/异议/报价/追单/成交/复购
    scene_tag character varying(50)                            , -- 场景标签（2-4 字）
    dialogue_quote jsonb                                       , -- 对话摘录（JSONB）
    objection text                                             , -- 核心异议
    emotion_change text                                        , -- 情绪变化
    key_actions text                                           , -- 关键动作
    scripts text                                               , -- 关键话术
    advance_nodes text                                         , -- 推进节点
    result character varying(10)                               , -- 结果：win/loss/delay
    reusable_points text                                       , -- 可复用点
    failure_points text                                        , -- 失败点
    source character varying(500)                              , -- 出处（章节链）
    contributor_id uuid                                        , -- 贡献人ID
    confidence double precision                                , -- 置信度
    version integer NOT NULL DEFAULT 1                         , -- 版本号
    effective_at timestamp without time zone                   , -- 生效时间（可选）
    expire_at timestamp without time zone                      , -- 失效时间（派生过期态）
    status character varying(20) NOT NULL DEFAULT 'pending'    , -- 状态：draft/pending/active/deprecated
    embedding vector(1024)                                     , -- 语义向量（DashScope 1024 维，审批时写入）
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.industry_case IS '行业案例库 — 过程型案例（客户画像/推进过程/结果/可复用点）';

CREATE INDEX IF NOT EXISTS idx_case_company ON public.industry_case(company_id);
CREATE INDEX IF NOT EXISTS idx_case_funnel ON public.industry_case(funnel_stage);
CREATE INDEX IF NOT EXISTS idx_case_scene ON public.industry_case(scene_tag);
CREATE INDEX IF NOT EXISTS idx_case_result ON public.industry_case(result);
CREATE INDEX IF NOT EXISTS idx_case_status ON public.industry_case(status);
CREATE INDEX IF NOT EXISTS idx_case_embedding_hnsw ON public.industry_case USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='200');

-- ============================================================
-- 知识批量上传任务：追踪「文档上传 → AI 萃取」异步状态
-- 含章节级进度 + 断点续传字段（T16）
-- ============================================================
CREATE TABLE IF NOT EXISTS public.knowledge_upload_task (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid                                            , -- 所属企业ID，NULL=平台公共上传
    file_name character varying(500)                           , -- 上传文件名
    file_path character varying(1000)                          , -- 文件存储路径（断点续传重新解析用）
    status character varying(20) NOT NULL DEFAULT 'extracting' , -- 状态：extracting/done/failed
    produced_count integer NOT NULL DEFAULT 0                  , -- 产出知识条目数
    total_sections integer NOT NULL DEFAULT 0                  , -- 文档章节总数（结构解析后写入）
    processed_sections integer NOT NULL DEFAULT 0              , -- 已处理章节数（断点续传检查点）
    error_message character varying(1000)                      , -- 失败原因（status=failed 时展示）
    created_by uuid                                            , -- 创建人ID
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.knowledge_upload_task IS '知识批量上传任务 — 追踪文档上传→AI萃取异步状态（含章节级进度+断点续传）';

CREATE INDEX IF NOT EXISTS idx_upload_task_company ON public.knowledge_upload_task(company_id);
CREATE INDEX IF NOT EXISTS idx_upload_task_status ON public.knowledge_upload_task(status);

-- ============================================================
-- 案例批量上传任务：与 knowledge_upload_task 对称
-- ============================================================
CREATE TABLE IF NOT EXISTS public.case_upload_task (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid                                            , -- 所属企业ID，NULL=平台公共上传
    file_name character varying(500)                           , -- 上传文件名
    file_path character varying(1000)                          , -- 文件存储路径（断点续传重新解析用）
    status character varying(20) NOT NULL DEFAULT 'extracting' , -- 状态：extracting/done/failed
    produced_count integer NOT NULL DEFAULT 0                  , -- 产出案例条数
    total_sections integer NOT NULL DEFAULT 0                  , -- 文档章节总数（结构解析后写入）
    processed_sections integer NOT NULL DEFAULT 0              , -- 已处理章节数（断点续传检查点）
    error_message character varying(1000)                      , -- 失败原因（status=failed 时展示）
    created_by uuid                                            , -- 创建人ID
    created_at timestamp without time zone NOT NULL DEFAULT now(), -- 创建时间
    updated_at timestamp without time zone NOT NULL DEFAULT now()  -- 更新时间
);
COMMENT ON TABLE public.case_upload_task IS '案例批量上传任务 — 追踪文档上传→AI萃取案例异步状态（含章节级进度+断点续传）';

CREATE INDEX IF NOT EXISTS idx_case_upload_task_company ON public.case_upload_task(company_id);
CREATE INDEX IF NOT EXISTS idx_case_upload_task_status ON public.case_upload_task(status);
