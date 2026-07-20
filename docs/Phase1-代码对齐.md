# Phase 1 — 前后端代码对齐清单

> 每一项修改都对齐到现有代码的具体字段、方法、文件

---

## 一、颗粒修改

### 1.1 数据模型对齐

**现有表**: `experience_grain`（已有，不需要建表）
**新增表**: `grain_edit_history`（Phase 3 新建，Phase 1 不建）

**现有字段** (`model/ExperienceGrain.java`):

| 前端编辑项 | 数据库列 | Java 字段 | 类型 | 已有 |
|-----------|---------|-----------|------|------|
| 专家思考 | `expert_thought` | `expertThought` | TEXT | ✅ |
| 标准话术 | `standard_script` | `standardScript` | TEXT | ✅ |
| 常见错误 | `common_mistakes` | `commonMistakes` | TEXT | ✅ |
| 适用条件 | `applicable_condition` | `applicableCondition` | TEXT | ✅ |
| 场景标签 | `scene_tag` | `sceneTag` | VARCHAR(50) | ✅ |
| 权重 | `weight` | `weight` | DOUBLE | ✅ |
| 状态 | `status` | `status` | VARCHAR(20) | ✅ |
| 编辑内容 | `edited_content` | `editedContent` | TEXT(JSON) | ✅ |
| 👍次数 | `helpful_count` | `helpfulCount` | INT | ✅ |
| 👎次数 | `unhelpful_count` | `unhelpfulCount` | INT | ✅ |
| 质量评分 | `quality_score` | `qualityScore` | DOUBLE | ✅ |

**Repository**: `repository/ExperienceGrainRepository.java`（已有，无需新建）
- 新增需要的方法: `findDistinctSceneTagsBySpaceId`（Phase 1 推荐问题用）

### 1.2 API 对齐

**Phase 1 新增 API**（只读查看）:

```java
// AdminGrainController.java（新建）
@RestController
@RequestMapping("/admin/grains")
@RequiredArgsConstructor
public class AdminGrainController {
    private final ExperienceGrainRepository grainRepository;

    // GET /admin/grains?skillId={skillId}&sort=helpful&limit=20
    // 查询已有: grainRepository.findBySpaceId(spaceId)
    // 排序: Java stream sorted by helpfulCount desc
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listGrains(
        @RequestParam UUID skillId,
        @RequestParam(defaultValue = "helpful") String sort,
        @RequestParam(defaultValue = "20") int limit) { ... }

    // GET /admin/grains/{grainId}
    // 查询已有: grainRepository.findById(grainId)
    @GetMapping("/{grainId}")
    public ApiResponse<Map<String, Object>> getGrain(@PathVariable UUID grainId) { ... }
}
```

**Phase 3 新增 API**（编辑）:
```java
// PUT /admin/grains/{grainId}
// 更新: grainRepository.save(grain)
// 重嵌: embeddingService.embed() + jdbc.update()
// 审计: adminAuditLogRepository.save()
@PutMapping("/{grainId}")
public ApiResponse<Void> updateGrain(@PathVariable UUID grainId,
                                      @RequestBody UpdateGrainRequest body) { ... }
```

### 1.3 前端组件对齐

**已有页面**: `frontend/src/app/admin/` — 目录存在，仪表盘/对话/审核页面已有

**新增路由**:
```
frontend/src/app/admin/grains/page.tsx          ← 颗粒列表页（Phase 1）
frontend/src/app/admin/grains/[grainId]/page.tsx ← 颗粒详情页（Phase 1）
frontend/src/app/admin/insights/page.tsx         ← 仪表盘（Phase 2，Phase 1 先做简化版）
```

**复用组件**:
- 已有 `LoadingSpinner` → 列表加载态
- 已有 Tab 切换模式 → 颗粒列表的状态筛选
- 页面布局复用 Admin 现有 layout

**新增组件**（放在 `frontend/src/components/admin/`）:
```
StatCards.tsx          ← 汇总卡片
SceneBarChart.tsx      ← 场景柱状图（依赖 recharts）
RagPieChart.tsx        ← RAG 饼图（依赖 recharts）
GrainRankTable.tsx     ← 颗粒排行榜
GrainDetail.tsx        ← 颗粒详情查看
KnowledgeGapPanel.tsx  ← 缺口卡片列表
FeedbackList.tsx       ← 反馈审查列表
```

