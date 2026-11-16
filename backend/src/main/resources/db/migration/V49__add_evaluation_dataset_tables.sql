-- ============================================================
-- V49 评测中心 P0：题集 + 版本 + 题目 + AI 生成候选
-- 用途：研发回归(regression) / 分身验收(acceptance) / 培训考试(training)
-- ============================================================

CREATE TABLE IF NOT EXISTS evaluation_dataset (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id uuid,
    name varchar(255) NOT NULL,
    description text,
    purpose varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'draft',
    current_version integer NOT NULL DEFAULT 1,
    item_count integer NOT NULL DEFAULT 0,
    created_by uuid,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT evaluation_dataset_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ds_company_status ON evaluation_dataset(company_id, status);
CREATE INDEX IF NOT EXISTS idx_ds_purpose ON evaluation_dataset(purpose);

CREATE TABLE IF NOT EXISTS evaluation_dataset_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    dataset_id uuid NOT NULL,
    version integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'draft',
    item_count integer NOT NULL DEFAULT 0,
    frozen_at timestamp without time zone,
    created_by uuid,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT evaluation_dataset_version_pkey PRIMARY KEY (id),
    CONSTRAINT uq_ds_version UNIQUE (dataset_id, version)
);
CREATE INDEX IF NOT EXISTS idx_ds_version_ds ON evaluation_dataset_version(dataset_id);

CREATE TABLE IF NOT EXISTS evaluation_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id uuid,
    dataset_id uuid NOT NULL,
    version integer NOT NULL,
    question text NOT NULL,
    context text,
    expected_points jsonb,
    forbidden_points jsonb,
    citation_requirement jsonb,
    scene_tag varchar(50),
    gold_basis text,
    scoring_rule jsonb,
    retrieval_expected jsonb,
    order_no integer NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'active',
    created_by uuid,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT evaluation_item_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_item_ds_ver ON evaluation_item(dataset_id, version);

CREATE TABLE IF NOT EXISTS evaluation_generation_job (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    dataset_id uuid NOT NULL,
    source_type varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'running',
    produced_count integer NOT NULL DEFAULT 0,
    error_message varchar(1000),
    created_by uuid,
    created_at timestamp without time zone NOT NULL DEFAULT now(),
    updated_at timestamp without time zone NOT NULL DEFAULT now(),
    CONSTRAINT evaluation_generation_job_pkey PRIMARY KEY (id)
);
-- 同一题集同一时间只允许一个 running 任务（防连续双击重复出题）
CREATE UNIQUE INDEX IF NOT EXISTS uq_gen_job_running ON evaluation_generation_job(dataset_id) WHERE status = 'running';
