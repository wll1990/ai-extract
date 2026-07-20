CREATE EXTENSION IF NOT EXISTS vector;

-- =============================================
-- V1: 完整初始化
-- =============================================

-- 1. 企业
CREATE TABLE company (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    logo_url VARCHAR(500),
    brand_color VARCHAR(7) DEFAULT '#1A2B4C',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
COMMENT ON TABLE company IS '企业/租户';
COMMENT ON COLUMN company.id IS '企业ID';
COMMENT ON COLUMN company.name IS '企业名称';
COMMENT ON COLUMN company.logo_url IS 'Logo地址';
COMMENT ON COLUMN company.brand_color IS '品牌色';

-- 2. 用户
CREATE TABLE "user" (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'employee' CHECK (role IN ('super_admin', 'employee')),
    avatar_url VARCHAR(500),
    phone VARCHAR(20),
    account VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_user_company ON "user"(company_id);
COMMENT ON TABLE "user" IS '用户';
COMMENT ON COLUMN "user".id IS '用户ID';
COMMENT ON COLUMN "user".company_id IS '所属企业ID';
COMMENT ON COLUMN "user".name IS '真实姓名';
COMMENT ON COLUMN "user".role IS '角色: super_admin=管理员, employee=普通用户';
COMMENT ON COLUMN "user".avatar_url IS '头像地址';
COMMENT ON COLUMN "user".phone IS '手机号';
COMMENT ON COLUMN "user".account IS '登录账号(企业内唯一)';
COMMENT ON COLUMN "user".password_hash IS 'BCrypt密码哈希';
COMMENT ON COLUMN "user".is_active IS '是否启用';

-- 3. 空间
CREATE TABLE space (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    tags JSONB DEFAULT '[]',
    is_public BOOLEAN DEFAULT false,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'paused', 'archived')),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_space_user ON space(user_id);
COMMENT ON TABLE space IS '个人空间(一个用户一个空间)';
COMMENT ON COLUMN space.id IS '空间ID';
COMMENT ON COLUMN space.user_id IS '所属用户ID';
COMMENT ON COLUMN space.title IS '空间标题';
COMMENT ON COLUMN space.description IS '空间描述(展示为销冠头衔)';
COMMENT ON COLUMN space.tags IS '标签JSON数组';
COMMENT ON COLUMN space.is_public IS '是否公开';
COMMENT ON COLUMN space.status IS '状态: active=活跃, paused=暂停, archived=归档';

-- 4. 访谈会话
CREATE TABLE interview_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL,
    topic VARCHAR(200),
    status VARCHAR(20) DEFAULT 'created' CHECK (status IN ('created', 'in_progress', 'paused', 'completed', 'abandoned', 'failed')),
    current_phase VARCHAR(20) DEFAULT 'opening' CHECK (current_phase IN ('opening', 'storytelling', 'modeling', 'closing')),
    collect_case_story BOOLEAN DEFAULT false,
    collect_steps BOOLEAN DEFAULT false,
    collect_decision BOOLEAN DEFAULT false,
    collect_mindset BOOLEAN DEFAULT false,
    collect_boundary BOOLEAN DEFAULT false,
    collect_checklist BOOLEAN DEFAULT false,
    invite_code VARCHAR(50),
    invited_by UUID,
    expert_skill_id UUID,
    interview_type VARCHAR(20) DEFAULT 'sales',
    last_active_at TIMESTAMP DEFAULT NOW(),
    created_at TIMESTAMP DEFAULT NOW(),
    finished_at TIMESTAMP
);
CREATE INDEX idx_session_space ON interview_session(space_id);
CREATE INDEX idx_session_status ON interview_session(status);
COMMENT ON TABLE interview_session IS '销冠访谈会话';
COMMENT ON COLUMN interview_session.id IS '会话ID';
COMMENT ON COLUMN interview_session.space_id IS '所属空间ID';
COMMENT ON COLUMN interview_session.topic IS '访谈主题';
COMMENT ON COLUMN interview_session.status IS '状态: created→in_progress→completed';
COMMENT ON COLUMN interview_session.current_phase IS '当前阶段: opening/storytelling/modeling/closing';
COMMENT ON COLUMN interview_session.interview_type IS '访谈类型: sales=销冠访谈';
COMMENT ON COLUMN interview_session.invite_code IS '邀请码';
COMMENT ON COLUMN interview_session.last_active_at IS '最后活跃时间';
COMMENT ON COLUMN interview_session.finished_at IS '完成时间';

