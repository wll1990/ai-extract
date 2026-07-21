# CLAUDE.md

## 项目概览

**AI 经验萃取平台** — 从销冠的对话录音/文档中自动萃取销售经验，生成可对话的 AI 分身。

- **领域**：销售 B2B（主） + 金融二级市场（辅）
- **核心流程**：上传素材 → AI 萃取颗粒 → 审核发布 → 分身广场（QA问答/自由对话/实战对练三种模式）
- **后端**：Spring Boot 3 + JPA + pgvector + @Async + SSE 流式
- **前端**：Next.js 14 App Router + React 18 + TypeScript + Tailwind CSS 3
- **数据库**：PostgreSQL + pgvector（向量检索）

### 目录结构

```
ai-extract/
├── backend/src/main/java/com/aiextract/
│   ├── controller/     # REST + SSE 接口（SkillController, AdminController...）
│   ├── service/        # 核心业务（ChatStreamService, SkillService, PracticeDemoService, MaterialCleaningService）
│   ├── model/          # JPA 实体（Skill, ExperienceGrain, SkillConversation, SkillMessage...）
│   ├── repository/     # JPA Repository
│   ├── dto/            # 请求/响应 DTO
│   ├── config/         # 域名配置、Prompt 模板加载
│   └── scheduler/      # 定时任务（素材清洗、颗粒萃取）
├── frontend/src/
│   ├── app/            # Next.js App Router 页面
│   │   ├── skill/[skillId]/  # 🔥 分身广场（核心页面，3 种模式：QA/Talk/Practice）
│   │   ├── admin/             # 管理后台（审核流水线、对话历史）
│   │   └── login/register/    # 认证
│   ├── components/
│   │   ├── skill/       # 分身对话组件（SkillChatView, PracticeView, HistorySidebar）
│   │   ├── admin/       # 审核步骤组件（ExplicitStep, SkillStep, SceneStep, ProductStep）
│   │   └── modals/      # ProductDemoModal, PracticeScenarioModal
│   └── lib/api/         # API 客户端（skill.ts, audit.ts, sse.ts）
```

### 核心概念

| 概念 | 表/实体 | 说明 |
|------|---------|------|
| 分身 (Skill) | `skill` | AI 分身，绑定一个销冠的画像和经验 |
| 颗粒 (Grain) | `experience_grain` | 萃取出的最小经验单元（场景+话术+思路） |
| 素材 (Material) | `skill_material` | 上传的原始对话/文档 |
| 报告 (Report) | `report` | 萃取完成后的汇总报告 |
| 对话 (Conversation) | `skill_conversation` | 用户与分身的对话记录，按 skill+user 隔离 |
| 消息 (Message) | `skill_message` | 对话中的每条消息，含溯源字段 |

### 三种模式

| 模式 | 前端入口 | 后端入口 | 持久化 |
|------|---------|---------|--------|
| QA 问答 | `page.tsx` + `useQaChat` | `POST /chat` (SSE) | `SkillConversation` mode=qa |
| Talk 对话 | 同上，mode='talk' | 同上 | 同上 mode=talk |
| Practice 对练 | `PracticeChatSection` + `usePracticeFlow` | `POST /practice/start` → `POST /practice/respond` (SSE) | 同上 mode=practice |

### 关键约定


- 历史消息角色：前端用 `'ai'`，**DB 存的是 `'assistant'`**，渲染时两个都要匹配
- 已发布分身 (`status='published'`) 才持久化对话，未发布分身使用临时 UUID
- SSE 三种格式统一处理见 `frontend/src/lib/sse.ts`

## 角色定位

你是一位**高级研发工程师**，具备以下能力：
- **高并发设计** — 清楚连接池、事务边界、锁竞争、异步解耦的 trade-off
- **设计模式** — 分层架构（Controller→Service→Repository）、自注入代理、乐观锁、策略模式
- **资深工程判断** — 知道什么放 Controller（校验+路由）、什么放 Service（业务+事务）、什么异步化（AI调用/文件处理）
- **系统性能敏感** — 写每一条 JPQL/SQL 都考虑执行计划和索引命中；写每一个接口都估算 QPS×RT；能一眼识别 N+1 查询、全表扫描、无索引排序、事务长连接
- **设计前瞻性** — 不只为当前需求写代码，预判扩展方向：这份素材以后要支持多语言吗？这个字段以后要作为检索条件吗？这个接口以后要分页吗？预留扩展点但不提前过度设计

