# Phase 1 执行手册 v2.2

> 修订: 表注释 + 缺口真实记录 + 前端图表设计 + Admin 编辑设计 + 依赖

---

## 0. 新增依赖

### 前端

```bash
cd frontend && npm install recharts
```

`package.json` 新增一行，tree-shaken 后约 40KB。

### 后端

无新依赖。Spring Data JPA + Flyway 已有。

---

## 1. 建表

### 1.1 knowledge_gap

**触发条件（精确）**:

```
ChatStreamService.retrieveGrainsWithScores() 内:

① pgvector HNSW 检索 → 返回空列表 scored.isEmpty() == true
② 用户消息长度 ≥ 5 字符（过滤"继续""嗯""好""哦"等口语衔接词）
③ 同时满足 → 写入一条

不触发:
- RAG 至少命中 1 条 → 不触发
- 消息长度 < 5 → 不触发
- 非用户消息 → 不触发
```

**判断"未发现颗粒"**: `scored.isEmpty()`。Pgvector `ORDER BY embedding <=> query_vector` 返回空。

```sql
-- V2__create_knowledge_gap.sql

COMMENT ON TABLE knowledge_gap IS '知识缺口——用户提问后RAG检索无匹配颗粒时记录，用于发现分身知识盲区';

CREATE TABLE knowledge_gap (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,                              -- 哪个分身
    space_id UUID NOT NULL,                              -- 哪个空间
    query TEXT NOT NULL,                                 -- 用户提问原文
    scene_tag VARCHAR(100),                              -- 推测的场景标签
    attempted_query_count INT NOT NULL DEFAULT 1,        -- 该场景累计出现次数(写入时 COUNT+1)
    status VARCHAR(20) NOT NULL DEFAULT 'open',          -- open/reviewing/resolved/ignored
    resolved_by VARCHAR(100),
    resolved_at TIMESTAMP,
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN knowledge_gap.id IS '主键';
COMMENT ON COLUMN knowledge_gap.skill_id IS '所属分身ID';
COMMENT ON COLUMN knowledge_gap.space_id IS '所属空间ID';
COMMENT ON COLUMN knowledge_gap.query IS '用户提问原文';
COMMENT ON COLUMN knowledge_gap.scene_tag IS '系统推测的场景标签';
COMMENT ON COLUMN knowledge_gap.attempted_query_count IS '该场景累计出现次数(写入时计算)';
COMMENT ON COLUMN knowledge_gap.status IS '状态: open/reviewing/resolved/ignored';
COMMENT ON COLUMN knowledge_gap.resolved_by IS '处理人';
COMMENT ON COLUMN knowledge_gap.resolved_at IS '处理时间';
COMMENT ON COLUMN knowledge_gap.note IS '管理员备注';

CREATE INDEX idx_kg_skill_status ON knowledge_gap(skill_id, status);
CREATE INDEX idx_kg_skill_time ON knowledge_gap(skill_id, created_at DESC);
CREATE INDEX idx_kg_space ON knowledge_gap(space_id);
```

**设计说明**: 不做 UNIQUE 约束，每次都写新行。后续按 `scene_tag GROUP BY` 聚合即可看趋势。`attempted_query_count` 写入时通过 `SELECT COUNT(*) WHERE skill_id=? AND scene_tag=?` + 1 计算。

### 1.2 feedback_log

```sql
-- V3__create_feedback_log.sql

COMMENT ON TABLE feedback_log IS '用户反馈记录——每次打分完整留存';

CREATE TABLE feedback_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,                              -- 哪个分身
    conversation_id UUID,                                -- 哪次对话
    message_id UUID,                                     -- 哪条AI消息
    user_id UUID,                                        -- 哪个用户
    grain_id UUID,                                       -- 关联颗粒(NULL=无匹配时的打分)
    rating VARCHAR(10) NOT NULL,                         -- 'up'/'down'
    query TEXT,                                          -- 用户当时的提问
    ai_response VARCHAR(500),                            -- AI回答前500字
    rag_score DOUBLE PRECISION,                          -- 当时RAG平均匹配度
    source VARCHAR(20) NOT NULL DEFAULT 'user',          -- 'user'/'backfill'
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN feedback_log.id IS '主键';
COMMENT ON COLUMN feedback_log.skill_id IS '所属分身ID';
COMMENT ON COLUMN feedback_log.conversation_id IS '所属对话ID';
COMMENT ON COLUMN feedback_log.message_id IS 'AI消息ID';
COMMENT ON COLUMN feedback_log.user_id IS '打分用户ID';
COMMENT ON COLUMN feedback_log.grain_id IS '关联颗粒(NULL=无匹配时的打分)';
COMMENT ON COLUMN feedback_log.rating IS '评分: up=有帮助, down=没帮助';
COMMENT ON COLUMN feedback_log.query IS '用户当时的提问原文';
COMMENT ON COLUMN feedback_log.ai_response IS 'AI回答截取前500字';
COMMENT ON COLUMN feedback_log.rag_score IS '回答时的RAG平均匹配度';
COMMENT ON COLUMN feedback_log.source IS '来源: user=用户, backfill=存量迁移';

CREATE INDEX idx_fl_skill_time ON feedback_log(skill_id, created_at DESC);
CREATE INDEX idx_fl_grain ON feedback_log(grain_id) WHERE grain_id IS NOT NULL;
CREATE INDEX idx_fl_rating ON feedback_log(skill_id, rating);
```

