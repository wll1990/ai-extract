-- ============================================================
-- V1__init.sql — 全量建表（合并 V1-V39）+ 初始数据
-- Auto-generated: 2026-07-30
-- ============================================================


--
--


--
--


--

--
--


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: admin_audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_audit_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 主键
    admin_id uuid NOT NULL                                  , -- 操作人ID
    action character varying(50) NOT NULL                   , -- 操作类型: edit_grain/deprecate_grain/create_grain/resolve_gap/edit_domain/edit_prompt
    target_type character varying(50) NOT NULL              , -- 操作对象类型: grain/gap/prompt/domain
    target_id uuid                                          , -- 操作对象ID
    detail jsonb                                            , -- 操作详情(JSONB): 字段名+新旧值
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE public.admin_audit_log IS '管理员审计日志 — 记录管理端关键操作';

--
-- Name: COLUMN admin_audit_log.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN admin_audit_log.admin_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN admin_audit_log.action; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN admin_audit_log.target_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN admin_audit_log.target_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN admin_audit_log.detail; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: analytics_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.analytics_event (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 主键
    skill_id uuid                                           , -- 关联分身ID
    conversation_id uuid                                    , -- 关联对话ID
    user_id uuid                                            , -- 用户ID
    event_type character varying(50) NOT NULL               , -- 事件类型: recommendation_show/click, mode_switch, conversation_end
    event_data jsonb                                        , -- 事件数据(JSONB)
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE public.analytics_event IS '分析事件 — 用户行为埋点数据';

--
-- Name: COLUMN analytics_event.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN analytics_event.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN analytics_event.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN analytics_event.user_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN analytics_event.event_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN analytics_event.event_data; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: answer_correction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.answer_correction (
    id uuid NOT NULL,
    skill_id uuid NOT NULL                                  , -- 关联的分身 ID
    conversation_id uuid                                    , -- 关联的会话 ID（可空 — 离线矫正）
    message_id uuid                                         , -- 被矫正的 AI 消息 ID
    original_query text                                     , -- 用户当时问的问题
    bad_response text                                       , -- AI 的错误回答
    corrected_response text                                 , -- Admin 给出的正确答案
    grain_ids jsonb                                         , -- 涉及的颗粒 ID 列表（JSONB 数组）— 矫正后这些颗粒 weight × 0.7
    corrected_by character varying(100)                     , -- 操作人标识
    created_at timestamp without time zone DEFAULT now() NOT NULL  -- 矫正时间
);

--
-- Name: TABLE answer_correction; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.answer_correction IS '回答矫正记录 — Admin 标记 AI 错误回答并联动颗粒权重衰减';

--
-- Name: COLUMN answer_correction.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.message_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.original_query; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.bad_response; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.corrected_response; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.grain_ids; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.corrected_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN answer_correction.created_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    nickname character varying(50) NOT NULL                 , -- 昵称，游客自动生成"访客xxxx"，注册时可改
    account character varying(100)                          , -- 登录账号，游客为 NULL，注册后平台全局唯一
    password_hash character varying(255)                    , -- BCrypt 密码哈希，游客为 NULL
    status character varying(20) DEFAULT 'guest'::character varying NOT NULL , -- 状态: guest=游客（未设账号密码） / registered=已注册
    source_share_id uuid                                    , -- 来源分享ID（skill_share.id），用于转化归因
    last_active_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    source character varying(10) DEFAULT 'share'::character varying , -- 用户来源: share=分享链接, platform=平台注册, partner=合作方嵌入
    company_id uuid                                         , -- 所属企业UUID(仅partner用户有值)=PartnerApp.app_id
    avatar_url character varying(500),
    CONSTRAINT app_user_status_check CHECK (((status)::text = ANY ((ARRAY['guest'::character varying, 'registered'::character varying])::text[])))
);

--
-- Name: TABLE app_user; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.app_user IS 'C端用户（平台级，与企业 user 表完全独立）';

