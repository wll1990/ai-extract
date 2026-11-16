-- ============================================================
-- demo-seed.sql — 演示企业域种子数据
--
-- 用法：psql -d aiextract -f scripts/demo-seed.sql
-- 前置：建议先执行 demo-reset.sql（demo-up.sh 会自动做）
-- 幂等：所有插入使用固定 UUID + ON CONFLICT DO NOTHING
--
-- 账号：demo_admin / demo_coach / demo_learner，密码均为 123456
-- 企业：演示企业 · 星际软件
-- ============================================================

BEGIN;

-- ---------- 1. 企业 ----------
INSERT INTO public.company (id, name, brand_color)
VALUES ('d0000000-0000-0000-0000-000000000001', '演示企业 · 星际软件', '#6559C7')
ON CONFLICT (id) DO NOTHING;

-- 登录页只认企业注册码（不认 companyId），没有这条演示账号在界面上登不进去
INSERT INTO public.company_register_code
  (id, company_id, code, enabled, max_uses, used_count, default_role)
VALUES
  ('d2000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
   'DEMO2026', true, 0, 0, 'employee')
ON CONFLICT (code) DO NOTHING;

-- ---------- 2. 用户（密码 123456 的 bcrypt hash） ----------
INSERT INTO public."user" (id, company_id, name, role, account, password_hash, is_active, source)
VALUES
  ('d1000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
   '陈锋', 'company_admin', 'demo_admin',
   '$2y$10$zhZa1j8JIGH0uiVWVxwRXOf7DQoFZikfv4zZB25hPR0hk1aoxbrbi', true, 'enterprise'),
  ('d1000000-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000001',
   '顾研', 'employee', 'demo_coach',
   '$2y$10$zhZa1j8JIGH0uiVWVxwRXOf7DQoFZikfv4zZB25hPR0hk1aoxbrbi', true, 'enterprise'),
  ('d1000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000001',
   '林小满', 'employee', 'demo_learner',
   '$2y$10$zhZa1j8JIGH0uiVWVxwRXOf7DQoFZikfv4zZB25hPR0hk1aoxbrbi', true, 'enterprise')
ON CONFLICT (id) DO NOTHING;

-- ---------- 3. 空间 ----------
INSERT INTO public.space (id, user_id, title, description, tags, is_public, status)
VALUES (
  'd2000000-0000-0000-0000-000000000001',
  'd1000000-0000-0000-0000-000000000001',
  '陈锋的经验空间',
  '12 年 B2B 大客户销售 · 复杂决策链 · 大客户推进',
  $j$["B2B","大客户","决策链","异议处理"]$j$::jsonb,
  true, 'active'
)
ON CONFLICT (id) DO NOTHING;

-- ---------- 4. 分身 ----------
INSERT INTO public.skill (
  id, space_id, model_name, status, display_name, owner_name, owner_title, department, seniority,
  tags, target_scenarios, limitations, published_at, published_by, domain, opening_message,
  talk_config, practice_openings, conversation_count, user_count, satisfaction_rate, last_active_at,
  type, company_id, created_by, intro_profile, recommended_questions, description
) VALUES (
  'd3000000-0000-0000-0000-000000000001',
  'd2000000-0000-0000-0000-000000000001',
  'deepseek-chat', 'published', '陈锋 · 大客户推进', '陈锋', '销售总监', '大客户部', '12 年',
  $j$["B2B","大客户","决策链","切换风险"]$j$::jsonb,
  $j$["复杂决策链","采购停滞","切换风险","异议处理"]$j$::jsonb,
  '不替代报价、合同与法务判断；涉及具体折扣时只给判断框架，不给最终数字。',
  now() - interval '30 days', 'd1000000-0000-0000-0000-000000000001',
  'sales.b2b_enterprise',
  '我是陈锋的数字分身。说说你手上卡住的客户，我们先判断真正卡在哪一步。',
  $j${"mode":"quick","maxWords":220}$j$::jsonb,
  '你正在和客户方的采购负责人沟通系统替换。对方第一句话是：“你们比竞品贵 15%，凭什么？”',
  128, 46, 94, now() - interval '2 hours',
  'individual', NULL,
  'd1000000-0000-0000-0000-000000000001',
  $j${"headline":"把“客户没进展”拆成可验证的推进动作","strengths":["先判断顾虑再谈方案","把抽象担心追问到具体事故","用并行回退降低决策风险"],"boundaries":["不替代合同与法务判断","不承诺具体折扣","证据不足时明确说不知道"]}$j$::jsonb,
  $j$["客户说贵，但一直约不到决策人，怎么判断真假？","关键人支持我们，采购却拖着不启动，下一步找谁？","客户有过失败切换经历，怎么重建信任？"]$j$::jsonb,
  '面向大客户复杂决策链的经验分身：先判断，再给下一步。'
)
ON CONFLICT (id) DO NOTHING;