-- 5. 访谈消息
CREATE TABLE interview_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL,
    role VARCHAR(10) NOT NULL CHECK (role IN ('ai', 'user', 'system')),
    content TEXT NOT NULL,
    phase VARCHAR(20),
    depth INT DEFAULT 0,
    stage_status JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_message_created ON interview_message(session_id, created_at);
COMMENT ON TABLE interview_message IS '访谈对话记录';
COMMENT ON COLUMN interview_message.id IS '消息ID';
COMMENT ON COLUMN interview_message.session_id IS '所属会话ID';
COMMENT ON COLUMN interview_message.role IS '角色: ai=AI提问, user=用户回答';
COMMENT ON COLUMN interview_message.content IS '消息内容';
COMMENT ON COLUMN interview_message.phase IS '所属阶段';
COMMENT ON COLUMN interview_message.depth IS '追问深度';

-- 6. 报告
CREATE TABLE report (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID,
    session_id UUID,
    title VARCHAR(200) NOT NULL,
    subtitle VARCHAR(500),
    content_json JSONB NOT NULL DEFAULT '{}',
    word_url VARCHAR(500),
    ppt_url VARCHAR(500),
    web_published BOOLEAN DEFAULT true,
    file_status VARCHAR(20) DEFAULT 'synced' CHECK (file_status IN ('synced', 'pending_regenerate')),
    rating DECIMAL(2,1) DEFAULT 4.5,
    view_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_report_space ON report(space_id);
COMMENT ON TABLE report IS '萃取报告';
COMMENT ON COLUMN report.id IS '报告ID';
COMMENT ON COLUMN report.space_id IS '所属空间ID';
COMMENT ON COLUMN report.session_id IS '来源访谈会话ID';
COMMENT ON COLUMN report.title IS '报告标题';
COMMENT ON COLUMN report.subtitle IS '报告副标题';
COMMENT ON COLUMN report.content_json IS '报告内容JSON(chapters/steps/decisions等)';
COMMENT ON COLUMN report.word_url IS 'Word文件地址';
COMMENT ON COLUMN report.ppt_url IS 'PPT文件地址';
COMMENT ON COLUMN report.file_status IS '文件状态: synced=已同步';
COMMENT ON COLUMN report.rating IS '评分(1-5)';
COMMENT ON COLUMN report.view_count IS '浏览次数';

-- 7. 经验颗粒
CREATE TABLE experience_grain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL,
    report_id UUID,
    source_material_id UUID,
    scene_tag VARCHAR(50),
    scene_description TEXT,
    expert_thought TEXT,
    standard_script TEXT,
    common_mistakes TEXT,
    applicable_condition TEXT,
    embedding VECTOR(1024),
    weight FLOAT DEFAULT 1.0,
    quality_score DOUBLE PRECISION,
    difficulty_level VARCHAR(20),
    verification_notes JSONB,
    edited_content TEXT,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'deprecated')),
    helpful_count INT DEFAULT 0,
    unhelpful_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_grain_space ON experience_grain(space_id);
CREATE INDEX idx_grain_scene ON experience_grain(scene_tag);
CREATE INDEX IF NOT EXISTS idx_grain_embedding_hnsw
    ON experience_grain USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);