### 1.3 conversation_stats

```sql
-- V4__create_conversation_stats.sql

COMMENT ON TABLE conversation_stats IS '对话统计——每次AI回复一条，飞轮报表单一数据源';

CREATE TABLE conversation_stats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,                              -- 哪个分身
    conversation_id UUID NOT NULL,                       -- 哪次对话
    user_id UUID,                                        -- 哪个用户
    mode VARCHAR(20) NOT NULL,                           -- qa/discuss/talk/practice/enterprise
    rag_high_count INT NOT NULL DEFAULT 0,              -- 高匹配颗粒数
    rag_ref_count INT NOT NULL DEFAULT 0,               -- 参考匹配颗粒数
    rag_none_count INT NOT NULL DEFAULT 0,              -- 无匹配次数
    rag_avg_similarity DOUBLE PRECISION,                -- 平均相似度
    feedback_up INT NOT NULL DEFAULT 0,                  -- 👍次数
    feedback_down INT NOT NULL DEFAULT 0,                -- 👎次数
    error_type VARCHAR(20),                              -- NULL=正常/timeout/error/cancelled
    is_test BOOLEAN NOT NULL DEFAULT FALSE,              -- Admin测试标记
    llm_duration_ms INT,                                 -- LLM生成耗时
    total_duration_ms INT,                               -- 端到端耗时
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN conversation_stats.id IS '主键';
COMMENT ON COLUMN conversation_stats.skill_id IS '所属分身ID';
COMMENT ON COLUMN conversation_stats.conversation_id IS '对话ID';
COMMENT ON COLUMN conversation_stats.user_id IS '用户ID';
COMMENT ON COLUMN conversation_stats.mode IS '模式: qa/discuss/talk/practice/enterprise';
COMMENT ON COLUMN conversation_stats.rag_high_count IS '高匹配颗粒数';
COMMENT ON COLUMN conversation_stats.rag_ref_count IS '参考匹配颗粒数';
COMMENT ON COLUMN conversation_stats.rag_none_count IS '无匹配次数';
COMMENT ON COLUMN conversation_stats.rag_avg_similarity IS '平均相似度';
COMMENT ON COLUMN conversation_stats.error_type IS '异常类型';
COMMENT ON COLUMN conversation_stats.is_test IS '是否Admin测试对话';
COMMENT ON COLUMN conversation_stats.llm_duration_ms IS 'LLM生成耗时ms';
COMMENT ON COLUMN conversation_stats.total_duration_ms IS '端到端总耗时ms';

CREATE INDEX idx_cs_skill_time ON conversation_stats(skill_id, created_at DESC);
CREATE INDEX idx_cs_conv ON conversation_stats(conversation_id);
CREATE INDEX idx_cs_skill_mode ON conversation_stats(skill_id, mode);
```

### 1.4 grain_retrieve_log

