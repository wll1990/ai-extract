-- 对话历史溯源：一条回答可能引用多条经验颗粒，原表只存了第一条。
-- 历史消息重新打开时，前端需要完整颗粒列表与平均相似度才能正确展示「精准/关联/综合画像」。
ALTER TABLE skill_message ADD COLUMN IF NOT EXISTS grain_ids TEXT;
ALTER TABLE skill_message ADD COLUMN IF NOT EXISTS avg_similarity DOUBLE PRECISION;

COMMENT ON COLUMN skill_message.grain_ids IS '本次回答引用的颗粒 ID，逗号分隔（最多 3 条）';
COMMENT ON COLUMN skill_message.avg_similarity IS '本次回答的平均相似度（0-100），用于展示匹配等级';