COMMENT ON TABLE experience_grain IS '经验颗粒(销冠知识的最小单元)';
COMMENT ON COLUMN experience_grain.id IS '颗粒ID';
COMMENT ON COLUMN experience_grain.space_id IS '所属空间ID';
COMMENT ON COLUMN experience_grain.report_id IS '来源报告ID';
COMMENT ON COLUMN experience_grain.source_material_id IS '来源素材ID';
COMMENT ON COLUMN experience_grain.scene_tag IS '场景标签(如"价格异议""破冰")';
COMMENT ON COLUMN experience_grain.scene_description IS '场景描述';
COMMENT ON COLUMN experience_grain.expert_thought IS '销冠思路';
COMMENT ON COLUMN experience_grain.standard_script IS '标准话术';
COMMENT ON COLUMN experience_grain.common_mistakes IS '常见错误';
COMMENT ON COLUMN experience_grain.applicable_condition IS '适用条件';
COMMENT ON COLUMN experience_grain.embedding IS '向量嵌入(1024维)';
COMMENT ON COLUMN experience_grain.weight IS '权重(0.1-2.0)';
COMMENT ON COLUMN experience_grain.quality_score IS '质量评分(0-5)';
COMMENT ON COLUMN experience_grain.difficulty_level IS '难度: beginner/intermediate/advanced/master';
COMMENT ON COLUMN experience_grain.status IS '状态: active=有效, deprecated=已废弃';
COMMENT ON COLUMN experience_grain.helpful_count IS '有用反馈数';
COMMENT ON COLUMN experience_grain.unhelpful_count IS '无用反馈数';