--
-- Name: COLUMN app_user.nickname; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN app_user.account; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN app_user.password_hash; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN app_user.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN app_user.source_share_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN app_user.source; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN app_user.company_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: auto_insight; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auto_insight (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid,
    type character varying(50) NOT NULL                     , -- 洞察类型：gap_burst=缺口爆发, satisfaction_drop=满意率骤降, hit_rate_drop=命中率下降, new_pattern=发现新高频场景, inactive=分身不活跃
    title character varying(500) NOT NULL,
    description text,
    severity character varying(20) DEFAULT 'info'::character varying NOT NULL , -- 严重程度：critical=需立即处理, warning=建议关注, info=仅供参考
    evidence jsonb DEFAULT '{}'::jsonb NOT NULL             , -- JSONB 数据依据：{positive_samples, negative_samples, satisfaction_delta, source_conv_ids, source_grain_ids, source_gap_ids}
    candidate_grain_id uuid                                 , -- 关联的 AI 生成的候选颗粒（为 NULL 表示该洞察未产生候选颗粒）
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    resolved_by uuid,
    resolved_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE auto_insight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.auto_insight IS 'AI 自动发现的洞察——从数据中识别规律/异常/新场景';

--
-- Name: COLUMN auto_insight.type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN auto_insight.severity; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN auto_insight.evidence; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN auto_insight.candidate_grain_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: candidate_grain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.candidate_grain (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid,
    scene_tag character varying(50) NOT NULL                , -- 场景标签，如 报价-ROI锚定
    scene_description text,
    expert_thought text NOT NULL                            , -- AI 发现的销售策略/思考方式
    standard_script text                                    , -- AI 生成的推荐话术
    common_mistakes text                                    , -- AI 识别的常见话术错误
    applicable_condition text                               , -- 适用此颗粒的场景条件
    source_insight_id uuid NOT NULL                         , -- 产生此候选颗粒的洞察记录 ID
    source_evidence jsonb DEFAULT '{}'::jsonb NOT NULL      , -- JSONB 数据依据：{positive_samples, negative_samples, satisfaction_delta, source_conv_ids, source_grain_ids}
    status character varying(20) DEFAULT 'pending_review'::character varying NOT NULL , -- 审核状态：pending_review=待审核, approved=已通过(已写入experience_grain), rejected=已拒绝
    reviewed_by uuid,
    reviewed_at timestamp without time zone,
    note text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE candidate_grain; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.candidate_grain IS 'AI 自动生成的候选技能颗粒——管理员审核通过后写入 experience_grain';

--
-- Name: COLUMN candidate_grain.scene_tag; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN candidate_grain.expert_thought; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN candidate_grain.standard_script; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN candidate_grain.common_mistakes; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN candidate_grain.applicable_condition; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN candidate_grain.source_insight_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN candidate_grain.source_evidence; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN candidate_grain.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: company; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 企业ID
    name character varying(255) NOT NULL                    , -- 企业名称
    logo_url character varying(500)                         , -- Logo地址
    brand_color character varying(7) DEFAULT '#1A2B4C'::character varying , -- 品牌色
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    contact_name character varying(100)                     , -- 联系人
    contact_phone character varying(30)                     , -- 联系电话
    contact_email character varying(200)                    , -- 联系邮箱
    address character varying(500)                          , -- 企业地址
    industry character varying(100)                         , -- 所属行业
    scale character varying(50)                             , -- 企业规模
    notes text                                              , -- 备注
    status character varying(20) DEFAULT 'active'::character varying  -- 状态：active=合作中, archived=已归档
);

--
-- Name: TABLE company; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.company IS '企业/租户';

--
-- Name: COLUMN company.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.logo_url; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.brand_color; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.contact_name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.contact_phone; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.contact_email; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.address; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.industry; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.scale; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.notes; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: company_register_code; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_register_code (
    id uuid NOT NULL,
    company_id uuid NOT NULL,
    code character varying(20) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    max_uses integer DEFAULT 0                              , -- 最大使用次数，0=不限
    used_count integer DEFAULT 0                            , -- 已使用次数
    created_by uuid,
    created_at timestamp without time zone DEFAULT now(),
    expires_at timestamp without time zone,
    default_role character varying(20) DEFAULT 'employee'::character varying  -- 此注册码创建的用户的默认角色：employee / company_admin
);

--
-- Name: TABLE company_register_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.company_register_code IS '企业注册码，管理员生成后新员工扫码注册自动归入企业';

--
-- Name: COLUMN company_register_code.max_uses; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company_register_code.used_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN company_register_code.default_role; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: conversation_stats; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversation_stats (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 主键
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    conversation_id uuid NOT NULL                           , -- 对话ID(多轮对话中可重复)
    user_id uuid                                            , -- 用户ID
    mode character varying(20) NOT NULL                     , -- 对话模式: qa/discuss/talk/practice/enterprise
    rag_high_count integer DEFAULT 0 NOT NULL               , -- 高匹配颗粒数(similarity≥阈值的颗粒)
    rag_ref_count integer DEFAULT 0 NOT NULL                , -- 参考匹配颗粒数
    rag_none_count integer DEFAULT 0 NOT NULL               , -- 无匹配次数(RAG返回空结果)
    rag_avg_similarity double precision                     , -- 本轮RAG平均相似度
    feedback_up integer DEFAULT 0 NOT NULL,
    feedback_down integer DEFAULT 0 NOT NULL,
    error_type character varying(20)                        , -- 异常类型: NULL=正常, timeout, error, cancelled
    is_test boolean DEFAULT false NOT NULL                  , -- 是否Admin测试对话
    llm_duration_ms integer                                 , -- LLM生成耗时(毫秒)
    total_duration_ms integer                               , -- 端到端总耗时(毫秒)
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    skill_type character varying(10) DEFAULT 'individual'::character varying
);

COMMENT ON TABLE public.conversation_stats IS '对话统计 — 每轮对话的RAG质量和反馈聚合';

--
-- Name: COLUMN conversation_stats.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.user_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.mode; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.rag_high_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.rag_ref_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.rag_none_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.rag_avg_similarity; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.error_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.is_test; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.llm_duration_ms; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN conversation_stats.total_duration_ms; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: experience_grain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.experience_grain (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 颗粒ID
    space_id uuid NOT NULL                                  , -- 所属空间ID
    report_id uuid                                          , -- 来源报告ID
    source_material_id uuid                                 , -- 来源素材ID
    scene_tag character varying(50)                         , -- 场景标签(如"价格异议""破冰")
    scene_description text                                  , -- 场景描述
    expert_thought text                                     , -- 销冠思路
    standard_script text                                    , -- 标准话术
    common_mistakes text                                    , -- 常见错误
    applicable_condition text                               , -- 适用条件
    embedding public.vector(1024)                           , -- 向量嵌入(1024维)
    weight double precision DEFAULT 1.0                     , -- 权重(0.1-2.0)
    quality_score double precision                          , -- 质量评分(0-5)
    difficulty_level character varying(20)                  , -- 难度: beginner/intermediate/advanced/master
    verification_notes jsonb,
    edited_content text,
    status character varying(20) DEFAULT 'active'::character varying , -- 状态: active=有效, deprecated=已废弃
    helpful_count integer DEFAULT 0                         , -- 有用反馈数
    unhelpful_count integer DEFAULT 0                       , -- 无用反馈数
    created_at timestamp without time zone DEFAULT now(),
    source_type character varying(20) DEFAULT 'file_upload'::character varying , -- 颗粒来源: file_upload | interview
    source_interview_id uuid                                , -- 关联 interview_session.id，访谈产出的颗粒可追溯到具体会话
    search_text tsvector GENERATED ALWAYS AS ((((setweight(to_tsvector('simple'::regconfig, (COALESCE(scene_tag, ''::character varying))::text), 'A'::"char") || setweight(to_tsvector('simple'::regconfig, COALESCE(scene_description, ''::text)), 'B'::"char")) || setweight(to_tsvector('simple'::regconfig, COALESCE(expert_thought, ''::text)), 'C'::"char")) || setweight(to_tsvector('simple'::regconfig, COALESCE(standard_script, ''::text)), 'C'::"char"))) STORED , -- 全文检索向量 — ts_rank BM25 近似排序，GIN 索引加速
    CONSTRAINT experience_grain_status_check CHECK (((status)::text = ANY ((ARRAY['active'::character varying, 'deprecated'::character varying])::text[])))
);

--
-- Name: TABLE experience_grain; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.experience_grain IS '经验颗粒(销冠知识的最小单元)';

--
-- Name: COLUMN experience_grain.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.space_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.report_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.source_material_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.scene_tag; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.scene_description; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.expert_thought; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.standard_script; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.common_mistakes; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.applicable_condition; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.embedding; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.weight; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.quality_score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.difficulty_level; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.helpful_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.unhelpful_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.source_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.source_interview_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN experience_grain.search_text; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: expert_document; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.expert_document (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    expert_id uuid NOT NULL                                 , -- 所属技能ID
    file_name character varying(500) NOT NULL,
    file_url character varying(500),
    file_type character varying(50),
    file_size bigint,
    parsed_content text,
    status character varying(20) DEFAULT 'uploaded'::character varying , -- 状态: uploaded→parsing→parsed/failed, pending_manual=待人工
    created_at timestamp without time zone DEFAULT now(),
    retry_count integer DEFAULT 0                           , -- 解析失败重试次数，上限3次
    CONSTRAINT expert_document_status_check CHECK (((status)::text = ANY ((ARRAY['uploaded'::character varying, 'parsing'::character varying, 'parsed'::character varying, 'pending_manual'::character varying, 'failed'::character varying])::text[])))
);

--
-- Name: TABLE expert_document; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.expert_document IS '萃取师素材文档';

--
-- Name: COLUMN expert_document.expert_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_document.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_document.retry_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: expert_grain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.expert_grain (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    expert_id uuid NOT NULL                                 , -- 所属技能ID
    category character varying(50) NOT NULL                 , -- 分类: judgment_intuition/mental_model/failure_lesson等
    source_type character varying(20) DEFAULT 'interview'::character varying,
    scene_description text,
    knowledge_content text NOT NULL                         , -- 知识内容
    application_rule text,
    priority integer DEFAULT 1                              , -- 优先级(1-5)
    consensus_type character varying(20) DEFAULT 'single'::character varying , -- 共识类型: single=单人, consensus=共识, conflict=冲突
    consensus_expert_ids jsonb DEFAULT '[]'::jsonb,
    status character varying(20) DEFAULT 'active'::character varying,
    embedding public.vector(1024)                           , -- 向量嵌入(1024维)
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    domain character varying(64)                            , -- 领域ID，继承自 ExpertSkill.domain
    CONSTRAINT expert_grain_category_check CHECK (((category)::text = ANY ((ARRAY['judgment_intuition'::character varying, 'mental_model'::character varying, 'failure_lesson'::character varying, 'validation_method'::character varying, 'metaphor_framework'::character varying, 'rhythm_sense'::character varying, 'typing_method'::character varying])::text[]))),
    CONSTRAINT expert_grain_consensus_type_check CHECK (((consensus_type)::text = ANY ((ARRAY['single'::character varying, 'consensus'::character varying, 'conflict'::character varying])::text[]))),
    CONSTRAINT expert_grain_priority_check CHECK (((priority >= 1) AND (priority <= 5))),
    CONSTRAINT expert_grain_source_type_check CHECK (((source_type)::text = ANY ((ARRAY['interview'::character varying, 'document'::character varying])::text[]))),
    CONSTRAINT expert_grain_status_check CHECK (((status)::text = ANY ((ARRAY['active'::character varying, 'under_review'::character varying, 'deprecated'::character varying])::text[])))
);

--
-- Name: TABLE expert_grain; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.expert_grain IS '萃取师知识颗粒';

--
-- Name: COLUMN expert_grain.expert_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_grain.category; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_grain.knowledge_content; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_grain.priority; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_grain.consensus_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_grain.embedding; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_grain.domain; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: expert_skill; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.expert_skill (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 技能ID
    name character varying(200) NOT NULL                    , -- 技能名称
    description text,
    source_type character varying(20) DEFAULT 'interview'::character varying , -- 来源类型: interview=访谈, document=文档
    style_tags jsonb DEFAULT '[]'::jsonb                    , -- 风格标签JSON
    industry_tags jsonb DEFAULT '[]'::jsonb                 , -- 行业标签JSON
    seniority character varying(50),
    skill_file character varying(500),
    report_id uuid,
    grain_count integer DEFAULT 0                           , -- 已提取颗粒数
    status character varying(20) DEFAULT 'pending'::character varying , -- 状态: pending→analyzing→extracting→active
    locked_by character varying(64)                         , -- 处理锁持有者
    locked_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    domain character varying(64)                            , -- 领域ID，隔离不同域的萃取师经验
    source_session_id uuid                                  , -- 元访谈 session ID，来源为 interview 时关联
    source_content text                                     , -- 元访谈转录文本，供分析管道处理
    CONSTRAINT expert_skill_source_type_check CHECK (((source_type)::text = ANY ((ARRAY['interview'::character varying, 'document'::character varying, 'hybrid'::character varying])::text[]))),
    CONSTRAINT expert_skill_status_check CHECK (((status)::text = ANY ((ARRAY['pending'::character varying, 'analyzing'::character varying, 'extracting'::character varying, 'active'::character varying, 'failed'::character varying])::text[])))
);

--
-- Name: TABLE expert_skill; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.expert_skill IS '萃取师技能(元萃取)';

--
-- Name: COLUMN expert_skill.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.source_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.style_tags; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.industry_tags; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.grain_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.locked_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.domain; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.source_session_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN expert_skill.source_content; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: extraction_drop_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.extraction_drop_log (
    id uuid NOT NULL,
    material_id uuid NOT NULL,
    space_id uuid NOT NULL,
    stage character varying(30) NOT NULL,
    chunk_index integer,
    content_preview text,
    collided_grain_id uuid,
    similarity numeric(4,3),
    detail jsonb,
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT extraction_drop_log_stage_check CHECK (((stage)::text = ANY ((ARRAY['dedup'::character varying, 'verification'::character varying, 'verification_skipped'::character varying])::text[])))
);

--
-- Name: TABLE extraction_drop_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.extraction_drop_log IS '萃取管道淘汰明细：chunk去重丢弃/对抗验证拒绝/验证跳过，用于排查颗粒缺失';

--
-- Name: feedback_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feedback_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 主键
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    conversation_id uuid                                    , -- 所属对话ID
    message_id uuid                                         , -- AI消息ID
    user_id uuid                                            , -- 打分用户ID
    grain_id uuid                                           , -- 关联的经验颗粒(NULL=无匹配时的打分)
    rating character varying(10) NOT NULL                   , -- 评分: up=有帮助, down=没帮助
    query text                                              , -- 用户当时的提问原文
    ai_response character varying(500)                      , -- AI回答截取前500字
    rag_score double precision                              , -- 回答时的RAG平均匹配度
    source character varying(20) DEFAULT 'user'::character varying NOT NULL , -- 来源: user=用户打分, backfill=存量迁移
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE public.feedback_log IS '用户反馈日志 — 对话中用户对AI回答的打分记录';

--
-- Name: COLUMN feedback_log.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.message_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.user_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.grain_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.rating; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.query; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.ai_response; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.rag_score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN feedback_log.source; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: grain_edit_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.grain_edit_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 主键
    grain_id uuid NOT NULL                                  , -- 被编辑的颗粒ID
    field_name character varying(50) NOT NULL               , -- 修改的字段名(expertThought/standardScript/commonMistakes/applicableCondition/sceneTag/weight)
    old_value text                                          , -- 修改前的内容
    new_value text                                          , -- 修改后的内容
    edited_by character varying(100)                        , -- 修改人
    edit_note text                                          , -- 修改原因(Admin填写)
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE public.grain_edit_history IS '颗粒编辑历史 — Admin修改经验颗粒的变更追踪';

--
-- Name: COLUMN grain_edit_history.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_edit_history.grain_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_edit_history.field_name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_edit_history.old_value; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_edit_history.new_value; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_edit_history.edited_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_edit_history.edit_note; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: grain_retrieve_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.grain_retrieve_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 主键
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    conversation_id uuid NOT NULL                           , -- 所属对话ID
    original_query text                                     , -- 用户原始提问
    rewritten_query text                                    , -- LLM改写后的查询
    grain_id uuid NOT NULL                                  , -- 命中的颗粒ID
    scene_tag character varying(100)                        , -- 颗粒的场景标签
    similarity double precision NOT NULL                    , -- 余弦相似度(0~1)
    tier character varying(10)                              , -- 分层标记: high=高匹配, ref=参考, NULL=低匹配
    "position" integer NOT NULL,                             -- 排名(1-based)
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE public.grain_retrieve_log IS 'RAG检索日志 — 每次对话的颗粒召回明细';

--
-- Name: COLUMN grain_retrieve_log.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.original_query; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.rewritten_query; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.grain_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.scene_tag; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.similarity; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN grain_retrieve_log.tier; Type: COMMENT; Schema: public; Owner: -
--


--
-- Name: im_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.im_channel (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id uuid NOT NULL                                , -- 所属企业ID
    channel_type character varying(20) NOT NULL             , -- 渠道类型: feishu/wecom/wechat/dingtalk
    enabled boolean DEFAULT false                           , -- 是否启用
    config jsonb DEFAULT '{}'::jsonb NOT NULL               , -- 渠道配置JSON
    linked_skills jsonb DEFAULT '[]'::jsonb                 , -- 关联的分身ID列表JSON
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    CONSTRAINT im_channel_channel_type_check CHECK (((channel_type)::text = ANY ((ARRAY['feishu'::character varying, 'wecom'::character varying, 'wechat'::character varying, 'dingtalk'::character varying])::text[])))
);

--
-- Name: TABLE im_channel; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.im_channel IS 'IM渠道配置';

--
-- Name: COLUMN im_channel.company_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN im_channel.channel_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN im_channel.enabled; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN im_channel.config; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN im_channel.linked_skills; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: interview_invite_code; Type: TABLE; Schema: public; Owner: -
--
CREATE TABLE public.interview_invite_code (
    id uuid NOT NULL,
    type character varying(10) DEFAULT 'enterprise'::character varying NOT NULL,  -- enterprise | personal
    company_id uuid,                          -- enterprise 时必填
    invited_by character varying(100),        -- personal 时必填，邀请者昵称
    code character varying(20) NOT NULL,      -- 8位 base62，全局唯一
    enabled boolean DEFAULT true NOT NULL,    -- 启停开关
    max_uses integer DEFAULT 0,              -- 最大使用次数，0=不限  -- 最大使用次数，0=不限
    used_count integer DEFAULT 0,            -- 已使用次数        -- 已使用次数
    created_by uuid,                         -- 创建人 ID
    created_at timestamp without time zone DEFAULT now(),
    expires_at timestamp without time zone   -- 过期时间，NULL=永久
);

--
-- Name: TABLE interview_invite_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.interview_invite_code IS '访谈邀请码，不绑定space，扫码登录的员工自己决定space';

--
-- Name: COLUMN interview_invite_code.max_uses; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_invite_code.used_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: interview_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interview_message (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 消息ID
    session_id uuid NOT NULL                                , -- 所属会话ID
    role character varying(10) NOT NULL                     , -- 角色: ai=AI提问, user=用户回答
    content text NOT NULL                                   , -- 消息内容
    phase character varying(20)                             , -- 所属阶段
    depth integer DEFAULT 0                                 , -- 追问深度
    stage_status jsonb DEFAULT '{}'::jsonb,
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT interview_message_role_check CHECK (((role)::text = ANY ((ARRAY['ai'::character varying, 'user'::character varying, 'system'::character varying])::text[])))
);

--
-- Name: TABLE interview_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.interview_message IS '访谈对话记录';

--
-- Name: COLUMN interview_message.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_message.session_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_message.role; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_message.content; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_message.phase; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_message.depth; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: interview_phase_summary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interview_phase_summary (
    id uuid NOT NULL,
    session_id uuid NOT NULL                                , -- 关联的访谈会话 ID
    phase character varying(20) NOT NULL                    , -- 阶段标识: opening / storytelling / modeling / closing
    phase_label character varying(20) NOT NULL              , -- 阶段中文标签: 开场定调 / 故事深描 / 模型提炼 / 收网确认
    summary text NOT NULL                                   , -- AI 生成的本阶段已收集关键信息摘要
    created_at timestamp without time zone DEFAULT now() NOT NULL  -- 摘要生成时间
);

--
-- Name: TABLE interview_phase_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.interview_phase_summary IS '访谈阶段摘要 — 阶段完成时异步生成，后续阶段替代全量历史减少 token';

--
-- Name: COLUMN interview_phase_summary.session_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_phase_summary.phase; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_phase_summary.phase_label; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_phase_summary.summary; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_phase_summary.created_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: interview_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interview_session (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 会话ID
    space_id uuid NOT NULL                                  , -- 所属空间ID
    topic character varying(200)                            , -- 访谈主题
    status character varying(20) DEFAULT 'created'::character varying , -- 状态: created→in_progress→completed
    current_phase character varying(20) DEFAULT 'opening'::character varying , -- 当前阶段: opening/storytelling/modeling/closing
    invite_code character varying(50)                       , -- 邀请码
    invited_by uuid,
    expert_skill_id uuid,
    interview_type character varying(20) DEFAULT 'sales'::character varying , -- 访谈类型: sales=销冠访谈
    last_active_at timestamp without time zone DEFAULT now() , -- 最后活跃时间
    created_at timestamp without time zone DEFAULT now(),
    finished_at timestamp without time zone                 , -- 完成时间
    domain character varying(64)                            , -- 领域ID，如 sales.b2b_enterprise / finance.secondary_market
    collect_status jsonb DEFAULT '{}'::jsonb,
    CONSTRAINT interview_session_current_phase_check CHECK (((current_phase)::text = ANY ((ARRAY['opening'::character varying, 'storytelling'::character varying, 'modeling'::character varying, 'closing'::character varying])::text[]))),
    CONSTRAINT interview_session_status_check CHECK (((status)::text = ANY ((ARRAY['created'::character varying, 'in_progress'::character varying, 'paused'::character varying, 'completed'::character varying, 'abandoned'::character varying, 'failed'::character varying])::text[])))
);

--
-- Name: TABLE interview_session; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.interview_session IS '销冠访谈会话';

--
-- Name: COLUMN interview_session.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.space_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.topic; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.current_phase; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.invite_code; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.interview_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.last_active_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.finished_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN interview_session.domain; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: knowledge_gap; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_gap (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 主键
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    space_id uuid NOT NULL                                  , -- 所属空间ID
    query text NOT NULL                                     , -- 用户提问原文
    scene_tag character varying(100)                        , -- 系统推测的场景标签
    attempted_query_count integer DEFAULT 1 NOT NULL        , -- 该场景累计出现次数
    status character varying(20) DEFAULT 'open'::character varying NOT NULL , -- 状态: open/reviewing/resolved/ignored
    resolved_by character varying(100)                      , -- 处理人
    resolved_at timestamp without time zone                 , -- 处理时间
    note text                                               , -- 管理员备注
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    embedding public.vector(1024)                            -- 缺口文本的向量表示，用于 pgvector 余弦聚类
);

COMMENT ON TABLE public.knowledge_gap IS '知识缺口 — 用户提问但RAG无匹配的问题收集';

--
-- Name: COLUMN knowledge_gap.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.space_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.query; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.scene_tag; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.attempted_query_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.resolved_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.resolved_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.note; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN knowledge_gap.embedding; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: partner_app; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.partner_app (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    app_id character varying(50) NOT NULL,
    app_name character varying(100) NOT NULL,
    secret_key character varying(500) NOT NULL,
    old_secret_key character varying(500),
    old_key_expires_at timestamp without time zone,
    status character varying(10) DEFAULT 'ENABLED'::character varying NOT NULL,
    contact_name character varying(50),
    contact_email character varying(100),
    created_by uuid,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);

COMMENT ON TABLE public.partner_app IS '合作方应用 — 第三方嵌入集成的应用注册';

--
-- Name: report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 报告ID
    space_id uuid                                           , -- 所属空间ID
    session_id uuid                                         , -- 来源访谈会话ID
    title character varying(200) NOT NULL                   , -- 报告标题
    subtitle character varying(500)                         , -- 报告副标题
    content_json jsonb DEFAULT '{}'::jsonb NOT NULL         , -- 报告内容JSON(chapters/steps/decisions等)
    word_url character varying(500)                         , -- Word文件地址
    ppt_url character varying(500)                          , -- PPT文件地址
    web_published boolean DEFAULT true,
    file_status character varying(20) DEFAULT 'synced'::character varying , -- 文件状态: synced=已同步
    rating numeric(2,1) DEFAULT 4.5                         , -- 评分(1-5)
    view_count integer DEFAULT 0                            , -- 浏览次数
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    CONSTRAINT report_file_status_check CHECK (((file_status)::text = ANY ((ARRAY['synced'::character varying, 'pending_regenerate'::character varying])::text[])))
);

