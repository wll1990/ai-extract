-- 组织分身创建时使用 draft 状态（前端也按「草稿」展示），
-- 但历史 skill_status_check 只允许 generating/reviewing/published/discarded，
-- 导致 POST /admin/organization-skills 直接违反约束（500）。
-- 这里把 draft 纳入合法状态；个体分身仍只使用 generating/reviewing/published/discarded。

ALTER TABLE public.skill DROP CONSTRAINT IF EXISTS skill_status_check;
ALTER TABLE public.skill ADD CONSTRAINT skill_status_check
    CHECK (status IN ('generating', 'reviewing', 'published', 'discarded', 'draft'));