-- 8. AI分身
CREATE TABLE skill (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID NOT NULL UNIQUE,
    model_name VARCHAR(100) DEFAULT 'deepseek-chat',
    model_config JSONB DEFAULT '{}',
    status VARCHAR(20) DEFAULT 'generating' CHECK (status IN ('generating', 'reviewing', 'published', 'discarded')),
    display_name VARCHAR(200),
    owner_name VARCHAR(100),
    owner_title VARCHAR(200),
    department VARCHAR(200),
    seniority VARCHAR(50),
    tags JSONB DEFAULT '[]',
    target_scenarios JSONB DEFAULT '[]',
    limitations TEXT,
    publish_notes TEXT,
    published_at TIMESTAMP,
    published_by UUID,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
COMMENT ON TABLE skill IS 'AI分身(每个空间一个)';
COMMENT ON COLUMN skill.id IS '分身ID';
COMMENT ON COLUMN skill.space_id IS '所属空间ID';
COMMENT ON COLUMN skill.model_name IS '使用的大模型名称';
COMMENT ON COLUMN skill.status IS '状态: generating→reviewing→published/discarded';
COMMENT ON COLUMN skill.display_name IS '对外展示名称';
COMMENT ON COLUMN skill.owner_name IS '销冠真实姓名(展示用)';
COMMENT ON COLUMN skill.owner_title IS '销冠职位(展示用)';
COMMENT ON COLUMN skill.department IS '所属部门';
COMMENT ON COLUMN skill.seniority IS '从业年限';
COMMENT ON COLUMN skill.tags IS '灵活标签JSON: ["金融","B2B"]';
COMMENT ON COLUMN skill.target_scenarios IS '适用场景JSON: ["初次拜访","异议处理"]';
COMMENT ON COLUMN skill.limitations IS '已知局限性';
COMMENT ON COLUMN skill.publish_notes IS '发布审核备注';
COMMENT ON COLUMN skill.published_at IS '发布时间';
COMMENT ON COLUMN skill.published_by IS '发布人ID';

-- 9. 分身画像
CREATE TABLE skill_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL UNIQUE,
    personality TEXT,
    speaking_style TEXT,
    background TEXT,
    common_phrases TEXT,
    knowledge_domains JSONB DEFAULT '[]',
    communication_preferences JSONB DEFAULT '[]',
    weakness_notes TEXT,
    extra_context TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
COMMENT ON TABLE skill_profile IS '分身画像(人设+风格)';
COMMENT ON COLUMN skill_profile.skill_id IS '所属分身ID';
COMMENT ON COLUMN skill_profile.personality IS '性格描述';
COMMENT ON COLUMN skill_profile.speaking_style IS '说话风格';
COMMENT ON COLUMN skill_profile.background IS '背景经历';
COMMENT ON COLUMN skill_profile.common_phrases IS '口头禅';
COMMENT ON COLUMN skill_profile.knowledge_domains IS '擅长领域JSON';
COMMENT ON COLUMN skill_profile.communication_preferences IS '沟通偏好JSON';

-- 10. 会话历史
CREATE TABLE skill_conversation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    user_id UUID NOT NULL,
    title VARCHAR(200),
    mode VARCHAR(20) DEFAULT 'qa' CHECK (mode IN ('qa', 'practice')),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_skill_conv_skill ON skill_conversation(skill_id);
CREATE INDEX idx_skill_conv_user ON skill_conversation(user_id);
COMMENT ON TABLE skill_conversation IS '分身对话会话';
COMMENT ON COLUMN skill_conversation.id IS '会话ID';
COMMENT ON COLUMN skill_conversation.skill_id IS '所属分身ID';
COMMENT ON COLUMN skill_conversation.user_id IS '对话用户ID';
COMMENT ON COLUMN skill_conversation.title IS '会话标题(取首条消息前30字)';
COMMENT ON COLUMN skill_conversation.mode IS '模式: qa=问答, practice=对练, quick=快速提问, discuss=自由讨论, talk=自由对话';

CREATE TABLE skill_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    grain_id UUID,
    report_id UUID,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_skill_msg_conv ON skill_message(conversation_id);
COMMENT ON TABLE skill_message IS '分身对话消息';
COMMENT ON COLUMN skill_message.conversation_id IS '所属会话ID';
COMMENT ON COLUMN skill_message.role IS '角色: user=用户, assistant=AI分身';
COMMENT ON COLUMN skill_message.content IS '消息内容';
COMMENT ON COLUMN skill_message.grain_id IS '关联的经验颗粒ID';
COMMENT ON COLUMN skill_message.report_id IS '关联的报告ID';

-- 11. 素材管理
CREATE TABLE skill_material (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    uploaded_by UUID NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    file_url VARCHAR(500),
    file_type VARCHAR(200),
    file_size BIGINT,
    parsed_content TEXT,
    version INT DEFAULT 1,
    replaces_material_id UUID,
    status VARCHAR(20) DEFAULT 'uploaded' CHECK (status IN ('uploaded','cleaning','cleaned','analyzing','analyzed','extracted','discarded')),
    analysis_notes TEXT,
    extraction_metadata TEXT,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_material_skill ON skill_material(skill_id);
COMMENT ON TABLE skill_material IS '分身素材(上传的文档/录音等)';
COMMENT ON COLUMN skill_material.skill_id IS '所属分身ID';
COMMENT ON COLUMN skill_material.uploaded_by IS '上传人ID';
COMMENT ON COLUMN skill_material.file_name IS '文件名';
COMMENT ON COLUMN skill_material.file_url IS '文件存储地址';
COMMENT ON COLUMN skill_material.file_type IS '文件类型';
COMMENT ON COLUMN skill_material.file_size IS '文件大小(字节)';
COMMENT ON COLUMN skill_material.parsed_content IS '解析后的文本内容';
COMMENT ON COLUMN skill_material.version IS '版本号';
COMMENT ON COLUMN skill_material.status IS '状态: uploaded→cleaning→analyzing→extracted';
COMMENT ON COLUMN skill_material.locked_by IS '处理锁持有者';
COMMENT ON COLUMN skill_material.locked_at IS '处理锁时间';

-- 12. 评分
CREATE TABLE skill_evaluation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    conversation_id UUID,
    mode VARCHAR(20) CHECK (mode IN ('qa','practice','auto_evaluate','acceptance_report')),
    evaluator_id UUID,
    score INT CHECK (score >= 0 AND score <= 100),
    style_score INT,
    consistency_score INT,
    behavior_score INT,
    script_reuse_score INT,
    score_detail JSONB,
    strengths JSONB DEFAULT '[]',
    improvements JSONB DEFAULT '[]',
    demo_script TEXT,
    edited_response TEXT,
    edited_by UUID,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_eval_skill ON skill_evaluation(skill_id);
COMMENT ON TABLE skill_evaluation IS '分身评估记录';
COMMENT ON COLUMN skill_evaluation.skill_id IS '所属分身ID';
COMMENT ON COLUMN skill_evaluation.conversation_id IS '关联会话ID';
COMMENT ON COLUMN skill_evaluation.mode IS '评估模式: qa/practice/auto_evaluate/acceptance_report';
COMMENT ON COLUMN skill_evaluation.score IS '综合评分(0-100)';
COMMENT ON COLUMN skill_evaluation.style_score IS '风格分(权重30%)';
COMMENT ON COLUMN skill_evaluation.consistency_score IS '一致性分(权重30%)';
COMMENT ON COLUMN skill_evaluation.behavior_score IS '行为分(权重20%)';
COMMENT ON COLUMN skill_evaluation.script_reuse_score IS '话术复用分(权重20%)';
COMMENT ON COLUMN skill_evaluation.strengths IS '优点JSON数组';
COMMENT ON COLUMN skill_evaluation.improvements IS '改进点JSON数组';
COMMENT ON COLUMN skill_evaluation.demo_script IS '销冠示范话术';

-- 13. 验收
CREATE TABLE skill_acceptance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending','testing','passed','rejected')),
    test_score INT,
    test_detail JSONB,
    test_notes TEXT,
    accepted_by UUID,
    accepted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_accept_skill ON skill_acceptance(skill_id);
COMMENT ON TABLE skill_acceptance IS '分身验收记录';
COMMENT ON COLUMN skill_acceptance.skill_id IS '所属分身ID';
COMMENT ON COLUMN skill_acceptance.status IS '状态: pending→testing→passed/rejected';
COMMENT ON COLUMN skill_acceptance.test_score IS '测试得分';
COMMENT ON COLUMN skill_acceptance.accepted_by IS '验收人ID';
COMMENT ON COLUMN skill_acceptance.accepted_at IS '验收时间';

CREATE TABLE skill_acceptance_question (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    acceptance_id UUID NOT NULL,
    question TEXT NOT NULL,
    expected_points JSONB DEFAULT '[]',
    actual_answer TEXT,
    score INT,
    score_detail JSONB,
    question_order INT DEFAULT 0
);
COMMENT ON TABLE skill_acceptance_question IS '验收考题';
COMMENT ON COLUMN skill_acceptance_question.acceptance_id IS '所属验收记录ID';
COMMENT ON COLUMN skill_acceptance_question.question IS '考题内容';
COMMENT ON COLUMN skill_acceptance_question.expected_points IS '期望得分点JSON';
COMMENT ON COLUMN skill_acceptance_question.actual_answer IS '实际回答';
COMMENT ON COLUMN skill_acceptance_question.score IS '得分';

-- 14. 报告历史
CREATE TABLE report_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,
    version VARCHAR(50) NOT NULL,
    generated_at TIMESTAMP DEFAULT NOW(),
    material_ids TEXT DEFAULT '[]',
    grain_count INT DEFAULT 0,
    metadata TEXT DEFAULT '{}'
);
CREATE INDEX idx_report_history_skill ON report_history(skill_id);
COMMENT ON TABLE report_history IS '报告生成历史';
COMMENT ON COLUMN report_history.skill_id IS '所属分身ID';
COMMENT ON COLUMN report_history.version IS '版本号';
COMMENT ON COLUMN report_history.material_ids IS '关联素材ID列表JSON';
COMMENT ON COLUMN report_history.grain_count IS '颗粒数量';

-- 15. 工具
CREATE TABLE tool (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id UUID,
    report_id UUID,
    type VARCHAR(30) NOT NULL CHECK (type IN ('poster', 'card', 'checklist', 'script')),
    name VARCHAR(200) NOT NULL,
    file_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_tool_space ON tool(space_id);
CREATE INDEX idx_tool_type ON tool(type);
COMMENT ON TABLE tool IS '销售工具(海报/卡片/清单/剧本)';
COMMENT ON COLUMN tool.type IS '类型: poster=海报, card=卡片, checklist=清单, script=剧本';
COMMENT ON COLUMN tool.name IS '工具名称';

-- 16. IM渠道
CREATE TABLE im_channel (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    channel_type VARCHAR(20) NOT NULL CHECK (channel_type IN ('feishu', 'wecom', 'wechat', 'dingtalk')),
    enabled BOOLEAN DEFAULT false,
    config JSONB NOT NULL DEFAULT '{}',
    linked_skills JSONB DEFAULT '[]',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
COMMENT ON TABLE im_channel IS 'IM渠道配置';
COMMENT ON COLUMN im_channel.company_id IS '所属企业ID';
COMMENT ON COLUMN im_channel.channel_type IS '渠道类型: feishu/wecom/wechat/dingtalk';
COMMENT ON COLUMN im_channel.enabled IS '是否启用';
COMMENT ON COLUMN im_channel.config IS '渠道配置JSON';
COMMENT ON COLUMN im_channel.linked_skills IS '关联的分身ID列表JSON';

-- 17. 萃取师
CREATE TABLE expert_skill (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    source_type VARCHAR(20) DEFAULT 'interview' CHECK (source_type IN ('interview', 'document', 'hybrid')),
    style_tags JSONB DEFAULT '[]',
    industry_tags JSONB DEFAULT '[]',
    seniority VARCHAR(50),
    skill_file VARCHAR(500),
    report_id UUID,
    grain_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'analyzing', 'extracting', 'active', 'failed')),
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
COMMENT ON TABLE expert_skill IS '萃取师技能(元萃取)';
COMMENT ON COLUMN expert_skill.id IS '技能ID';
COMMENT ON COLUMN expert_skill.name IS '技能名称';
COMMENT ON COLUMN expert_skill.source_type IS '来源类型: interview=访谈, document=文档';
COMMENT ON COLUMN expert_skill.style_tags IS '风格标签JSON';
COMMENT ON COLUMN expert_skill.industry_tags IS '行业标签JSON';
COMMENT ON COLUMN expert_skill.status IS '状态: pending→analyzing→extracting→active';
COMMENT ON COLUMN expert_skill.locked_by IS '处理锁持有者';
COMMENT ON COLUMN expert_skill.grain_count IS '已提取颗粒数';

CREATE TABLE expert_grain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expert_id UUID NOT NULL,
    category VARCHAR(50) NOT NULL CHECK (category IN ('judgment_intuition', 'mental_model', 'failure_lesson', 'validation_method', 'metaphor_framework', 'rhythm_sense', 'typing_method')),
    source_type VARCHAR(20) DEFAULT 'interview' CHECK (source_type IN ('interview', 'document')),
    scene_description TEXT,
    knowledge_content TEXT NOT NULL,
    application_rule TEXT,
    priority INT DEFAULT 1 CHECK (priority BETWEEN 1 AND 5),
    consensus_type VARCHAR(20) DEFAULT 'single' CHECK (consensus_type IN ('single', 'consensus', 'conflict')),
    consensus_expert_ids JSONB DEFAULT '[]',
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'under_review', 'deprecated')),
    embedding VECTOR(1024),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_expert_grain_category ON expert_grain(category);
