CREATE TABLE workflow_definition (
    id           UUID PRIMARY KEY,
    workflow_key VARCHAR(64)  NOT NULL,
    version      INTEGER      NOT NULL,
    name         VARCHAR(128) NOT NULL,
    graph        JSONB        NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (workflow_key, version)
);

CREATE TABLE workflow_run (
    id               UUID PRIMARY KEY,
    definition_id    UUID        NOT NULL REFERENCES workflow_definition(id),
    user_id          UUID        NOT NULL,
    company_id       UUID,
    status           VARCHAR(16) NOT NULL,
    input            JSONB,
    output           JSONB,
    error            TEXT,
    current_node     VARCHAR(64),
    lease_owner      VARCHAR(64),
    lease_until      TIMESTAMP WITH TIME ZONE,
    attempts         INTEGER     NOT NULL DEFAULT 0,
    available_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    idempotency_key  VARCHAR(128),
    resume_token     VARCHAR(64),
    waiting_until    TIMESTAMP WITH TIME ZONE,
    event_seq        INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE,
    finished_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workflow_run_claim
    ON workflow_run (status, available_at, lease_until);
CREATE UNIQUE INDEX uk_workflow_run_idem
    ON workflow_run (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE workflow_node_run (
    id          UUID PRIMARY KEY,
    run_id      UUID        NOT NULL REFERENCES workflow_run(id),
    node_key    VARCHAR(64) NOT NULL,
    node_type   VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    input       JSONB,
    output      JSONB,
    error       TEXT,
    attempt     INTEGER     NOT NULL DEFAULT 1,
    started_at  TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (run_id, node_key, attempt)
);

CREATE TABLE workflow_event (
    id         BIGSERIAL PRIMARY KEY,
    run_id     UUID        NOT NULL REFERENCES workflow_run(id),
    seq        BIGINT      NOT NULL,
    type       VARCHAR(32) NOT NULL,
    payload    JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (run_id, seq)
);