---

## 二、画像修改

### 2.1 数据模型对齐

**现有表**: `skill_profile`（已有，不需要建表）

**现有字段** (`model/SkillProfile.java`):

| 前端编辑项 | 数据库列 | Java 字段 | 类型 |
|-----------|---------|-----------|------|
| 性格 | `personality` | `personality` | TEXT |
| 说话风格 | `speaking_style` | `speakingStyle` | TEXT |
| 背景 | `background` | `background` | TEXT |
| 口头禅 | `common_phrases` | `commonPhrases` | TEXT |
| 擅长领域 | `knowledge_domains` | `knowledgeDomains` | JSONB |
| 沟通偏好 | `communication_preferences` | `communicationPreferences` | JSONB |
| 弱点备注 | `weakness_notes` | `weaknessNotes` | TEXT |
| 额外上下文 | `extra_context` | `extraContext` | TEXT |

**注意**: `knowledge_domains` 和 `communication_preferences` 在 DB 中存的是 JSONB 数组 `["政府销售","B2B大客户"]`，前端编辑时需 parse/stringify。

**Repository**: 已有 `SkillProfileRepository`，查询方法:
```java
Optional<SkillProfile> findBySkillId(UUID skillId);   // 已有
```

### 2.2 API 对齐

**Phase 3 新增**:
```java
// AdminProfileController.java（新建）
@GetMapping("/admin/skills/{skillId}/profile")
public ApiResponse<SkillProfile> getProfile(@PathVariable UUID skillId) {
    return ApiResponse.success(profileRepository.findBySkillId(skillId).orElse(null));
}

@PutMapping("/admin/skills/{skillId}/profile")
public ApiResponse<Void> updateProfile(@PathVariable UUID skillId,
                                        @RequestBody UpdateProfileRequest body) {
    SkillProfile p = profileRepository.findBySkillId(skillId)
        .orElseThrow(() -> new BusinessException(404, "画像不存在"));
    if (body.getPersonality() != null) p.setPersonality(body.getPersonality());
    if (body.getSpeakingStyle() != null) p.setSpeakingStyle(body.getSpeakingStyle());
    // ... 其他字段同理
    profileRepository.save(p);
    return ApiResponse.success(null);
}
```

### 2.3 前端对齐

```
frontend/src/app/admin/skills/[skillId]/profile/page.tsx  ← 画像编辑页（Phase 3）
```

---

## 三、提示词修改

### 3.1 现有加载机制

`PromptLoader` 已支持:
- 本地缓存: `ConcurrentHashMap<String, CacheEntry>`
- Redis 缓存: `StringRedisTemplate`（可选）
- 精确失效: `invalidate(name)` / `invalidate(name, domain)`
- mtime 检测: 外部文件修改后自动重载

**当前**：提示词存文件系统（classpath 或 `PROMPTS_DIR`）
**未来**：提示词存 DB `prompt_template` 表（Phase 4 持久化）

### 3.2 Phase 4 需要的改动

- 新建 `prompt_template` 表 + JPA 实体
- `PromptLoader.loadInternal()` 改为 DB 优先 + 文件兜底
- Admin 前端提示词编辑页面

---

## 四、数据流对齐

### 4.1 仪表盘数据查询（Query 映射到已有表）

| 前端卡片 | 查询逻辑 | 已有 Repository 方法 |
|---------|---------|---------------------|
| 对话量 | `SELECT COUNT(*) FROM conversation_stats WHERE skill_id=? AND created_at BETWEEN ? AND ?` | 新增 `conversationStatsRepository` |
| 活跃用户 | `SELECT COUNT(DISTINCT user_id) FROM conversation_stats WHERE skill_id=? AND ...` | 同上 |
| 👍率 | `SELECT COUNT(*) FILTER(WHERE rating='up') / COUNT(*) FROM feedback_log WHERE skill_id=? AND ...` | 新增 `feedbackLogRepository` |
| 7日留存 | 需要跨表关联，Phase 1 先不做，显示"—" | — |
| 场景TOP | `SELECT g.scene_tag, COUNT(*) FROM grain_retrieve_log r JOIN experience_grain g ON r.grain_id=g.id WHERE r.skill_id=? GROUP BY g.scene_tag` | 新增 `grainRetrieveLogRepository` |
| RAG分布 | `SELECT SUM(rag_high_count), SUM(rag_ref_count), SUM(rag_none_count) FROM conversation_stats WHERE skill_id=?` | 新增 `conversationStatsRepository` |
| 👍颗粒 | `SELECT * FROM experience_grain WHERE space_id=? ORDER BY helpful_count DESC LIMIT 20` | 已有 `grainRepository` |
| 👎颗粒 | 同上按 `unhelpful_count DESC` | 已有 |
| 缺口列表 | `SELECT * FROM knowledge_gap WHERE skill_id=? AND status='open' ORDER BY attempted_query_count DESC` | 新增 `knowledgeGapRepository` |