CREATE INDEX idx_expert_grain_expert ON expert_grain(expert_id);
CREATE INDEX idx_expert_grain_status ON expert_grain(status);
COMMENT ON TABLE expert_grain IS '萃取师知识颗粒';
COMMENT ON COLUMN expert_grain.expert_id IS '所属技能ID';
COMMENT ON COLUMN expert_grain.category IS '分类: judgment_intuition/mental_model/failure_lesson等';
COMMENT ON COLUMN expert_grain.knowledge_content IS '知识内容';
COMMENT ON COLUMN expert_grain.priority IS '优先级(1-5)';
COMMENT ON COLUMN expert_grain.consensus_type IS '共识类型: single=单人, consensus=共识, conflict=冲突';
COMMENT ON COLUMN expert_grain.embedding IS '向量嵌入(1024维)';

CREATE TABLE expert_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expert_id UUID NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    file_url VARCHAR(500),
    file_type VARCHAR(50),
    file_size BIGINT,
    parsed_content TEXT,
    status VARCHAR(20) DEFAULT 'uploaded' CHECK (status IN ('uploaded', 'parsing', 'parsed', 'pending_manual', 'failed')),
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_expert_document_expert ON expert_document(expert_id);
COMMENT ON TABLE expert_document IS '萃取师素材文档';
COMMENT ON COLUMN expert_document.expert_id IS '所属技能ID';
COMMENT ON COLUMN expert_document.status IS '状态: uploaded→parsing→parsed/failed, pending_manual=待人工';