--
-- Name: TABLE report; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.report IS '萃取报告';

--
-- Name: COLUMN report.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.space_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.session_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.title; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.subtitle; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.content_json; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.word_url; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.ppt_url; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.file_status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.rating; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report.view_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: report_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    version character varying(50) NOT NULL                  , -- 版本号
    generated_at timestamp without time zone DEFAULT now(),
    material_ids text DEFAULT '[]'::text                    , -- 关联素材ID列表JSON
    grain_count integer DEFAULT 0                           , -- 颗粒数量
    metadata text DEFAULT '{}'::text
);

--
-- Name: TABLE report_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.report_history IS '报告生成历史';

--
-- Name: COLUMN report_history.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report_history.version; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report_history.material_ids; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN report_history.grain_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 分身ID
    space_id uuid                                           , -- 所属空间ID（组织分身为 null）
    model_name character varying(100) DEFAULT 'deepseek-chat'::character varying , -- 使用的大模型名称
    model_config jsonb DEFAULT '{}'::jsonb,
    status character varying(20) DEFAULT 'generating'::character varying , -- 状态: generating→reviewing→published/discarded
    display_name character varying(200)                     , -- 对外展示名称
    owner_name character varying(100)                       , -- 销冠真实姓名(展示用)
    owner_title character varying(200)                      , -- 销冠职位(展示用)
    department character varying(200)                       , -- 所属部门
    seniority character varying(50)                         , -- 从业年限
    tags jsonb DEFAULT '[]'::jsonb                          , -- 灵活标签JSON: ["金融","B2B"]
    target_scenarios jsonb DEFAULT '[]'::jsonb              , -- 适用场景JSON: ["初次拜访","异议处理"]
    limitations text                                        , -- 已知局限性
    publish_notes text                                      , -- 发布审核备注
    published_at timestamp without time zone                , -- 发布时间
    published_by uuid                                       , -- 发布人ID
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    domain character varying(50)                            , -- 领域ID，如 sales.b2b_enterprise / finance.secondary_market
    avatar_url character varying(500)                       , -- 分身头像URL
    opening_message text                                    , -- 分身开场白 — 聊天页入场态展示
    talk_config jsonb DEFAULT '{}'::jsonb,
    practice_openings text,
    conversation_count integer DEFAULT 0,
    user_count integer DEFAULT 0,
    satisfaction_rate integer DEFAULT 0,
    last_active_at timestamp without time zone,
    type character varying(20) DEFAULT 'individual'::character varying, -- individual=个人分身, organization=组织分身
    company_id uuid                                         , -- 企业归属（组织分身显式设置，个体分身经 space→user 两跳解析）
    member_skill_ids jsonb DEFAULT '[]'::jsonb             , -- 组织分身成员 skillId 数组，individual 为空
    description text                                        , -- 组织分身描述，individual 不使用
    created_by uuid                                         , -- 创建者 userId
    intro_profile jsonb,
    recommended_questions jsonb,
    CONSTRAINT skill_status_check CHECK (((status)::text = ANY ((ARRAY['generating'::character varying, 'reviewing'::character varying, 'published'::character varying, 'discarded'::character varying])::text[])))
);

--
-- Name: TABLE skill; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill IS 'AI分身 — 统一个体分身和组织分身，type 字段区分';