### 4.2 前端 API 调用对齐

**已有 API 客户端**: `frontend/src/lib/api/skill.ts`（`submitFeedback` 等已封装）
**新增客户端**: `frontend/src/lib/api/admin-insights.ts`

```typescript
// 新建文件
export async function getSkillOverview(skillId: string, range: string) { ... }
export async function getSceneTop(skillId: string, range: string) { ... }
export async function getRagDistribution(skillId: string, range: string) { ... }
export async function getTopGrains(skillId: string, sort: 'best' | 'worst') { ... }
export async function getKnowledgeGaps(skillId: string) { ... }
export async function getFeedbackLogs(skillId: string, rating?: string, page?: number) { ... }
export async function getGrainDetail(grainId: string) { ... }
```

---

## 五、SSE 事件流对齐

### 5.1 现有 SSE 事件类型

**ChatChunk.java 已支持工厂方法**:
```java
ChatChunk.content(text)           → type="content"
ChatChunk.done()                  → type="done"
ChatChunk.error(msg)              → type="error"
ChatChunk.meta(convId)            → type="meta"
ChatChunk.warning(msg,act,skillId)→ type="warning"
ChatChunk.source(...)             → type="source"  // 已有 avgSimilarity
ChatChunk.event(type, data)       → 自定义类型
```

**前端 SSE 解析** (`sse.ts`):
- `onContent`, `onDone`, `onError`, `onMeta`, `onSource` — 已有
- `onEvent(type, data)` — 已有，自定义事件走这个

### 5.2 新增 suggested 事件

**后端发送**:
```java
// ChatStreamService.java, buildSourceChunkFlux 中
if (grains.isEmpty()) {
    return Flux.just(ChatChunk.event("suggested",
        Map.of("questions", (Object) suggestedQuestions)));
}
```

**前端接收**: 已有 `onEvent` 回调，无需改 SSE 解析层。在 page.tsx 或 hook 中:
```typescript
onEvent: (type, data) => {
  if (type === 'suggested' && data.questions) {
    setSuggestedQuestions(data.questions as string[]);
  }
}
```

### 5.3 新增埋点 API

**后端**: `POST /api/v1/analytics/event` — 暴露在 Spring Security 白名单中
**前端调用**: 原生 `fetch()`，不通过 SSE，不需要 AbortController

```typescript
async function trackEvent(eventType: string, data: Record<string, unknown>) {
  fetch('/api/v1/analytics/event', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ event_type: eventType, event_data: data }),
  }).catch(() => {}); // 静默失败，不影响主流程
}
```

---

## 六、前端现有结构对齐

### 6.1 Admin 侧边栏导航

**现有** (`frontend/src/app/admin/page.tsx` 或 layout):

```
📊 仪表盘         → /admin/dashboard
📋 审核流水线      → /admin/skills
💬 对话历史        → /admin/conversations
👥 用户管理        → /admin/users
⚙️ IM 配置        → /admin/im
```

**Phase 1 新增**:
```
🔍 颗粒详情        → /admin/grains          （从仪表盘点入）
```

**Phase 2 新增**:
```
📊 分身调优        → /admin/insights         （新的主导航项）
```

### 6.2 样式体系