-- =============================================
-- 开发期追加（原 V3-V7 合并）
-- =============================================

-- 1. skill_message 增加 role_label 列（原 V3）
ALTER TABLE skill_message ADD COLUMN IF NOT EXISTS role_label VARCHAR(20);
COMMENT ON COLUMN skill_message.role_label IS '角色展示名: 我 / 销冠 / 客户 / 我（销冠）';

-- 2. skill_conversation.mode 扩展检查约束（原 V4）
ALTER TABLE skill_conversation DROP CONSTRAINT IF EXISTS skill_conversation_mode_check;
ALTER TABLE skill_conversation ADD CONSTRAINT skill_conversation_mode_check
    CHECK (mode IN ('qa', 'practice', 'quick', 'discuss', 'talk'));

-- 3. skill 表 domain 列 + skill_material 表 material_type 列（原 V4 migration）
ALTER TABLE skill ADD COLUMN IF NOT EXISTS domain VARCHAR(50);
COMMENT ON COLUMN skill.domain IS '领域ID，如 sales.b2b_enterprise / finance.secondary_market';

ALTER TABLE skill_material ADD COLUMN IF NOT EXISTS material_type VARCHAR(20);
COMMENT ON COLUMN skill_material.material_type IS '素材类型: dialogue=对话, monologue=独白/心得, interview=访谈';

