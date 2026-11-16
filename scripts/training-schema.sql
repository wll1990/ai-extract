-- ============================================================
-- training-schema.sql — 培训中心全量建表（P2 计划/批次/学习/评分/认证 + P3 补训 + 共用导出）
--
-- 【部署】上线/新环境执行本文件，一条命令收敛到最终态：
--   psql -U <user> -d <database> -v ON_ERROR_STOP=1 -f scripts/training-schema.sql
--
-- 【幂等】本文件可重复执行：
--   · 建表用 CREATE TABLE IF NOT EXISTS
--   · 补列用 ALTER TABLE ... ADD COLUMN IF NOT EXISTS
--   · 唯一/枚举约束用 DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT
--   · 索引用 CREATE INDEX IF NOT EXISTS
--
-- 【重置】本文件不删数据。清空重建请执行 scripts/training-reset.sql（仅开发/测试）。
--
-- 前提：PostgreSQL 13+（gen_random_uuid 内置）；jsonb 内置；company/user 表已存在
--
-- 【外键决策】同 evaluation_*：本库约定不建 FK，引用完整性由 Service 校验并显式返回 409。
--   例：TrainingAssignmentService（校验 plan 版本已发布、学员属于本企业）、
--       TrainingAttemptService（校验 task_record → assignment 归属）。
--
-- 【状态列】全部 varchar + CHECK，与库内其余表一致。
--
-- 【评审修订】docs/44 阻塞项 B1–B13 已全部落进本文件：
--   B1 阶段 → training_task.phase_no / phase_name
--   B4 补训 → training_assignment.source_assignment_id
--   B5 评分失败 → attempt.status 含 error + error_message + grading_attempts
--   B6 考试时长/逾期 → training_task.exam_duration_minutes + 截止扫描索引
--   B8 计数守卫 → required_* 计数列 + CHECK 兜底
-- ============================================================

-- ============================================================
-- 一、计划与任务（3 表）
-- ============================================================

