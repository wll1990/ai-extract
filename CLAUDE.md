# CLAUDE.md

## 项目概览

**AI 经验萃取平台** — 从销冠的对话录音/文档中自动萃取销售经验，生成可对话的 AI 分身（Skill）。

- **领域**：销售 B2B（主） + 金融二级市场（辅，通过领域 YAML 层级继承 `domain/*.yml`）
- **核心流程**：上传素材 → AI 萃取颗粒 → 审核发布 → 分身广场（QA/自由对话/实战对练）

### 五个服务（以代码为准）

| 服务 | 技术栈 | 职责 |
|------|--------|------|
| `backend/` | Spring Boot 3 + JPA + pgvector + **Spring AI** | **唯一核心**：业务、LLM 调用、RAG、SSE、调度全在这 |
| `frontend/` | Next.js 14 | B 端管理后台 + 分身广场主应用 |
| `platform/` | Next.js 14 | C 端平台「MindForge」（发现专家/我的分身/分享页） |
| `ai-service/` | Python FastAPI | **只剩文件解析**（txt/docx/pdf/pptx/xlsx/csv/html/图片/音频→文本） |
| `im-bridge/` | Node.js | IM 桥：飞书（扫码 + WebSocket）、企业微信（自建应用回调）、钉钉（Stream 长连接），HMAC 验签转发后端 |

> ⚠️ **与 README 的偏差**：README 声称 ai-service 提供 `/internal/chat`、报告生成、访谈引擎——**这些已全部迁回 backend**（Spring AI 直连 DeepSeek）。ai-service 现在只有 `/internal/parse-file`（[internal.py](ai-service/routers/internal.py)，179 行，`services/` 为空）。后端 `client/` 目录空置。

### 目录结构

```
ai-extract/
├── backend/src/main/java/com/aiextract/
│   ├── controller/   # REST + SSE（SkillController=分身对话, AdminController=审核流水线）
│   ├── service/      # 核心业务（ChatStreamService/RagPipelineService/MaterialCleaningScheduler...）
│   ├── model/        # 37 个 JPA 实体
│   ├── repository/   # JPA Repository + pgvector 原生 SQL
│   ├── config/       # 安全/权限/SSE/Prompt/领域/多租户（AiConfig, SecurityConfig, Permission, RolePermissions...）
│   ├── scheduler/    # 定时任务（DataCleanupScheduler, SkillStatsScheduler）
│   └── common/       # ApiResponse, PageResponse, TraceContext
├── frontend/src/     # B 端：app/(admin, skill/[skillId], interview, report, explore, space) + lib/(api, sse.ts)
├── platform/src/     # C 端 MindForge：app/(discover, chat, platform/my, s/[shareCode], h5)
├── ai-service/       # 文件解析服务（routers/internal.py → /internal/parse-file）
├── im-bridge/src/    # index.js/bridge.js/registry.js/channels/{feishu,wecom,dingtalk}.js
├── prompts/          # 40+ 个 .md Prompt 模板（领域感知，PromptLoader 加载）
└── docs/             # 30+ 设计/API/测试文档
```

### 多租户归属链路

```
Company → User(company_id) → Space(user_id) → Skill(space_id) → ExperienceGrain(space_id)
```

- [User](backend/src/main/java/com/aiextract/model/User.java)：**统一用户表**，B 端（`super_admin`/`company_admin`/`employee`）+ C 端（`c_guest`/`c_user`/`c_partner`），`companyId` 可空
- [Skill](backend/src/main/java/com/aiextract/model/Skill.java)：`type=individual|organization`；状态机 `generating→reviewing→published/discarded`；发布期 @Async 预生成字段（`openingMessage`/`introProfile`/`recommendedQuestions`/`practiceOpenings`）+ 互动统计列（`SkillStatsScheduler` 每 5 分钟聚合）
- [ExperienceGrain](backend/src/main/java/com/aiextract/model/ExperienceGrain.java)：`embedding VECTOR(1024)`（JPA 不管，原生 SQL 更新）；`weight`/`qualityScore`/`status=active|deprecated`；`sourceType=file_upload|interview`
- [SkillConversation](backend/src/main/java/com/aiextract/model/SkillConversation.java) / [SkillMessage](backend/src/main/java/com/aiextract/model/SkillMessage.java)：按 skill+user 隔离；消息带 `grainId`/`reportId` 溯源字段

### 权限体系（单一真相源）

`Permission` 常量分级 → `RolePermissions.ROLE_MAP`（角色→权限码）→ JWT 自包含 claim → SecurityConfig 校验：

1. [Permission](backend/src/main/java/com/aiextract/config/Permission.java)：权限码常量（Platform 级/Company 级/Basic 级/C 端服务等级）
2. [RolePermissions](backend/src/main/java/com/aiextract/config/RolePermissions.java)：`ROLE_MAP` 唯一映射表，三个消费点同源（JwtAuthFilter、`/auth/me`、SecurityConfig）
3. [JwtUtil](backend/src/main/java/com/aiextract/util/JwtUtil.java)：JWT 含 `userId/companyId/role/permissions/jti`
4. [SecurityConfig](backend/src/main/java/com/aiextract/config/SecurityConfig.java)：**默认 `denyAll()`**，接口显式白名单，规则顺序敏感
5. [JwtAuthFilter](backend/src/main/java/com/aiextract/config/JwtAuthFilter.java)：Bearer→query param 双通道；标准 JWT + 合作方 JWT（`?token=`+`appId`）双链；Redis 黑名单撤销（fail-open）
6. [CompanyScopeService](backend/src/main/java/com/aiextract/config/CompanyScopeService.java)：ThreadLocal 请求级缓存 `companyId→spaceIds`，`isSuperAdmin()`（有 `COMPANY_MANAGE`）跳过企业隔离
7. [TokenContext](backend/src/main/java/com/aiextract/config/TokenContext.java)：ThreadLocal 传 userId/companyId，`TaskDecorator` 跨线程
8. [NavConfigController](backend/src/main/java/com/aiextract/controller/NavConfigController.java)：后端按权限过滤菜单下发，消除前端 route→permission drift