--
-- Name: COLUMN skill.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.space_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.model_name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.display_name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.owner_name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.owner_title; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.department; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.seniority; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.tags; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.target_scenarios; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.limitations; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.publish_notes; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.published_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.published_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.domain; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.avatar_url; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill.opening_message; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_acceptance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_acceptance (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    status character varying(20) DEFAULT 'pending'::character varying , -- 状态: pending→testing→passed/rejected
    test_score integer                                      , -- 测试得分
    test_detail jsonb,
    test_notes text,
    accepted_by uuid                                        , -- 验收人ID
    accepted_at timestamp without time zone                 , -- 验收时间
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT skill_acceptance_status_check CHECK (((status)::text = ANY ((ARRAY['pending'::character varying, 'testing'::character varying, 'passed'::character varying, 'rejected'::character varying])::text[])))
);

--
-- Name: TABLE skill_acceptance; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_acceptance IS '分身验收记录';

--
-- Name: COLUMN skill_acceptance.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance.test_score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance.accepted_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance.accepted_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_acceptance_question; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_acceptance_question (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    acceptance_id uuid NOT NULL                             , -- 所属验收记录ID
    question text NOT NULL                                  , -- 考题内容
    expected_points jsonb DEFAULT '[]'::jsonb               , -- 期望得分点JSON
    actual_answer text                                      , -- 实际回答
    score integer                                           , -- 得分
    score_detail jsonb,
    question_order integer DEFAULT 0
);

--
-- Name: TABLE skill_acceptance_question; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_acceptance_question IS '验收考题';

--
-- Name: COLUMN skill_acceptance_question.acceptance_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance_question.question; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance_question.expected_points; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance_question.actual_answer; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_acceptance_question.score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_conversation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_conversation (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 会话ID
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    user_id uuid NOT NULL                                   , -- 对话用户ID
    title character varying(200)                            , -- 会话标题(取首条消息前30字)
    mode character varying(20) DEFAULT 'qa'::character varying , -- 模式: qa=问答, practice=对练, quick=快速提问, discuss=自由讨论, talk=自由对话
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    CONSTRAINT skill_conversation_mode_check CHECK (((mode)::text = ANY ((ARRAY['qa'::character varying, 'practice'::character varying, 'quick'::character varying, 'discuss'::character varying, 'talk'::character varying])::text[])))
);

--
-- Name: TABLE skill_conversation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_conversation IS '分身对话会话';

--
-- Name: COLUMN skill_conversation.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_conversation.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_conversation.user_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_conversation.title; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_conversation.mode; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_evaluation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_evaluation (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    conversation_id uuid                                    , -- 关联会话ID
    mode character varying(20)                              , -- 评估模式: qa/practice/auto_evaluate/acceptance_report
    evaluator_id uuid,
    score integer                                           , -- 综合评分(0-100)
    style_score integer                                     , -- 风格分(权重30%)
    consistency_score integer                               , -- 一致性分(权重30%)
    behavior_score integer                                  , -- 行为分(权重20%)
    script_reuse_score integer                              , -- 话术复用分(权重20%)
    score_detail jsonb,
    strengths jsonb DEFAULT '[]'::jsonb                     , -- 优点JSON数组
    improvements jsonb DEFAULT '[]'::jsonb                  , -- 改进点JSON数组
    demo_script text                                        , -- 销冠示范话术
    edited_response text,
    edited_by uuid,
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT skill_evaluation_mode_check CHECK (((mode)::text = ANY ((ARRAY['qa'::character varying, 'practice'::character varying, 'auto_evaluate'::character varying, 'acceptance_report'::character varying])::text[]))),
    CONSTRAINT skill_evaluation_score_check CHECK (((score >= 0) AND (score <= 100)))
);

--
-- Name: TABLE skill_evaluation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_evaluation IS '分身评估记录';

--
-- Name: COLUMN skill_evaluation.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.mode; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.style_score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.consistency_score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.behavior_score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.script_reuse_score; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.strengths; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.improvements; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_evaluation.demo_script; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_material; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_material (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    uploaded_by uuid NOT NULL                               , -- 上传人ID
    file_name character varying(500) NOT NULL               , -- 文件名
    file_url character varying(500)                         , -- 文件存储地址
    file_type character varying(200)                        , -- 文件类型
    file_size bigint                                        , -- 文件大小(字节)
    parsed_content text                                     , -- 解析后的文本内容
    version integer DEFAULT 1                               , -- 版本号
    replaces_material_id uuid,
    status character varying(20) DEFAULT 'uploaded'::character varying , -- 状态: uploaded→cleaning→analyzing→analyzed→extracted; rejected=准入不通过; failed=访谈转录清洗失败; discarded=已废弃
    analysis_notes text,
    extraction_metadata text,
    locked_by character varying(64)                         , -- 处理锁持有者
    locked_at timestamp without time zone                   , -- 处理锁时间
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    material_type character varying(20)                     , -- 素材类型: dialogue=对话, monologue=独白/心得, interview=访谈
    retry_count integer DEFAULT 0                           , -- 解析/清洗失败重试次数，上限3次
    CONSTRAINT skill_material_status_check CHECK (((status)::text = ANY ((ARRAY['uploaded'::character varying, 'cleaning'::character varying, 'cleaned'::character varying, 'analyzing'::character varying, 'analyzed'::character varying, 'extracted'::character varying, 'discarded'::character varying, 'failed'::character varying, 'rejected'::character varying])::text[])))
);

--
-- Name: TABLE skill_material; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_material IS '分身素材(上传的文档/录音等)';

--
-- Name: COLUMN skill_material.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.uploaded_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.file_name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.file_url; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.file_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.file_size; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.parsed_content; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.version; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.locked_by; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.locked_at; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.material_type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_material.retry_count; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_message (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    conversation_id uuid NOT NULL                           , -- 所属会话ID
    role character varying(20) NOT NULL                     , -- 角色: user=用户, assistant=AI分身
    content text NOT NULL                                   , -- 消息内容
    grain_id uuid                                           , -- 关联的经验颗粒ID
    report_id uuid                                          , -- 关联的报告ID
    created_at timestamp without time zone DEFAULT now(),
    role_label character varying(20)                        , -- 角色展示名: 我 / 销冠 / 客户 / 我（销冠）
    CONSTRAINT skill_message_role_check CHECK (((role)::text = ANY ((ARRAY['user'::character varying, 'assistant'::character varying, 'system'::character varying])::text[])))
);

--
-- Name: TABLE skill_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_message IS '分身对话消息';

--
-- Name: COLUMN skill_message.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_message.role; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_message.content; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_message.grain_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_message.report_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_message.role_label; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_profile (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid NOT NULL                                  , -- 所属分身ID
    personality text                                        , -- 性格描述
    speaking_style text                                     , -- 说话风格
    background text                                         , -- 背景经历
    common_phrases text                                     , -- 口头禅
    knowledge_domains jsonb DEFAULT '[]'::jsonb             , -- 擅长领域JSON
    communication_preferences jsonb DEFAULT '[]'::jsonb     , -- 沟通偏好JSON
    weakness_notes text,
    extra_context text,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);

--
-- Name: TABLE skill_profile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_profile IS '分身画像(人设+风格)';

--
-- Name: COLUMN skill_profile.skill_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_profile.personality; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_profile.speaking_style; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_profile.background; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_profile.common_phrases; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_profile.knowledge_domains; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_profile.communication_preferences; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: skill_share; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_share (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    skill_id uuid NOT NULL,
    company_id uuid                                         , -- 企业归属(C端分身分享时为null)
    share_code character varying(16) NOT NULL               , -- 短码，URL 形如 /s/{share_code}，base62 随机 10 位
    channel character varying(50) DEFAULT 'public'::character varying NOT NULL , -- public=对外分享, internal=对内分享
    enabled boolean DEFAULT true NOT NULL                   , -- 共享开关：关闭后分享链接立即失效
    created_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE skill_share; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.skill_share IS '分身对外分享链接（一 skill 可多渠道多码）';

--
-- Name: COLUMN skill_share.company_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_share.share_code; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_share.channel; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN skill_share.enabled; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: space; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.space (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 空间ID
    user_id uuid NOT NULL                                   , -- 空间所有者ID。B端存user.id，C端存app_user.id。无外键约束。
    title character varying(200) NOT NULL                   , -- 空间标题
    description text                                        , -- 空间描述(展示为销冠头衔)
    tags jsonb DEFAULT '[]'::jsonb                          , -- 标签JSON数组
    is_public boolean DEFAULT false                         , -- 是否公开
    status character varying(20) DEFAULT 'active'::character varying , -- 状态: active=活跃, paused=暂停, archived=归档
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    CONSTRAINT space_status_check CHECK (((status)::text = ANY ((ARRAY['active'::character varying, 'paused'::character varying, 'archived'::character varying])::text[])))
);

--
-- Name: TABLE space; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.space IS '个人空间(一个用户一个空间)';

--
-- Name: COLUMN space.id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN space.user_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN space.title; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN space.description; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN space.tags; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN space.is_public; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN space.status; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: token_usage_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.token_usage_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid,
    usage_date date NOT NULL,
    model_type character varying(20) NOT NULL,
    model_name character varying(100),
    input_tokens integer DEFAULT 0,
    output_tokens integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT now(),
    prompt_chars integer DEFAULT 0,
    completion_chars integer DEFAULT 0
);

COMMENT ON TABLE public.token_usage_log IS 'Token用量日志 — LLM调用token消耗统计';

--
-- Name: tool; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tool (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    space_id uuid,
    report_id uuid,
    type character varying(30) NOT NULL                     , -- 类型: poster=海报, card=卡片, checklist=清单, script=剧本
    name character varying(200) NOT NULL                    , -- 工具名称
    file_url character varying(500),
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT tool_type_check CHECK (((type)::text = ANY ((ARRAY['poster'::character varying, 'card'::character varying, 'checklist'::character varying, 'script'::character varying])::text[])))
);

--
-- Name: TABLE tool; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tool IS '销售工具(海报/卡片/清单/剧本)';

--
-- Name: COLUMN tool.type; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN tool.name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."user" (
    id uuid DEFAULT gen_random_uuid() NOT NULL              , -- 用户ID
    company_id uuid NOT NULL                                , -- 所属企业ID
    name character varying(100) NOT NULL                    , -- 真实姓名
    role character varying(20) DEFAULT 'employee'::character varying NOT NULL , -- 角色: super_admin=管理员, employee=普通用户
    avatar_url character varying(500)                       , -- 头像地址
    phone character varying(20)                             , -- 手机号
    account character varying(100) NOT NULL                 , -- 登录账号(企业内唯一)
    password_hash character varying(255) NOT NULL           , -- BCrypt密码哈希
    is_active boolean DEFAULT true                          , -- 是否启用
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    CONSTRAINT chk_user_role CHECK (((role)::text = ANY ((ARRAY['super_admin'::character varying, 'company_admin'::character varying, 'employee'::character varying])::text[])))
);

--
-- Name: TABLE "user"; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public."user" IS '用户';

--
-- Name: COLUMN "user".id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".company_id; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".name; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".role; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".avatar_url; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".phone; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".account; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".password_hash; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: COLUMN "user".is_active; Type: COMMENT; Schema: public; Owner: -
--