-- ---------- 5. 经验颗粒（12 条） ----------
INSERT INTO public.experience_grain (
  id, space_id, report_id, scene_tag, scene_description, expert_thought, standard_script,
  common_mistakes, applicable_condition, weight, quality_score, difficulty_level,
  status, source_type, source_interview_id
) VALUES
  ('d3100000-0000-0000-0000-000000000001','d2000000-0000-0000-0000-000000000001',NULL,
   '采购停滞','客户认可价值但采购迟迟不启动','先判断预算所有者是否完成内部优先级确认，而不是把问题归到采购部门',
   '我想确认一件事：如果今年不做，最让您担心的是什么？','默认采购在拖延','决策链长、采购流程规范的场景',1.4,4.7,'advanced','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000002','d2000000-0000-0000-0000-000000000001',NULL,
   '价格异议','客户拿竞品报价压价','先确认对方比的是单价还是三年总拥有成本',
   '您更在意一次性采购成本，还是三年总拥有成本？','直接解释价格构成','客户在多家比较中的场景',1.3,4.6,'intermediate','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000003','d2000000-0000-0000-0000-000000000001',NULL,
   '切换风险','客户有过失败切换经历','把担心追问到具体事故，再用并行与回退方案降低风险',
   '去年那次是哪一段出的问题？是数据迁移还是切换窗口？','只承诺“我们很稳”','客户有历史事故、决策保守的场景',1.5,4.9,'advanced','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000004','d2000000-0000-0000-0000-000000000001',NULL,
   '关键人','只有一个内部支持者','判断支持者是否愿意引荐业务负责人，而不是继续单线加深关系',
   '除了您，还有谁会为这件事的结果负责？','只吊在一个人身上','单点支持、决策链未打开的场景',1.4,4.5,'intermediate','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000005','d2000000-0000-0000-0000-000000000001',NULL,
   '破冰','客户已拒绝所有销售','研究对方公开表达里的重复词，找他真正怕的事',
   '我读了您在行业论坛的发言，关于稳定优先于先进的观点，我深以为然。','只研究职位不研究人','关键人有明确个人顾虑的场景',1.2,4.4,'advanced','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000006','d2000000-0000-0000-0000-000000000001',NULL,
   '方案汇报','客户听完方案没有反馈','沉默通常不是不认可，而是没听到与他考核相关的那一页',
   '如果只能保留一页给您的领导看，您会留哪一页？','把沉默当成同意','客户多层汇报的场景',1.1,4.3,'intermediate','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000007','d2000000-0000-0000-0000-000000000001',NULL,
   '决策人','始终约不到最终签字人','用“帮他向上汇报”作为理由，而不是“想认识领导”',
   '我整理了一页给管理层的对比，您看要不要我陪您一起过一遍？','直接要求见领导','中层愿意引荐但怕担责的场景',1.3,4.6,'advanced','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000008','d2000000-0000-0000-0000-000000000001',NULL,
   '竞品','客户说已经在和竞品谈','判断是流程性比较还是已经内定，看对方是否愿意给具体评估标准',
   '在回答价格之前，我想先问：您评估这两家时最看重哪两项？','听到竞品就降价','多家比较、尚未定标的场景',1.2,4.4,'intermediate','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000009','d2000000-0000-0000-0000-000000000001',NULL,
   '试用','客户愿意试用但不愿给数据','先用脱敏样例跑通一个真实场景，再谈数据授权',
   '我们先用一份脱敏数据跑您最关心的那个场景，两周后看结果，可以吗？','一上来就要全量数据','客户对数据安全敏感的场景',1.1,4.2,'intermediate','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000010','d2000000-0000-0000-0000-000000000001',NULL,
   '续约','老客户续约时压价','把续约谈判转成下一阶段价值回顾，而不是价格拉锯',
   '这一年里哪三个变化是您愿意写进汇报的？','只谈折扣','已合作一年以上、有可量化结果的场景',1.3,4.7,'advanced','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000011','d2000000-0000-0000-0000-000000000001',NULL,
   '异议处理','客户说“再考虑考虑”','追问考虑的具体对象：是预算、风险，还是内部意见不统一',
   '您考虑的主要是预算、风险，还是内部还有不同意见？','直接进入逼单','客户礼貌性拖延的场景',1.4,4.8,'advanced','active','interview','d4000000-0000-0000-0000-000000000001'),
  ('d3100000-0000-0000-0000-000000000012','d2000000-0000-0000-0000-000000000001',NULL,
   '边界','客户要求承诺激进的上线时间','明确说出不能承诺的部分，用分阶段上线替代整体压缩',
   '整体压缩到一个月我不能承诺，但可以先把一个业务单元上线，您看是否可行？','为了签单先答应再说','时间压力大、实施复杂度高的场景',1.2,4.5,'advanced','active','interview','d4000000-0000-0000-0000-000000000001')
ON CONFLICT (id) DO NOTHING;

-- 颗粒回填来源报告
UPDATE public.experience_grain
   SET report_id = 'd4100000-0000-0000-0000-000000000001'
 WHERE space_id = 'd2000000-0000-0000-0000-000000000001'
   AND report_id IS NULL;

