-- 模型技能（Agent Skills）：平台级 / 企业级技能定义与参考资料。
-- 详细设计见 docs/77-模型技能Skills-详细设计.md
CREATE TABLE public.agent_skill (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_key       VARCHAR(64)   NOT NULL,
    name            VARCHAR(128)  NOT NULL,
    description     VARCHAR(1024) NOT NULL,
    body_md         TEXT          NOT NULL,
    source          VARCHAR(16)   NOT NULL,
    company_id      UUID REFERENCES public.company(id) ON DELETE CASCADE,
    version         INTEGER       NOT NULL DEFAULT 1,
    status          VARCHAR(16)   NOT NULL DEFAULT 'draft',
    token_estimate  INTEGER       NOT NULL DEFAULT 0,
    lock_version    INTEGER       NOT NULL DEFAULT 0,
    created_by      UUID,
    updated_by      UUID,
    published_by    UUID,
    archived_by     UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE,
    archived_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_agent_skill_source CHECK (source IN ('builtin','platform','tenant','mcp')),
    CONSTRAINT ck_agent_skill_status CHECK (status IN ('draft','published','archived')),
    CONSTRAINT ck_agent_skill_scope CHECK (
        (source = 'tenant' AND company_id IS NOT NULL) OR
        (source IN ('builtin','platform','mcp') AND company_id IS NULL)
    )
);

CREATE UNIQUE INDEX uk_agent_skill_platform_version
    ON public.agent_skill (skill_key, version) WHERE company_id IS NULL;
CREATE UNIQUE INDEX uk_agent_skill_tenant_version
    ON public.agent_skill (company_id, skill_key, version) WHERE company_id IS NOT NULL;

CREATE UNIQUE INDEX uk_agent_skill_platform_published
    ON public.agent_skill (skill_key) WHERE company_id IS NULL AND status = 'published';
CREATE UNIQUE INDEX uk_agent_skill_tenant_published
    ON public.agent_skill (company_id, skill_key) WHERE company_id IS NOT NULL AND status = 'published';
CREATE UNIQUE INDEX uk_agent_skill_platform_draft
    ON public.agent_skill (skill_key) WHERE company_id IS NULL AND status = 'draft';
CREATE UNIQUE INDEX uk_agent_skill_tenant_draft
    ON public.agent_skill (company_id, skill_key) WHERE company_id IS NOT NULL AND status = 'draft';

CREATE INDEX idx_agent_skill_list
    ON public.agent_skill (company_id, status, updated_at DESC);

CREATE TABLE public.agent_skill_resource (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id   UUID NOT NULL REFERENCES public.agent_skill(id) ON DELETE CASCADE,
    path       VARCHAR(255) NOT NULL,
    content    TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (skill_id, path)
);

CREATE INDEX idx_agent_skill_resource_skill ON public.agent_skill_resource (skill_id);
