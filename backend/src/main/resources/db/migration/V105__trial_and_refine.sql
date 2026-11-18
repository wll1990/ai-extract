-- 试问台与对话式纠偏（docs/76）。
--
-- 三张表全部是「离线演练」用途：
--   skill_trial_session / skill_trial_message —— 试问会话，与真实员工对话零交叉；
--   grain_refine_draft —— 对话式纠偏草稿，base_* 保留改前快照用于 diff 与并发检测。
--
-- 注意：试问消息不写 skill_conversation / skill_message，因此这里的 session_id
-- 只指向本表，不做外键到业务会话。

CREATE TABLE IF NOT EXISTS skill_trial_session (
    id              uuid PRIMARY KEY,
    skill_id        uuid NOT NULL,
    user_id         uuid NOT NULL,
    first_question  varchar(200),
    message_count   integer NOT NULL DEFAULT 0,
    created_at      timestamp NOT NULL DEFAULT now(),
    updated_at      timestamp NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trial_session_owner
    ON skill_trial_session (skill_id, user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_trial_session_skill
    ON skill_trial_session (skill_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS skill_trial_message (
    id                uuid PRIMARY KEY,
    session_id        uuid NOT NULL REFERENCES skill_trial_session (id) ON DELETE CASCADE,
    skill_id          uuid NOT NULL,
    role              varchar(16) NOT NULL,
    content           text NOT NULL,
    meta              jsonb,
    client_message_id varchar(64),
    created_at        timestamp NOT NULL DEFAULT now(),
    CONSTRAINT chk_trial_msg_role CHECK (role IN ('user', 'assistant'))
);

-- 幂等键只在用户消息上有意义；partial index 允许助手消息 client_message_id 为空
CREATE UNIQUE INDEX IF NOT EXISTS uk_trial_msg_client
    ON skill_trial_message (session_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_trial_msg
    ON skill_trial_message (session_id, created_at);

CREATE TABLE IF NOT EXISTS grain_refine_draft (
    id               uuid PRIMARY KEY,
    skill_id         uuid NOT NULL,
    grain_id         uuid NOT NULL,
    user_id          uuid NOT NULL,
    status           varchar(16) NOT NULL DEFAULT 'draft',
    base_scene       text,
    base_thought     text,
    base_script      text,
    base_mistake     text,
    base_condition   text,
    draft_scene      text,
    draft_thought    text,
    draft_script     text,
    draft_mistake    text,
    draft_condition  text,
    changed_fields   jsonb NOT NULL DEFAULT '[]',
    transcript       jsonb,
    embedding_status varchar(16) NOT NULL DEFAULT 'pending',
    created_at       timestamp NOT NULL DEFAULT now(),
    applied_at       timestamp,
    CONSTRAINT chk_refine_status CHECK (status IN ('draft', 'applied', 'discarded', 'reverted')),
    CONSTRAINT chk_refine_embedding CHECK (embedding_status IN ('pending', 'ready', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_refine_grain
    ON grain_refine_draft (grain_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_refine_skill_status
    ON grain_refine_draft (skill_id, status);