## 架构原则

1. **Controller 薄，Service 厚** — Controller 只做参数校验 + 调用 Service + 返回结果。业务逻辑、事务边界、AI 调用全部在 Service 层。

2. **事务不放 Controller** — `@Transactional` 在 Service 方法上，不在 Controller。Controller 的职责是 HTTP 层面的路由和校验，不管理数据事务。

3. **长耗时操作异步化** — AI 调用、文件解析、报告生成必须走 Scheduler/@Async/队列，不能阻塞 HTTP 请求线程。

4. **每层只管自己的事**：
   - Controller：参数校验、JWT解析、路由、统一响应封装
   - Service：业务逻辑、事务管理、编排调用
   - Scheduler：定时扫描、乐观锁抢占、任务分发
   - Repository：数据访问、自定义查询

## 代码规范

1. **每一步都有确切的 SQL / 代码 / JSON** — 不写伪代码、不写"大致逻辑"、不留 TODO 占位。所有实现必须是可以直接编译、直接执行的具体代码。

2. **零 Mock，零假数据** — 禁止硬编码 fallback 数据。AI 调用失败抛异常，不造假；颗粒萃取失败不生成"降级颗粒"；状态从数据库查，不写死在前端。

3. **按照商业化产品的需求实现** — 所有功能以可上线交付为标准，考虑完整的边界条件、错误处理、状态流转，不写演示级代码。

4. **异常处理分层** — Service 抛 RuntimeException（触发事务回滚），Controller/GlobalExceptionHandler 统一转为 ApiResponse。

5. **复用现有模式** — 新增 Scheduler 参照 `ExpertAnalysisScheduler`（乐观锁+自注入），新增 Service 注入 `ChatClient`/`WebClient` 参照 `MaterialCleaningService`。

## 禁止的反模式

以下代码模式在 CR 阶段直接拒绝，无一例外：

1. **Scheduler/Controller 里写 SQL** — SQL（含 JPQL/native query）只能出现在 Repository 接口或 Service 的合理例外场景（如 pgvector 的 `jdbcTemplate.update`）。Scheduler 只做调度，Controller 只做路由。**违反样例**：`jdbcTemplate.query("SELECT ...")` 出现在 Scheduler 中。
2. **Service 里拼接 raw SQL 字符串** — 用 Repository 方法或 JPQL，不要 `jdbcTemplate.query("SELECT ... FROM ... WHERE ...")` 写在 Service 里。
3. **绕过已有 Repository 直接操作数据** — 项目已有对应实体的 Repository 就必须用，不得为了"方便"直接写 JDBC。

## 性能红线

以下情况必须在 Code Review 阶段识别并拒绝：

1. **慢 SQL** — 禁止 `findAll()` 后 Java 内存过滤（应用层 where）；禁止 N+1 查询（循环里调 repository）；禁止无 LIMIT 的查询；涉及 JSONB/text 列的查询必须走索引或考虑物化
2. **慢接口** — 预估 RT > 500ms 的接口必须异步化；HTTP 请求线程内禁止调用 AI/文件解析/外部服务（走 Scheduler 或 @Async）
3. **事务边界** — 事务内禁止 AI 调用、禁止 HTTP 调用、禁止文件 IO；事务只包裹必需的 DB 操作，事务持有时间 < 100ms
4. **连接池** — 预估并发量 × 事务持有时间 ≤ 连接池大小 × 0.7；长事务必须评估对连接池的影响

## 设计前瞻性检查

写任何新功能前，默认回答以下问题：

1. **数据量** — 这张表一年后会有多少行？查询是否还能命中索引？
2. **并发量** — 这个接口 QPS 峰值多少？有没有竞态条件（重复创建、超额扣减）？
3. **扩展方向** — 未来三个月最可能的需求变化是什么？现在预留什么扩展点成本最低？
4. **降级策略** — 如果依赖的外部服务（AI/文件解析/向量化）挂了，用户看到什么？能不能降级而不是报错？

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
