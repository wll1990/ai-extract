-- 产品导览演示数据（幂等）
-- 只补 demo-seed.sql 缺失、且导览章节会依赖的最小数据。
-- 企业：d0000000-0000-0000-0000-000000000001
-- 分身：d3000000-0000-0000-0000-000000000001
-- 空间：d2000000-0000-0000-0000-000000000001

-- 1) 一条开放知识缺口：让「知识怎么更新」章节能演示补颗粒并自动关闭
INSERT INTO knowledge_gap (
  id, skill_id, space_id, query, scene_tag,
  attempted_query_count, status, created_at, updated_at
) VALUES (
  'bbbbbbbb-0000-0000-0000-000000000001',
  'd3000000-0000-0000-0000-000000000001',
  'd2000000-0000-0000-0000-000000000001',
  '客户要求先看同行业案例，再决定是否进入试用',
  '案例证明', 3, 'open', now(), now()
) ON CONFLICT (id) DO NOTHING;

-- 2) 素材列表至少保留一条已完成记录，避免导览第 4 章没有锚点
--    demo-seed.sql 已提供「华星科技-三次拜访记录.txt」；这里只做存在性检查说明，
--    不重复插入，避免每次执行都产生重复素材。

-- 3) 知识库/案例库各两条，保证导览第 7 章与手册截图不是空页面
INSERT INTO knowledge_entry (
  id, company_id, source_type, category, title, content, status, created_at, updated_at
)
SELECT 'cccccccc-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
 'tenant', 'product', '旗舰版产品能力与边界',
 '旗舰版包含多空间、组织分身、培训认证与私有化部署选项。不包含定制模型训练；数据默认存储在国内区域。',
 'active', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM knowledge_entry WHERE id = 'cccccccc-0000-0000-0000-000000000001');

INSERT INTO knowledge_entry (
  id, company_id, source_type, category, title, content, status, created_at, updated_at
)
SELECT 'cccccccc-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000001',
 'tenant', 'price', '标准报价与折扣审批规则',
 '标准版按席位年付；超过 50 席可申请阶梯价。低于八折需要销售总监审批，低于七折需要总经理审批。',
 'active', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM knowledge_entry WHERE id = 'cccccccc-0000-0000-0000-000000000002');

INSERT INTO industry_case (
  id, company_id, title, scene_tag, result, key_actions, reusable_points, status, created_at, updated_at
)
SELECT 'dddddddd-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
 '制造业客户预算停滞，用停机损失推动审批', '预算停滞', 'win',
 '先找生产负责人量化停机损失，再把方案与损失放在一张表里对比，最后由生产负责人推动采购。',
 '预算停滞时不要继续催采购，先找到真正承担损失的业务负责人。',
 'active', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM industry_case WHERE id = 'dddddddd-0000-0000-0000-000000000001');

INSERT INTO industry_case (
  id, company_id, title, scene_tag, result, key_actions, reusable_points, status, created_at, updated_at
)
SELECT 'dddddddd-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000001',
 '客户拿竞品报价压价，用三年总成本换比价维度', '价格异议', 'win',
 '不解释单价，先问客户更在意一次性采购成本还是三年总拥有成本，再逐项对比维护与切换成本。',
 '价格异议的本质往往是比价维度单一，先把维度拉开再谈价格。',
 'active', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM industry_case WHERE id = 'dddddddd-0000-0000-0000-000000000002');