--
-- Name: admin_audit_log admin_audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_audit_log
    ADD CONSTRAINT admin_audit_log_pkey PRIMARY KEY (id);

--
-- Name: analytics_event analytics_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.analytics_event
    ADD CONSTRAINT analytics_event_pkey PRIMARY KEY (id);

--
-- Name: answer_correction answer_correction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.answer_correction
    ADD CONSTRAINT answer_correction_pkey PRIMARY KEY (id);

--
-- Name: app_user app_user_account_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_account_key UNIQUE (account);

--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

--
-- Name: auto_insight auto_insight_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auto_insight
    ADD CONSTRAINT auto_insight_pkey PRIMARY KEY (id);

--
-- Name: candidate_grain candidate_grain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candidate_grain
    ADD CONSTRAINT candidate_grain_pkey PRIMARY KEY (id);

--
-- Name: company company_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company
    ADD CONSTRAINT company_pkey PRIMARY KEY (id);

--
-- Name: company_register_code company_register_code_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_register_code
    ADD CONSTRAINT company_register_code_code_key UNIQUE (code);

--
-- Name: company_register_code company_register_code_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_register_code
    ADD CONSTRAINT company_register_code_pkey PRIMARY KEY (id);

--
-- Name: conversation_stats conversation_stats_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_stats
    ADD CONSTRAINT conversation_stats_pkey PRIMARY KEY (id);

--
-- Name: experience_grain experience_grain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.experience_grain
    ADD CONSTRAINT experience_grain_pkey PRIMARY KEY (id);

--
-- Name: expert_document expert_document_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expert_document
    ADD CONSTRAINT expert_document_pkey PRIMARY KEY (id);

--
-- Name: expert_grain expert_grain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expert_grain
    ADD CONSTRAINT expert_grain_pkey PRIMARY KEY (id);

--
-- Name: expert_skill expert_skill_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.expert_skill
    ADD CONSTRAINT expert_skill_pkey PRIMARY KEY (id);

--
-- Name: extraction_drop_log extraction_drop_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.extraction_drop_log
    ADD CONSTRAINT extraction_drop_log_pkey PRIMARY KEY (id);

--
-- Name: feedback_log feedback_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feedback_log
    ADD CONSTRAINT feedback_log_pkey PRIMARY KEY (id);

--
-- Name: grain_edit_history grain_edit_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grain_edit_history
    ADD CONSTRAINT grain_edit_history_pkey PRIMARY KEY (id);

--
-- Name: grain_retrieve_log grain_retrieve_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grain_retrieve_log
    ADD CONSTRAINT grain_retrieve_log_pkey PRIMARY KEY (id);

--
-- Name: im_channel im_channel_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.im_channel
    ADD CONSTRAINT im_channel_pkey PRIMARY KEY (id);

--
-- Name: interview_invite_code interview_invite_code_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_invite_code
    ADD CONSTRAINT interview_invite_code_code_key UNIQUE (code);

--
-- Name: interview_invite_code interview_invite_code_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_invite_code
    ADD CONSTRAINT interview_invite_code_pkey PRIMARY KEY (id);

--
-- Name: interview_message interview_message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_message
    ADD CONSTRAINT interview_message_pkey PRIMARY KEY (id);

--
-- Name: interview_phase_summary interview_phase_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_phase_summary
    ADD CONSTRAINT interview_phase_summary_pkey PRIMARY KEY (id);

--
-- Name: interview_session interview_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interview_session
    ADD CONSTRAINT interview_session_pkey PRIMARY KEY (id);

--
-- Name: knowledge_gap knowledge_gap_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_gap
    ADD CONSTRAINT knowledge_gap_pkey PRIMARY KEY (id);

--
-- Name: partner_app partner_app_app_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.partner_app
    ADD CONSTRAINT partner_app_app_id_key UNIQUE (app_id);

--
-- Name: partner_app partner_app_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.partner_app
    ADD CONSTRAINT partner_app_pkey PRIMARY KEY (id);

--
-- Name: report_history report_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_history
    ADD CONSTRAINT report_history_pkey PRIMARY KEY (id);

--
-- Name: report report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report
    ADD CONSTRAINT report_pkey PRIMARY KEY (id);

--
-- Name: skill_acceptance skill_acceptance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_acceptance
    ADD CONSTRAINT skill_acceptance_pkey PRIMARY KEY (id);

--
-- Name: skill_acceptance_question skill_acceptance_question_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_acceptance_question
    ADD CONSTRAINT skill_acceptance_question_pkey PRIMARY KEY (id);

--
-- Name: skill_conversation skill_conversation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_conversation
    ADD CONSTRAINT skill_conversation_pkey PRIMARY KEY (id);

--
-- Name: skill_evaluation skill_evaluation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_evaluation
    ADD CONSTRAINT skill_evaluation_pkey PRIMARY KEY (id);

--
-- Name: skill_material skill_material_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_material
    ADD CONSTRAINT skill_material_pkey PRIMARY KEY (id);

--
-- Name: skill_message skill_message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_message
    ADD CONSTRAINT skill_message_pkey PRIMARY KEY (id);

--
-- Name: skill skill_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill
    ADD CONSTRAINT skill_pkey PRIMARY KEY (id);

--
-- Name: skill_profile skill_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_profile
    ADD CONSTRAINT skill_profile_pkey PRIMARY KEY (id);

--
-- Name: skill_profile skill_profile_skill_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_profile
    ADD CONSTRAINT skill_profile_skill_id_key UNIQUE (skill_id);

--
-- Name: skill_share skill_share_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_share
    ADD CONSTRAINT skill_share_pkey PRIMARY KEY (id);

--
-- Name: skill_share skill_share_share_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_share
    ADD CONSTRAINT skill_share_share_code_key UNIQUE (share_code);

--
-- Name: space space_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.space
    ADD CONSTRAINT space_pkey PRIMARY KEY (id);

--
-- Name: token_usage_log token_usage_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_usage_log
    ADD CONSTRAINT token_usage_log_pkey PRIMARY KEY (id);

--
-- Name: tool tool_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool
    ADD CONSTRAINT tool_pkey PRIMARY KEY (id);

--
-- Name: user uq_user_company_account; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT uq_user_company_account UNIQUE (company_id, account);

--
-- Name: user user_account_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_account_key UNIQUE (account);

--
-- Name: user user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (id);

--
-- Name: idx_aal_admin_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aal_admin_time ON public.admin_audit_log USING btree (admin_id, created_at DESC);

--
-- Name: idx_aal_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aal_target ON public.admin_audit_log USING btree (target_type, target_id);

--
-- Name: idx_accept_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_accept_skill ON public.skill_acceptance USING btree (skill_id);

--
-- Name: idx_ae_skill_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ae_skill_time ON public.analytics_event USING btree (skill_id, created_at DESC);

--
-- Name: idx_ae_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ae_type ON public.analytics_event USING btree (event_type);

--
-- Name: idx_app_user_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_user_company ON public.app_user USING btree (company_id) WHERE (company_id IS NOT NULL);

--
-- Name: idx_app_user_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_user_source ON public.app_user USING btree (source_share_id);

--
-- Name: idx_auto_insight_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auto_insight_created ON public.auto_insight USING btree (created_at DESC);

--
-- Name: idx_auto_insight_severity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auto_insight_severity ON public.auto_insight USING btree (severity);

--
-- Name: idx_auto_insight_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auto_insight_skill ON public.auto_insight USING btree (skill_id);

--
-- Name: idx_auto_insight_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auto_insight_status ON public.auto_insight USING btree (status);

--
-- Name: idx_candidate_grain_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidate_grain_created ON public.candidate_grain USING btree (created_at DESC);

--
-- Name: idx_candidate_grain_insight; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidate_grain_insight ON public.candidate_grain USING btree (source_insight_id);

--
-- Name: idx_candidate_grain_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidate_grain_skill ON public.candidate_grain USING btree (skill_id);

--
-- Name: idx_candidate_grain_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_candidate_grain_status ON public.candidate_grain USING btree (status);

--
-- Name: idx_ccode_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ccode_code ON public.company_register_code USING btree (code);

--
-- Name: idx_correction_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_correction_skill ON public.answer_correction USING btree (skill_id);

--
-- Name: idx_cs_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cs_conv ON public.conversation_stats USING btree (conversation_id);

--
-- Name: idx_cs_skill_mode; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cs_skill_mode ON public.conversation_stats USING btree (skill_id, mode);

--
-- Name: idx_cs_skill_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cs_skill_time ON public.conversation_stats USING btree (skill_id, created_at DESC);

--
-- Name: idx_drop_log_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_drop_log_material ON public.extraction_drop_log USING btree (material_id);

--
-- Name: idx_drop_log_space_stage; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_drop_log_space_stage ON public.extraction_drop_log USING btree (space_id, stage);

--
-- Name: idx_eval_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_skill ON public.skill_evaluation USING btree (skill_id);

--
-- Name: idx_experience_grain_space_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_experience_grain_space_status ON public.experience_grain USING btree (space_id, status);

--
-- Name: idx_expert_document_expert; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_expert_document_expert ON public.expert_document USING btree (expert_id);

--
-- Name: idx_expert_grain_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_expert_grain_category ON public.expert_grain USING btree (category);

--
-- Name: idx_expert_grain_expert; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_expert_grain_expert ON public.expert_grain USING btree (expert_id);

--
-- Name: idx_expert_grain_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_expert_grain_status ON public.expert_grain USING btree (status);

--
-- Name: idx_expert_skill_status_locked; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_expert_skill_status_locked ON public.expert_skill USING btree (status, locked_at);

--
-- Name: idx_fl_grain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fl_grain ON public.feedback_log USING btree (grain_id) WHERE (grain_id IS NOT NULL);

--
-- Name: idx_fl_rating; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fl_rating ON public.feedback_log USING btree (skill_id, rating);

--
-- Name: idx_fl_skill_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fl_skill_time ON public.feedback_log USING btree (skill_id, created_at DESC);

--
-- Name: idx_geh_grain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_geh_grain ON public.grain_edit_history USING btree (grain_id, created_at DESC);

--
-- Name: idx_grain_embedding_hnsw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grain_embedding_hnsw ON public.experience_grain USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='200');

--
-- Name: idx_grain_fts; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grain_fts ON public.experience_grain USING gin (search_text);

--
-- Name: INDEX idx_grain_fts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.idx_grain_fts IS '全文检索 GIN 倒排索引 — 支持 @@ 匹配和 ts_rank 排序';

--
-- Name: idx_grain_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grain_scene ON public.experience_grain USING btree (scene_tag);

--
-- Name: idx_grain_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grain_space ON public.experience_grain USING btree (space_id);

--
-- Name: idx_grl_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grl_conv ON public.grain_retrieve_log USING btree (conversation_id);

--
-- Name: idx_grl_grain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grl_grain ON public.grain_retrieve_log USING btree (grain_id);

