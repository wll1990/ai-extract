-- 快照补企业归属：学员读取快照时同样要执行企业隔离，不能因为内容已冻结就绕过租户边界。
UPDATE public.training_task t
   SET resource_snapshot = t.resource_snapshot || jsonb_build_object('companyId', k.company_id::text)
  FROM public.knowledge_entry k
 WHERE t.resource_type = 'knowledge'
   AND t.resource_id = k.id
   AND t.resource_snapshot IS NOT NULL
   AND NOT (t.resource_snapshot ? 'companyId');

UPDATE public.training_task t
   SET resource_snapshot = t.resource_snapshot || jsonb_build_object('companyId', c.company_id::text)
  FROM public.industry_case c
 WHERE t.resource_type = 'case'
   AND t.resource_id = c.id
   AND t.resource_snapshot IS NOT NULL
   AND NOT (t.resource_snapshot ? 'companyId');
