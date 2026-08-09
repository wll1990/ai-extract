-- ============================================================
-- V4__merge_user_tables.sql — 合并 app_user 到 "user" 表
--
-- 策略：只增列 + 放宽约束 + 迁数据 + 删旧表，一次完成。
-- role CHECK 从 3 个 B 端角色扩展到 6 个角色（含 c_guest/c_user/c_partner）。
-- account 全局唯一约束从表级 UNIQUE 改为 C 端部分唯一索引。
-- ============================================================

-- 1. 给 "user" 表加 C 端列（全部可空，已有 B 端行不受影响）
--    name 字段统一承载 B 端真实姓名 + C 端昵称，不另加 nickname 列
ALTER TABLE public."user"
    ADD COLUMN IF NOT EXISTS status          character varying(20),
    ADD COLUMN IF NOT EXISTS source          character varying(10),
    ADD COLUMN IF NOT EXISTS source_share_id  uuid,
    ADD COLUMN IF NOT EXISTS last_active_at   timestamp without time zone;

-- 2. 放宽 B 端 NOT NULL 约束 → 允许写入 C 端行
ALTER TABLE public."user" ALTER COLUMN company_id DROP NOT NULL;
ALTER TABLE public."user" ALTER COLUMN name DROP NOT NULL;
ALTER TABLE public."user" ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE public."user" ALTER COLUMN account DROP NOT NULL;

-- 3. 约束调整
--    "user" 表现在有两个 UNIQUE：
--      uq_user_company_account(company_id, account) — 保留，B 端企业内唯一
--      user_account_key(account)                     — 删除，替换为 C 端部分唯一索引
ALTER TABLE public."user" DROP CONSTRAINT IF EXISTS user_account_key;

-- C 端非空 account 全局唯一（部分索引：只约束 source IS NOT NULL 的行）
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_account_cend
    ON public."user"(account) WHERE account IS NOT NULL AND source IS NOT NULL;

-- 4. 扩展 role CHECK 约束以容纳全部 6 种角色
ALTER TABLE public."user" DROP CONSTRAINT IF EXISTS chk_user_role;
ALTER TABLE public."user" ADD CONSTRAINT chk_user_role CHECK (
    role IN ('super_admin', 'company_admin', 'employee', 'c_guest', 'c_user', 'c_partner')
);

-- 5. 迁数据：app_user → "user"（仅当 app_user 表还存在时执行）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'app_user') THEN
        INSERT INTO public."user" (
            id, company_id, name, role, avatar_url, account,
            password_hash, is_active, status, source, source_share_id,
            last_active_at, created_at, updated_at
        )
        SELECT
            au.id,
            au.company_id,
            au.nickname,
            CASE
                WHEN au.source = 'partner'  THEN 'c_partner'
                WHEN au.status = 'guest'    THEN 'c_guest'
                WHEN au.status = 'registered' THEN 'c_user'
                ELSE 'c_guest'
            END,
            au.avatar_url,
            au.account,
            au.password_hash,
            true,
            au.status,
            COALESCE(au.source, 'share'),
            au.source_share_id,
            au.last_active_at,
            au.created_at,
            au.updated_at
        FROM public.app_user au
        WHERE NOT EXISTS (SELECT 1 FROM public."user" u WHERE u.id = au.id);
    END IF;
END $$;

-- 6. 给已有 B 端用户设 source = 'enterprise'（不再用 NULL 区分 B/C 端）
UPDATE public."user" SET source = 'enterprise' WHERE source IS NULL;

-- 7. 新索引（C 端常用查询路径）
CREATE INDEX IF NOT EXISTS idx_user_source
    ON public."user"(source) WHERE source IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_source_share
    ON public."user"(source_share_id) WHERE source_share_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_last_active
    ON public."user"(last_active_at) WHERE last_active_at IS NOT NULL;

-- 8. 删旧表（数据已在步骤 5 迁完，无 FK 依赖）
DROP TABLE IF EXISTS public.app_user;
