-- ============================================================
-- qa-seed.sql — 线上 QA 测试租户（幂等）
--
-- 用法（生产库只监听 127.0.0.1，需在服务器上执行）：
--   ssh mindforge 'set -a; . /opt/mindforge/config/backend.env; set +a; \
--     PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -U "$PG_USER" -d "$PG_DATABASE" \
--     -v ON_ERROR_STOP=1 -v qa_password_hash="<bcrypt>" -f -' < qa-seed.sql
--
-- 说明：
--   * 固定 UUID，可重复执行；不会碰真实企业和演示企业。
--   * 账号仅用于 QA，密码 hash 由 runner 生成，不写进仓库。
--   * 种子对象名称统一带 "QA-" 前缀，便于识别和清理。
-- ============================================================

BEGIN;

\set ON_ERROR_STOP on

-- 企业
INSERT INTO public.company (id, name, industry, status, notes)
VALUES ('0a000000-0000-0000-0000-000000000001',
        'QA 测试企业（勿删）', '软件', 'active',
        '黑盒测试专用租户，由 scripts/qa 维护')
ON CONFLICT (id) DO UPDATE
  SET name = EXCLUDED.name,
      status = 'active',
      notes = EXCLUDED.notes;

-- 注册码
INSERT INTO public.company_register_code
  (id, company_id, code, enabled, max_uses, used_count, default_role)
VALUES ('0a000000-0000-0000-0000-000000000002',
        '0a000000-0000-0000-0000-000000000001',
        'QATEST01', true, 0, 0, 'employee')
ON CONFLICT (code) DO UPDATE
  SET company_id = EXCLUDED.company_id,
      enabled = true,
      default_role = 'employee';

-- 用户：管理员 / 员工 / 教练
INSERT INTO public."user"
  (id, company_id, name, account, password_hash, role, is_active, source, status, token_version)
VALUES
  ('0a000000-0000-0000-0000-000000000011', '0a000000-0000-0000-0000-000000000001',
   'QA 管理员', 'qa_admin', :'qa_password_hash', 'company_admin', true, 'qa', 'active', 0),
  ('0a000000-0000-0000-0000-000000000012', '0a000000-0000-0000-0000-000000000001',
   'QA 员工', 'qa_emp', :'qa_password_hash', 'employee', true, 'qa', 'active', 0),
  ('0a000000-0000-0000-0000-000000000013', '0a000000-0000-0000-0000-000000000001',
   'QA 教练', 'qa_coach', :'qa_password_hash', 'employee', true, 'qa', 'active', 0),
  ('0a000000-0000-0000-0000-000000000014', '0a000000-0000-0000-0000-000000000001',
   'QA 超管', 'qa_super', :'qa_password_hash', 'super_admin', true, 'qa', 'active', 0)
ON CONFLICT (id) DO UPDATE
  SET company_id = EXCLUDED.company_id,
      name = EXCLUDED.name,
      password_hash = EXCLUDED.password_hash,
      role = EXCLUDED.role,
      is_active = true,
      status = 'active',
      token_version = 0;

-- C 端账号：无企业上下文，走 /c/auth/login（仅用于权限矩阵）
INSERT INTO public."user"
  (id, company_id, name, account, password_hash, role, is_active, source, status, token_version)
VALUES
  ('0a000000-0000-0000-0000-000000000015', NULL, 'QA C 用户', 'qa_c_user',
   :'qa_password_hash', 'c_user', true, 'qa', 'active', 0),
  ('0a000000-0000-0000-0000-000000000016', NULL, 'QA C 游客', 'qa_c_guest',
   :'qa_password_hash', 'c_guest', true, 'qa', 'active', 0),
  ('0a000000-0000-0000-0000-000000000017', NULL, 'QA C 合作方', 'qa_c_partner',
   :'qa_password_hash', 'c_partner', true, 'qa', 'active', 0)
ON CONFLICT (id) DO UPDATE
  SET name = EXCLUDED.name,
      password_hash = EXCLUDED.password_hash,
      role = EXCLUDED.role,
      is_active = true,
      status = 'active',
      token_version = 0;

-- 空间：管理员一个、教练一个（skill.space_id 唯一）
INSERT INTO public.space (id, user_id, title, description, status)
VALUES
  ('0a000000-0000-0000-0000-000000000021', '0a000000-0000-0000-0000-000000000011',
   'QA 管理员空间', 'QA 测试专用空间', 'active'),
  ('0a000000-0000-0000-0000-000000000022', '0a000000-0000-0000-0000-000000000013',
   'QA 教练空间', 'QA 教练分身空间', 'active'),
  ('0a000000-0000-0000-0000-000000000023', '0a000000-0000-0000-0000-000000000012',
   'QA 员工空间', 'QA 员工素材空间', 'active')
