-- V18: Skill 开场白字段
-- System B 企业端聊天页入场态需要展示分身的开场白消息

ALTER TABLE skill ADD COLUMN IF NOT EXISTS opening_message TEXT;

COMMENT ON COLUMN skill.opening_message IS '分身开场白 — 聊天页入场态展示，一般为专家自我介绍或欢迎语';