```sql
-- V5__create_grain_retrieve_log.sql

COMMENT ON TABLE grain_retrieve_log IS 'RAG检索日志——每次命中记录，30天自动清理';

CREATE TABLE grain_retrieve_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID NOT NULL,                              -- 哪个分身
    conversation_id UUID NOT NULL,                       -- 哪次对话
    original_query TEXT,                                 -- 用户原始提问
    rewritten_query TEXT,                                -- LLM改写后查询
    grain_id UUID NOT NULL,                              -- 命中颗粒
    scene_tag VARCHAR(100),                              -- 颗粒场景标签
    similarity DOUBLE PRECISION NOT NULL,                -- 余弦相似度
    tier VARCHAR(10),                                    -- high/ref/NULL
    position INT NOT NULL,                               -- 1-based rank
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN grain_retrieve_log.id IS '主键';
COMMENT ON COLUMN grain_retrieve_log.skill_id IS '所属分身ID';
COMMENT ON COLUMN grain_retrieve_log.conversation_id IS '所属对话ID';
COMMENT ON COLUMN grain_retrieve_log.original_query IS '用户原始提问';
COMMENT ON COLUMN grain_retrieve_log.rewritten_query IS 'LLM改写后查询';
COMMENT ON COLUMN grain_retrieve_log.grain_id IS '命中颗粒ID';
COMMENT ON COLUMN grain_retrieve_log.scene_tag IS '颗粒场景标签';
COMMENT ON COLUMN grain_retrieve_log.similarity IS '余弦相似度';
COMMENT ON COLUMN grain_retrieve_log.tier IS '分层: high/ref/NULL';
COMMENT ON COLUMN grain_retrieve_log.position IS '排名';

CREATE INDEX idx_grl_skill_time ON grain_retrieve_log(skill_id, created_at DESC);
CREATE INDEX idx_grl_grain ON grain_retrieve_log(grain_id);
CREATE INDEX idx_grl_conv ON grain_retrieve_log(conversation_id);
```

清理: `@Scheduled(cron="0 0 3 * * ?")` → `DELETE WHERE created_at < NOW() - INTERVAL '30 days'`

### 1.5 analytics_event

```sql
-- V6__create_analytics_event.sql

COMMENT ON TABLE analytics_event IS '前端埋点——用户行为追踪，30天清理';

CREATE TABLE analytics_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id UUID,
    conversation_id UUID,
    user_id UUID,
    event_type VARCHAR(50) NOT NULL,                     -- recommendation_show/click, mode_switch, conversation_end
    event_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN analytics_event.id IS '主键';
COMMENT ON COLUMN analytics_event.skill_id IS '关联分身';
COMMENT ON COLUMN analytics_event.conversation_id IS '关联对话';
COMMENT ON COLUMN analytics_event.user_id IS '用户';
COMMENT ON COLUMN analytics_event.event_type IS '事件类型';
COMMENT ON COLUMN analytics_event.event_data IS '事件数据JSONB';

CREATE INDEX idx_ae_skill_time ON analytics_event(skill_id, created_at DESC);
CREATE INDEX idx_ae_type ON analytics_event(event_type);
```

### 1.6 admin_audit_log

```sql
-- V7__create_admin_audit_log.sql

COMMENT ON TABLE admin_audit_log IS '管理员操作审计——Phase 3开始写入';

CREATE TABLE admin_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL,                         -- edit_grain/deprecate_grain/create_grain/resolve_gap
    target_type VARCHAR(50) NOT NULL,                    -- grain/gap/prompt/domain
    target_id UUID,
    detail JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN admin_audit_log.id IS '主键';
COMMENT ON COLUMN admin_audit_log.admin_id IS '操作人ID';
COMMENT ON COLUMN admin_audit_log.action IS '操作类型';
COMMENT ON COLUMN admin_audit_log.target_type IS '对象类型';
COMMENT ON COLUMN admin_audit_log.target_id IS '对象ID';
COMMENT ON COLUMN admin_audit_log.detail IS '详情JSONB';

CREATE INDEX idx_aal_admin_time ON admin_audit_log(admin_id, created_at DESC);
```

---

## 2. 前端图表设计

### 2.1 新增依赖

```bash
cd frontend && npm install recharts
```

`package.json` 加一行 `"recharts": "^2.12.0"`。tree-shaken 约 40KB。

### 2.2 Admin 仪表盘布局