-- ---------- 6. 访谈会话与消息 ----------
INSERT INTO public.interview_session (
  id, space_id, topic, status, current_phase, interview_type, domain, created_at, finished_at, last_active_at
) VALUES (
  'd4000000-0000-0000-0000-000000000001','d2000000-0000-0000-0000-000000000001',
  '如何在复杂决策链中找到并搞定关键人','completed','closing','expert','sales.b2b_enterprise',
  now() - interval '32 days', now() - interval '30 days', now() - interval '30 days'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.interview_message (id, session_id, role, content, phase, depth, created_at)
VALUES
  ('d4010000-0000-0000-0000-000000000001','d4000000-0000-0000-0000-000000000001','ai',
   '请回忆过去半年里，你搞定过的最难的一个客户。化名就好，难在哪？','opening',0, now() - interval '32 days'),
  ('d4010000-0000-0000-0000-000000000002','d4000000-0000-0000-0000-000000000001','user',
   '华星科技。他们想换核心结算系统。难在采购一直说在评估，但三个月没有任何推进。','opening',0, now() - interval '32 days'),
  ('d4010000-0000-0000-0000-000000000003','d4000000-0000-0000-0000-000000000001','ai',
   '三个月没有推进——你当时判断卡在哪？','storytelling',0, now() - interval '31 days'),
  ('d4010000-0000-0000-0000-000000000004','d4000000-0000-0000-0000-000000000001','user',
   '我一开始以为是价格。后来发现他们对外的说法是“还在评估”，但业务部门一直在内部协调技术团队。','storytelling',1, now() - interval '31 days'),
  ('d4010000-0000-0000-0000-000000000005','d4000000-0000-0000-0000-000000000001','ai',
   '如果把你刚才的判断总结成几步，你会怎么分？','modeling',0, now() - interval '30 days'),
  ('d4010000-0000-0000-0000-000000000006','d4000000-0000-0000-0000-000000000001','user',
   '先判断顾虑，再追问到具体事故，然后用并行和回退把风险降下来，最后请支持者帮忙向上汇报。','modeling',0, now() - interval '30 days')
ON CONFLICT (id) DO NOTHING;

-- ---------- 7. 六章报告 ----------
INSERT INTO public.report (id, space_id, session_id, title, subtitle, content_json, file_status, rating, view_count, share_code, share_enabled, web_published)
VALUES (
  'd4100000-0000-0000-0000-000000000001','d2000000-0000-0000-0000-000000000001','d4000000-0000-0000-0000-000000000001',
  '透视','在复杂决策链中找到并搞定关键人',
  $j${
    "chapters":[
      {"order":1,"title":"案例故事","content":"华星科技计划更换核心结算系统，采购持续表示“还在评估”，三个月没有推进。业务部门却一直在内部协调技术团队。"},
      {"order":2,"title":"方法论：透视四步法","steps":[
        {"order":1,"name":"判断顾虑","action":"先确认对方真正担心的事","script":"如果今年不做，最让您担心的是什么？","mistake":"把停滞归因于价格"},
        {"order":2,"name":"追问事故","action":"把抽象担心追问到具体事件","script":"去年那次是哪一段出的问题？","mistake":"用“我们很稳”回应"},
        {"order":3,"name":"降低风险","action":"用并行与回退替代整体承诺","script":"保留旧系统并行两周，异常可一键回退。","mistake":"为了签单先答应再说"},
        {"order":4,"name":"向上汇报","action":"帮支持者准备给管理层的材料","script":"我整理一页给管理层，陪您一起过一遍。","mistake":"直接要求见领导"}
      ]},
      {"order":3,"title":"关键决策点分析","decisions":[
        {"title":"客户说“还在评估”，走哪条路？","options":["降价","继续催采购","判断内部优先级"],"chosen":"判断内部优先级","reason":"业务部门仍在投入，说明不是价值不认可"}
      ]},
      {"order":4,"title":"专家心法","quotes":["沉默通常不是不认可，而是没听到与他考核相关的那一页","用并行回退把决策风险降到可接受"],"oneliner":"先判断，再给下一步","metaphor":"像医生先看片再开方"},
      {"order":5,"title":"避坑指南","pitfalls":[
        {"title":"把沉默当成同意","solution":"用“只能保留一页”逼出真实关注点"},
        {"title":"听到竞品就降价","solution":"先确认评估标准与比较口径"}
      ]},
      {"order":6,"title":"行动检查清单与情景练习","practice_scene":{"title":"客户有过失败切换经历","setting":"你是销售，我是刚经历过失败切换的采购负责人","customerLine":"去年换系统停了半天业务，这次凭什么相信你们？"}}
    ]
  }$j$::jsonb,
  'synced', 4.9, 1256, 'demo-perspective', true, true
)
ON CONFLICT (id) DO NOTHING;

-- ---------- 8. 素材 ----------
INSERT INTO public.skill_material (id, skill_id, uploaded_by, file_name, file_type, file_size, parsed_content, status, material_type)
VALUES (
  'd4200000-0000-0000-0000-000000000001','d3000000-0000-0000-0000-000000000001',
  'd1000000-0000-0000-0000-000000000001','华星科技-三次拜访记录.txt','txt',18432,
  '第一次拜访：采购表示还在评估。第二次：业务部门提及去年切换事故。第三次：确认并行回退方案可行。',
  'extracted','visit_note'
)
ON CONFLICT (id) DO NOTHING;

-- ---------- 9. 复盘会话 ----------
INSERT INTO public.review_session (
  id, company_id, reviewer_id, coach_skill_id, coach_owner_id, coach_name, coach_title,
  customer_name, topic, transcript, transcript_chars, source, share_transcript_with_coach,
  status, created_at, updated_at
) VALUES
  ('d5000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','d3000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','陈锋','销售总监','华星科技','核心系统替换',
   $t$客户：我们内部还在评估，暂时不着急上新的系统。
销售：理解，张总。我不催您做决定。想确认一件事：如果今年不上，最让您担心的是什么？
客户：主要是怕上线之后影响业务，去年我们换过一次，出了问题。
销售：去年那次是哪一段出的问题？是数据迁移还是切换窗口？
客户：切换窗口。业务部门停了半天。
销售：谢谢您说清楚。我们的方案可以把切换放在周末，并且保留旧系统并行两周，任何异常都能一键回退。
客户：如果真能这样，可以再聊。你把并行回退的说明发我，我拿给业务部门看。
销售：好，我明天上午发您。另外想约下周三和业务部门一起过一遍，您看方便吗？$t$,
   1, 'text', true, 'report_ready', now() - interval '2 days', now() - interval '2 days'),

  ('d5000000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','d3000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','陈锋','销售总监','云海集团','结算系统比价',
   $t$客户：价格还是高，竞品报价比你们低 15%。
销售：如果只看单价，确实不占优势。您更在意一次性采购成本，还是三年总拥有成本？
客户：我们看三年。
销售：那我们把三年运维和迁移成本一起算，下周给您一版对比。$t$,
   1, 'text', true, 'converted', now() - interval '7 days', now() - interval '5 days'),

  ('d5000000-0000-0000-0000-000000000003','d0000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','d3000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','陈锋','销售总监','星河制造','二期项目启动时间',
   $t$客户：我们下季度再启动这个项目。
销售：好的，那这季度我先帮您把现状梳理清楚。
客户：可以。$t$,
   1, 'text', false, 'active', now() - interval '1 hour', now() - interval '1 hour'),

  ('d5000000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000003','d3000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','陈锋','销售总监','启明医疗','首次方案汇报',
   $t$客户：你们这个方案看起来功能挺全，但我们最关心的是上线之后谁来兜底。
销售：上线后前三个月我们会有驻场支持，出问题两小时内响应。
客户：那如果业务部门不配合呢？
销售：这个我们需要一起定一个推进机制，我建议下周先和业务负责人对齐一次。$t$,
   1, 'text', true, 'report_ready', now() - interval '1 day', now() - interval '1 day')
ON CONFLICT (id) DO NOTHING;

UPDATE public.review_session SET transcript_chars = length(transcript)
 WHERE company_id = 'd0000000-0000-0000-0000-000000000001';

-- ---------- 10. 复盘段落 ----------
INSERT INTO public.review_segment (id, session_id, seq, speaker, text, char_count) VALUES
  ('d5100000-0000-0000-0000-000000000001','d5000000-0000-0000-0000-000000000001',1,'customer','我们内部还在评估，暂时不着急上新的系统。',22),
  ('d5100000-0000-0000-0000-000000000002','d5000000-0000-0000-0000-000000000001',2,'sales','理解，张总。我不催您做决定。想确认一件事：如果今年不上，最让您担心的是什么？',42),
  ('d5100000-0000-0000-0000-000000000003','d5000000-0000-0000-0000-000000000001',3,'customer','主要是怕上线之后影响业务，去年我们换过一次，出了问题。',27),
  ('d5100000-0000-0000-0000-000000000004','d5000000-0000-0000-0000-000000000001',4,'sales','去年那次是哪一段出的问题？是数据迁移还是切换窗口？',26),
  ('d5100000-0000-0000-0000-000000000005','d5000000-0000-0000-0000-000000000001',5,'customer','切换窗口。业务部门停了半天。',15),
  ('d5100000-0000-0000-0000-000000000006','d5000000-0000-0000-0000-000000000001',6,'sales','谢谢您说清楚。我们的方案可以把切换放在周末，并且保留旧系统并行两周，任何异常都能一键回退。',47),
  ('d5100000-0000-0000-0000-000000000007','d5000000-0000-0000-0000-000000000001',7,'customer','如果真能这样，可以再聊。你把并行回退的说明发我，我拿给业务部门看。',34),
  ('d5100000-0000-0000-0000-000000000008','d5000000-0000-0000-0000-000000000001',8,'sales','好，我明天上午发您。另外想约下周三和业务部门一起过一遍，您看方便吗？',35),

  ('d5100000-0000-0000-0000-000000000011','d5000000-0000-0000-0000-000000000002',1,'customer','价格还是高，竞品报价比你们低 15%。',19),
  ('d5100000-0000-0000-0000-000000000012','d5000000-0000-0000-0000-000000000002',2,'sales','如果只看单价，确实不占优势。您更在意一次性采购成本，还是三年总拥有成本？',39),
  ('d5100000-0000-0000-0000-000000000013','d5000000-0000-0000-0000-000000000002',3,'customer','我们看三年。',6),
  ('d5100000-0000-0000-0000-000000000014','d5000000-0000-0000-0000-000000000002',4,'sales','那我们把三年运维和迁移成本一起算，下周给您一版对比。',26),

  ('d5100000-0000-0000-0000-000000000021','d5000000-0000-0000-0000-000000000003',1,'customer','我们下季度再启动这个项目。',12),
  ('d5100000-0000-0000-0000-000000000022','d5000000-0000-0000-0000-000000000003',2,'sales','好的，那这季度我先帮您把现状梳理清楚。',19),
  ('d5100000-0000-0000-0000-000000000023','d5000000-0000-0000-0000-000000000003',3,'customer','可以。',3),

  ('d5100000-0000-0000-0000-000000000031','d5000000-0000-0000-0000-000000000004',1,'customer','你们这个方案看起来功能挺全，但我们最关心的是上线之后谁来兜底。',31),
  ('d5100000-0000-0000-0000-000000000032','d5000000-0000-0000-0000-000000000004',2,'sales','上线后前三个月我们会有驻场支持，出问题两小时内响应。',27),
  ('d5100000-0000-0000-0000-000000000033','d5000000-0000-0000-0000-000000000004',3,'customer','那如果业务部门不配合呢？',13),
  ('d5100000-0000-0000-0000-000000000034','d5000000-0000-0000-0000-000000000004',4,'sales','这个我们需要一起定一个推进机制，我建议下周先和业务负责人对齐一次。',34)
ON CONFLICT (id) DO NOTHING;

-- ---------- 11. 复盘报告 ----------
INSERT INTO public.review_report (
  id, company_id, session_id, version, status, model_name, prompt_version,
  summary, payload, attempts, generated_at, created_by, created_at, updated_at
) VALUES (
  'd5200000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001',
  'd5000000-0000-0000-0000-000000000001',1,'ready','deepseek-chat','v1',
  '客户认可价值，但担忧切换影响业务；已就并行与回退方案建立下一步。',
  $p${
    "summary":"客户认可价值，但担忧切换影响业务；已就并行与回退方案建立下一步。",
    "strengths":[
      {"point":"先确认顾虑，而不是催单","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000002","quote":"我不催您做决定"}],"confidence":"high","suggestion":null},
      {"point":"把抽象担心追问到具体事故","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000004","quote":"去年那次是哪一段出的问题"}],"confidence":"high","suggestion":null},
      {"point":"用并行与回退降低决策风险","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000006","quote":"保留旧系统并行两周"}],"confidence":"high","suggestion":null}
    ],
    "weaknesses":[
      {"point":"未在本次对话中确认预算归属与最终签字人","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000008","quote":"和业务部门一起过一遍"}],"confidence":"medium","suggestion":"下次明确预算归属、最终签字人与内部决策时点。"}
    ],
    "risks":[
      {"point":"客户有过失败切换经历，信任修复需要可验证证据","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000003","quote":"去年我们换过一次，出了问题"}],"confidence":"high","suggestion":"准备同行业切换案例与回退演练记录，最好由客户侧业务部门共同验收。"}
    ],
    "customerSignals":[
      {"field":"上线风险顾虑","value":"担心切换影响业务","sourceType":"customer_words","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000003","quote":"怕上线之后影响业务"}],"confidence":"high"},
      {"field":"推进信号","value":"愿意引荐业务部门","sourceType":"customer_words","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000007","quote":"我拿给业务部门看"}],"confidence":"medium"},
      {"field":"预算","value":null,"sourceType":"unknown","evidence":[],"confidence":"low"}
    ],
    "nextSteps":[
      {"title":"发送并行回退说明","channel":"wechat","dueInDays":1,"content":"整理切换窗口、并行周期与一键回退材料。","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000007","quote":"把并行回退的说明发我"}]},
      {"title":"约业务部门三方会议","channel":"phone","dueInDays":5,"content":"确认业务影响、验收标准与切换窗口。","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000008","quote":"和业务部门一起过一遍"}]}
    ],
    "leadDraft":{"name":"华星科技","contactName":"张总","contactPhone":null,"needSummary":"核心系统替换，担忧切换影响业务，已同意评估并行回退方案。"}
  }$p$::jsonb,
  1, now() - interval '2 days', 'd1000000-0000-0000-0000-000000000001',
  now() - interval '2 days', now() - interval '2 days'
),
(
  'd5200000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001',
  'd5000000-0000-0000-0000-000000000002',1,'ready','deepseek-chat','v1',
  '把单价比较转成三年总拥有成本比较，已约定下周给出对比。',
  $p${
    "summary":"把单价比较转成三年总拥有成本比较，已约定下周给出对比。",
    "strengths":[{"point":"没有直接解释价格，先确认比较口径","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000012","quote":"三年总拥有成本"}],"confidence":"high","suggestion":null}],
    "weaknesses":[],
    "risks":[{"point":"尚未拿到竞品三年成本口径，对比可能失真","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000011","quote":"竞品报价比你们低 15%"}],"confidence":"medium","suggestion":"确认竞品报价是否含实施与运维。"}],
    "customerSignals":[{"field":"评估口径","value":"看三年总成本","sourceType":"customer_words","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000013","quote":"我们看三年"}],"confidence":"high"}],
    "nextSteps":[{"title":"提交三年总拥有成本对比","channel":"email","dueInDays":5,"content":"包含实施、运维、迁移与切换风险成本。","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000014","quote":"下周给您一版对比"}]}],
    "leadDraft":{"name":"云海集团","contactName":null,"contactPhone":null,"needSummary":"结算系统比价，关注三年总拥有成本。"}
  }$p$::jsonb,
  1, now() - interval '5 days', 'd1000000-0000-0000-0000-000000000001',
  now() - interval '5 days', now() - interval '5 days'
),
(
  'd5200000-0000-0000-0000-000000000003','d0000000-0000-0000-0000-000000000001',
  'd5000000-0000-0000-0000-000000000003',1,'generating','deepseek-chat','v1',
  NULL, NULL, 2, NULL, 'd1000000-0000-0000-0000-000000000001',
  now() - interval '1 hour', now() - interval '1 hour'
),
(
  'd5200000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-000000000001',
  'd5000000-0000-0000-0000-000000000004',1,'ready','deepseek-chat','v1',
  '回应了兜底与响应时效，但未把“业务部门不配合”转成具体推进机制。',
  $p${
    "summary":"回应了兜底与响应时效，但未把“业务部门不配合”转成具体推进机制。",
    "strengths":[{"point":"正面回应上线兜底","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000032","quote":"前三个月我们会有驻场支持"}],"confidence":"high","suggestion":null}],
    "weaknesses":[{"point":"对“业务部门不配合”只给了开会建议，没有机制","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000034","quote":"一起定一个推进机制"}],"confidence":"high","suggestion":"给出会议目标、参与角色与决策输出物。"}],
    "risks":[{"point":"推进机制未落到具体责任人与时间点","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000033","quote":"业务部门不配合"}],"confidence":"medium","suggestion":"把机制写成三方确认的一页纸。"}],
    "customerSignals":[{"field":"核心顾虑","value":"上线后谁兜底","sourceType":"customer_words","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000031","quote":"上线之后谁来兜底"}],"confidence":"high"}],
    "nextSteps":[{"title":"准备三方推进机制一页纸","channel":"wechat","dueInDays":2,"content":"包含目标、角色、节奏与升级路径。","evidence":[{"segmentId":"d5100000-0000-0000-0000-000000000034","quote":"和业务负责人对齐一次"}]}],
    "leadDraft":{"name":"启明医疗","contactName":null,"contactPhone":null,"needSummary":"首次方案汇报，关注上线兜底与业务部门配合。"}
  }$p$::jsonb,
  1, now() - interval '1 day', 'd1000000-0000-0000-0000-000000000003',
  now() - interval '1 day', now() - interval '1 day'
)
ON CONFLICT (id) DO NOTHING;

