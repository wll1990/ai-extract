-- 专家经验（萃取师）接入多租户隔离
-- 背景：expert_skill 原先没有企业字段，列表/详情/修改/删除全库可达，
--      任何拥有 EXPERT_MANAGE 的企业管理员都能读写其他企业的萃取师。
-- 规则：company_id 非空 = 企业私有；company_id 为空 = 平台级，仅超管可见可写。

ALTER TABLE public.expert_skill
    ADD COLUMN IF NOT EXISTS company_id uuid;

ALTER TABLE public.expert_skill
    ADD COLUMN IF NOT EXISTS created_by uuid;

COMMENT ON COLUMN public.expert_skill.company_id IS '所属企业；为空表示平台级（仅超管可见）';
COMMENT ON COLUMN public.expert_skill.created_by IS '创建人 user_id';

-- 存量回填：元访谈创建的萃取师可沿 source_session_id → interview_session.space_id → space.user_id → user.company_id 找回企业
UPDATE public.expert_skill e
SET company_id = u.company_id
FROM public.interview_session i
         JOIN public.space s ON s.id = i.space_id
         JOIN public."user" u ON u.id = s.user_id
WHERE e.source_session_id = i.id
  AND e.company_id IS NULL
  AND u.company_id IS NOT NULL;

-- 文件上传创建的存量萃取师无法回溯归属，保持 company_id 为空（平台级，仅超管可维护）

CREATE INDEX IF NOT EXISTS idx_expert_skill_company_status
    ON public.expert_skill (company_id, status);
