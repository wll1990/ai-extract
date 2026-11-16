-- 历史 token 流水补 company_id：企业维度统计此前只按 user_id 过滤，
-- 改为 company_id 后，未回填的历史行会导致企业报表少算。
UPDATE public.token_usage_log t
SET company_id = u.company_id
FROM public."user" u
WHERE t.user_id = u.id
  AND t.company_id IS NULL
  AND u.company_id IS NOT NULL;