```
┌──────────────────────────────────────────────────────────────┐
│  🏠 仪表盘                     [分身选择器 ▽]  [本周 ▼]     │
├──────────────────────────────────────────────────────────────┤
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ 💬 对话量 │ │ 👥 用户  │ │ 👍 满意率│ │ 📅 留存  │       │
│  │  1,247   │ │  38 人   │ │  87.3%   │ │  62%     │       │
│  │  ↑12%    │ │  ↑5人    │ │  ↑3.2%   │ │  ↑8%     │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│                                                              │
│  ┌────────────────────────┐ ┌──────────────────────────┐    │
│  │ 📊 场景 TOP5           │ │ 🎯 RAG 匹配分布           │    │
│  │         ██████         │ │      ◉ 🟢高匹配 42%      │    │
│  │    ████████████        │ │    ◉   🟡参考 28%        │    │
│  │   ██████████           │ │   ◉    ⚪无匹配 18%       │    │
│  │  ████████              │ │  ◉     🔴缺口 12%        │    │
│  │  ██████                │ │                          │    │
│  │  政 价 异 决 需        │ │                          │    │
│  └────────────────────────┘ └──────────────────────────┘    │
│                                                              │
│  ┌────────────────────────┐ ┌──────────────────────────┐    │
│  │ ⭐ 👍 最佳颗粒          │ │ ⚠️ 👎 待优化             │    │
│  │ 1. 首次报价留20%  👍47 │ │ 1. 竞品功能对比    👎8  │    │
│  │ 2. 先诊断再报价  👍41 │ │ 2. 低价客户筛选    👎5  │    │
│  │ 3. 找关键人方法  👍38 │ │ 3. SaaS续约话术    👎3  │    │
│  └────────────────────────┘ └──────────────────────────┘    │
│                                                              │
│  🔴 待处理缺口 (12个)                                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ "SaaS续约怎么谈" 15次 | 场景:价格谈判 | [补充][忽略] │   │
│  │ "技术负责人搞定" 8次  | 场景:决策推进 | [补充][忽略] │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

### 2.3 组件结构

```
AdminDashboardPage
├── SkillSelector              // 下拉选择分身
├── DateRangePicker            // 本周/本月/自定义
├── StatCards                  // 4个汇总卡片 (纯Tailwind)
├── ChartsRow
│   ├── SceneBarChart          // Recharts <BarChart>
│   └── RagPieChart            // Recharts <PieChart>
├── GrainRankings
│   ├── BestGrainsTable        // 纯Tailwind <table>
│   └── WorstGrainsTable       // 纯Tailwind <table>
└── KnowledgeGapsPanel
    └── GapCard[]              // 缺口卡片列表
```

### 2.4 汇总卡片（纯 Tailwind，零依赖）

```tsx
function StatCards({ stats }: { stats: StatItem[] }) {
  return (
    <div className="grid grid-cols-4 gap-4">
      {stats.map(s => (
        <div key={s.label} className="rounded-xl bg-surface-2 p-5 shadow-sm">
          <p className="text-sm text-muted-foreground">{s.label}</p>
          <p className="text-2xl font-bold mt-1">{s.value}</p>
          <p className={`text-xs mt-1 ${s.trend > 0 ? 'text-green-600' : 'text-red-500'}`}>
            {s.trend > 0 ? '↑' : '↓'}{Math.abs(s.trend)}{s.unit} 较上周
          </p>
        </div>
      ))}
    </div>
  );
}
```

### 2.5 场景 TOP5 柱状图（Recharts `<BarChart>`）

```tsx
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