-- 4. 调度器扫描索引（原 V5）
CREATE INDEX IF NOT EXISTS idx_skill_material_status_locked ON skill_material (status, locked_at);
CREATE INDEX IF NOT EXISTS idx_expert_skill_status_locked ON expert_skill (status, locked_at);
CREATE INDEX IF NOT EXISTS idx_experience_grain_space_status ON experience_grain (space_id, status);

-- 5. GIN trigram 索引 — LIKE '%kw%' 前导通配符仍命中索引（原 V6）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_report_title_trgm ON report USING gin (title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_report_subtitle_trgm ON report USING gin (subtitle gin_trgm_ops);

-- 6. user 表 UNIQUE(company_id, account) 约束（原 V7）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_user_company_account'
    ) THEN
        ALTER TABLE "user" ADD CONSTRAINT uq_user_company_account UNIQUE (company_id, account);
    END IF;
END $$;

-- 1. 默认企业
INSERT INTO company (id, name, logo_url, brand_color) VALUES
('c0000000-0000-0000-0000-000000000001', '默认企业', NULL, '#1A2B4C');

-- 2. 默认管理员（密码: admin123）
INSERT INTO "user" (id, company_id, name, role, account, password_hash, is_active, created_at, updated_at) VALUES
('00000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', '系统管理员', 'super_admin', 'admin', '$2b$10$80bLpb/rrOHpaMkb7/Bowe/0FMFGmjxSK1wNOHj044tymecaSNmFe', true, NOW(), NOW());
