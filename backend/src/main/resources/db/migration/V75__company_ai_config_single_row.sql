-- 一个企业只保留一份对话模型配置：厂商由 provider 列承载，不再用多行区分。
-- 先清理历史重复行（保留 updated_at 最新；时间相同按 id 兜底），再收敛唯一约束。

DELETE FROM company_ai_config a
USING company_ai_config b
WHERE a.company_id = b.company_id
  AND a.id <> b.id
  AND (a.updated_at < b.updated_at
       OR (a.updated_at = b.updated_at AND a.id < b.id));

ALTER TABLE company_ai_config DROP CONSTRAINT IF EXISTS uq_cac_company_provider;
ALTER TABLE company_ai_config ADD CONSTRAINT uq_cac_company UNIQUE (company_id);
