-- AI 考题草稿态：V53 的检查约束只允许 active/deprecated，需加入 draft。
ALTER TABLE public.evaluation_item DROP CONSTRAINT IF EXISTS chk_item_status;
ALTER TABLE public.evaluation_item
    ADD CONSTRAINT chk_item_status CHECK (status IN ('draft', 'active', 'deprecated'));