-- 培训计划
CREATE TABLE IF NOT EXISTS public.training_plan (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid NOT NULL                                   , -- 所属企业（企业隔离）
    name character varying(255) NOT NULL                       , -- 计划名
    description text                                           , -- 描述
    status character varying(20) NOT NULL DEFAULT 'draft'      , -- draft/published/retired
    current_version integer NOT NULL DEFAULT 1                 , -- 当前版本号
    created_by uuid                                            , -- 创建人
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_plan IS '培训计划 — 版本化；发布后可分配，退役阻止新分配但不终止已有学习';

-- 计划版本：发布即冻结任务集；已发布版本的 task 只读，编辑必须创建新版本
CREATE TABLE IF NOT EXISTS public.training_plan_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    plan_id uuid NOT NULL                                      , -- 所属计划
    version integer NOT NULL                                   , -- 版本号
    status character varying(20) NOT NULL DEFAULT 'draft'      , -- draft/published
    task_count integer NOT NULL DEFAULT 0                      , -- 冻结时任务数快照
    published_at timestamp without time zone                   , -- 发布时间
    created_by uuid                                            , -- 创建人
    created_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_plan_version IS '培训计划版本 — 发布冻结，分配绑定此版本';

-- 培训任务（按阶段分组）
CREATE TABLE IF NOT EXISTS public.training_task (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    plan_id uuid NOT NULL                                      , -- 所属计划
    version integer NOT NULL                                   , -- 所属计划版本
    phase_no integer NOT NULL DEFAULT 1                        , -- 阶段序号（从 1 开始）
    phase_name character varying(100)                          , -- 阶段名（如「第一周：产品知识」）
    type character varying(20) NOT NULL                        , -- reading/practice/exam
    title character varying(255) NOT NULL                      , -- 任务标题
    description text                                           , -- 任务说明
    is_required boolean NOT NULL DEFAULT true                  , -- 必修（认证门槛）
    order_no integer NOT NULL                                  , -- 阶段内顺序
    prerequisites jsonb NOT NULL DEFAULT '[]'::jsonb           , -- 前置任务 id 数组
    resource_type character varying(20)                        , -- knowledge/case/skill
    resource_id uuid                                           , -- 资源 id（practice 时=分身 skill_id）
    scene_tag character varying(50)                            , -- 陪练场景标签
    allow_hints boolean NOT NULL DEFAULT true                  , -- 考试型任务固定 false（服务端拒绝提示）
    exam_dataset_id uuid                                       , -- 考试题集
    exam_dataset_version integer                               , -- 考试题集版本（冻结）
    exam_item_count integer                                    , -- 抽题数；NULL=全量
    exam_duration_minutes integer                              , -- 考试时长（分钟），exam 必填
    pass_criteria jsonb                                        , -- {"minScore":80,"minPointCoverage":0.7}
    max_attempts integer NOT NULL DEFAULT 1                    , -- 总尝试次数（含首次）
    deadline_policy character varying(20) NOT NULL DEFAULT 'auto_submit' , -- auto_submit/timeout_fail
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_task IS '培训任务 — 按 phase 分组，随计划版本冻结';

-- ============================================================
-- 二、批次与分配（2 表）
-- ============================================================

-- 培训批次/班级：绑定冻结的计划版本
CREATE TABLE IF NOT EXISTS public.training_cohort (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid NOT NULL                                   , -- 所属企业
    plan_id uuid NOT NULL                                      , -- 计划
    plan_version integer NOT NULL                              , -- 绑定的冻结版本
    name character varying(255) NOT NULL                       , -- 批次名
    mentor_id uuid                                             , -- 导师/复核人（数据级授权仅限本批次）
    deadline timestamp without time zone                       , -- 批次截止
    status character varying(20) NOT NULL DEFAULT 'active'     , -- active/completed/cancelled/archived
    created_by uuid                                            ,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_cohort IS '培训批次 — 绑定计划版本；mentor 仅能复核本批次';

-- 学员分配
CREATE TABLE IF NOT EXISTS public.training_assignment (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid NOT NULL                                   , -- 所属企业
    cohort_id uuid NOT NULL                                    , -- 所属批次
    learner_id uuid NOT NULL                                   , -- 学员
    source_assignment_id uuid                                  , -- 补训来源分配（B4：补训=新批次新分配）
    -- pending_certification 长 21，列宽必须 ≥ 21（varchar(20) 会在必修全过时写入失败）
    status character varying(32) NOT NULL DEFAULT 'not_started' , -- not_started/in_progress/pending_certification/certified/cancelled
    total_tasks integer NOT NULL DEFAULT 0                     , -- 冗余：任务总数
    required_total integer NOT NULL DEFAULT 0                  , -- 冗余：必修任务数
    completed_tasks integer NOT NULL DEFAULT 0                 , -- 冗余：完成数（阅读已确认或尝试出结论）
    required_passed integer NOT NULL DEFAULT 0                 , -- 冗余：必修通过数（认证判定用）
    passed_tasks integer NOT NULL DEFAULT 0                    , -- 冗余：通过数
    deadline timestamp without time zone                       , -- 逾期为时间派生态，不存状态
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_assignment IS '学员分配 — 计数同事务增量更新，禁止事后批量重算';

-- ============================================================
-- 三、学习与尝试（2 表）
-- ============================================================

-- 学员-任务进度行：分配时按冻结版本一次性建齐（一学员一任务一行）
CREATE TABLE IF NOT EXISTS public.training_task_record (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    assignment_id uuid NOT NULL                                , -- 所属分配
    task_id uuid NOT NULL                                      , -- 任务
    status character varying(20) NOT NULL DEFAULT 'locked'     , -- locked/available/in_progress/pending_review/passed/failed/waived
    acknowledged_at timestamp without time zone                , -- 阅读确认（只证明已读，不代表达标）
    waived_by uuid                                             , -- 豁免人（waived 必填）
    waived_reason text                                         , -- 豁免理由（必填）
    started_at timestamp without time zone                     ,
    finished_at timestamp without time zone                    ,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_task_record IS '学员任务进度 — 三类任务统一口径，认证与看板都读这里';

-- 尝试（陪练/考试）：一次提交一行，重考建新 attempt_no
CREATE TABLE IF NOT EXISTS public.training_attempt (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    assignment_id uuid NOT NULL                                , -- 所属分配
    task_id uuid NOT NULL                                      , -- 任务
    task_record_id uuid NOT NULL                               , -- 对应进度行
    type character varying(20) NOT NULL                        , -- practice/exam
    attempt_no integer NOT NULL DEFAULT 1                      , -- 第几次尝试
    status character varying(20) NOT NULL DEFAULT 'in_progress' , -- in_progress/submitted/grading/pending_review/finalized/error
    result character varying(20)                               , -- pass/fail/undetermined（业务结论独立）
    skill_id uuid                                              , -- 陪练绑定的分身
    conversation_id uuid                                       , -- 陪练对话（复用 skill_conversation）
    question_snapshot jsonb                                    , -- 考试冻结题面；含金标，禁止下发学员
    answers jsonb                                              , -- 草稿/最终答案 {"<itemId>":"<text>"}
    score jsonb                                                , -- 评分结果（含 confidence/needReview）
    error_message text                                         , -- 评分失败原因（status=error）
    termination_reason character varying(20)                   , -- 系统终止原因：auto_submit/timeout_fail/system_error
    grading_attempts integer NOT NULL DEFAULT 0                , -- 已领取评分次数（防无限重试）
    started_at timestamp without time zone                     ,
    submitted_at timestamp without time zone                   ,
    deadline timestamp without time zone                       , -- min(开始+时长, 分配截止)
    finished_at timestamp without time zone                    ,
    lease_owner character varying(100)                         , -- 评分租约持有者
    lease_expires_at timestamp without time zone               ,
    heartbeat_at timestamp without time zone                   ,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_attempt IS '培训尝试 — 执行态与业务结论分离；评分失败落 error 不无限重试';

-- ============================================================
-- 四、复核与认证（2 表）
-- ============================================================

-- 主管/导师复核
CREATE TABLE IF NOT EXISTS public.training_review (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    attempt_id uuid NOT NULL                                   , -- 被复核尝试
    reviewer_id uuid                                           , -- 复核人
    action character varying(20) NOT NULL                      , -- pass/reject
    reason text NOT NULL                                       , -- 必填；退回须写具体要求
    original_score jsonb                                       , -- 原分（保留）
    adjusted_score jsonb                                       , -- 复核后分
    created_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_review IS '主管复核 — 只追加，不改历史';

-- 认证：一次分配一条
CREATE TABLE IF NOT EXISTS public.training_certification (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid NOT NULL                                   , -- 所属企业
    assignment_id uuid NOT NULL                                , -- 所属分配
    learner_id uuid NOT NULL                                   , -- 学员
    status character varying(20) NOT NULL DEFAULT 'pending'    , -- pending/confirmed/remediating
    confirmed_by uuid                                          , -- 确认人
    confirmed_at timestamp without time zone                   ,
    note text                                                  ,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.training_certification IS '认证 — 必修全过后主管确认；P3 补训置 remediating';

-- ============================================================
-- 五、导出（评测/培训共用，G8 落点）
-- ============================================================

CREATE TABLE IF NOT EXISTS public.export_job (
    id uuid DEFAULT gen_random_uuid() NOT NULL                 , -- 主键
    company_id uuid NOT NULL                                   , -- 所属企业
    source_type character varying(20) NOT NULL                 , -- evaluation_run/training_cohort
    source_id uuid NOT NULL                                    , -- 来源 id
    status character varying(20) NOT NULL DEFAULT 'running'    , -- running/done/failed
    file_path character varying(1000)                          , -- 导出文件
    error_message text                                         ,
    created_by uuid                                            ,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.export_job IS '导出任务 — 评测报告与培训批次共用';

-- ============================================================
-- 六、约束（幂等：先 DROP IF EXISTS 再 ADD）
-- ============================================================

ALTER TABLE public.training_plan DROP CONSTRAINT IF EXISTS ck_tp_status;
ALTER TABLE public.training_plan ADD CONSTRAINT ck_tp_status
    CHECK (status IN ('draft','published','retired'));

ALTER TABLE public.training_plan_version DROP CONSTRAINT IF EXISTS ck_tpv_status;
ALTER TABLE public.training_plan_version ADD CONSTRAINT ck_tpv_status
    CHECK (status IN ('draft','published'));
ALTER TABLE public.training_plan_version DROP CONSTRAINT IF EXISTS uq_tpv_plan_version;
ALTER TABLE public.training_plan_version ADD CONSTRAINT uq_tpv_plan_version
    UNIQUE (plan_id, version);

ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_type;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_type
    CHECK (type IN ('reading','practice','exam'));
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_reading_resource;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_reading_resource
    CHECK (type <> 'reading' OR (resource_type IN ('knowledge','case') AND resource_id IS NOT NULL));
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_practice_resource;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_practice_resource
    CHECK (type <> 'practice' OR (resource_type = 'skill' AND resource_id IS NOT NULL));
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_exam_dataset;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_exam_dataset
    CHECK (type <> 'exam' OR (exam_dataset_id IS NOT NULL AND exam_dataset_version IS NOT NULL));
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_exam_duration;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_exam_duration
    CHECK (type <> 'exam' OR (exam_duration_minutes IS NOT NULL AND exam_duration_minutes > 0));
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_exam_no_hints;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_exam_no_hints
    CHECK (type <> 'exam' OR allow_hints = false);
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_phase;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_phase
    CHECK (phase_no >= 1);
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_max_attempts;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_max_attempts
    CHECK (max_attempts >= 1);
ALTER TABLE public.training_task DROP CONSTRAINT IF EXISTS ck_tt_deadline_policy;
ALTER TABLE public.training_task ADD CONSTRAINT ck_tt_deadline_policy
    CHECK (deadline_policy IN ('auto_submit','timeout_fail'));

ALTER TABLE public.training_cohort DROP CONSTRAINT IF EXISTS ck_tc_status;
ALTER TABLE public.training_cohort ADD CONSTRAINT ck_tc_status
    CHECK (status IN ('active','completed','cancelled','archived'));

-- 兼容已存在的旧库：pending_certification 长 21，varchar(20) 会在必修全过时写入失败。
-- ALTER 为幂等操作（同类型重复执行不报错），旧库重跑本脚本即可修复。
ALTER TABLE public.training_assignment ALTER COLUMN status TYPE character varying(32);

ALTER TABLE public.training_assignment DROP CONSTRAINT IF EXISTS ck_ta_status;
ALTER TABLE public.training_assignment ADD CONSTRAINT ck_ta_status
    CHECK (status IN ('not_started','in_progress','pending_certification','certified','cancelled'));
ALTER TABLE public.training_assignment DROP CONSTRAINT IF EXISTS uq_ta_cohort_learner;
ALTER TABLE public.training_assignment ADD CONSTRAINT uq_ta_cohort_learner
    UNIQUE (cohort_id, learner_id);
-- 计数不变式：0 <= required_passed <= required_total <= total_tasks；passed <= completed <= total
ALTER TABLE public.training_assignment DROP CONSTRAINT IF EXISTS ck_ta_counters;
ALTER TABLE public.training_assignment ADD CONSTRAINT ck_ta_counters
    CHECK (completed_tasks >= 0 AND passed_tasks >= 0
           AND passed_tasks <= completed_tasks AND completed_tasks <= total_tasks
           AND required_passed >= 0 AND required_passed <= required_total
           AND required_total <= total_tasks);

ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS ck_ttr_status;
ALTER TABLE public.training_task_record ADD CONSTRAINT ck_ttr_status
    CHECK (status IN ('locked','available','in_progress','pending_review','passed','failed','waived'));
-- 旧唯一键 (assignment_id, task_id) 必须直接删除，不能先 ADD：
-- 已有 round 1/2 补训数据时 ADD 会 23505，导致整个脚本中断（P1-4）。
ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS uq_ttr_assignment_task;
ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS ck_ttr_waived;
ALTER TABLE public.training_task_record ADD CONSTRAINT ck_ttr_waived
    CHECK (status <> 'waived' OR (waived_by IS NOT NULL AND waived_reason IS NOT NULL));
-- 阅读任务的已读时间只能有值于 passed/waived，防止「点了已读但状态未变」
ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS ck_ttr_ack;
ALTER TABLE public.training_task_record ADD CONSTRAINT ck_ttr_ack
    CHECK (acknowledged_at IS NULL OR status IN ('passed','waived','in_progress'));

-- 兼容旧库：系统终止原因（auto_submit/timeout_fail/system_error）
ALTER TABLE public.training_attempt
    ADD COLUMN IF NOT EXISTS termination_reason character varying(20);

ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_type;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_type
    CHECK (type IN ('practice','exam'));
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_status;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_status
    CHECK (status IN ('in_progress','submitted','grading','pending_review','finalized','error'));
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_result;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_result
    CHECK (result IS NULL OR result IN ('pass','fail','undetermined'));
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS uq_tat_record_attempt;
ALTER TABLE public.training_attempt ADD CONSTRAINT uq_tat_record_attempt
    UNIQUE (task_record_id, attempt_no);
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_attempt_no;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_attempt_no
    CHECK (attempt_no >= 1);
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_practice_skill;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_practice_skill
    CHECK (type <> 'practice' OR skill_id IS NOT NULL);
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_exam_snapshot;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_exam_snapshot
    CHECK (type <> 'exam' OR question_snapshot IS NOT NULL);
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_error_msg;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_error_msg
    CHECK (status <> 'error' OR error_message IS NOT NULL);
ALTER TABLE public.training_attempt DROP CONSTRAINT IF EXISTS ck_tat_grading_attempts;
ALTER TABLE public.training_attempt ADD CONSTRAINT ck_tat_grading_attempts
    CHECK (grading_attempts >= 0);

ALTER TABLE public.training_review DROP CONSTRAINT IF EXISTS ck_trv_action;
ALTER TABLE public.training_review ADD CONSTRAINT ck_trv_action
    CHECK (action IN ('pass','reject'));
ALTER TABLE public.training_review DROP CONSTRAINT IF EXISTS ck_trv_reason;
ALTER TABLE public.training_review ADD CONSTRAINT ck_trv_reason
    CHECK (length(btrim(reason)) > 0);

-- ============================================================
-- 五之二、P3 增量：补训轮次 / 撤销认证 / 延期 / 站内信 / 导出
-- ============================================================

-- 任务记录：同一任务可在原分配下追加补训轮（round_no > 1），原失败行保留
ALTER TABLE public.training_task_record
    ADD COLUMN IF NOT EXISTS round_no integer NOT NULL DEFAULT 1;
ALTER TABLE public.training_task_record
    ADD COLUMN IF NOT EXISTS remediation_of_record_id uuid;
ALTER TABLE public.training_task_record
    ADD COLUMN IF NOT EXISTS remediation_reason text;
ALTER TABLE public.training_task_record
    ADD COLUMN IF NOT EXISTS remediation_created_by uuid;
-- 旧库兜底（列已存在且为空时同样安全）
UPDATE public.training_task_record SET round_no = 1 WHERE round_no IS NULL;
ALTER TABLE public.training_task_record ALTER COLUMN round_no SET DEFAULT 1;
ALTER TABLE public.training_task_record ALTER COLUMN round_no SET NOT NULL;

ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS uq_ttr_assignment_task;
ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS uq_ttr_assignment_task_round;
ALTER TABLE public.training_task_record ADD CONSTRAINT uq_ttr_assignment_task_round
    UNIQUE (assignment_id, task_id, round_no);

ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS ck_ttr_round;
ALTER TABLE public.training_task_record ADD CONSTRAINT ck_ttr_round
    CHECK (round_no >= 1);
ALTER TABLE public.training_task_record DROP CONSTRAINT IF EXISTS ck_ttr_remediation;
ALTER TABLE public.training_task_record ADD CONSTRAINT ck_ttr_remediation
    CHECK (round_no = 1 OR (remediation_of_record_id IS NOT NULL
                            AND length(btrim(remediation_reason)) > 0));

-- 分配：截止延期留痕（首次延期保留原截止）
ALTER TABLE public.training_assignment ADD COLUMN IF NOT EXISTS original_deadline timestamp without time zone;
ALTER TABLE public.training_assignment ADD COLUMN IF NOT EXISTS deadline_extended_at timestamp without time zone;
ALTER TABLE public.training_assignment ADD COLUMN IF NOT EXISTS deadline_extended_by uuid;
ALTER TABLE public.training_assignment ADD COLUMN IF NOT EXISTS deadline_extend_reason text;

-- 站内信（学员/导师通知中心）
CREATE TABLE IF NOT EXISTS public.user_notification (
    id uuid DEFAULT gen_random_uuid() NOT NULL                , -- 主键
    company_id uuid NOT NULL                                  , -- 所属企业（隔离）
    user_id uuid NOT NULL                                     , -- 收件人
    type character varying(40) NOT NULL                       , -- training_pending_review / training_certified ...
    title character varying(255) NOT NULL                     , -- 标题
    content text                                              , -- 正文
    link character varying(500)                               , -- 前端跳转
    biz_type character varying(40)                            , -- 关联对象类型
    biz_id uuid                                               , -- 关联对象 id
    dedupe_key character varying(200)                         , -- 去重键（可选）
    read_at timestamp without time zone                       , -- 已读时间
    created_at timestamp without time zone NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.user_notification IS '站内信 — 认证/复核/补训/延期/导出事件通知，dedupe_key 防重复';

-- 导出任务：异步领取与租约
ALTER TABLE public.export_job ADD COLUMN IF NOT EXISTS finished_at timestamp without time zone;
ALTER TABLE public.export_job ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0;
ALTER TABLE public.export_job ADD COLUMN IF NOT EXISTS lease_owner character varying(100);
ALTER TABLE public.export_job ADD COLUMN IF NOT EXISTS lease_expires_at timestamp without time zone;
ALTER TABLE public.export_job ADD COLUMN IF NOT EXISTS row_count integer;
ALTER TABLE public.export_job DROP CONSTRAINT IF EXISTS ck_ej_attempts;
ALTER TABLE public.export_job ADD CONSTRAINT ck_ej_attempts CHECK (attempts >= 0);

ALTER TABLE public.training_certification DROP CONSTRAINT IF EXISTS ck_tcf_status;
ALTER TABLE public.training_certification ADD CONSTRAINT ck_tcf_status
    CHECK (status IN ('pending','confirmed','remediating','revoked'));
-- 一个分配最多一条"当前生效"认证；历史 revoked 行可共存
ALTER TABLE public.training_certification DROP CONSTRAINT IF EXISTS uq_tcf_assignment;
DROP INDEX IF EXISTS public.uq_tcf_assignment;
ALTER TABLE public.training_certification ADD COLUMN IF NOT EXISTS revoked_by uuid;
ALTER TABLE public.training_certification ADD COLUMN IF NOT EXISTS revoked_at timestamp without time zone;
ALTER TABLE public.training_certification ADD COLUMN IF NOT EXISTS revoke_reason text;
CREATE UNIQUE INDEX IF NOT EXISTS uq_tcf_assignment_confirmed
    ON public.training_certification(assignment_id)
    WHERE status = 'confirmed';
ALTER TABLE public.training_certification DROP CONSTRAINT IF EXISTS ck_tcf_confirmed;
ALTER TABLE public.training_certification ADD CONSTRAINT ck_tcf_confirmed
    CHECK (status <> 'confirmed' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL));
ALTER TABLE public.training_certification DROP CONSTRAINT IF EXISTS ck_tcf_revoked;
ALTER TABLE public.training_certification ADD CONSTRAINT ck_tcf_revoked
    CHECK (status <> 'revoked'
           OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL
               AND length(btrim(revoke_reason)) > 0));

ALTER TABLE public.export_job DROP CONSTRAINT IF EXISTS ck_ej_source_type;
ALTER TABLE public.export_job ADD CONSTRAINT ck_ej_source_type
    CHECK (source_type IN ('evaluation_run','training_cohort'));
ALTER TABLE public.export_job DROP CONSTRAINT IF EXISTS ck_ej_status;
ALTER TABLE public.export_job ADD CONSTRAINT ck_ej_status
    CHECK (status IN ('running','done','failed'));

-- ============================================================
-- 七、索引（按实际查询路径；不做无谓索引）
-- ============================================================

-- 计划列表：企业 + 状态 + 时间倒序
CREATE INDEX IF NOT EXISTS idx_tp_company_status
    ON public.training_plan(company_id, status, created_at DESC);
-- 任务树：计划 + 版本 + 阶段 + 阶段内顺序（发布校验/预览/下发共用）
CREATE INDEX IF NOT EXISTS idx_tt_plan_version_phase
    ON public.training_task(plan_id, version, phase_no, order_no);
-- 批次列表：企业 + 状态；以及按计划找回批次
CREATE INDEX IF NOT EXISTS idx_tc_company_status
    ON public.training_cohort(company_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tc_plan
    ON public.training_cohort(plan_id, plan_version);
-- 我的培训（learner 维度）；批次进度（cohort 维度）
CREATE INDEX IF NOT EXISTS idx_ta_company_learner
    ON public.training_assignment(company_id, learner_id, status);
CREATE INDEX IF NOT EXISTS idx_ta_cohort_status
    ON public.training_assignment(cohort_id, status);
-- 我的任务：assignment 下按状态
CREATE INDEX IF NOT EXISTS idx_ttr_assignment_status
    ON public.training_task_record(assignment_id, status);
-- 评分领取：只索引未完成的两种态（submitted + grading），随历史尝试增长不膨胀。
-- 只索引 submitted 会让「置 grading 后进程崩溃」的行永久领不回来（评测侧同款坑，
-- 见 evaluation-schema.sql idx_run_item_claim）。
CREATE INDEX IF NOT EXISTS idx_tat_claim
    ON public.training_attempt(status, lease_expires_at)
    WHERE status IN ('submitted', 'grading');
-- 领取排序：可领取的行都有 submitted_at；独立索引避免每轮对全部积压行排序
CREATE INDEX IF NOT EXISTS idx_tat_claim_order
    ON public.training_attempt(submitted_at, id)
    WHERE status IN ('submitted', 'grading');
-- 截止扫描：只扫进行中的尝试
CREATE INDEX IF NOT EXISTS idx_tat_deadline
    ON public.training_attempt(deadline)
    WHERE status = 'in_progress';
-- 陪练逾期：按分配截止扫描未结束的分配（避免每轮全表扫 assignment）
CREATE INDEX IF NOT EXISTS idx_ta_deadline_open
    ON public.training_assignment(deadline)
    WHERE status IN ('not_started', 'in_progress', 'pending_certification');
-- 按分配取尝试 / 陪练逾期 join 分配表
CREATE INDEX IF NOT EXISTS idx_tat_assignment
    ON public.training_attempt(assignment_id, attempt_no DESC);
-- 尝试列表 / 续接进行中
CREATE INDEX IF NOT EXISTS idx_tat_record
    ON public.training_attempt(task_record_id, attempt_no DESC);
-- 复核追溯
CREATE INDEX IF NOT EXISTS idx_trv_attempt
    ON public.training_review(attempt_id, created_at);
-- 认证看板
CREATE INDEX IF NOT EXISTS idx_tcf_company_status
    ON public.training_certification(company_id, status);
-- 导出列表
CREATE INDEX IF NOT EXISTS idx_ej_company_source
    ON public.export_job(company_id, source_type, created_at DESC);

-- ============================================================
-- 八、P3 索引
-- ============================================================

-- 有效轮次：按分配+任务取最新轮
CREATE INDEX IF NOT EXISTS idx_ttr_task_round
    ON public.training_task_record(assignment_id, task_id, round_no DESC);
-- 补训溯源
CREATE INDEX IF NOT EXISTS idx_ttr_remediation_of
    ON public.training_task_record(remediation_of_record_id)
    WHERE remediation_of_record_id IS NOT NULL;
-- 认证历史（追加式）
CREATE INDEX IF NOT EXISTS idx_tcf_assignment_history
    ON public.training_certification(assignment_id, created_at DESC);
-- 批次成员稳定分页
CREATE INDEX IF NOT EXISTS idx_ta_cohort_created
    ON public.training_assignment(cohort_id, created_at, id);
-- 站内信：未读列表与去重
CREATE UNIQUE INDEX IF NOT EXISTS uq_un_dedupe
    ON public.user_notification(dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_un_user_unread
    ON public.user_notification(user_id, read_at, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_un_company_created
    ON public.user_notification(company_id, created_at DESC);
-- 导出领取
CREATE INDEX IF NOT EXISTS idx_ej_claim
    ON public.export_job(status, lease_expires_at) WHERE status = 'running';
-- pending_review 滞留扫描
CREATE INDEX IF NOT EXISTS idx_tat_pending_review_age
    ON public.training_attempt(submitted_at) WHERE status = 'pending_review';
-- 评分失败滞留扫描（error 行没有 submitted_at，用表达式索引匹配查询谓词）
CREATE INDEX IF NOT EXISTS idx_tat_error_age
    ON public.training_attempt(COALESCE(finished_at, created_at)) WHERE status = 'error';
-- 看板「待处理时长」统一按 COALESCE(submitted,finished,created) 过滤，表达式索引避免全表扫描
CREATE INDEX IF NOT EXISTS idx_tat_pending_or_error_age
    ON public.training_attempt((COALESCE(submitted_at, finished_at, created_at)))
    WHERE status IN ('pending_review', 'error');