项目使用 Tailwind CSS 3，已定义的设计 token:
- `bg-surface` / `bg-surface-2` — 背景层级
- `text-foreground` / `text-muted-foreground` / `text-muted-foreground-2` — 文字层级
- `border-border` / `border-border-strong` — 边框
- `bg-primary` / `text-primary` / `bg-primary-light` — 主题色
- `text-success` / `bg-success-bg` — 成功色
- `text-danger` / `bg-danger-bg` — 危险色
- `text-warning` / `bg-warning-bg` — 警告色

新增组件全部使用这些已有 token，不引入新颜色体系。

### 6.3 已有可复用组件

| 组件 | 路径 | 用途 |
|------|------|------|
| `LoadingSpinner` | `components/ui/LoadingSpinner.tsx` | 加载态 |
| `PhaseProgressBar` | `components/chat/PhaseProgressBar.tsx` | 进度条（可复用） |
| `MessageBubble` | `components/chat/MessageBubble.tsx` | 对话气泡 |
| `SkillChatView` | `components/skill/SkillChatView.tsx` | 聊天容器 |
| `Admin` 页面布局 | `app/admin/` | 已有侧边栏+内容区布局 |

---

## 七、Phase 1 完整文件清单

### 后端新建

```
model/KnowledgeGap.java
model/FeedbackLog.java
model/ConversationStats.java
model/GrainRetrieveLog.java
model/AnalyticsEvent.java
model/AdminAuditLog.java
repository/KnowledgeGapRepository.java
repository/FeedbackLogRepository.java
repository/ConversationStatsRepository.java
repository/GrainRetrieveLogRepository.java
repository/AnalyticsEventRepository.java
repository/AdminAuditLogRepository.java
controller/AdminInsightController.java        ← Phase 1 只做查询
controller/AnalyticsController.java           ← 接收埋点
dto/UpdateFeedbackRequest.java                ← 扩展 FeedbackRequest 字段
```

### 后端修改

```
model/ChatChunk.java                          ← 已有，不修改
service/ChatStreamService.java                ← 加 5 个写入点
service/SkillService.java                     ← submitFeedback 增强 + suggestedQuestions
controller/SkillController.java               ← 加 suggested endpoint
dto/FeedbackRequest.java                      ← 加 4 个字段
dto/SkillChatRequest.java                     ← 加 isTest 字段
```

### 前端新建

```
lib/api/admin-insights.ts                     ← API 客户端
app/admin/grains/page.tsx                     ← 颗粒列表（可选，从仪表盘点入）
app/admin/grains/[grainId]/page.tsx           ← 颗粒详情
components/admin/StatCards.tsx
components/admin/SceneBarChart.tsx
components/admin/RagPieChart.tsx
components/admin/GrainRankTable.tsx
components/admin/GrainDetail.tsx
components/admin/KnowledgeGapPanel.tsx
components/admin/FeedbackList.tsx
components/skill/SkillSuggestedQuestions.tsx  ← 推荐问题卡片
```

### 前端修改

```
lib/sse.ts                                    ← onSuggested 回调（已有 onEvent，不改）
app/skill/[skillId]/hooks/useQaChat.ts        ← history + feedback 参数
app/skill/[skillId]/page.tsx                  ← 推荐问题卡片集成
lib/api/skill.ts                              ← respondPractice 加 sceneTag 参数
package.json                                  ← 加 recharts
```

### Flyway 迁移

```
db/migration/V2__create_knowledge_gap.sql
db/migration/V3__create_feedback_log.sql
db/migration/V4__create_conversation_stats.sql
db/migration/V5__create_grain_retrieve_log.sql
db/migration/V6__create_analytics_event.sql
db/migration/V7__create_admin_audit_log.sql
db/migration/V8__backfill_feedback_log.sql
```

---

## 八、不需要新建/修改的

以下已有资产直接复用，不需要改动:

| 资产 | 用途 |
|------|------|
| `ExperienceGrain.java` + Repository | 颗粒 CRUD，已有全字段 |
| `SkillProfile.java` + Repository | 画像 CRUD，已有全字段 |
| `ChatChunk.java` | SSE 事件，已支持自定义事件 |
| `sse.ts` | 前端 SSE 解析，已支持 onEvent |
| `PromptLoader.java` | 提示词加载，已有 invalidate |
| `DomainConfigLoader.java` | 领域配置，已有 invalidate |
| Admin layout | 侧边栏+内容区布局 |
| Tailwind design tokens | 颜色/间距/字体 |