ON CONFLICT (id) DO UPDATE
  SET user_id = EXCLUDED.user_id,
      title = EXCLUDED.title,
      status = 'active';

-- 分身：一个草稿（素材链路用）、一个已发布（问答/复盘用）
INSERT INTO public.skill
  (id, space_id, company_id, status, display_name, owner_name, owner_title, department,
   domain, type, description, created_by)
VALUES
  ('0a000000-0000-0000-0000-000000000031', '0a000000-0000-0000-0000-000000000021',
   '0a000000-0000-0000-0000-000000000001', 'generating', 'QA 基础分身', 'QA 管理员', '测试',
   'QA', 'sales.b2b_enterprise', 'individual', 'QA 素材与发布链路使用', '0a000000-0000-0000-0000-000000000011'),
  ('0a000000-0000-0000-0000-000000000032', '0a000000-0000-0000-0000-000000000022',
   '0a000000-0000-0000-0000-000000000001', 'published', 'QA 教练分身', 'QA 教练', '销售教练',
   'QA', 'sales.b2b_enterprise', 'individual', 'QA 问答与复盘链路使用', '0a000000-0000-0000-0000-000000000013'),
  ('0a000000-0000-0000-0000-000000000033', '0a000000-0000-0000-0000-000000000023',
   '0a000000-0000-0000-0000-000000000001', 'generating', 'QA 员工分身', 'QA 员工', '测试',
   'QA', 'sales.b2b_enterprise', 'individual', 'QA 员工素材链路使用', '0a000000-0000-0000-0000-000000000012')
ON CONFLICT (id) DO UPDATE
  SET company_id = EXCLUDED.company_id,
      status = EXCLUDED.status,
      display_name = EXCLUDED.display_name,
      owner_name = EXCLUDED.owner_name,
      published_at = CASE WHEN EXCLUDED.status = 'published' THEN now() ELSE NULL END;

-- 知识：只读列表/检索用（pending，不参与检索）
INSERT INTO public.knowledge_entry
  (id, company_id, source_type, category, title, content, status, source, created_by, version)
VALUES
  ('0a000000-0000-0000-0000-000000000041', '0a000000-0000-0000-0000-000000000001',
   'tenant', 'methodology', 'QA 基础知识', 'QA 测试知识内容，用于知识库列表与审核链路验证。',
   'pending', 'qa', '0a000000-0000-0000-0000-000000000011', 1),
  -- 已生效知识：培训阅读任务开始前会校验资源状态，pending 会被 409 拒绝
  ('0a000000-0000-0000-0000-000000000042', '0a000000-0000-0000-0000-000000000001',
   'tenant', 'methodology', 'QA 培训知识（已生效）', 'QA 培训链路专用已生效知识内容。',
   'active', 'qa', '0a000000-0000-0000-0000-000000000011', 1),
  -- 平台公共知识：验证「公共库可见 → 复制到企业」链路（company_id 为空即公共）
  ('0a000000-0000-0000-0000-000000000043', NULL,
   'public', 'price', 'QA 公共知识（可复制）', '公共知识复制链路验证：报价先锚定年度收益，再倒推价格区间。',
   'active', 'qa', '0a000000-0000-0000-0000-000000000011', 1)
ON CONFLICT (id) DO UPDATE
  SET title = EXCLUDED.title,
      content = EXCLUDED.content,
      status = EXCLUDED.status;

-- 公共案例：验证「公共案例可见 → 复制到企业」链路
INSERT INTO public.industry_case
  (id, company_id, title, customer_profile, funnel_stage, scene_tag, objection,
   key_actions, scripts, result, reusable_points, failure_points, source, status, version)
VALUES
  ('0a000000-0000-0000-0000-00000000c1', NULL,
   'QA 公共案例（可复制）', '制造业客户，预算审批链为采购+财务。', 'proposal', '价格异议',
   '客户认为报价高于竞品。', '拆两期交付，第一期收 40%。', '用年度收益锚定价格。',
   'win', '分期交付化解预算压力。', '未提前确认回款周期。', 'qa', 'active', 1)
ON CONFLICT (id) DO UPDATE
  SET title = EXCLUDED.title,
      status = EXCLUDED.status;

-- 评测题集：只读列表/状态机用
INSERT INTO public.evaluation_dataset
  (id, company_id, name, description, purpose, status, current_version, item_count, created_by)
VALUES
  ('0a000000-0000-0000-0000-000000000051', '0a000000-0000-0000-0000-000000000001',
   'QA 测试题集', 'QA 评测链路使用，初始为 draft', 'acceptance', 'draft', 1, 0,
   '0a000000-0000-0000-0000-000000000011')
ON CONFLICT (id) DO UPDATE
  SET name = EXCLUDED.name,
      description = EXCLUDED.description,
      status = EXCLUDED.status;