function SceneBarChart({ data }: { data: { scene: string; count: number }[] }) {
  return (
    <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
      <h3 className="text-sm font-semibold mb-3">📊 场景提问 TOP5</h3>
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={data} margin={{ top: 5, right: 20, bottom: 5, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis dataKey="scene" tick={{ fontSize: 12 }} />
          <YAxis tick={{ fontSize: 12 }} />
          <Tooltip
            contentStyle={{ borderRadius: 8, border: '1px solid #e5e7eb', fontSize: 13 }}
            formatter={(value: number) => [`${value} 次`, '提问量']}
          />
          <Bar dataKey="count" fill="#165DFF" radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```

渲染: 蓝色圆角柱，悬停显示 "312 次 提问量"。

**数据查询** (`AdminInsightController`):
```java
@GetMapping("/{skillId}/scene-top")
public ApiResponse<List<Map<String, Object>>> getSceneTop(@PathVariable UUID skillId) {
    // 从 grain_retrieve_log 聚合按 scene_tag 分组计数
    List<Object[]> rows = grainRetrieveLogRepository.countBySkillIdGroupBySceneTag(skillId);
    return ApiResponse.success(rows.stream().map(row -> Map.of(
        "scene", row[0], "count", row[1]
    )).collect(Collectors.toList()));
}
```

### 2.6 RAG 分布饼图（Recharts `<PieChart>`）

```tsx
import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from 'recharts';

const RAG_COLORS = { '高匹配': '#22C55E', '参考': '#EAB308', '无匹配': '#9CA3AF', '缺口': '#EF4444' };

function RagPieChart({ data }: { data: { name: string; value: number }[] }) {
  return (
    <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
      <h3 className="text-sm font-semibold mb-3">🎯 RAG 匹配分布</h3>
      <ResponsiveContainer width="100%" height={220}>
        <PieChart>
          <Pie data={data} cx="50%" cy="50%" innerRadius={50} outerRadius={85}
            paddingAngle={3} dataKey="value">
            {data.map((entry) => (
              <Cell key={entry.name} fill={RAG_COLORS[entry.name] || '#6B7280'} />
            ))}
          </Pie>
          <Tooltip />
          <Legend iconType="circle" />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
```

环形图，四种颜色区分匹配等级。数据从 `conversation_stats` 汇总。

### 2.7 颗粒排行榜表格（纯 Tailwind）

```tsx
function GrainRankTable({ grains, type }: { grains: GrainRank[]; type: 'best' | 'worst' }) {
  return (
    <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
      <h3 className="text-sm font-semibold mb-3">
        {type === 'best' ? '⭐ 最佳颗粒' : '⚠️ 待优化颗粒'}
      </h3>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-muted-foreground">
            <th className="text-left py-2 font-medium">颗粒描述</th>
            <th className="text-right py-2 font-medium w-16">👍</th>
            <th className="text-right py-2 font-medium w-16">👎</th>
          </tr>
        </thead>
        <tbody>
          {grains.map((g, i) => (
            <tr key={g.id}
              className="border-b border-border/50 hover:bg-primary-light/50 cursor-pointer transition-colors"
              onClick={() => openGrainDetail(g.id)}>
              <td className="py-2.5">
                <span className="text-muted-foreground mr-2">{i + 1}.</span>
                {g.description}
              </td>
              <td className="text-right text-green-600 font-medium">{g.helpful}</td>
              <td className="text-right text-red-400">{g.unhelpful}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

### 2.8 缺口卡片列表

```tsx
function KnowledgeGapsPanel({ gaps }: { gaps: GapItem[] }) {
  return (
    <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold">🔴 待处理知识缺口 ({gaps.length}个)</h3>
      </div>
      <div className="space-y-2">
        {gaps.map(g => (
          <div key={g.id}
            className="flex items-center justify-between rounded-lg border border-border/50 p-3 hover:bg-primary-light/30 transition-colors">
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">"{g.query}"</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                出现 {g.count} 次 · 场景: {g.sceneTag || '未知'} · 最近: {g.lastSeen}
              </p>
            </div>
            <div className="flex gap-2 ml-4 flex-shrink-0">
              <button onClick={() => handleSupplement(g)}
                className="text-xs bg-primary/10 text-primary rounded-lg px-3 py-1.5 hover:bg-primary/20">
                补充颗粒
              </button>
              <button onClick={() => handleIgnore(g)}
                className="text-xs bg-muted rounded-lg px-3 py-1.5 hover:bg-border">
                忽略
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
```

### 2.9 反馈审查列表

```
┌──────────────────────────────────────────────────────────────┐
│  📋 反馈审查                     [筛选: 👎 ▼]  [日期 ▼]    │
├──────────────────────────────────────────────────────────────┤
│  👎 2小时前 · 用户: 张* · 场景: 价格谈判                     │
│  Q: "客户说你们比竞品贵30%怎么办？"                           │
│  A: "不降价、算总账。您算一笔账..."                           │
│  🔗 颗粒: 竞品功能对比话术 · RAG匹配: 68%                    │
│  [查看原始对话] [查看颗粒详情] [标记已处理]                   │
├──────────────────────────────────────────────────────────────┤
│  👎 昨天 · 用户: 李* · 场景: — (无匹配)                      │
│  Q: "怎么写Python爬虫"                                       │
│  A: "写代码我不擅长，我是做销售的..."                         │
│  🔗 无关联颗粒 · 推荐问题已展示                               │
│  [标记已处理]                                                │
└──────────────────────────────────────────────────────────────┘
```

```tsx
function FeedbackList({ items }: { items: FeedbackItem[] }) {
  return (
    <div className="space-y-3">
      {items.map(f => (
        <div key={f.id} className="rounded-xl bg-surface-2 p-4 shadow-sm">
          <div className="flex items-center gap-2 mb-2">
            <span className={`text-sm font-medium ${f.rating === 'down' ? 'text-red-500' : 'text-green-600'}`}>
              {f.rating === 'down' ? '👎' : '👍'}
            </span>
            <span className="text-xs text-muted-foreground">{f.time} · 用户: {f.userName} · 场景: {f.sceneTag || '—'}</span>
          </div>
          <p className="text-sm"><span className="text-muted-foreground">Q:</span> {f.query}</p>
          <p className="text-sm mt-1"><span className="text-muted-foreground">A:</span> {f.aiResponse}</p>
          <div className="flex items-center gap-3 mt-2 text-xs text-muted-foreground">
            <span>🔗 颗粒: {f.grainTitle || '无关联颗粒'}</span>
            {f.ragScore && <span>RAG匹配: {Math.round(f.ragScore * 100)}%</span>}
          </div>
          <div className="flex gap-2 mt-2">
            <button className="text-xs text-primary hover:underline">查看原始对话</button>
            {f.grainId && <button className="text-xs text-primary hover:underline">查看颗粒详情</button>}
            <button className="text-xs text-muted-foreground hover:underline">标记已处理</button>
          </div>
        </div>
      ))}
    </div>
  );
}
```

### 2.10 颗粒编辑器（Phase 1 查看模式，Phase 3 编辑模式）

**查看模式**（Phase 1 实现）:
```tsx
function GrainDetail({ grain }: { grain: GrainDetail }) {
  return (
    <div className="rounded-xl bg-surface-2 p-6 shadow-sm max-w-2xl">
      <h2 className="text-lg font-bold mb-2">🔍 {grain.sceneDescription || grain.sceneTag}</h2>
      <p className="text-xs text-muted-foreground mb-4">
        场景: {grain.sceneTag} · 质量评分: {grain.qualityScore}/5 · 👍{grain.helpfulCount} 👎{grain.unhelpfulCount} · 状态: {grain.status}
      </p>

      <Field label="🧠 专家思考" value={grain.expertThought} />
      <Field label="💬 标准话术" value={grain.standardScript} />
      <Field label="⚠️ 常见错误" value={grain.commonMistakes} />
      <Field label="📌 适用条件" value={grain.applicableCondition} />

      <div className="flex gap-2 mt-4 pt-4 border-t border-border">
        <button className="text-sm bg-primary text-white rounded-lg px-4 py-2">编辑颗粒</button>
        <button className="text-sm text-red-500 rounded-lg px-4 py-2 hover:bg-red-50">标记废弃</button>
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value?: string }) {
  return (
    <div className="mb-3">
      <p className="text-xs text-muted-foreground mb-1">{label}</p>
      <p className="text-sm bg-surface p-3 rounded-lg">{value || '（未填写）'}</p>
    </div>
  );
}
```

**编辑模式**（Phase 3，此处为设计蓝图）:
```tsx
function GrainEditor({ grain, onSave }: Props) {
  const [form, setForm] = useState({
    expertThought: grain.expertThought || '',
    standardScript: grain.standardScript || '',
    commonMistakes: grain.commonMistakes || '',
    applicableCondition: grain.applicableCondition || '',
  });
  const [reason, setReason] = useState('');

  return (
    <div className="rounded-xl bg-surface-2 p-6 shadow-sm max-w-2xl">
      <h2 className="text-lg font-bold mb-4">✏️ 编辑颗粒</h2>

      <EditField label="🧠 专家思考" value={form.expertThought}
        onChange={v => setForm(p => ({ ...p, expertThought: v }))} />
      <EditField label="💬 标准话术" value={form.standardScript}
        onChange={v => setForm(p => ({ ...p, standardScript: v }))} />
      <EditField label="⚠️ 常见错误" value={form.commonMistakes}
        onChange={v => setForm(p => ({ ...p, commonMistakes: v }))} />
      <EditField label="📌 适用条件" value={form.applicableCondition}
        onChange={v => setForm(p => ({ ...p, applicableCondition: v }))} />

      <div className="mt-4 pt-4 border-t border-border">
        <label className="text-xs text-muted-foreground mb-1 block">📝 修改说明（必填）</label>
        <input value={reason} onChange={e => setReason(e.target.value)}
          className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="为什么改这个颗粒？" />
      </div>

      <div className="flex gap-2 mt-4">
        <button className="text-sm rounded-lg px-4 py-2 border">取消</button>
        <button disabled={!reason} onClick={() => onSave(form, reason)}
          className="text-sm bg-primary text-white rounded-lg px-4 py-2 disabled:opacity-40">
          保存并重新生成向量
        </button>
      </div>
    </div>
  );
}
```

保存流程: `PUT /admin/grains/{id}` → 更新 4 字段 → 写 grain_edit_history → 重新 embed → 写 admin_audit_log。

---

## 3. 技能修改（完整设计）

技能修改 = **颗粒修改** + **画像修改** + **提示词修改**。三者覆盖了"分身不够聪明"的全部根源。

### 3.1 修改入口总览

```
Admin 分身调优页面
├── 📊 仪表盘          → 看数据，发现问题
├── 📋 反馈审查         → 看被踩的回答，定位具体颗粒
├── 🔍 颗粒管理         → 查看/编辑/废弃/新增颗粒
├── 👤 画像调整         → 修改 personality/speaking_style 等
├── 📝 提示词管理       → 编辑 prompt 模板(Phase 3+)
└── 🧪 测试对话         → 改完立即验证
```

### 3.2 修改类型详解

#### A. 颗粒修改（Phase 1 查看 + Phase 3 编辑）

**从哪里进入**:
- 仪表盘 👎TOP 颗粒榜 → 点击颗粒 → 查看详情 → 点"编辑"
- 反馈审查列表 → 点击"查看颗粒详情" → 点"编辑"
- 颗粒管理页面 → 搜索/筛选 → 点击编辑

**可修改的字段**:

| 字段 | 数据库列 | 示例 | 修改后影响 |
|------|---------|------|-----------|
| 专家思考 | `expert_thought` | "不能直接降价，先量化损失感知..." | AI 回答的判断逻辑更准 |
| 标准话术 | `standard_script` | "王总我理解，咱们算一笔账..." | AI 引用的原话更自然 |
| 常见错误 | `common_mistakes` | "新人直接报折扣价，没问对比版本" | AI 警告更准确 |
| 适用条件 | `applicable_condition` | "竞品相同功能级别时有效" | AI 知道何时用这个话术 |
| 场景标签 | `scene_tag` | 从"通用"改为"异议处理" | RAG 检索更精准 |
| 权重 | `weight` | 从 1.0 改为 1.5 | 该颗粒检索时更容易被命中 |

**修改流程**:
```
点击编辑 → 修改字段 → 填写修改原因 → 保存
  → ① UPDATE experience_grain SET ...
  → ② INSERT grain_edit_history (旧值+新值)
  → ③ 重新 embed(expert_thought + standard_script)
  → ④ UPDATE embedding column
  → ⑤ INSERT admin_audit_log
  → ⑥ 失效相关缓存 → 下次对话生效
```

**API**: `PUT /admin/grains/{id}` — 接收 Part A 的可修改字段
**API**: `POST /admin/grains` — 新增颗粒（管理员手动补充）
**API**: `POST /admin/grains/{id}/deprecate` — 标记废弃

#### B. 画像修改（Phase 3）

**从哪里进入**: 分身调优 → 画像调整

**可修改的字段**（存在 `skill_profile` 表）:

| 字段 | 数据库列 | 示例 | 修改后影响 |
|------|---------|------|-----------|
| 性格 | `personality` | "直接、务实，不讲虚的" | AI 回答的语气和态度 |
| 说话风格 | `speaking_style` | "用短句，口语化，偶尔带点幽默" | AI 的表达方式 |
| 背景 | `background` | "10年传统行业高客单销售" | AI 自我介绍和案例参照 |
| 口头禅 | `common_phrases` | "咱们算笔账""这个事儿吧" | AI 的语言特点 |
| 擅长领域 | `knowledge_domains` | "政府销售、B2B大客户" | AI 知识域边界 |
| 技能标签 | `skill_tags` | "谈判专家、关系高手" | AI 自我定位 |
| 适用场景 | `target_scenarios` | "价格谈判、决策推进" | AI 场景覆盖 |
| 沟通偏好 | `communication_preferences` | "先给结论再解释、用数据说话" | AI 回答结构 |

**修改流程**:
```
Admin 打开画像调整 → 表单预填当前值 → 修改 → 保存
  → UPDATE skill_profile SET ...
  → 下次对话 System Prompt 自动使用新画像
  → 无需重启，无需重新 embedding
```

**API**: `PUT /admin/skills/{skillId}/profile`

**画像编辑 UI**:
```tsx
function ProfileEditor({ profile, skillId, onSave }: Props) {
  return (
    <div className="rounded-xl bg-surface-2 p-6 shadow-sm max-w-2xl">
      <h2 className="text-lg font-bold mb-1">👤 画像调整</h2>
      <p className="text-sm text-muted-foreground mb-4">
        修改后下次对话立即生效，无需重新萃取
      </p>

      <div className="space-y-4">
        <EditField label="性格 (personality)" value={profile.personality}
          hint="AI 回答的语气和态度。例如: 直接务实/耐心细致/幽默风趣"
          onChange={v => setForm(p => ({ ...p, personality: v }))} />

        <EditField label="说话风格 (speaking_style)" value={profile.speakingStyle}
          hint="AI 的表达方式。例如: 短句口语化/结构化分点/讲故事风格"
          onChange={v => setForm(p => ({ ...p, speakingStyle: v }))} />

        <EditField label="背景 (background)" value={profile.background}
          hint="以第一人称写。例如: 我做了10年传统行业高客单销售..."
          onChange={v => setForm(p => ({ ...p, background: v }))} />

        <EditField label="口头禅 (common_phrases)" value={profile.commonPhrases}
          hint="逗号分隔。例如: 咱们算笔账, 这个事儿吧, 说白了就是"
          onChange={v => setForm(p => ({ ...p, commonPhrases: v }))} />

        <EditField label="擅长领域 (knowledge_domains)" value={profile.knowledgeDomains}
          hint="决定 AI 的知识边界。例如: 政府销售、B2B大客户、价格谈判"
          onChange={v => setForm(p => ({ ...p, knowledgeDomains: v }))} />

        <EditField label="沟通偏好 (communication_preferences)" value={profile.communicationPrefs}
          hint="例如: 先给结论再解释、用数据说话、喜欢用比喻"
          onChange={v => setForm(p => ({ ...p, communicationPrefs: v }))} />
      </div>

      <div className="flex gap-2 mt-6 pt-4 border-t border-border">
        <button className="text-sm rounded-lg px-4 py-2 border">取消</button>
        <button onClick={() => onSave(form)}
          className="text-sm bg-primary text-white rounded-lg px-6 py-2">
          保存画像
        </button>
      </div>
    </div>
  );
}
```

#### C. 提示词修改（Phase 3+）

**从哪里进入**: 分身调优 → 提示词管理（未来 DB 持久化后）

**可修改的内容**: 当前 32 个在用 .md 文件。Admin 选择模板 → 编辑 → 发布新版本 → 即时生效。

---

### 3.3 技能修改的触发路径

```
数据 → 洞察 → 行动

路径 1: 低分颗粒
  dashboard 👎TOP → 点颗粒 → 看原始对话 → 判断问题
    → 颗粒话术不好 → 编辑颗粒(Part A)
    → 颗粒逻辑不对 → 编辑专家思考(Part A)

路径 2: 画像不对
  dashboard 满意率下降 → 对话抽样 → 发现 AI 语气不像真人
    → 画像调整(Part B) → 改 personality/speaking_style

路径 3: 知识缺失
  dashboard 缺口列表 → 看高频缺口 → 判断是否需要补充
    → 有现成素材 → 上传素材重新萃取
    → 无素材 → 手动新增颗粒(Part A 的新增功能)

路径 4: 全局行为异常
  dashboard 满意率骤降 → 排查不是颗粒/画像问题
    → 提示词修改(Part C) → 调整全局行为规则
```

### 3.4 各 Phase 能力矩阵

| 能力 | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|------|---------|---------|---------|---------|
| 查看颗粒详情 | ✅ 只读 | ✅ | ✅ | ✅ |
| 编辑颗粒 | ❌ | ❌ | ✅ | ✅ |
| 新增颗粒 | ❌ | ❌ | ✅ | ✅ |
| 废弃颗粒 | ❌ | ❌ | ✅ | ✅ |
| 查看画像 | ✅ 只读 | ✅ | ✅ | ✅ |
| 编辑画像 | ❌ | ❌ | ✅ | ✅ |
| 编辑提示词 | ❌ | ❌ | ❌ | ✅ |
| 测试对话 | ❌ | ❌ | ✅ | ✅ |
| 颗粒操作审计 | ❌ | ❌ | ✅ | ✅ |
| 分身主自助修改 | ❌ | ❌ | ❌ | ✅ |

---

## 4. 飞轮核心逻辑（代码）

（与 v2.0 相同）

---

## 5. 执行顺序

```
① 建表 V2~V7 → ② 实体 6 个 → ③ Repository 6 个
  → ④ 1.2.1 RAG 0 → 写 gap
  → ⑤ 1.2.6 grain_retrieve_log
  → ⑥ 1.2.7 RAG 改写日志
  → ⑦ 1.2.2 FeedbackRequest + submitFeedback
  → ⑧ SkillChatRequest 加 isTest
  → ⑨ 1.2.3-1.2.5 conversation_stats
  → ⑩ 1.3.1 推荐问题 API
  → ⑪ 1.3 前端（仪表盘+反馈审查+颗粒查看+推荐卡片+埋点API）
  → ⑫ 1.2.8 存量迁移
  → ⑬ mvn compile + npm run build
```

## 6. 编译前自检

- [ ] 6 张表 Flyway 版本号不冲突
- [ ] 6 个 Entity 字段与建表 SQL 一致
- [ ] ChatStreamService 新增 Repository 注入已添加
- [ ] FeedbackRequest/SkillChatRequest 新字段前端已适配
- [ ] SSE suggested 事件 sse.ts onEvent 已处理
- [ ] recharts 已 npm install
- [ ] npm run build + mvn compile 通过
