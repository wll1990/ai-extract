-- 分身废弃时间：用于 30 天恢复窗口判定（updatedAt 会被其他更新覆盖）
ALTER TABLE public.skill
    ADD COLUMN IF NOT EXISTS discarded_at timestamp;

COMMENT ON COLUMN public.skill.discarded_at IS '废弃时间；恢复窗口 30 天，恢复后清空';