--
-- Name: idx_grl_skill_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grl_skill_time ON public.grain_retrieve_log USING btree (skill_id, created_at DESC);

--
-- Name: idx_iicode_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iicode_code ON public.interview_invite_code USING btree (code);

--
-- Name: idx_kg_skill_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kg_skill_status ON public.knowledge_gap USING btree (skill_id, status);

--
-- Name: idx_kg_skill_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kg_skill_time ON public.knowledge_gap USING btree (skill_id, created_at DESC);

--
-- Name: idx_kg_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kg_space ON public.knowledge_gap USING btree (space_id);

--
-- Name: idx_material_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_skill ON public.skill_material USING btree (skill_id);

--
-- Name: idx_message_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_message_created ON public.interview_message USING btree (session_id, created_at);

--
-- Name: idx_phase_summary_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_phase_summary_session ON public.interview_phase_summary USING btree (session_id, phase);

--
-- Name: idx_report_history_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_history_skill ON public.report_history USING btree (skill_id);

--
-- Name: idx_report_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_space ON public.report USING btree (space_id);

--
-- Name: idx_report_subtitle_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_subtitle_trgm ON public.report USING gin (subtitle public.gin_trgm_ops);

--
-- Name: idx_report_title_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_title_trgm ON public.report USING gin (title public.gin_trgm_ops);

--
-- Name: idx_session_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_session_space ON public.interview_session USING btree (space_id);

--
-- Name: idx_session_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_session_status ON public.interview_session USING btree (status);

--
-- Name: idx_skill_conv_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_conv_skill ON public.skill_conversation USING btree (skill_id);

--
-- Name: idx_skill_conv_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_conv_user ON public.skill_conversation USING btree (user_id);

--
-- Name: idx_skill_material_status_locked; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_material_status_locked ON public.skill_material USING btree (status, locked_at);

--
-- Name: idx_skill_msg_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_msg_conv ON public.skill_message USING btree (conversation_id);

--
-- Name: idx_skill_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_type ON public.skill USING btree (type);

--
-- Name: idx_skill_company_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_company_type ON public.skill USING btree (company_id, type);

--
-- Name: idx_skill_company_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_company_status ON public.skill USING btree (company_id, status);

--
-- Name: idx_skill_space_id_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_skill_space_id_unique ON public.skill USING btree (space_id) WHERE (space_id IS NOT NULL);

--
-- Name: idx_skill_share_skill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skill_share_skill ON public.skill_share USING btree (skill_id);

--
-- Name: idx_space_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_space_user ON public.space USING btree (user_id);

--
-- Name: idx_token_date_model; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_token_date_model ON public.token_usage_log USING btree (usage_date, model_type);

--
-- Name: idx_token_user_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_token_user_date ON public.token_usage_log USING btree (user_id, usage_date);

--
-- Name: idx_tool_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_space ON public.tool USING btree (space_id);

--
-- Name: idx_tool_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_type ON public.tool USING btree (type);

--
-- Name: idx_user_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_company ON public."user" USING btree (company_id);


-- ============================================================
-- 字段注释（PostgreSQL 元数据，\d+ 和 GUI 工具依赖）
-- ============================================================

-- admin_audit_log column comments
COMMENT ON COLUMN public.admin_audit_log.id IS '主键';
COMMENT ON COLUMN public.admin_audit_log.admin_id IS '操作人ID';
COMMENT ON COLUMN public.admin_audit_log.action IS '操作类型: edit_grain/deprecate_grain/create_grain/resolve_gap/edit_domain/edit_prompt';
COMMENT ON COLUMN public.admin_audit_log.target_type IS '操作对象类型: grain/gap/prompt/domain';
COMMENT ON COLUMN public.admin_audit_log.target_id IS '操作对象ID';
COMMENT ON COLUMN public.admin_audit_log.detail IS '操作详情(JSONB): 字段名+新旧值';

-- analytics_event column comments
COMMENT ON COLUMN public.analytics_event.id IS '主键';
COMMENT ON COLUMN public.analytics_event.skill_id IS '关联分身ID';
COMMENT ON COLUMN public.analytics_event.conversation_id IS '关联对话ID';
COMMENT ON COLUMN public.analytics_event.user_id IS '用户ID';
COMMENT ON COLUMN public.analytics_event.event_type IS '事件类型: recommendation_show/click, mode_switch, conversation_end';
COMMENT ON COLUMN public.analytics_event.event_data IS '事件数据(JSONB)';

-- answer_correction column comments
COMMENT ON COLUMN public.answer_correction.skill_id IS '关联的分身 ID';
COMMENT ON COLUMN public.answer_correction.conversation_id IS '关联的会话 ID（可空 — 离线矫正）';
COMMENT ON COLUMN public.answer_correction.message_id IS '被矫正的 AI 消息 ID';
COMMENT ON COLUMN public.answer_correction.original_query IS '用户当时问的问题';
COMMENT ON COLUMN public.answer_correction.bad_response IS 'AI 的错误回答';
COMMENT ON COLUMN public.answer_correction.corrected_response IS 'Admin 给出的正确答案';
COMMENT ON COLUMN public.answer_correction.grain_ids IS '涉及的颗粒 ID 列表（JSONB 数组）— 矫正后这些颗粒 weight × 0.7';
COMMENT ON COLUMN public.answer_correction.corrected_by IS '操作人标识';
COMMENT ON COLUMN public.answer_correction.created_at IS '矫正时间';

-- app_user column comments
COMMENT ON COLUMN public.app_user.nickname IS '昵称，游客自动生成"访客xxxx"，注册时可改';
COMMENT ON COLUMN public.app_user.account IS '登录账号，游客为 NULL，注册后平台全局唯一';
COMMENT ON COLUMN public.app_user.password_hash IS 'BCrypt 密码哈希，游客为 NULL';
COMMENT ON COLUMN public.app_user.status IS '状态: guest=游客（未设账号密码） / registered=已注册';
COMMENT ON COLUMN public.app_user.source_share_id IS '来源分享ID（skill_share.id），用于转化归因';
COMMENT ON COLUMN public.app_user.source IS '用户来源: share=分享链接, platform=平台注册, partner=合作方嵌入';
COMMENT ON COLUMN public.app_user.company_id IS '所属企业UUID(仅partner用户有值)=PartnerApp.app_id';

-- auto_insight column comments
COMMENT ON COLUMN public.auto_insight.type IS '洞察类型：gap_burst=缺口爆发, satisfaction_drop=满意率骤降, hit_rate_drop=命中率下降, new_pattern=发现新高频场景, inactive=分身不活跃';
COMMENT ON COLUMN public.auto_insight.severity IS '严重程度：critical=需立即处理, warning=建议关注, info=仅供参考';
COMMENT ON COLUMN public.auto_insight.evidence IS 'JSONB 数据依据：{positive_samples, negative_samples, satisfaction_delta, source_conv_ids, source_grain_ids, source_gap_ids}';
COMMENT ON COLUMN public.auto_insight.candidate_grain_id IS '关联的 AI 生成的候选颗粒（为 NULL 表示该洞察未产生候选颗粒）';

-- candidate_grain column comments
COMMENT ON COLUMN public.candidate_grain.scene_tag IS '场景标签，如 报价-ROI锚定';
COMMENT ON COLUMN public.candidate_grain.expert_thought IS 'AI 发现的销售策略/思考方式';
COMMENT ON COLUMN public.candidate_grain.standard_script IS 'AI 生成的推荐话术';
COMMENT ON COLUMN public.candidate_grain.common_mistakes IS 'AI 识别的常见话术错误';
COMMENT ON COLUMN public.candidate_grain.applicable_condition IS '适用此颗粒的场景条件';
COMMENT ON COLUMN public.candidate_grain.source_insight_id IS '产生此候选颗粒的洞察记录 ID';
COMMENT ON COLUMN public.candidate_grain.source_evidence IS 'JSONB 数据依据：{positive_samples, negative_samples, satisfaction_delta, source_conv_ids, source_grain_ids}';
COMMENT ON COLUMN public.candidate_grain.status IS '审核状态：pending_review=待审核, approved=已通过(已写入experience_grain), rejected=已拒绝';

-- company column comments
COMMENT ON COLUMN public.company.id IS '企业ID';
COMMENT ON COLUMN public.company.name IS '企业名称';
COMMENT ON COLUMN public.company.logo_url IS 'Logo地址';
COMMENT ON COLUMN public.company.brand_color IS '品牌色';
COMMENT ON COLUMN public.company.contact_name IS '联系人';
COMMENT ON COLUMN public.company.contact_phone IS '联系电话';
COMMENT ON COLUMN public.company.contact_email IS '联系邮箱';
COMMENT ON COLUMN public.company.address IS '企业地址';
COMMENT ON COLUMN public.company.industry IS '所属行业';
COMMENT ON COLUMN public.company.scale IS '企业规模';
COMMENT ON COLUMN public.company.notes IS '备注';
COMMENT ON COLUMN public.company.status IS '状态：active=合作中, archived=已归档';

-- company_register_code column comments
COMMENT ON COLUMN public.company_register_code.max_uses IS '最大使用次数，0=不限';
COMMENT ON COLUMN public.company_register_code.used_count IS '已使用次数';
COMMENT ON COLUMN public.company_register_code.default_role IS '此注册码创建的用户的默认角色：employee / company_admin';

-- conversation_stats column comments
COMMENT ON COLUMN public.conversation_stats.id IS '主键';
COMMENT ON COLUMN public.conversation_stats.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.conversation_stats.conversation_id IS '对话ID(多轮对话中可重复)';
COMMENT ON COLUMN public.conversation_stats.user_id IS '用户ID';
COMMENT ON COLUMN public.conversation_stats.mode IS '对话模式: qa/discuss/talk/practice/enterprise';
COMMENT ON COLUMN public.conversation_stats.rag_high_count IS '高匹配颗粒数(similarity≥阈值的颗粒)';
COMMENT ON COLUMN public.conversation_stats.rag_ref_count IS '参考匹配颗粒数';
COMMENT ON COLUMN public.conversation_stats.rag_none_count IS '无匹配次数(RAG返回空结果)';
COMMENT ON COLUMN public.conversation_stats.rag_avg_similarity IS '本轮RAG平均相似度';
COMMENT ON COLUMN public.conversation_stats.error_type IS '异常类型: NULL=正常, timeout, error, cancelled';
COMMENT ON COLUMN public.conversation_stats.is_test IS '是否Admin测试对话';
COMMENT ON COLUMN public.conversation_stats.llm_duration_ms IS 'LLM生成耗时(毫秒)';
COMMENT ON COLUMN public.conversation_stats.total_duration_ms IS '端到端总耗时(毫秒)';