-- 培训计划：只读列表/状态机用（该表无主键，用 UPDATE + INSERT WHERE NOT EXISTS）
UPDATE public.training_plan
   SET name = 'QA 测试培训计划',
       description = 'QA 培训链路使用，初始为 draft',
       status = 'draft'
 WHERE id = '0a000000-0000-0000-0000-000000000061';

INSERT INTO public.training_plan
  (id, company_id, name, description, status, current_version, created_by)
SELECT '0a000000-0000-0000-0000-000000000061',
       '0a000000-0000-0000-0000-000000000001',
       'QA 测试培训计划', 'QA 培训链路使用，初始为 draft', 'draft', 1,
       '0a000000-0000-0000-0000-000000000011'
WHERE NOT EXISTS (
  SELECT 1 FROM public.training_plan WHERE id = '0a000000-0000-0000-0000-000000000061'
);

-- 销售复盘：只读列表/转线索前置用
INSERT INTO public.review_session
  (id, company_id, reviewer_id, coach_skill_id, coach_owner_id, coach_name, coach_title,
   customer_name, topic, transcript, transcript_chars, source, status)
VALUES
  ('0a000000-0000-0000-0000-000000000071', '0a000000-0000-0000-0000-000000000001',
   '0a000000-0000-0000-0000-000000000012', '0a000000-0000-0000-0000-000000000032',
   '0a000000-0000-0000-0000-000000000013', 'QA 教练', '销售教练',
   'QA-种子客户', 'QA 种子复盘', '客户说价格太高，我们还没有拿到预算。', 22, 'text', 'active')
ON CONFLICT (id) DO UPDATE
  SET coach_skill_id = EXCLUDED.coach_skill_id,
      coach_owner_id = EXCLUDED.coach_owner_id,
      customer_name = EXCLUDED.customer_name,
      topic = EXCLUDED.topic,
      status = 'active';

-- B 企业：跨租户隔离用（固定种子，不参与清理）
INSERT INTO public.company (id, name, industry, status, notes)
VALUES ('0b000000-0000-0000-0000-000000000001', 'QA 隔离企业 B（勿删）', '软件', 'active',
        '跨租户测试专用，由 scripts/qa 维护')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'active';

INSERT INTO public."user"
  (id, company_id, name, account, password_hash, role, is_active, source, status, token_version)
VALUES
  ('0b000000-0000-0000-0000-000000000011', '0b000000-0000-0000-0000-000000000001',
   'QA B 管理员', 'qa_b_admin', :'qa_password_hash', 'company_admin', true, 'qa', 'active', 0)
ON CONFLICT (id) DO UPDATE
  SET password_hash = EXCLUDED.password_hash, company_id = EXCLUDED.company_id,
      role = EXCLUDED.role, is_active = true, status = 'active', token_version = 0;

INSERT INTO public.space (id, user_id, title, description, status)
VALUES
  ('0b000000-0000-0000-0000-000000000021', '0b000000-0000-0000-0000-000000000011',
   'QA B 空间', '跨租户测试空间', 'active'),
  -- 一个空间最多一个分身（idx_skill_space_id_unique），草稿单独放一个空间
  ('0b000000-0000-0000-0000-000000000022', '0b000000-0000-0000-0000-000000000011',
   'QA B 草稿空间', '跨租户草稿隔离测试', 'active')
ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, title = EXCLUDED.title, status = 'active';

INSERT INTO public.skill
  (id, space_id, company_id, status, display_name, owner_name, owner_title, department,
   domain, type, description, created_by, published_at)
VALUES
  -- 已发布：跨租户材料/会话等写接口仍应 403，但详情按公开目录规则可读
  ('0b000000-0000-0000-0000-000000000031', '0b000000-0000-0000-0000-000000000021',
   '0b000000-0000-0000-0000-000000000001', 'published', 'QA B 分身', 'QA B 管理员', '测试',
   'QA', 'sales.b2b_enterprise', 'individual', '跨租户测试使用', '0b000000-0000-0000-0000-000000000011', now()),
  -- 未发布：任何跨租户读取都必须 403/404（GOLD-TENANT-02）
  ('0b000000-0000-0000-0000-000000000034', '0b000000-0000-0000-0000-000000000022',
   '0b000000-0000-0000-0000-000000000001', 'generating', 'QA B 草稿分身', 'QA B 管理员', '测试',
   'QA', 'sales.b2b_enterprise', 'individual', '跨租户草稿隔离测试', '0b000000-0000-0000-0000-000000000011', null)
ON CONFLICT (id) DO UPDATE
  SET company_id = EXCLUDED.company_id, status = EXCLUDED.status,
      display_name = EXCLUDED.display_name,
      published_at = CASE WHEN EXCLUDED.status = 'published' THEN now() ELSE NULL END;

COMMIT;