### 三条核心链路

1. **分身对话**（QA/Talk/Practice/Enterprise/Organization）：`SkillController POST /skills/{id}/chat` (SSE) → `ChatStreamService` 按 type 分发 → 三阶段 Setup(RAG)→Stream(LLM)→Post-stream(meta+source 溯源) → `ChatStreamAdapter` → Spring AI `ChatClient.stream` → `SseAdapter.fromFlux` → [ChatChunk](backend/src/main/java/com/aiextract/model/ChatChunk.java) 事件
2. **素材萃取**（异步）：`MaterialCleaningScheduler` 每 30s 扫描 → material 乐观锁 + skill 级 Redis 锁 → parseFile(调 ai-service) → clean(AI 萃取) → saveGrains → embed(DashScope) → 语义去重 → `generating→reviewing` → 报告标脏 → 每 2 分钟合并生成报告
3. **访谈报告**：`InterviewController` + `InterviewService` + `ReportGenerationService`（AI 四阶段访谈 → 六章报告）

### RAG 检索

- [GrainRetriever](backend/src/main/java/com/aiextract/service/GrainRetriever.java)：pgvector `cosine_distance <=>` ANN(HNSW) + **BM25(zhparser) hybrid(RRF 融合)** + `weight × 拉普拉斯平滑反馈权重` 重排；多空间并行 ANN
- [RagPipelineService](backend/src/main/java/com/aiextract/service/RagPipelineService.java)：查询改写(LLM) → **4 级降级**(降阈值→去质量门禁→Dense-only→记录缺口) → tier 分层(high≥0.5/ref≥0.3，阈值来自领域配置) → sceneTag 2-4 字 boost

### AI 调用

- `AiConfig` 定义 `ChatClient`（Spring AI OpenAi → DeepSeek `deepseek-chat`），**后端直连，不经 ai-service**
- [TokenAwareChatModel](backend/src/main/java/com/aiextract/config/TokenAwareChatModel.java)：ChatModel 装饰器，流式自估 token（中文 1 token≈2 字符）
- [DashScopeEmbeddingService](backend/src/main/java/com/aiextract/service/DashScopeEmbeddingService.java)：阿里 DashScope `text-embedding-v4` 1024 维
- [PromptLoader](backend/src/main/java/com/aiextract/config/PromptLoader.java)：40+ .md 模板，领域感知 + L1 本地/L2 Redis 双层缓存
- [DomainConfigLoader](backend/src/main/java/com/aiextract/config/DomainConfigLoader.java)：领域 YAML 层级继承（`sales.b2b_enterprise` 继承 `sales`）

### 三种模式

| 模式 | 后端入口 | 前端入口 | 说明 |
|------|---------|---------|------|
| QA 问答 | `POST /skills/{id}/chat` (SSE) | `page.tsx` + `useQaChat` | 默认，结论先行（mode=quick） |
| Talk 对话 | 同上 `mode='talk'` | 同上 | 自由对话 |
| Practice 对练 | `POST /skills/{id}/practice/start` → `/practice/respond` (SSE) | `PracticeChatSection` + `usePracticeFlow` | AI 扮演客户，逐轮评判 |

模式解析在 [SkillService.resolveMode](backend/src/main/java/com/aiextract/service/SkillService.java#L511)：`/对练`→practice、`/讨论`→discuss、默认 quick。

### 关键约定

- 历史消息角色：前端用 `'ai'`，**DB 存 `'assistant'`**，渲染时两者都要匹配
- 已发布分身 (`status='published'`) 才持久化对话，未发布分身使用临时 UUID
- SSE 统一走 `frontend/src/lib/sse.ts`（逐行 JSON + raw 两种格式），禁止内联 fetch 手写解析
- `im-bridge` 与后端 `/internal/im/*` 之间 HMAC-SHA256 双向验签（`im-bridge/src/hmac.js` ↔ `config/ImBridgeAuth`）
- IM 渠道契约平台无关：`/internal/im/message` 收统一消息，`/internal/im/{channelType}/complete|active` 做生命周期；新增渠道只需加一个 `channels/*.js` 适配器
- 企业微信回调走 `GET/POST /wecom/callback`（需公网 HTTPS 指向桥 3010）；钉钉走 Stream，无需公网回调

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

6. **分页响应统一用 `PageResponse`** — 所有返回分页列表的 Controller 必须使用 `common.PageResponse.of()` 组装响应，禁止手写 `Map.put("content")` / `Map.put("total")` 等样板代码。

```java
// ✅ 正确：一行搞定
return ApiResponse.success(PageResponse.of(customContent, springPage, page, size));

// ✅ 直接用 Page content 时
return ApiResponse.success(PageResponse.of(skillPage, page, size));

// ❌ 禁止：手写 7 行样板
Map<String, Object> response = new LinkedHashMap<>();
response.put("content", result);
response.put("page", page);
response.put("size", size);
response.put("total", page.getTotalElements());
response.put("totalPages", page.getTotalPages());
return ApiResponse.success(response);
```

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