-- experience_grain column comments
COMMENT ON COLUMN public.experience_grain.id IS '颗粒ID';
COMMENT ON COLUMN public.experience_grain.space_id IS '所属空间ID';
COMMENT ON COLUMN public.experience_grain.report_id IS '来源报告ID';
COMMENT ON COLUMN public.experience_grain.source_material_id IS '来源素材ID';
COMMENT ON COLUMN public.experience_grain.scene_tag IS '场景标签(如"价格异议""破冰")';
COMMENT ON COLUMN public.experience_grain.scene_description IS '场景描述';
COMMENT ON COLUMN public.experience_grain.expert_thought IS '销冠思路';
COMMENT ON COLUMN public.experience_grain.standard_script IS '标准话术';
COMMENT ON COLUMN public.experience_grain.common_mistakes IS '常见错误';
COMMENT ON COLUMN public.experience_grain.applicable_condition IS '适用条件';
COMMENT ON COLUMN public.experience_grain.embedding IS '向量嵌入(1024维)';
COMMENT ON COLUMN public.experience_grain.weight IS '权重(0.1-2.0)';
COMMENT ON COLUMN public.experience_grain.quality_score IS '质量评分(0-5)';
COMMENT ON COLUMN public.experience_grain.difficulty_level IS '难度: beginner/intermediate/advanced/master';
COMMENT ON COLUMN public.experience_grain.status IS '状态: active=有效, deprecated=已废弃';
COMMENT ON COLUMN public.experience_grain.helpful_count IS '有用反馈数';
COMMENT ON COLUMN public.experience_grain.unhelpful_count IS '无用反馈数';
COMMENT ON COLUMN public.experience_grain.source_type IS '颗粒来源: file_upload | interview';
COMMENT ON COLUMN public.experience_grain.source_interview_id IS '关联 interview_session.id，访谈产出的颗粒可追溯到具体会话';
COMMENT ON COLUMN public.experience_grain.search_text IS '全文检索向量 — ts_rank BM25 近似排序，GIN 索引加速';

-- expert_document column comments
COMMENT ON COLUMN public.expert_document.expert_id IS '所属技能ID';
COMMENT ON COLUMN public.expert_document.status IS '状态: uploaded→parsing→parsed/failed, pending_manual=待人工';
COMMENT ON COLUMN public.expert_document.retry_count IS '解析失败重试次数，上限3次';

-- expert_grain column comments
COMMENT ON COLUMN public.expert_grain.expert_id IS '所属技能ID';
COMMENT ON COLUMN public.expert_grain.category IS '分类: judgment_intuition/mental_model/failure_lesson等';
COMMENT ON COLUMN public.expert_grain.knowledge_content IS '知识内容';
COMMENT ON COLUMN public.expert_grain.priority IS '优先级(1-5)';
COMMENT ON COLUMN public.expert_grain.consensus_type IS '共识类型: single=单人, consensus=共识, conflict=冲突';
COMMENT ON COLUMN public.expert_grain.embedding IS '向量嵌入(1024维)';
COMMENT ON COLUMN public.expert_grain.domain IS '领域ID，继承自 ExpertSkill.domain';

-- expert_skill column comments
COMMENT ON COLUMN public.expert_skill.id IS '技能ID';
COMMENT ON COLUMN public.expert_skill.name IS '技能名称';
COMMENT ON COLUMN public.expert_skill.source_type IS '来源类型: interview=访谈, document=文档';
COMMENT ON COLUMN public.expert_skill.style_tags IS '风格标签JSON';
COMMENT ON COLUMN public.expert_skill.industry_tags IS '行业标签JSON';
COMMENT ON COLUMN public.expert_skill.grain_count IS '已提取颗粒数';
COMMENT ON COLUMN public.expert_skill.status IS '状态: pending→analyzing→extracting→active';
COMMENT ON COLUMN public.expert_skill.locked_by IS '处理锁持有者';
COMMENT ON COLUMN public.expert_skill.domain IS '领域ID，隔离不同域的萃取师经验';
COMMENT ON COLUMN public.expert_skill.source_session_id IS '元访谈 session ID，来源为 interview 时关联';
COMMENT ON COLUMN public.expert_skill.source_content IS '元访谈转录文本，供分析管道处理';

-- feedback_log column comments
COMMENT ON COLUMN public.feedback_log.id IS '主键';
COMMENT ON COLUMN public.feedback_log.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.feedback_log.conversation_id IS '所属对话ID';
COMMENT ON COLUMN public.feedback_log.message_id IS 'AI消息ID';
COMMENT ON COLUMN public.feedback_log.user_id IS '打分用户ID';
COMMENT ON COLUMN public.feedback_log.grain_id IS '关联的经验颗粒(NULL=无匹配时的打分)';
COMMENT ON COLUMN public.feedback_log.rating IS '评分: up=有帮助, down=没帮助';
COMMENT ON COLUMN public.feedback_log.query IS '用户当时的提问原文';
COMMENT ON COLUMN public.feedback_log.ai_response IS 'AI回答截取前500字';
COMMENT ON COLUMN public.feedback_log.rag_score IS '回答时的RAG平均匹配度';
COMMENT ON COLUMN public.feedback_log.source IS '来源: user=用户打分, backfill=存量迁移';

-- grain_edit_history column comments
COMMENT ON COLUMN public.grain_edit_history.id IS '主键';
COMMENT ON COLUMN public.grain_edit_history.grain_id IS '被编辑的颗粒ID';
COMMENT ON COLUMN public.grain_edit_history.field_name IS '修改的字段名(expertThought/standardScript/commonMistakes/applicableCondition/sceneTag/weight)';
COMMENT ON COLUMN public.grain_edit_history.old_value IS '修改前的内容';
COMMENT ON COLUMN public.grain_edit_history.new_value IS '修改后的内容';
COMMENT ON COLUMN public.grain_edit_history.edited_by IS '修改人';
COMMENT ON COLUMN public.grain_edit_history.edit_note IS '修改原因(Admin填写)';

-- grain_retrieve_log column comments
COMMENT ON COLUMN public.grain_retrieve_log.id IS '主键';
COMMENT ON COLUMN public.grain_retrieve_log.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.grain_retrieve_log.conversation_id IS '所属对话ID';
COMMENT ON COLUMN public.grain_retrieve_log.original_query IS '用户原始提问';
COMMENT ON COLUMN public.grain_retrieve_log.rewritten_query IS 'LLM改写后的查询';
COMMENT ON COLUMN public.grain_retrieve_log.grain_id IS '命中的颗粒ID';
COMMENT ON COLUMN public.grain_retrieve_log.scene_tag IS '颗粒的场景标签';
COMMENT ON COLUMN public.grain_retrieve_log.similarity IS '余弦相似度(0~1)';
COMMENT ON COLUMN public.grain_retrieve_log.tier IS '分层标记: high=高匹配, ref=参考, NULL=低匹配';

-- im_channel column comments
COMMENT ON COLUMN public.im_channel.company_id IS '所属企业ID';
COMMENT ON COLUMN public.im_channel.channel_type IS '渠道类型: feishu/wecom/wechat/dingtalk';
COMMENT ON COLUMN public.im_channel.enabled IS '是否启用';
COMMENT ON COLUMN public.im_channel.config IS '渠道配置JSON';
COMMENT ON COLUMN public.im_channel.linked_skills IS '关联的分身ID列表JSON';

-- interview_invite_code column comments
COMMENT ON COLUMN public.interview_invite_code.type IS 'enterprise | personal';
COMMENT ON COLUMN public.interview_invite_code.company_id IS 'enterprise 时必填';
COMMENT ON COLUMN public.interview_invite_code.invited_by IS 'personal 时必填，邀请者昵称';
COMMENT ON COLUMN public.interview_invite_code.code IS '8位 base62，全局唯一';
COMMENT ON COLUMN public.interview_invite_code.enabled IS '启停开关';
COMMENT ON COLUMN public.interview_invite_code.max_uses IS '最大使用次数，0=不限  -- 最大使用次数，0=不限';
COMMENT ON COLUMN public.interview_invite_code.used_count IS '已使用次数        -- 已使用次数';
COMMENT ON COLUMN public.interview_invite_code.created_by IS '创建人 ID';
COMMENT ON COLUMN public.interview_invite_code.expires_at IS '过期时间，NULL=永久';

-- interview_message column comments
COMMENT ON COLUMN public.interview_message.id IS '消息ID';
COMMENT ON COLUMN public.interview_message.session_id IS '所属会话ID';
COMMENT ON COLUMN public.interview_message.role IS '角色: ai=AI提问, user=用户回答';
COMMENT ON COLUMN public.interview_message.content IS '消息内容';
COMMENT ON COLUMN public.interview_message.phase IS '所属阶段';
COMMENT ON COLUMN public.interview_message.depth IS '追问深度';

-- interview_phase_summary column comments
COMMENT ON COLUMN public.interview_phase_summary.session_id IS '关联的访谈会话 ID';
COMMENT ON COLUMN public.interview_phase_summary.phase IS '阶段标识: opening / storytelling / modeling / closing';
COMMENT ON COLUMN public.interview_phase_summary.phase_label IS '阶段中文标签: 开场定调 / 故事深描 / 模型提炼 / 收网确认';
COMMENT ON COLUMN public.interview_phase_summary.summary IS 'AI 生成的本阶段已收集关键信息摘要';
COMMENT ON COLUMN public.interview_phase_summary.created_at IS '摘要生成时间';

-- interview_session column comments
COMMENT ON COLUMN public.interview_session.id IS '会话ID';
COMMENT ON COLUMN public.interview_session.space_id IS '所属空间ID';
COMMENT ON COLUMN public.interview_session.topic IS '访谈主题';
COMMENT ON COLUMN public.interview_session.status IS '状态: created→in_progress→completed';
COMMENT ON COLUMN public.interview_session.current_phase IS '当前阶段: opening/storytelling/modeling/closing';
COMMENT ON COLUMN public.interview_session.invite_code IS '邀请码';
COMMENT ON COLUMN public.interview_session.interview_type IS '访谈类型: sales=销冠访谈';
COMMENT ON COLUMN public.interview_session.last_active_at IS '最后活跃时间';
COMMENT ON COLUMN public.interview_session.finished_at IS '完成时间';
COMMENT ON COLUMN public.interview_session.domain IS '领域ID，如 sales.b2b_enterprise / finance.secondary_market';

-- knowledge_gap column comments
COMMENT ON COLUMN public.knowledge_gap.id IS '主键';
COMMENT ON COLUMN public.knowledge_gap.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.knowledge_gap.space_id IS '所属空间ID';
COMMENT ON COLUMN public.knowledge_gap.query IS '用户提问原文';
COMMENT ON COLUMN public.knowledge_gap.scene_tag IS '系统推测的场景标签';
COMMENT ON COLUMN public.knowledge_gap.attempted_query_count IS '该场景累计出现次数';
COMMENT ON COLUMN public.knowledge_gap.status IS '状态: open/reviewing/resolved/ignored';
COMMENT ON COLUMN public.knowledge_gap.resolved_by IS '处理人';
COMMENT ON COLUMN public.knowledge_gap.resolved_at IS '处理时间';
COMMENT ON COLUMN public.knowledge_gap.note IS '管理员备注';
COMMENT ON COLUMN public.knowledge_gap.embedding IS '缺口文本的向量表示，用于 pgvector 余弦聚类';

