-- ============================================================
-- V24：user.role CHECK 加上 company_admin。
-- V1 里内联定义未显式命名，用 DO 块动态查找并重建。
-- ============================================================

DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT con.conname INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'user'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%role%';

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE "user" DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE "user" ADD CONSTRAINT chk_user_role
    CHECK (role IN ('super_admin', 'company_admin', 'employee'));