-- ---------- 12. 线索 / 联系人 / 时间线 / 任务 ----------
INSERT INTO public.lead (
  id, company_id, owner_id, name, name_normalized, contact_name, contact_phone, contact_phone_digits,
  need_summary, source, source_review_id, status, next_follow_up_at, last_activity_at, created_by, created_at, updated_at
) VALUES (
  'd6000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001',
  'd1000000-0000-0000-0000-000000000001','云海集团','云海集团','张总','13800138000','13800138000',
  '结算系统比价，关注三年总拥有成本。','review','d5000000-0000-0000-0000-000000000002',
  'following', now() + interval '1 day', now() - interval '2 hours',
  'd1000000-0000-0000-0000-000000000001', now() - interval '5 days', now() - interval '2 hours'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.lead_contact (id, company_id, lead_id, name, phone, phone_digits, role, is_primary, note, created_by) VALUES
  ('d6100000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','张总','13800138000','13800138000','decision_maker',true,'分管采购与财务','d1000000-0000-0000-0000-000000000001'),
  ('d6100000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','李工','13900139000','13900139000','influencer',false,'技术评估负责人','d1000000-0000-0000-0000-000000000001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.lead_activity (id, company_id, lead_id, type, content, actor_id, source_type, source_id, occurred_at) VALUES
  ('d6200000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','created','从复盘转为线索：云海集团','d1000000-0000-0000-0000-000000000001','review_session','d5000000-0000-0000-0000-000000000002', now() - interval '5 days'),
  ('d6200000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','contact_added','新增联系人：李工','d1000000-0000-0000-0000-000000000001','lead_contact','d6100000-0000-0000-0000-000000000002', now() - interval '4 days'),
  ('d6200000-0000-0000-0000-000000000003','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','task_created','创建任务：提交三年总拥有成本对比','d1000000-0000-0000-0000-000000000001','follow_up_task','d6300000-0000-0000-0000-000000000001', now() - interval '4 days'),
  ('d6200000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','status_changed','线索状态：new → following','d1000000-0000-0000-0000-000000000001',NULL,NULL, now() - interval '2 hours')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.follow_up_task (
  id, company_id, lead_id, review_id, owner_id, title, channel, content, due_at, status, created_by, created_at, updated_at
) VALUES
  ('d6300000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','d5000000-0000-0000-0000-000000000002',
   'd1000000-0000-0000-0000-000000000001','提交三年总拥有成本对比','email','包含实施、运维、迁移与切换风险成本。',
   date_trunc('day', now()) + interval '18 hours','pending','d1000000-0000-0000-0000-000000000001', now() - interval '4 days', now() - interval '4 days'),
  ('d6300000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001','d5000000-0000-0000-0000-000000000002',
   'd1000000-0000-0000-0000-000000000001','约业务部门三方会议','phone','确认业务影响、验收标准与切换窗口。',
   now() - interval '2 days','pending','d1000000-0000-0000-0000-000000000001', now() - interval '5 days', now() - interval '5 days'),
  ('d6300000-0000-0000-0000-000000000003','d0000000-0000-0000-0000-000000000001',NULL,'d5000000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','整理华星科技切换案例','wechat','作为下次沟通的证据材料。',
   now() + interval '3 days','pending','d1000000-0000-0000-0000-000000000001', now() - interval '1 day', now() - interval '1 day')
ON CONFLICT (id) DO NOTHING;

-- 已转线索的会话回填 lead_id
UPDATE public.review_session SET lead_id = 'd6000000-0000-0000-0000-000000000001'
 WHERE id = 'd5000000-0000-0000-0000-000000000002';

-- ---------- 13. 教练请求与补充 ----------
INSERT INTO public.review_coach_request (
  id, company_id, session_id, coach_user_id, requested_by, message, status, requested_at
) VALUES (
  'd7000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001',
  'd5000000-0000-0000-0000-000000000004','d1000000-0000-0000-0000-000000000001',
  'd1000000-0000-0000-0000-000000000003',
  '客户问业务部门不配合怎么办，我给的机制太虚了，想请您补充一个可落地的做法。','pending',
  now() - interval '6 hours'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.review_coach_note (
  id, company_id, session_id, coach_user_id, content, created_at
) VALUES (
  'd7100000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001',
  'd5000000-0000-0000-0000-000000000004','d1000000-0000-0000-0000-000000000001',
  '下次先约 20 分钟三方会，会前发一页纸：目标、参与角色、三个必须当场确认的问题。会议结束前把责任人和时间点写进纪要。',
  now() - interval '4 hours'
)
ON CONFLICT (id) DO NOTHING;

-- ---------- 14. 培训计划 / 版本 / 任务 ----------
INSERT INTO public.training_plan (id, company_id, name, description, status, current_version, created_by)
VALUES ('d8000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001',
  '大客户推进能力训练','围绕决策链判断、切换风险与异议处理的两项演练。','published',1,
  'd1000000-0000-0000-0000-000000000001');

INSERT INTO public.training_plan_version (id, plan_id, version, status, task_count, published_at, created_by)
VALUES ('d8100000-0000-0000-0000-000000000001','d8000000-0000-0000-0000-000000000001',1,'published',2,
  now() - interval '10 days','d1000000-0000-0000-0000-000000000001')
ON CONFLICT (plan_id, version) DO NOTHING;

INSERT INTO public.training_task (
  id, plan_id, version, phase_no, phase_name, type, title, description, is_required, order_no,
  prerequisites, resource_type, resource_id, scene_tag, allow_hints, pass_criteria, max_attempts,
  deadline_policy, created_at, updated_at
) VALUES
  ('d8200000-0000-0000-0000-000000000001','d8000000-0000-0000-0000-000000000001',1,1,'判断与异议',
   'practice','向陈锋分身演练“切换风险”异议','客户有过失败切换经历，练习用并行与回退降低风险。',true,1,
   '[]'::jsonb,'skill','d3000000-0000-0000-0000-000000000001','切换风险',true,
   $j${"minScore":60}$j$::jsonb,3,'auto_submit', now() - interval '10 days', now() - interval '10 days'),
  ('d8200000-0000-0000-0000-000000000002','d8000000-0000-0000-0000-000000000001',1,2,'推进机制',
   'practice','向陈锋分身演练“业务不配合”','练习把“开会”变成有责任人、有输出物的推进机制。',true,2,
   '[]'::jsonb,'skill','d3000000-0000-0000-0000-000000000001','推进机制',true,
   $j${"minScore":60}$j$::jsonb,3,'auto_submit', now() - interval '10 days', now() - interval '10 days');

-- ---------- 15. 批次 / 分配 / 进度 / 尝试 / 认证 ----------
INSERT INTO public.training_cohort (id, company_id, plan_id, plan_version, name, mentor_id, deadline, status, created_by) VALUES
  ('d8300000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001','d8000000-0000-0000-0000-000000000001',1,
   '第 1 期 · 学员批次','d1000000-0000-0000-0000-000000000002', now() + interval '10 days','active','d1000000-0000-0000-0000-000000000001'),
  ('d8300000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001','d8000000-0000-0000-0000-000000000001',1,
   '第 2 期 · 导师复核批次','d1000000-0000-0000-0000-000000000001', now() + interval '15 days','active','d1000000-0000-0000-0000-000000000001');

INSERT INTO public.training_assignment (
  id, company_id, cohort_id, learner_id, status, total_tasks, required_total,
  completed_tasks, required_passed, passed_tasks, deadline
) VALUES
  ('d8400000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001','d8300000-0000-0000-0000-000000000001',
   'd1000000-0000-0000-0000-000000000001','in_progress',2,2,1,1,1, now() + interval '10 days'),
  ('d8400000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001','d8300000-0000-0000-0000-000000000002',
   'd1000000-0000-0000-0000-000000000003','certified',2,2,2,2,2, now() + interval '15 days')
ON CONFLICT (cohort_id, learner_id) DO NOTHING;

INSERT INTO public.training_task_record (
  id, assignment_id, task_id, status, acknowledged_at, started_at, finished_at, round_no
) VALUES
  ('d8500000-0000-0000-0000-000000000001','d8400000-0000-0000-0000-000000000001','d8200000-0000-0000-0000-000000000001',
   'passed', now() - interval '3 days', now() - interval '4 days', now() - interval '3 days',1),
  ('d8500000-0000-0000-0000-000000000002','d8400000-0000-0000-0000-000000000001','d8200000-0000-0000-0000-000000000002',
   'available', NULL, NULL, NULL,1),
  ('d8500000-0000-0000-0000-000000000003','d8400000-0000-0000-0000-000000000002','d8200000-0000-0000-0000-000000000001',
   'passed', now() - interval '2 days', now() - interval '3 days', now() - interval '2 days',1),
  ('d8500000-0000-0000-0000-000000000004','d8400000-0000-0000-0000-000000000002','d8200000-0000-0000-0000-000000000002',
   'passed', now() - interval '1 day', now() - interval '2 days', now() - interval '1 day',1)
ON CONFLICT (assignment_id, task_id, round_no) DO NOTHING;

INSERT INTO public.training_attempt (
  id, assignment_id, task_id, task_record_id, type, attempt_no, status, result,
  skill_id, score, started_at, submitted_at, finished_at
) VALUES (
  'd8600000-0000-0000-0000-000000000001','d8400000-0000-0000-0000-000000000002',
  'd8200000-0000-0000-0000-000000000001','d8500000-0000-0000-0000-000000000003',
  'practice',1,'finalized','pass','d3000000-0000-0000-0000-000000000001',
  $j${"score":86,"confidence":"high","needReview":false,"comment":"能先确认事故细节，再用并行回退降低风险。"}$j$::jsonb,
  now() - interval '3 days', now() - interval '3 days', now() - interval '2 days'
)
ON CONFLICT (task_record_id, attempt_no) DO NOTHING;

INSERT INTO public.training_certification (
  id, company_id, assignment_id, learner_id, status, confirmed_by, confirmed_at, note
) VALUES (
  'd8700000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001',
  'd8400000-0000-0000-0000-000000000002','d1000000-0000-0000-0000-000000000003',
  'confirmed','d1000000-0000-0000-0000-000000000001', now() - interval '1 day','两项演练均通过，已确认。'
);

-- ---------- 16. 分身对话（继续上次） ----------
INSERT INTO public.skill_conversation (id, skill_id, user_id, title, mode, created_at, updated_at, channel)
VALUES ('d3200000-0000-0000-0000-000000000001','d3000000-0000-0000-0000-000000000001',
  'd1000000-0000-0000-0000-000000000001','客户说贵但约不到决策人','qa',
  now() - interval '3 hours', now() - interval '2 hours','web')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.skill_message (id, conversation_id, role, content, created_at) VALUES
  ('d3300000-0000-0000-0000-000000000001','d3200000-0000-0000-0000-000000000001','user',
   '客户说贵，但一直约不到决策人，怎么判断真假？', now() - interval '3 hours'),
  ('d3300000-0000-0000-0000-000000000002','d3200000-0000-0000-0000-000000000001','assistant',
   '先别把“约不到”当成关系问题。看两个信号：一是业务部门是否仍在内部投入；二是采购能否给出具体评估标准。两条都成立，通常不是价格问题，而是优先级还没排上。下一步不是降价，是帮支持者准备一页给管理层的材料。',
   now() - interval '2 hours')
ON CONFLICT (id) DO NOTHING;

-- ---------- 17. 分身互动明细（定时任务据此聚合，避免演示数字被重算成 0） ----------
INSERT INTO public.conversation_stats (id, skill_id, conversation_id, user_id, mode, is_test, created_at)
SELECT
  ('d3a00000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
  'd3000000-0000-0000-0000-000000000001',
  ('d3d00000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
  ('d3b00000-0000-0000-0000-' || lpad(to_hex((g % 46) + 1), 12, '0'))::uuid,
  'qa', false,
  now() - make_interval(days => (g % 30)) - make_interval(hours => (g % 12))
FROM generate_series(1, 128) AS g;

INSERT INTO public.feedback_log (id, skill_id, rating, source, created_at)
SELECT
  ('d3c00000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
  'd3000000-0000-0000-0000-000000000001',
  CASE WHEN g <= 94 THEN 'up' ELSE 'down' END,
  'web',
  now() - make_interval(days => (g % 30))
FROM generate_series(1, 100) AS g;

-- 立即按与 SkillStatsScheduler 相同的口径回填，避免门户在下一轮聚合前显示 0
UPDATE public.skill s SET
  conversation_count = (
    SELECT COUNT(DISTINCT conversation_id) FROM public.conversation_stats
     WHERE skill_id = s.id AND is_test = false AND created_at >= now() - interval '30 days'),
  user_count = (
    SELECT COUNT(DISTINCT user_id) FROM public.conversation_stats
     WHERE skill_id = s.id AND is_test = false AND created_at >= now() - interval '30 days'),
  satisfaction_rate = COALESCE((
    SELECT ROUND(SUM(CASE WHEN rating = 'up' THEN 1 ELSE 0 END)::numeric
                 / NULLIF(COUNT(*), 0) * 100)::int
      FROM public.feedback_log WHERE skill_id = s.id), 0),
  last_active_at = (
    SELECT MAX(created_at) FROM public.conversation_stats WHERE skill_id = s.id)
WHERE s.id = 'd3000000-0000-0000-0000-000000000001';

-- ---------- 18. 站内信 ----------
INSERT INTO public.user_notification (id, company_id, user_id, type, title, content, link, biz_type, biz_id, dedupe_key, created_at) VALUES
  ('d9000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000001',
   'sales_review_ready','复盘报告已生成','「华星科技」的复盘已生成，点击查看。','/sales/reviews/d5000000-0000-0000-0000-000000000001',
   'review_report','d5200000-0000-0000-0000-000000000001','sales:report:d5200000-0000-0000-0000-000000000001', now() - interval '2 days'),
  ('d9000000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000001',
   'sales_task_overdue','跟进任务已逾期','任务：约业务部门三方会议；已逾期 2 天','/sales/tasks',
   'follow_up_task','d6300000-0000-0000-0000-000000000002','task_overdue:demo', now() - interval '1 day'),
  ('d9000000-0000-0000-0000-000000000003','d0000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000001',
   'sales_coach_request','收到新的教练请求','林小满请求你补充「启明医疗」的复盘。','/sales/coach/requests/d5000000-0000-0000-0000-000000000004',
   'review_coach_request','d7000000-0000-0000-0000-000000000001','coach_request:demo', now() - interval '6 hours'),
  ('d9000000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000001',
   'training_pending_review','培训待复核','「第 2 期 · 导师复核批次」有待复核的演练。','/admin/training/cohorts',
   'training_assignment','d8400000-0000-0000-0000-000000000002','training_pending:demo', now() - interval '1 day');

COMMIT;