-- report column comments
COMMENT ON COLUMN public.report.id IS '报告ID';
COMMENT ON COLUMN public.report.space_id IS '所属空间ID';
COMMENT ON COLUMN public.report.session_id IS '来源访谈会话ID';
COMMENT ON COLUMN public.report.title IS '报告标题';
COMMENT ON COLUMN public.report.subtitle IS '报告副标题';
COMMENT ON COLUMN public.report.content_json IS '报告内容JSON(chapters/steps/decisions等)';
COMMENT ON COLUMN public.report.word_url IS 'Word文件地址';
COMMENT ON COLUMN public.report.ppt_url IS 'PPT文件地址';
COMMENT ON COLUMN public.report.file_status IS '文件状态: synced=已同步';
COMMENT ON COLUMN public.report.rating IS '评分(1-5)';
COMMENT ON COLUMN public.report.view_count IS '浏览次数';

-- report_history column comments
COMMENT ON COLUMN public.report_history.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.report_history.version IS '版本号';
COMMENT ON COLUMN public.report_history.material_ids IS '关联素材ID列表JSON';
COMMENT ON COLUMN public.report_history.grain_count IS '颗粒数量';

-- skill column comments
COMMENT ON COLUMN public.skill.id IS '分身ID';
COMMENT ON COLUMN public.skill.space_id IS '所属空间ID';
COMMENT ON COLUMN public.skill.model_name IS '使用的大模型名称';
COMMENT ON COLUMN public.skill.status IS '状态: generating→reviewing→published/discarded';
COMMENT ON COLUMN public.skill.display_name IS '对外展示名称';
COMMENT ON COLUMN public.skill.owner_name IS '销冠真实姓名(展示用)';
COMMENT ON COLUMN public.skill.owner_title IS '销冠职位(展示用)';
COMMENT ON COLUMN public.skill.department IS '所属部门';
COMMENT ON COLUMN public.skill.seniority IS '从业年限';
COMMENT ON COLUMN public.skill.tags IS '灵活标签JSON: ["金融","B2B"]';
COMMENT ON COLUMN public.skill.target_scenarios IS '适用场景JSON: ["初次拜访","异议处理"]';
COMMENT ON COLUMN public.skill.limitations IS '已知局限性';
COMMENT ON COLUMN public.skill.publish_notes IS '发布审核备注';
COMMENT ON COLUMN public.skill.published_at IS '发布时间';
COMMENT ON COLUMN public.skill.published_by IS '发布人ID';
COMMENT ON COLUMN public.skill.domain IS '领域ID，如 sales.b2b_enterprise / finance.secondary_market';
COMMENT ON COLUMN public.skill.avatar_url IS '分身头像URL';
COMMENT ON COLUMN public.skill.opening_message IS '分身开场白 — 聊天页入场态展示，一般为专家自我介绍或欢迎语';

-- skill_acceptance column comments
COMMENT ON COLUMN public.skill_acceptance.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.skill_acceptance.status IS '状态: pending→testing→passed/rejected';
COMMENT ON COLUMN public.skill_acceptance.test_score IS '测试得分';
COMMENT ON COLUMN public.skill_acceptance.accepted_by IS '验收人ID';
COMMENT ON COLUMN public.skill_acceptance.accepted_at IS '验收时间';

-- skill_acceptance_question column comments
COMMENT ON COLUMN public.skill_acceptance_question.acceptance_id IS '所属验收记录ID';
COMMENT ON COLUMN public.skill_acceptance_question.question IS '考题内容';
COMMENT ON COLUMN public.skill_acceptance_question.expected_points IS '期望得分点JSON';
COMMENT ON COLUMN public.skill_acceptance_question.actual_answer IS '实际回答';
COMMENT ON COLUMN public.skill_acceptance_question.score IS '得分';

-- skill_conversation column comments
COMMENT ON COLUMN public.skill_conversation.id IS '会话ID';
COMMENT ON COLUMN public.skill_conversation.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.skill_conversation.user_id IS '对话用户ID';
COMMENT ON COLUMN public.skill_conversation.title IS '会话标题(取首条消息前30字)';
COMMENT ON COLUMN public.skill_conversation.mode IS '模式: qa=问答, practice=对练, quick=快速提问, discuss=自由讨论, talk=自由对话';

-- skill_evaluation column comments
COMMENT ON COLUMN public.skill_evaluation.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.skill_evaluation.conversation_id IS '关联会话ID';
COMMENT ON COLUMN public.skill_evaluation.mode IS '评估模式: qa/practice/auto_evaluate/acceptance_report';
COMMENT ON COLUMN public.skill_evaluation.score IS '综合评分(0-100)';
COMMENT ON COLUMN public.skill_evaluation.style_score IS '风格分(权重30%)';
COMMENT ON COLUMN public.skill_evaluation.consistency_score IS '一致性分(权重30%)';
COMMENT ON COLUMN public.skill_evaluation.behavior_score IS '行为分(权重20%)';
COMMENT ON COLUMN public.skill_evaluation.script_reuse_score IS '话术复用分(权重20%)';
COMMENT ON COLUMN public.skill_evaluation.strengths IS '优点JSON数组';
COMMENT ON COLUMN public.skill_evaluation.improvements IS '改进点JSON数组';
COMMENT ON COLUMN public.skill_evaluation.demo_script IS '销冠示范话术';

-- skill_material column comments
COMMENT ON COLUMN public.skill_material.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.skill_material.uploaded_by IS '上传人ID';
COMMENT ON COLUMN public.skill_material.file_name IS '文件名';
COMMENT ON COLUMN public.skill_material.file_url IS '文件存储地址';
COMMENT ON COLUMN public.skill_material.file_type IS '文件类型';
COMMENT ON COLUMN public.skill_material.file_size IS '文件大小(字节)';
COMMENT ON COLUMN public.skill_material.parsed_content IS '解析后的文本内容';
COMMENT ON COLUMN public.skill_material.version IS '版本号';
COMMENT ON COLUMN public.skill_material.status IS '状态: uploaded→cleaning→analyzing→analyzed→extracted; rejected=准入不通过; failed=访谈转录清洗失败; discarded=已废弃';
COMMENT ON COLUMN public.skill_material.locked_by IS '处理锁持有者';
COMMENT ON COLUMN public.skill_material.locked_at IS '处理锁时间';
COMMENT ON COLUMN public.skill_material.material_type IS '素材类型: dialogue=对话, monologue=独白/心得, interview=访谈';
COMMENT ON COLUMN public.skill_material.retry_count IS '解析/清洗失败重试次数，上限3次';

-- skill_message column comments
COMMENT ON COLUMN public.skill_message.conversation_id IS '所属会话ID';
COMMENT ON COLUMN public.skill_message.role IS '角色: user=用户, assistant=AI分身';
COMMENT ON COLUMN public.skill_message.content IS '消息内容';
COMMENT ON COLUMN public.skill_message.grain_id IS '关联的经验颗粒ID';
COMMENT ON COLUMN public.skill_message.report_id IS '关联的报告ID';
COMMENT ON COLUMN public.skill_message.role_label IS '角色展示名: 我 / 销冠 / 客户 / 我（销冠）';

-- skill_profile column comments
COMMENT ON COLUMN public.skill_profile.skill_id IS '所属分身ID';
COMMENT ON COLUMN public.skill_profile.personality IS '性格描述';
COMMENT ON COLUMN public.skill_profile.speaking_style IS '说话风格';
COMMENT ON COLUMN public.skill_profile.background IS '背景经历';
COMMENT ON COLUMN public.skill_profile.common_phrases IS '口头禅';
COMMENT ON COLUMN public.skill_profile.knowledge_domains IS '擅长领域JSON';
COMMENT ON COLUMN public.skill_profile.communication_preferences IS '沟通偏好JSON';

-- skill_share column comments
COMMENT ON COLUMN public.skill_share.company_id IS '企业归属(C端分身分享时为null)';
COMMENT ON COLUMN public.skill_share.share_code IS '短码，URL 形如 /s/{share_code}，base62 随机 10 位';
COMMENT ON COLUMN public.skill_share.channel IS 'public=对外分享, internal=对内分享';
COMMENT ON COLUMN public.skill_share.enabled IS '共享开关：关闭后分享链接立即失效，企业内部使用不受影响';

-- space column comments
COMMENT ON COLUMN public.space.id IS '空间ID';
COMMENT ON COLUMN public.space.user_id IS '空间所有者ID。B端存user.id，C端存app_user.id。无外键约束。';
COMMENT ON COLUMN public.space.title IS '空间标题';
COMMENT ON COLUMN public.space.description IS '空间描述(展示为销冠头衔)';
COMMENT ON COLUMN public.space.tags IS '标签JSON数组';
COMMENT ON COLUMN public.space.is_public IS '是否公开';
COMMENT ON COLUMN public.space.status IS '状态: active=活跃, paused=暂停, archived=归档';

-- tool column comments
COMMENT ON COLUMN public.tool.type IS '类型: poster=海报, card=卡片, checklist=清单, script=剧本';
COMMENT ON COLUMN public.tool.name IS '工具名称';

-- "user" column comments
COMMENT ON COLUMN public."user".id IS '用户ID';
COMMENT ON COLUMN public."user".company_id IS '所属企业ID';
COMMENT ON COLUMN public."user".name IS '真实姓名';
COMMENT ON COLUMN public."user".role IS '角色: super_admin=管理员, employee=普通用户';
COMMENT ON COLUMN public."user".avatar_url IS '头像地址';
COMMENT ON COLUMN public."user".phone IS '手机号';
COMMENT ON COLUMN public."user".account IS '登录账号(企业内唯一)';
COMMENT ON COLUMN public."user".password_hash IS 'BCrypt密码哈希';
COMMENT ON COLUMN public."user".is_active IS '是否启用';

-- ============================================================
-- 初始数据
-- ============================================================

-- 1. 默认企业
INSERT INTO company (id, name, logo_url, brand_color) VALUES
('c0000000-0000-0000-0000-000000000001', '默认企业', NULL, '#1A2B4C');

-- 2. 默认管理员（密码: admin123）
INSERT INTO "user" (id, company_id, name, role, account, password_hash, is_active, created_at, updated_at) VALUES
('00000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', '系统管理员', 'super_admin', 'admin', '$2b$10$80bLpb/rrOHpaMkb7/Bowe/0FMFGmjxSK1wNOHj044tymecaSNmFe', true, NOW(), NOW());

-- 3. 默认企业注册码
INSERT INTO company_register_code (id, company_id, code, enabled, max_uses, used_count, created_at, default_role)
VALUES (gen_random_uuid(), 'c0000000-0000-0000-0000-000000000001', 'DEFAULT01', true, 0, 0, NOW(), 'company_admin');
