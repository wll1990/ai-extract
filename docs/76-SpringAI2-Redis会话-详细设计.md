# 详细设计文档：Spring AI 2.x / Spring Boot 4.x 升级 + Redis 会话态

> 状态：已实施（2026-11-18）；审核记录见第 12 节
> 范围：backend 认证/会话全链路、构建升级、MCP/Tool/Workflow 扩展点
> 非目标：不改业务模型与多租户链路；不做可视化工作流设计器；不重写 RAG
> 已确认决策：JWT **硬切**；多设备默认 **5 台上限**；Redis 主 + **DB 兜底**

---

## 0. 需求确认（2026-11-18）

| 决策项 | 结论 |
|---|---|
| 交付范围 | **全量**：框架升级 + Redis 会话 + 三方 H5 接入 + Tool/MCP（Client）+ 工作流引擎 + 一条示例流程 |
| Java | **保持 17**（不升级运行环境） |
| MCP | **只做 Client**，不对外提供 Server |
| Redis | 生产**单节点 + DB 兜底**；代码保持 Cluster 兼容（hash tag/无跨 slot pipeline），便于后续迁移 |
| 会话切换 | **硬切**，发布即全员重登 |
| 多设备 | 默认 5 台，超限淘汰最早登录 |
| 三方接入 | greenfield code exchange，不保留合作方自签 JWT |
| 示例工作流 | 素材 → 萃取 → 质量门禁 → 报告 |
| 开发分支 | `feature/spring-ai2-redis-session` |

---

## 1. 现状基线（代码事实）

### 1.1 构建

| 组件 | 当前 | 证据 |
|------|------|------|
| Spring Boot | 3.3.2 | `backend/pom.xml:11` |
| Spring AI | **1.0.0-M5**（里程碑版） | `backend/pom.xml:24` |
| Java | 17 | `backend/pom.xml:22` |
| JWT | jjwt 0.12.6 | `backend/pom.xml:23` |
| Redis | `spring-boot-starter-data-redis`，单节点 host/port | `pom.xml:79-82`，`application.yml:33-38` |
| AI | Spring AI OpenAI starter + pgvector starter，直连 DeepSeek | `pom.xml:149-158`，`application.yml:53-74` |

### 1.2 认证链路

| 事实 | 位置 |
|------|------|
| JWT claims：`userId/companyId/role/permissions/jti/ver/mcp` | `JwtUtil.java:102-116` |
| 默认 TTL 24h | `application.yml:130` |
| token 提取优先级：`Authorization: Bearer` → HttpOnly Cookie `token` → query `token`（仅 SSE 白名单） | `JwtAuthFilter.java:231-262` |
| SSE query token 白名单（7 类路径） | `JwtAuthFilter.java:66-73` |
| Cookie 写操作 CSRF 双提交 | `JwtAuthFilter.java:88-92`、`280-319` |
| `mustChangePassword` 只放行改密/身份/导航 | `JwtAuthFilter.java:111-115`、`267-277` |
| 超管切企业 `X-Acting-Company-Id`，fail-closed | `JwtAuthFilter.java:123-144` |
| 权限：签发时内嵌 + 旧 token 回退 `RolePermissions` | `JwtUtil.java:109`，`JwtAuthFilter.java:116-120` |
| 撤销：Redis 版本号（60s 缓存）+ jti 黑名单 + DB 回源 | `TokenRevocationService.java:36-125` |
| Redis key 仅 `auth:ver:{userId}`、`auth:revoked:jti:{jti}` | `TokenRevocationService.java:36-37` |
| 上下文传播：`TokenContext` + 所有线程池 `TaskDecorator` | `AiConfig.java:76,91,106,121,136,155,175,190` |
| 企业隔离缓存：`CompanyScopeService` ThreadLocal | `JwtAuthFilter.java:174` |

**现有 token 类型**（不能一刀切）：
1. B 端用户 JWT（24h）
2. C 端注册用户 JWT（30d，`application.yml:246`）
3. C 端游客 JWT（7d 滑动，`application.yml:245`）
4. 合作方 JWT（`?token=` + `appId`，**外部系统签名，我方只校验**，`PartnerJwtFilter`）
5. 注册邀请 JWT（`typ=register_invite`，`JwtUtil.java:140-170`）
6. IM 内部 HMAC（`/internal/im/*`，与用户 token 无关）
7. C 端游客发证/滑动续签：`ShareService.issueGuestSession`（`ShareService.java:355-392`，当前无事务）
8. C 端 `c_token` Cookie（`CAuthController`，固定 30d）
9. 注册/邀请内部签发：`AuthService.register/registerWithCode`、`InternalShareController`、`PublicController`

### 1.3 角色与权限

- `Permission` 常量 → `RolePermissions.ROLE_MAP` → JWT claim / `SecurityContext` authority。
- `SecurityConfig` 默认 `denyAll()`，显式白名单，规则顺序敏感。
- `isSuperAdmin()` 依据 `COMPANY_MANAGE`；企业隔离依据 `companyId → spaceIds`。
- 当前**无用户级权限覆盖**，权限只由 role 决定（改造前提）。

### 1.4 Spring AI 使用面

| 类 | 用法 |
|----|------|
| `AiConfig` | `ChatClient.builder(...)` + `OpenAiChatOptions.builder()`；`RestClientCustomizer` 设 10s connect / 180s read |
| `TokenAwareChatModel` | 装饰 `ChatModel`，流式自估 token |
| `TenantChatClientProxy` / `ChatClientResolver` | 按企业 Key 代理 `ChatClient` |
| `ChatStreamService` / `ChatStreamAdapter` | 三阶段 Setup→Stream→Post-stream |
| `SseAdapter` | `Flux<ChatResponse>` → SSE `ChatChunk` |
| `RagPipelineService` | 查询改写、4 级降级、tier 分层 |
| `DashScopeEmbeddingService` | 实现 Spring AI `EmbeddingModel` |
| `PromptLoader` | 40+ `.md` 模板，领域感知 |
| `GrainRetriever` | 原生 pgvector SQL + BM25 hybrid |
| `MaterialCleaningService` / `InterviewService` / `ReportGenerationService` / `SkillService` / `ExpertAnalysisScheduler` | `.call().content()`、`.entity(Class)`、手工 JSON 解析 |

**结论**：升级面集中在 6 个 Spring AI 直连类 + 所有 prompt 调用点；RAG 检索本身用原生 SQL，不应在本次重写。

---

## 2. 目标架构

```
Client
  │  token header / Authorization / Cookie / SSE ?token=
  ▼
TraceContextFilter（现有）
  ▼
SessionAuthFilter（新）
  ├─ 形如 JWT（含 "."）→ 跳过，交给 PartnerJwtFilter
  ├─ Redis EVAL（HGETALL+TTL+touch）→ 命中，放行
  ├─ Redis 异常/未命中 → DB auth_session 点查 → 命中回填，放行（降级）
  └─ 两者皆无 → 401；Redis+DB 均不可用 → 503
  ▼
PartnerJwtFilter（保留，合作方 JWT）
  ▼
SecurityConfig（权限校验，规则不变）
  ▼
Controller → Service
```

存储职责：

| 存储 | 角色 | 正常请求 | 故障时 |
|------|------|---------|--------|
| Redis | 主读路径 + 秒级撤销通道 | 1 次 Lua（1 RTT） | 回源 DB |
| PostgreSQL `auth_session` | 真相源 + 审计 + 设备列表 | 不读 | 唯一索引点查 |
| 现有业务表 | 不变 | 不变 | 不变 |

---

## 3. 认证与会话详细设计

### 3.1 Token 规范

- 生成：`SecureRandom` 32 bytes → Base64URL（无 padding，43 字符）。
- 存储：两处都只存 `SHA-256(token)` 小写 hex（64 字符）；客户端持有明文。
- 比较：DB/Redis 按 hash 索引查找，不做明文比较。
- 日志：禁止打印明文；调试只允许打印 `sessionId`（UUID），不允许打印 hash。
- 会话固定防护：登录必发新 token，绝不接受客户端提交的 token 作为会话。

### 3.2 Redis 数据模型

```
auth:tok:<hash>                       STRING -> uid，TTL = 同会话
  # 只拿到 token、不知道 uid 时先定位；单 key GET

auth:{u:<uid>}:sess:<hash>            HASH   TTL = min(idleTtl, exp-now)
  uid      userId
  cid      companyId（可空）
  role     角色
  mcp      mustChangePassword 0/1
  epoch    用户安全版本号（与 auth:{u:<uid>}:epoch 比对）
  pis      partner_app_id（可空）
  euid     external_user_id（可空）
  dev      deviceId
  ua       User-Agent（截断 512）
  uaHash   UA 哈希（合作方 code 绑定）
  ip       客户端 IP
  iat      登录时间 epoch millis
  exp      绝对过期 epoch millis
  idle     该会话的 idle TTL（秒，登录时按角色固化）
  seen     最近活跃 epoch millis

auth:{u:<uid>}:sessions               ZSET   TTL = absoluteTtl + 1h
  member = tokenHash
  score  = iat（按登录时间淘汰，不做每请求 ZADD）

auth:{u:<uid>}:epoch                  STRING 无 TTL（用户安全版本号）
```

- **同 slot Lua（session + epoch）** 完成「epoch 校验 + 读 + 绝对过期判定 + 条件续期」，避免 2 次 RTT：

```lua
-- KEYS[1] = auth:{u:<uid>}:sess:<hash>
-- KEYS[2] = auth:{u:<uid>}:epoch
-- ARGV[1] = idleTtlSec, ARGV[2] = touchThresholdSec,
-- ARGV[3] = nowMillis, ARGV[4] = maxTtlSec
local epoch = redis.call('GET', KEYS[2])
local sessEpoch = redis.call('HGET', KEYS[1], 'epoch')
if epoch and sessEpoch and epoch ~= sessEpoch then
  redis.call('DEL', KEYS[1])
  return nil
end
local exp = tonumber(redis.call('HGET', KEYS[1], 'exp') or '0')
if exp > 0 and exp <= tonumber(ARGV[3]) then
  redis.call('DEL', KEYS[1])
  return nil
end
local v = redis.call('HGETALL', KEYS[1])
if #v == 0 then return nil end
local ttl = redis.call('TTL', KEYS[1])
if ttl >= 0 and ttl < tonumber(ARGV[2]) then
  local t = math.min(tonumber(ARGV[1]), tonumber(ARGV[4]))
  if t > 0 then
    redis.call('EXPIRE', KEYS[1], t)
    redis.call('HSET', KEYS[1], 'seen', ARGV[3])
  end
end
return v
```

- Cluster 安全：脚本操作**同 slot** 的两个 key（hash tag `{u:<uid>}`）；`auth:tok:<hash>` 单独 GET；**禁止跨 slot pipeline**；踢全端用 `INCR epoch + DEL sessions ZSET`，不依赖逐 session DEL。
- 内存估算：单会话约 500–700 B；10 万在线会话约 50–70 MB。
- `maxmemory-policy` 建议 `volatile-lru`：会话有 TTL，被淘汰后由 DB 回源恢复，不会造成全站掉线。

### 3.3 DB 兜底表（Flyway 新增，现有表零改动）

```sql
CREATE TABLE auth_session (
  id                   UUID PRIMARY KEY,
  token_hash           VARCHAR(64)  NOT NULL,
  user_id              UUID         NOT NULL,
  company_id           UUID,
  role                 VARCHAR(32)  NOT NULL,
  epoch                BIGINT       NOT NULL DEFAULT 0,
  must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
  partner_app_id       UUID,
  external_user_id     VARCHAR(128),
  device_id            VARCHAR(64),
  user_agent           VARCHAR(512),
  ip                   VARCHAR(64),
  idle_ttl_seconds     INTEGER      NOT NULL,
  created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  last_seen_at         TIMESTAMP WITH TIME ZONE NOT NULL,
  absolute_expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  revoked_at           TIMESTAMP WITH TIME ZONE,
  revoked_reason       VARCHAR(64)
);

CREATE UNIQUE INDEX uk_auth_session_token_hash ON auth_session(token_hash);
CREATE INDEX idx_auth_session_user_active
  ON auth_session(user_id, created_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_auth_session_expire ON auth_session(absolute_expires_at);
CREATE INDEX idx_auth_session_partner
  ON auth_session(partner_app_id, external_user_id);
```

设计说明：
- `token_hash` 用 `VARCHAR(64)` 而非 `CHAR(64)`，避免 Hibernate `validate` 的类型/补空格差异。
- 软删保留审计；`last_seen_at` 只在登录与低频任务更新，**不在每请求更新**。
- 设备列表/淘汰按 `user_id + created_at` 命中部分索引。
- 清理：`SessionCleanupScheduler` 每天 03:30 分页删除 `absolute_expires_at < now() - 7d`，每批 1000，Repository 原生 SQL，Scheduler 不写 SQL。
- 规模：用户数 × 设备数 × 7 天留存；单行约 300 B。

### 3.4 登录流程

```
POST /auth/login
 1. 校验账号密码/验证码 + 现有登录限流
 2. 校验 user.status / company.status（active）——现状只查 isActive，改造补齐企业状态
 3. token = SecureRandom(32B)；hash = sha256(token)
 4. DB INSERT auth_session（**与登录业务同事务**，先落真相源；login 必须改为可写事务）
 5. 多设备淘汰：SELECT 该用户 active 会话 ORDER BY created_at DESC
    超出 max-devices 的行 UPDATE revoked_at=now(), reason='device_evicted'（同一事务）
 6. 事务提交后（afterCommit）：Redis 逐条命令，禁止跨 slot pipeline
      SET auth:tok:<hash> <uid> EX min(idle, exp-now)
      HSET auth:{u:<uid>}:sess:<hash> ... epoch=<当前 epoch>
      EXPIRE min(idle, exp-now)
      ZADD auth:{u:<uid>}:sessions iat hash
      EXPIRE absolute+1h
      对第 5 步淘汰的 hash 逐个 DEL + ZREM
 7. 返回 token（body）+ HttpOnly Cookie
 8. epoch 不存在时先 `SET auth:{u:<uid>}:epoch 0 NX`；`revokeAll` 用 `INCR` 递增
```

- **顺序**：DB 先提交，Redis 后写。Redis 写失败不阻断登录（降级），首次请求走 DB 回源。
- **并发**：同一用户并发登录用 `SET lock:session:{uid} <uuid> NX PX 3000` 保护第 5/6 步；拿不到锁仍签发会话，允许短暂超限，下次登录自愈。
- **事务边界**：DB 事务只包 INSERT/UPDATE，Redis 操作在提交后；不把外部 IO 放进事务。
- **响应**：保持现有登录响应结构，新增可选 `sessionId`；`mustChangePassword` 仍返回。

### 3.5 请求认证流程

```
SessionAuthFilter.doFilterInternal
 1. TraceContext.start / 回写 X-Trace-Id
 2. token = extractToken(request)
    a. header "token"
    b. Authorization: Bearer
    c. Cookie "token"
    d. query "token"（仅 SSE 白名单，mode=enforce）
 3. token 为空 → 交给合作方链 → 继续（由 SecurityConfig 决定 401/403）
 4. token 含 "." 且 3 段 → 视为 JWT，跳过会话查询（避免合作方请求打 DB）
 5. SessionService.get(rawToken)：
    a. `GET auth:tok:<hash>` 得 uid；未命中 → 负缓存/DB 回源
    b. EVAL 脚本（session + epoch，同 slot）→ 命中且 epoch 一致：构造 SessionInfo，放行
    c. epoch 不一致 / 未命中：Caffeine 负缓存 5s → 未命中则 DB 点查
       - DB 命中且 `revoked_at IS NULL AND absolute_expires_at > now()` → 回填 Redis（best effort），放行并标记 degraded
       - DB 未命中 → 401
    d. Redis 异常：直接 DB 点查（不经过负缓存），放行并标记 degraded；跳过 touch
    e. Redis + DB 均不可用 → 503
 6. 保留现有逻辑（顺序不变）：
    - Cookie 写操作 CSRF 双提交
    - mustChangePassword 白名单
    - X-Acting-Company-Id fail-closed
 7. 构造 authorities：role 名 + RolePermissions.getPermissions(role)
 8. SecurityContext + TokenContext.set(userId, companyId, actingCompanyId)
 9. finally：TraceContext.clear / TokenContext.clear / CompanyScopeService.clearCache
```

- **权限不存储**：每请求用 `RolePermissions` 静态 Map 解析，零 DB；ROLE_MAP 调整立即生效。
- **降级标记**：`request.setAttribute("auth.degraded", true)`，供日志/指标；业务无感。
- **负缓存**：`Caffeine<String, Boolean>`，`maximumSize=10000`，`expireAfterWrite=5s`，只缓存「DB 也未命中」，防止随机 token 打穿 DB。
- **SSE 在途**：过滤器只在连接建立时校验一次；踢下线不影响已建立的长连接（与现有语义一致）。

### 3.6 滑动续期与绝对过期

- `idle` 在登录时按角色固化进会话，后续配置调整不影响存量会话（可预期）。
- 续期条件：Redis 剩余 TTL < `idle × touchThreshold`（默认 0.5）。
- 续期上限：`min(idle, exp - now)`；`exp` 到期即删 key。
- DB 侧只校验 `absolute_expires_at`；降级期间「闲置过期」退化为「绝对过期」。
- C 端游客/注册用户沿用 7d/30d；B 端 2h idle + 12h absolute。

### 3.7 多设备与踢下线

- 上限 `max-devices-per-user`（默认 5），超出淘汰**最早登录**的会话。
- `single-session=true` 时，登录即撤销该用户其他全部会话。
- 设备标识：优先客户端 `deviceId`，否则 `sha256(User-Agent + Accept-Language)` 前 16 字符。
- 管理接口：
  - `GET /auth/sessions` → 当前用户 active 会话（不含 token/hash，只给 sessionId、device、ip、ua、createdAt、lastSeenAt、current）
  - `DELETE /auth/sessions/{sessionId}` → 校验归属后撤销
  - `POST /auth/sessions/revoke-all` → 撤销除当前外全部
  - `DELETE /admin/users/{userId}/sessions` → 需 `USER_MANAGE`
- 撤销动作统一：DB `revoked_at=now()` + `revoked_reason`；**事务提交后**执行 Redis。
- 单会话撤销：`DEL auth:tok:<hash>` + `DEL auth:{u:<uid>}:sess:<hash>` + `ZREM`。
- 全端撤销：`INCR auth:{u:<uid>}:epoch` + `DEL sessions ZSET`（同 slot，Lua 原子）；不依赖逐 session DEL，DEL 失败也不会漏。

### 3.8 撤销传播（必须先改的调用点）

| 触发 | 动作 | 说明 |
|------|------|------|
| 登出 | `revoke(current)` | 只当前会话 |
| 修改密码 / 管理员重置 | `revokeAll(userId)` | 强制全端重登 |
| 修改角色 | `revokeAll(userId)` | 防止旧 role 快照 |
| 停用/删除用户 | `revokeAll(userId)` | 立即失去访问 |
| 用户换企业 | `revokeAll(userId)` | 防止旧 companyId |
| 企业归档 | `revokeAllByCompany(companyId)` | 按企业批量撤销 |
| 多设备淘汰 | `revoke(sessionId)` | 登录流程内 |
| 管理员强制踢 | `revokeAll(userId)` | 管理接口 |

实现约束：
- 所有撤销必须在**事务提交后**执行 Redis DEL（`TransactionSynchronization.afterCommit`）。否则事务回滚会出现「DB 仍有效、Redis 已删 → 回源又放行」的竞态。
- 登录的 Redis 写同理，必须在 DB 提交后。
- 禁止在事务内调用 Redis/AI/HTTP（项目红线）。

### 3.9 角色、权限与 Controller 调用点兼容

- `RolePermissions.ROLE_MAP` 不变，仍是唯一真相源。
- **P0：30+ 处 Controller 级 `BaseController.getUserIdFromToken/getRoleFromToken`**（Skill/Interview/Report/Share/Space 等）必须全部改为 `TokenContext.get()` / `SecurityContext`；绝大多数取的是 `userId`，不只是 role/permissions。
- `UsernamePasswordAuthenticationToken.principal` 保持 `UUID`，`credentials` 保持原始 token 字符串（`AdminGrainService`/`KnowledgeCaseService`/`AuthController`/`WorkbenchController` 依赖）。
- 每个读 `jwtUtil.getRoleFromToken/getPermissionsFromToken` 的地方改为 `SessionInfo` / `SecurityContext`。
- `/auth/me`、`NavConfigController` 从 `SecurityContext.getAuthorities()` 或 `RolePermissions` 取，不读 token claim。
- `SecurityConfig` 规则、authority 组成（role 名 + 权限码）、超管切企业逻辑全部不变。
- 残留风险：session 里的 role 是快照；变更入口漏调 `revokeAll` 会让旧角色活到 TTL。靠集中封装（`AdminUserController` / `AuthService`）+ 切换清单把关。

### 3.10 降级矩阵

| Redis | DB | 认证行为 | 登录行为 | 撤销行为 |
|-------|----|---------|---------|---------|
| 正常 | 正常 | Redis 命中，滑动续期 | DB + Redis | DB + DEL Redis |
| 故障 | 正常 | DB 点查，跳过续期；降级计数 | DB 写入成功，Redis 写跳过 | DB 生效；下次请求 DB 回源即拒 |
| 正常 | 故障 | 已有会话 Redis 命中可用；未命中 → 503 | 503（真相源不可写） | 无法保证，503 |
| 故障 | 故障 | 503 | 503 | 503 |

- 503 语义：认证依赖不可用，前端**不清本地态、不跳登录页**，提示稍后重试。
- 401 语义：会话确实无效/过期/被撤销，前端清态并跳登录。
- 降级期间禁止向 Redis 回填/续期，避免故障恢复后写入半状态。

### 3.11 安全设计

- token 256 bit 熵；不可预测；不使用自增/UUID。
- 只在 Redis/DB 存 SHA-256；备份、dump、慢日志均不会泄露可用凭证。
- Cookie：`HttpOnly`；生产 `Secure=true`；`SameSite=Lax`；CSRF 双提交仅对 Cookie 写操作。
- query token 仅 SSE 白名单；URL 可能进 Referer/日志，保持现有约束，不扩大范围。
- CORS 必须显式允许请求头 `token, Authorization, Content-Type, X-CSRF-Token, X-Acting-Company-Id, X-Trace-Id`，并暴露 `X-Trace-Id`；否则浏览器预检失败。
- 管理端踢人必须校验 `USER_MANAGE`；用户自助踢设备必须校验 `session.userId == currentUserId`，防越权。
- 登录失败信息保持模糊，不区分「账号不存在/密码错误」。
- 禁止把 token 写入审计日志/异常堆栈。

### 3.12 可观测性

- Metrics（Micrometer）：`auth.session.lookup{result=hit|db_fallback|miss|error}`、`auth.session.touch`、`auth.session.revoke{result}`、`auth.db.fallback.latency`、`auth.session.create`。
- Logs：结构化；字段 `traceId/userId/sessionId/result/degraded`；无 token/hash。
- Alerts：
  - DB 回源比例 > 5%（5 分钟）
  - Redis 异常 > 10 次/分钟
  - revoke DEL 失败 > 0（立即告警）
  - DB 回源 p99 > 50ms
- 健康检查：`SessionStoreHealthIndicator` 分别报告 Redis/DB 状态；Redis DOWN 不直接让整个应用 DOWN（降级可用），DB DOWN 则应用 DEGRADED。

### 3.13 配置项

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}   # 生产单节点
      port: ${REDIS_PORT:6379}
      timeout: 2s
      connect-timeout: 2s
      # 后续迁 Cluster 时改用 cluster.nodes；代码已按 hash tag/无跨 slot pipeline 设计
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:20}

app:
  auth:
    query-token:
      mode: enforce
    session:
      b-end-idle-ttl: 2h
      b-end-absolute-ttl: 12h
      c-guest-idle-ttl: 7d
      c-user-idle-ttl: 30d
      c-absolute-ttl: 90d
      max-devices-per-user: 5
      single-session: false
      touch-threshold: 0.5
      negative-cache-seconds: 5
      cleanup-retention-days: 7
```

---

### 3.14 第三方 App / H5 嵌入（greenfield 设计）

**前提**：当前**没有任何三方接入**，不存在迁移包袱。不保留「合作方自签 JWT + `?token=`」作为主路径，按商业化标准一次设计到位。

**角色与信任边界**
- 我方超管创建 `PartnerApp`：`appId`（UUID，即 `companyId`）+ SK（仅创建时展示一次，AES 加密存储，支持 current/old 轮换）。
- 合作方**后端**持有 SK；H5 运行在我方域名，**不持有 SK**。
- 合作方用户落 `user` 表：`account=partner:{appId}:{externalUserId}`、`role=c_partner`、`companyId=appId`、`source=partner`。

**推荐流程：一次性 code 换取会话（token 不进 URL）**

1. 合作方后端 → `POST /internal/partner/session-codes`
   - HMAC：`HMAC-SHA256(SK, appId + timestamp + nonce + body)`；±5min 时间窗；nonce Redis `SETNX` 10min 防重放。
   - body：`{"externalUserId","userName","deviceId","ttlSeconds":7200}`
   - 返回：`{"code":"<32B random>","expiresIn":60}`
2. 合作方把 code 放到 H5 URL **fragment**：`https://<我方域名>/h5/...#code=...`（fragment 不发服务端/Referer），或通过 WebView JS bridge 传入；H5 读取后立即 `history.replaceState` 清除。
3. H5 → `POST /c/auth/partner/exchange`（无会话；body `{appId, code}`）
   - code 单次使用、60s 过期、绑定 appId + externalUserId + deviceId（可选 IP）；
   - 校验通过 → 查找/创建 `c_partner` 用户 → `SessionService.create`；
   - 返回 opaque token + 用户信息。
4. H5 后续 API/SSE 使用 `Authorization: Bearer` / `?token=`；**token 永不出现在初始 URL**。
5. 合作方后端可治理自己 app 下的会话：
   - `GET /internal/partner/sessions?externalUserId=`（设备列表）
   - `DELETE /internal/partner/sessions/{sessionId}`（踢单设备）
   - `POST /internal/partner/sessions/revoke-all`（踢该 externalUserId 全端）
   - 均以 HMAC 鉴权，并被强制限定在 `appId` 范围内。

**为什么不再用合作方自签 JWT**
- 长效 JWT 放 URL/WebView，泄露后无法按单用户撤销，只能 app 级禁用。
- 我方无法控制签发时间、TTL、设备、权限，审计困难。
- 去掉 `PartnerJwtFilter` 的「不解签名先读 appId」「任意路径 `?token=`」等攻击面。
- 不做双轨兼容：没有存量三方，直接上目标态。

**安全设计**
- code：32B `SecureRandom`、60s TTL、单次使用（Redis 只存 `SHA-256(code)`，`GETDEL` 原子消费）、绑定 appId/externalUserId/deviceId/UA hash。
- HMAC：时间窗 ±5min、nonce 防重放、SK 轮换；签名覆盖 method + path + body hash，防路径/参数篡改。
- 会话：2h idle / 12h absolute，与普通 C 端会话同构，可查设备、可踢、可撤销。
- 嵌入策略：`Content-Security-Policy: frame-ancestors` 按 `PartnerApp` 配置域名白名单；未配置默认拒绝 iframe。
- 限流/配额：按 appId 限制 code 签发 QPS、日活用户数、并发会话数；失败审计。
- 权限：`c_partner` 只访问 C 端分享/聊天接口；管理接口按现有角色隔离。
- 审计：`appId → session → user` 可追溯；记录 externalUserId、deviceId、IP、code 使用与失败原因。

**接口清单**

| 接口 | 调用方 | 鉴权 | 说明 |
|---|---|---|---|
| `POST /internal/partner/session-codes` | 合作方后端 | HMAC | 换一次性 code |
| `POST /c/auth/partner/exchange` | 合作方 H5 | 公开 + code | code 换会话 |
| `GET /internal/partner/sessions` | 合作方后端 | HMAC | 按 externalUserId 查设备 |
| `DELETE /internal/partner/sessions/{id}` | 合作方后端 | HMAC | 踢单设备 |
| `POST /internal/partner/sessions/revoke-all` | 合作方后端 | HMAC | 踢该用户全端 |
| `DELETE /admin/companies/{appId}/sessions` | 我方超管 | `USER_MANAGE` | 禁用合作方时全量撤销 |

**数据模型**
- 复用现有 `PartnerApp`（appId/SK/status/oldSK）。
- `auth_session` 增加 `partner_app_id`、`external_user_id`（均可空），并建 `(partner_app_id, external_user_id)` 索引；合作方查询/撤销必须带 appId 条件，不要靠 `account` 字符串解析。
- code 建议只存 Redis（短 TTL），不落库；若有强审计需求再加 `partner_session_code` 表。

**删除/退役**
- 删除 `PartnerJwtFilter` 与 `JwtAuthFilter` 中的「`?token=` + `appId`」分支；删除 `PartnerCrypto` 的 JWT 验签职责（保留 SK 加解密）。
- H5 页面把 `?token=` 读取改为 `?code=` + exchange；没有存量三方，可直接切。
- 内部测试用 dev profile 的 mock partner，不保留生产后门。

#### 3.14.1 三方方案自审（2026-11-18）

> 外部评审 agent 因 API 余额中断，本节由主设计人按同一评审清单完成；结论为「需修改后执行」。

**P0**
1. **code 仍在 URL 中**：60s + 单次只能降低不能消除泄漏（浏览器历史、截图、代理日志、Referer）。改为 **URL fragment**（`#code=...`，不发给服务端/Referer），H5 读取后立即 `history.replaceState` 清掉；H5 页面设置 `Referrer-Policy: no-referrer`；服务端禁止记录 `/h5/**` 的 query/hash。原生 App 有 JS bridge 时优先走 bridge，不经过 URL。
2. **code 消费不原子**：`GET` 后 `DEL` 有并发窗口，两个请求可能都兑换成功。改为 Redis `GETDEL`（或 Lua `GET+DEL`）原子消费；code 只存 `SHA-256(code)`，不存明文；value 含 appId/externalUserId/deviceId/uaHash/exp。
3. **并发首登会撞唯一键**：`user.account` 唯一，两个 exchange 同时首次创建同一 `partner:{appId}:{externalUserId}` 会有一个失败。捕获 `DataIntegrityViolationException` 后重查，或 `INSERT ... ON CONFLICT`（Repository 原生 SQL）再查；补并发测试。
4. **删除 `PartnerJwtFilter` 的引用面已确认**：`JwtAuthFilter` 字段/`hasAppIdClaim`/合作方分支、`JwtAuthFilterTest` mock、`PartnerException` 消费点；删除时同步改构造器与测试。`PartnerCrypto` 只保留 SK 加解密。
5. **`/internal/partner/**` 未进 SecurityConfig**：新增 `requestMatchers("/internal/partner/**").permitAll()`（Controller 内 HMAC 校验），并确保 `/internal/im/**` 现有规则不受影响。

**P1**
1. **IP 不做强绑定**：移动网络 NAT/漫游会误杀；绑定 `appId + externalUserId + deviceId + UA hash`，IP 仅记录/风控，不阻断。可选 PKCE：H5 生成 verifier，经原生 App 传给合作方后端，签名请求带 `code_challenge`，exchange 带 `code_verifier`；没有 bridge 时可省略。
2. **exchange 重试语义**：响应丢失时 code 已被消费，H5 无法重试。为 `partner:code-result:{hash}` 保存 `{sessionId, token}` 60s，同 code + 同 device 重试返回同一结果；或明确返回 409 由 H5 重新走 code。二选一，不能含糊。
3. **`PartnerApp` 缺字段**：现模型只有 `appId/appName/SK/status/contact`。需补 `allowed_frame_ancestors`（域名白名单）、`code_ttl_seconds`、`session_idle_ttl`、`session_absolute_ttl`、`max_sessions_per_user`、`daily_active_quota`。新增 Flyway（V100+）。
4. **会话索引**：`auth_session` 加 `partner_app_id`、`external_user_id`，并建 `(partner_app_id, external_user_id)` 索引；合作方查询/撤销必须带 appId 条件，不能只按 externalUserId。
5. **HMAC 细节**：签名覆盖 `method + path + bodySha256 + timestamp + nonce`；常量时间比较；nonce 10min；时间窗 ±5min；SK 轮换 current/old 且 old 到期即删；请求带 `X-Request-Id` 便于幂等/排障；全链路 TLS。
6. **嵌入头**：按 app 下发 `Content-Security-Policy: frame-ancestors <whitelist>`；不要同时下发 `X-Frame-Options: DENY`；H5 使用 `Authorization`/localStorage，不依赖第三方 Cookie（Safari ITP 会拦）。
7. **配额/滥用**：按 appId 限 code QPS、DAU、并发会话、单用户设备数；合作方后端被攻破时可批量伪造用户，需异常检测 + 一键禁用 app + 全量撤销。

**P2**
- code 兑换/撤销审计表（或结构化日志 + 保留期）；`PartnerException` 错误码补齐 `CODE_EXPIRED/CODE_USED/QUOTA_EXCEEDED`。
- 合作方 H5 刷新/多标签：token 存 localStorage，刷新复用；过期后由原生 App 重新走 code；不做 refresh token，除非后续有长期驻留场景。
- 面向三方的接入指南单独产出（本设计只保留内部契约）。

**审核确认正确**
- code exchange 优于合作方自签 JWT；SK 只在后端、H5 公开的边界正确。
- 会话与普通 C 端同构（Redis+DB+epoch）、合作方按 appId 范围治理、超管按 companyId 全量撤销，方向正确。
- 删除 `PartnerJwtFilter`、保留 `PartnerCrypto` 的职责拆分正确。
- `c_partner` 权限范围与现有 SecurityConfig 的 C 端服务等级一致。

---

## 4. 升级详细设计

### 4.1 版本矩阵（已确认目标，实施首日复核精确补丁）

| 组件 | 当前 | 目标 | 说明 |
|------|------|------|------|
| Spring Boot | 3.3.2 | **4.1.x**（或最新 4.0.x 补丁） | Spring AI 2.x 硬依赖 Boot 4 / Framework 7 |
| Spring AI | 1.0.0-M5 | **2.0.2**（BOM） | 2.0.0 GA 已于 2026-06-12 发布；1.x 只能配 Boot 3.5 |
| Java | 17 | **17（保持）** | Boot 4 支持 17+；不升级运行环境 |
| Spring Framework | 6.1 | 7.x | Jakarta EE 11 |
| Spring Security | 6.3 | 7.x | lambda DSL |
| Hibernate | 6.5 | 7.x | JPA 3.2 |
| Jackson | 2.x | 3.x（可兼容 2） | 最大不确定项 |
| Flyway | BOM 3.3 | BOM 4.0（11.x） | 校验更严 |
| JUnit | 5 | 6（以 BOM 为准） | 测试编译 |

Spring AI GA 起 artifact 重命名（必须同步）：
- `spring-ai-openai-spring-boot-starter` → `spring-ai-starter-model-openai`
- `spring-ai-pgvector-store-spring-boot-starter` → `spring-ai-starter-vector-store-pgvector`
- 新增：`spring-ai-starter-mcp-client`（本次只做 Client，不引 Server starter）
- 删除 `spring-milestones` 仓库（GA 走 Central）
- **不要保留「Boot 4 + Spring AI 1.x」组合**：1.x 只配 Boot 3.5，而 Boot 3.5 OSS 支持已于 2026-06-30 结束。1.x GA 虽已具备 MCP/Tool Calling，但只能作为 Boot 3.5 的过渡桥接，不作为最终方案。

### 4.2 编译迁移清单

| 区域 | 动作 | 风险 |
|------|------|------|
| SecurityConfig | 代码**已是 lambda DSL**，无需重写；只核对 Security 7 的 401/403/exceptionHandling 默认行为 | 低 |
| Jackson 3 | `JacksonConfig` 的 Jackson 2 `ObjectMapper` bean 会被忽略 → 改 `JsonMapperBuilderCustomizer`；`DomainConfigLoader` 改 Jackson 3 YAML；全仓 8 处 `new ObjectMapper()` 迁移（含 `JwtAuthFilter.java:220`、`PartnerJwtFilter.java:49`）；`ChatChunk` 改 catch `JacksonException`；核对日期/null/枚举/BigDecimal/未知字段 | 高 |
| Hibernate 7 | **78 个 `@Entity`** 启动 `validate`；自定义 JSONB/`@Type`；dialect；native query 返回类型 | 高 |
| Web | Boot 4 模块化：`spring-boot-starter-web` → `webmvc`（classic 可过渡）；`RestClientCustomizer` 移到 `org.springframework.boot.restclient` 且需 `spring-boot-starter-restclient`；`WebMvcConfigurer`；CORS；multipart | 中 |
| Flyway | `baseline-version: 50` / `validate-on-migrate: false` 保持；**当前最大版本 V99，新迁移从 V100 起** | 中 |
| 测试 | JUnit 6 / Mockito 新版本编译修复；现有 98 个测试全绿 | 中 |
| 构建 | `Dockerfile`、`dev.sh`、`prod.sh` 保持 JDK 17 | 低 |
| 依赖冲突 | dashscope/oss/poi 携带 Jackson 2；`mvn dependency:tree` 核对 | 高 |

### 4.3 Spring AI 2.0 API 迁移（按审核结论修正）

**2.0 的真实断点（不是「签名待确认」）：**

| 断点 | 现状 | 2.0 行为 | 影响文件 |
|------|------|---------|---------|
| `OpenAiApi` 删除 | `new OpenAiApi(baseUrl, apiKey, completionsPath, "/v1/embeddings", ...)` + `new OpenAiChatModel(api, options)` | 2.0 改官方 openai-java SDK；`OpenAiChatModel.builder()` / `OpenAiSetup` | `CompanyChatClientFactory.java:48-56` |
| `completionsPath` / `embeddingsPath` 不再生效 | 13 个 provider 靠「baseUrl 不含版本段 + 路径覆盖」路由 | SDK 自行追加 `/chat/completions` | `ProviderCatalog.java:36-77`、`CompanyChatClientFactory.java:48-49` |
| `Usage.getGenerationTokens()` | `TokenAwareChatModel.java:72,126` | 改名 `getCompletionTokens()`（`Integer`） | 同上 |
| `AssistantMessage.getContent()` | `TokenAwareChatModel.java:64,106`、`ChatStreamAdapter.java:167` | 改名 `getText()` | 同上 |
| `defaultOptions(ChatOptions)` | `AiConfig.java:37`、`CompanyChatClientFactory.java:58`、`CompanyAiConfigService.java:119` | 接收 `ChatOptions.Builder`，不再接收已 build 对象 | 同上 |
| 手工 builder 的工具链 | `ChatClient.builder(...)` 默认 `ToolCallingManager` 的 resolver 为空 | 2.0 工具循环收敛到 `ToolCallingAdvisor`；不自动扫描 `@Tool` | `AiConfig.java:35`、`CompanyChatClientFactory.java:57` |

**迁移动作：**
1. `CompanyChatClientFactory` 改为 `OpenAiChatModel.builder()` + 官方 `OpenAIClient`/`OpenAiSetup`；baseUrl/apiKey/timeout/maxRetries 进 client/options。
2. `ProviderCatalog` 删除 `completionsPath`，把完整前缀折进 `baseUrl`（DashScope `.../compatible-mode/v1`、豆包 `.../api/v3`、智谱 `.../api/paas/v4`、千帆 `.../v2`、Groq `.../openai/v1` 等）；为 13 个 provider 建路径矩阵测试（MockWebServer）。
3. `TokenAwareChatModel`：替换 `getCompletionTokens()` / `getText()`；补 `getOptions()` 委托 delegate，避免 2.0 取消隐式 options 合并后丢失模型参数。
4. `defaultOptions` 改为传 builder。
5. 工具链显式注册 `ToolCallingAdvisor` + `MethodToolCallbackProvider`（见 5.1）。
6. **所有 Spring AI import 文件都必须过一遍**（审核统计为 12 个，不是 6 个）：`AiConfig`、`TokenAwareChatModel`、`TenantChatClientProxy`、`ChatClientResolver`、`CompanyChatClientFactory`、`CompanyAiConfigService`、`ChatStreamService`、`ChatStreamAdapter`、`SseAdapter`、`RagPipelineService`、`GlobalExceptionHandler`、`EvaluationGenerationService`、`ExpertService`、`ExtractionReportService`、`MaterialCleaningService`、`InterviewService`、`ReportGenerationService`、`SkillService`、`ExpertAnalysisScheduler`（按 graphify 复核后取并集）。
7. **事实修正**：
   - `TenantChatClientProxy` 是 `Proxy.newProxyInstance(..., ChatClient.class, ...)`，不会因接口变更编译失败；真正风险是手工 builder 的工具管理器为空。
   - `DashScopeEmbeddingService` **不是** Spring AI `EmbeddingModel` 实现，是普通类；不要按接口迁移。
   - `PromptLoader` 无 Spring AI import，不涉及 `PromptTemplate` 迁移。
   - `RagPipelineService` 注入 `ChatStreamAdapter`，不直接调 `ChatClient`。
   - 全仓**没有** `.entity(Class)` 调用，9.3 的「结构化输出」回归项应删除或改为「手工 JSON 解析」。

**减法**：`spring-ai-starter-vector-store-pgvector` 代码无消费方（`GrainRetriever` 用 `com.pgvector.PGvector` + 原生 SQL），但 `application.yml:63-74` 的自动装配可能创建 `vector_store` 表（OpenAI 默认 1536 维与配置 1024 维冲突）。移除 starter 时**同时删除该配置块**，保留 `com.pgvector:pgvector`；用 condition report 确认无 `VectorStore` bean。禁止顺手重写 RAG。

### 4.4 SSE 契约（按审核结论修正）

审核发现现状与文档原描述不符，必须先修再升级：
- `SseAdapter.fromFlux(Flux<ChatChunk>, Duration)`，**不是** `Flux<ChatResponse>`；`ChatResponse → ChatChunk` 在 `ChatStreamAdapter` 完成。
- `SseAdapter` 订阅后丢弃 `Disposable`；客户端断开时上游继续生成并计费。必须保存 `Disposable`，在 `onCompletion/onTimeout/onError` 中 `dispose()`。
- `onTimeout` 只发 heartbeat 后 complete，没有 error 帧；`onError` 只日志。改为发 `ChatChunk.error("AI_TIMEOUT", ...)` / `ChatChunk.error("AI_ERROR", ...)`。
- `ChatChunk` 实际字段：`grainIds`（复数）、`reportId`；**没有** `usage`、没有单数 `grainId`；`knowledgeId`/`caseId` 存在但既未序列化也未反序列化 → 知识/案例溯源 ID 实际丢失。二选一：补序列化或删字段，不能留半成品。
- 契约测试：为 `heartbeat/done/error/timeout/source` 全部事件类型建 golden key/type 快照；补 `SseAdapter` 取消/超时/错误测试；前端 fixture 对齐。
- `chat.timeout-seconds: 180` 保持不变，但超时语义改为「发 error 帧 + dispose」。

### 4.5 超时、重试、用量（按审核结论修正）

- **2.0 的 OpenAI 集成走 openai-java/OkHttp，不再经过 Spring `RestClient`**。`RestClientCustomizer` 只影响 Spring 自己的客户端，对 LLM 调用无效；且租户工厂用 `RestClient.builder()` 自建，本来绕过 customizer。
- 超时改为在 `OpenAiChatOptions`/OkHttp client 显式配置：connect 10s、read 180s；blocking 与 streaming 分开建 client（2.0 共享 OkHttp `callTimeout`，统一 180s 会截断长流）。
- Boot 4 还需补 `spring-boot-starter-restclient`，`RestClientCustomizer` 包路径改为 `org.springframework.boot.restclient`。
- **应用级重试**：`ChatStreamService.java:507` 的 `.retry(1)` 会在已输出部分 chunk 后整段重放 → 用户看到重复内容 + 供应商重复计费。改为只在「尚未发出任何 content」时重试（`retryWhen` + emitted 标志），或直接移除。
- **usage 双写**：`TokenAwareChatModel` 在 `doOnError` 和 `doOnComplete` 都写 usage，重试会写两条。按 `requestId` 幂等，只记一次。
- `TokenUsageService` 继续从 `ChatResponse` metadata 取 usage；升级后必须校验流式/非流式都有值，且 `getCompletionTokens()` 不为 null。

### 4.6 升级测试策略

- 先编译，再启动（`ddl-auto: validate` 会暴露 Hibernate 7 映射问题）。
- 空库 + 历史库双跑 Flyway。
- 五种对话（QA/Talk/Practice/Enterprise/Organization）SSE 回归。
- 素材上传→解析→萃取→向量化→去重→审核→报告全链路。
- 访谈四阶段 + 六章报告。
- 结构化输出、查询改写、RAG 4 级降级。
- 长报告（>60s）不超时。

---

## 5. MCP / Tool / Workflow 扩展点

### 5.1 Tool Calling（按 2.0 语义重写）

2.0 的工具循环已重构，1.x 的写法不能直接沿用：
- 删除 `internalToolExecutionEnabled` / `toolNames()`；工具循环收敛到 `ToolCallingAdvisor`。
- 2.0 **不自动扫描 `@Tool` bean**；必须显式提供 `MethodToolCallbackProvider` / `ToolCallbackProvider`。
- 手工 `ChatClient.builder(...)` 的默认 `ToolCallingManager` resolver 为空，直接加工具会报「No ToolCallback found」；必须显式注册 advisor + provider。
- 现有仓库**没有** `@Tool` / `ToolCallback` / `ToolConfig`，本节是新增设计，不是迁移。
- 工具清单：`searchGrain`、`getSkillProfile`、`listScenarios`、`startPractice`。
- 只读工具默认开放；写工具**不能在流式循环里暂停等确认**，改为 workflow `human_wait` 两阶段：`prepare` 返回确认 token → 用户确认 → `execute` 校验 token。
- 隔离：单工具超时（默认 10s）、异常转工具错误消息，不打断主对话；工具输出视为不可信输入，防 prompt injection。
- 审计：记录 userId/tool/参数 hash/耗时/结果状态；不记录敏感原文。

### 5.2 MCP Client / Server

- Client：`spring-ai-starter-mcp-client`（2.0 仍有效），支持 stdio / SSE / streamable-http；连接配置进 `application.yml`。
- 2.0 的 `SyncMcpToolCallbackProvider` **不自动注册**，需显式挂到 `ToolCallbackProvider`；transport 类包名已迁移，按官方指南。
- 工具名统一前缀 `mcp_{server}_{tool}`；server 白名单，禁止用户输入动态 URL。
- 故障隔离：单 server 断连只移除其工具，不影响内置工具；启动时拉取工具列表并缓存。
- **本次不提供 Server**：不引入 `spring-ai-starter-mcp-server-webmvc`；外部 Agent 接入留到后续版本。

### 5.3 Workflow 引擎（自研轻量）

Spring AI 不提供工作流引擎；不引入 Spring AI Alibaba Graph（额外大依赖 + Boot 4 兼容未知）。

数据模型：

```sql
CREATE TABLE workflow_definition (
  id UUID PRIMARY KEY,
  workflow_key VARCHAR(64) NOT NULL,
  version INTEGER NOT NULL,
  name VARCHAR(128) NOT NULL,
  graph JSONB NOT NULL,
  status VARCHAR(16) NOT NULL,        -- draft|published|archived
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (workflow_key, version)
);

CREATE TABLE workflow_run (
  id UUID PRIMARY KEY,
  definition_id UUID NOT NULL REFERENCES workflow_definition(id),
  user_id UUID NOT NULL,
  company_id UUID,
  status VARCHAR(16) NOT NULL,        -- pending|running|waiting|succeeded|failed|canceled
  input JSONB,
  output JSONB,
  error TEXT,
  current_node VARCHAR(64),
  lease_owner VARCHAR(64),
  lease_until TIMESTAMPTZ,
  attempts INTEGER NOT NULL DEFAULT 0,
  available_at TIMESTAMPTZ NOT NULL DEFAULT now(),   -- 指数退避
  idempotency_key VARCHAR(128),                       -- 副作用幂等
  resume_token VARCHAR(64),                           -- human_wait 恢复鉴权
  waiting_until TIMESTAMPTZ,                          -- human_wait 过期
  created_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uk_workflow_run_idem
  ON workflow_run(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_workflow_run_claim ON workflow_run(status, available_at, lease_until);

CREATE TABLE workflow_event (
  id BIGSERIAL PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES workflow_run(id),
  seq BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  payload JSONB,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (run_id, seq)
);

CREATE TABLE workflow_node_run (
  id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES workflow_run(id),
  node_key VARCHAR(64) NOT NULL,
  node_type VARCHAR(16) NOT NULL,     -- llm|tool|mcp|branch|human_wait|end
  status VARCHAR(16) NOT NULL,
  input JSONB,
  output JSONB,
  error TEXT,
  attempt INTEGER NOT NULL DEFAULT 1,
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  UNIQUE (run_id, node_key, attempt)
);
```

引擎设计（审核补齐状态与围栏）：
- 节点图 JSON：`nodes[]`、`edges[]`、`startNode`；发布时做 schema 校验（环检测、可达性）。
- 领取：`UPDATE workflow_run SET lease_owner=?, lease_until=?, status='running'
  WHERE id=(SELECT id FROM workflow_run WHERE status IN ('pending','running')
  AND available_at <= now() AND (lease_until IS NULL OR lease_until < now())
  ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *`
  （Repository 原生 SQL）；单 run 单 worker + 心跳，复用 `EvaluationWorker` 模式。
- **Fencing**：后续所有写操作带 `AND lease_owner = ?`；租约过期后旧 worker 的写入必须被拒绝。
- 每步：加载 run → 执行当前节点 → 写 node_run → 更新 current_node/status，单节点事务。
- 幂等：`(run_id, node_key, attempt)` 唯一只能防重复插入；**副作用还必须带 `idempotency_key`**，execute 前校验。
- 重试：失败写 `available_at = now() + backoff(attempts)`；领取条件包含 `available_at <= now()`；超限置 failed 并记录 dead-letter。
- 人工等待：`status='waiting'` + `resume_token` + `waiting_until`；resume 校验 token、归属、过期，重复 resume 幂等。
- 进度：`workflow_event(run_id, seq, type, payload)` 按 seq 落库；SSE `/workflows/runs/{id}/stream` 支持断线从 `Last-Event-ID` 续传。
- 限额：单定义最多 50 节点；单 run 的 **`running` 累计时长上限 30 分钟，不含 `waiting`**；按企业限制并发；token 预算超限即失败。
- 版本：definition 不可变，run 固化 `definition_id`。
- 用量：`token_usage` 增加 `run_id`/`node_key`，按 run 聚合预算。

示例流程（已确认）：「素材 → 萃取 → 质量门禁 → 报告」，节点直接复用现有 `MaterialCleaningService` / 报告服务，不复制业务逻辑。

### 5.4 租户上下文与异步执行（审核新增）

- `ChatClientResolver.current()` 依赖 `TokenContext.getCompanyId()`；工作流 Scheduler、Reactor 工具回调、`@Async` 线程都没有该 ThreadLocal → 企业任务会走平台 Key、计费错归属、RAG 的 company/space 过滤失效。
- 改造：`ChatClientResolver.current(TenantContext ctx)` 显式接收 `userId/companyId/actingCompanyId`；工作流 run 表已存 `user_id/company_id`，执行时重建上下文。
- 新线程池必须挂 `TokenContext.Decorator`；Reactor 链路用 `contextWrite` 或显式参数，不能依赖 ThreadLocal。
- 必测：A 企业 workflow 不得命中 B 企业 Key/space；工具调用同样带租户上下文。

### 5.5 非目标

- 可视化流程设计器
- 任意代码执行节点
- 用户自定义 HTTP 节点（除非走白名单）

---

## 6. 接口契约

### 6.1 登录（保持兼容）

```http
POST /api/v1/auth/login
Content-Type: application/json

{"username":"...","password":"..."}
```

```json
{
  "code": 0,
  "data": {
    "token": "<43 字符不透明串>",
    "sessionId": "uuid",
    "mustChangePassword": false,
    "user": { "...": "..." }
  }
}
```

同时下发 `Set-Cookie: token=<token>; HttpOnly; SameSite=Lax; Path=/`。

### 6.2 会话管理

```http
GET /api/v1/auth/sessions
```
```json
{
  "code": 0,
  "data": [
    {
      "sessionId": "uuid",
      "deviceId": "web-chrome",
      "userAgent": "Mozilla/5.0 ...",
      "ip": "1.2.3.4",
      "createdAt": "2026-11-16T10:00:00Z",
      "lastSeenAt": "2026-11-16T12:00:00Z",
      "current": true
    }
  ]
}
```

```http
DELETE /api/v1/auth/sessions/{sessionId}
POST   /api/v1/auth/sessions/revoke-all
DELETE /api/v1/admin/users/{userId}/sessions
```

### 6.3 错误语义

| 状态 | 触发 | 前端动作 |
|------|------|---------|
| 401 | 会话不存在/过期/被撤销 | 清本地态，跳登录 |
| 403 | 权限不足 / CSRF 失败 / 强制改密 | 提示，不清态 |
| 429 | 登录/查询限流 | 提示稍后重试 |
| 503 | Redis + DB 均不可用 | 提示服务暂不可用，不清态 |

### 6.4 请求头兼容

| 来源 | 优先级 | 用途 |
|------|--------|------|
| `token: <opaque>` | 1 | 新主路径 |
| `Authorization: Bearer <opaque>` | 2 | 兼容 |
| Cookie `token` | 3 | 浏览器会话 + CSRF 双提交 |
| query `token` | 4 | 仅 SSE 白名单 |

---

## 7. 非功能设计

### 7.1 性能

| 指标 | 正常 | 降级（Redis 故障） |
|------|------|-------------------|
| 认证 Redis 操作 | 1 次 EVAL（1 RTT） | 0 |
| 认证 DB 操作 | 0 | 1 次唯一索引点查 |
| 认证 p99 | ≤ 3ms | ≤ 20ms |
| 登录写 | 1 DB INSERT + 1 pipeline | 1 DB INSERT |
| 撤销 | 1 DB UPDATE + N DEL（N≤5） | 1 DB UPDATE |

- DB 回源容量：按 `峰值认证 QPS × 1ms` 估算连接占用；`maximum-pool-size` 需覆盖业务 + 降级认证，建议 ≥ 30。
- Redis 连接：Lettuce 共享连接；设置 2s 超时，避免故障时线程堆积。
- 会话表增长：10 万用户 × 3 设备 × 7 天 ≈ 210 万行，索引点查无压力；超过 1 亿行再考虑分区。

### 7.2 容量

- Redis 内存：10 万活跃会话 ≈ 50–70 MB；索引 ZSET 额外约 10 MB。
- DB：单行约 300 B；210 万行 ≈ 600 MB（含索引）。
- 登录 Redis 写放大：每次登录 4–6 个命令，可 pipeline。

### 7.3 可用性

- Redis：生产**单节点 + DB 兜底**；`noeviction` + 内存告警（避免淘汰锁/限流键）；AOF 可选（DB 可回源）。代码保持 hash tag/无跨 slot pipeline 的 Cluster 兼容，后续可平滑迁移。
- DB：认证降级依赖主库；主库不可用 = 503，需与业务共用现有高可用方案。
- SLO：认证可用性 99.95%；降级模式可用性 99.9%；降级比例持续 > 5% 告警。

### 7.4 兼容性

- 现有 B/C 端前端只需把 token 放到 `token` header；`Authorization: Bearer` 仍可用。
- 合作方 JWT、注册邀请 JWT、IM HMAC 完全不变。
- SSE query token 不变。
- 旧 Redis key 无消费者，可清理。

---

## 8. 威胁模型（STRIDE）

| 威胁 | 场景 | 缓解 |
|------|------|------|
| Spoofing | token 被窃 | 256 bit 熵；HttpOnly/Secure/SameSite；只存 hash；短 TTL；可踢下线 |
| Spoofing | 会话固定 | 登录必发新 token；不接受客户端 token |
| Tampering | 客户端篡改 token | 随机 token 无结构，篡改后 hash 不命中 |
| Repudiation | 否认操作 | `auth_session` 审计 + 撤销原因 |
| Information disclosure | Redis/DB dump | 只存 SHA-256；日志脱敏 |
| Information disclosure | SSE URL token 进 Referer | 仅白名单路径；后续可评估一次性 SSE ticket |
| DoS | 随机 token 打 DB | 负缓存 5s + 网关限流 + 登录限流 |
| DoS | 大量登录写 DB | 现有登录限流；索引写入 |
| Elevation | 越权踢他人设备 | 校验 session 归属或 `USER_MANAGE` |
| Elevation | 角色变更后旧权限 | DB `revoked_at` + DEL Redis；集中封装 |
| CSRF | Cookie 会话被跨站写 | 双提交校验，保持现状 |
| XSS | 窃取 Cookie | HttpOnly；报告 HTML 转义保持 |

---

## 9. 测试计划

### 9.1 单元测试

- `SessionService`：创建/读取/续期/绝对过期/撤销/多设备淘汰/降级分支。
- Token 生成：长度、熵、唯一性、hash 稳定性。
- 会话哈希：SHA-256 输出、大小写、无明文。

### 9.2 集成测试（不引入 Testcontainers）

现有测试明确「Docker 不可用，不用 Testcontainers」（`TrainingSchemaDbTest.java:24`），且现有 98 个测试无 `@SpringBootTest`。集成测试改为：
- Repository/Service 测试沿用现有 H2/本地 PG 配置或 mock；
- 需要真实 Redis/DB 的用例放 `@EnabledIfEnvironmentVariable` 门禁，CI 用 docker-compose 起依赖；
- 不为本次改造强行引入 Testcontainers。

- 登录 → 请求 → 登出 → 401。
- 踢单端 / 踢全端 / 多设备第 6 台。
- 改角色/停用/换企业 → 下一请求 401。
- Redis 停止 → DB 回源放行；Redis 停止期间撤销 → 401。
- Redis + DB 停止 → 503。
- Redis `FLUSHALL` → 回源回填。
- 并发登录：同用户 10 并发，设备数 ≤ 5。
- 撤回落滚：事务回滚不产生 Redis 删除（afterCommit 验证）。
- CSRF：Cookie 写操作无头 → 403；带头 → 通过。
- 合作方 JWT / 邀请令牌不受影响。

### 9.3 升级回归

- 五种对话 SSE、素材/萃取/报告/访谈/评测全链路。
- **13 个 provider 的 tenant/platform 双路径**：MockWebServer 校验 baseUrl 拼接后路径正确（DeepSeek/DashScope 兼容模式/豆包/智谱/千帆/OpenRouter/Groq/自定义等）。
- `Usage` 真实值断言：`getCompletionTokens()` 非空，流式/非流式都统计。
- Jackson 3 契约：日期、null、枚举、BigDecimal、未知字段。
- Hibernate 7：启动 validate、分页 count、JSONB。
- Flyway：空库 + 历史库；新迁移从 V100 起。
- 长报告 > 60s 不超时；流式 180s 不被统一 callTimeout 截断。
- SSE：golden key/type、取消、超时 error 帧、error 帧。
- 重试不重复计费：MockWebServer 模拟「3 个 chunk 后断开」。
- 移除 pgvector starter 后无 `VectorStore` bean、无 `vector_store` 表副作用。

### 9.4 安全与性能

- 随机 token 扫描：负缓存生效，DB QPS 可控。
- 100/500 并发已认证请求：p99、Redis/DB 操作次数。
- Redis 故障注入：降级比例、恢复后是否停止回源。
- 越权：A 用户删除 B 用户 session → 403/404。

### 9.5 MCP/Workflow

- MCP：鉴权（会话 token/HMAC）、server 断连隔离、工具名冲突、超时、transport 包迁移。
- Tool：显式 `ToolCallbackProvider` 注册、无 resolver 报错、写工具两阶段确认、prompt injection 隔离。
- Workflow：正常推进、指数退避（`available_at`）、租约 fencing（旧 worker 写入被拒）、幂等（重复 execute 不产生副作用）、human_wait 越权/过期/重复 resume、SSE 按 seq 重放、跨租户隔离、30 分钟上限不含 waiting。

---

## 10. 发布与回滚

### 10.0 开发前准备（第一执行动作）

```bash
git checkout main
git pull --ff-only
git checkout -b feature/spring-ai2-redis-session
```

- 所有改动只在该分支提交，禁止直接提交 `main`。
- 分支内按「升级 → 会话切换 → 清理 → 扩展点」顺序提交，每个提交保持可编译、可启动。
- 分支名如与团队约定冲突，以团队规范为准（建议 `feature/spring-ai2-redis-session`）。

### 10.1 发布前

1. 确认 Spring Boot / Spring AI 精确版本与兼容矩阵。
2. Redis 生产拓扑（Cluster/Sentinel）、`maxmemory-policy`、超时。
3. DB 迁移在预发空库 + 历史库各跑一次。
4. 前端/platform 的 `token` header 与 CORS 预检验证。
5. 公告：发布即全员重登（硬切）。
6. 监控/告警就绪：回源比例、revoke 失败、503。

### 10.2 发布顺序

1. DB Flyway 迁移（`auth_session`；工作流表独立版本）。
2. backend 部署（Boot 4 + Spring AI + 新过滤器）。
3. frontend / platform 部署（header token）。
4. im-bridge / ai-service 无需变更，冒烟确认。
5. 冒烟：登录 → 对话 → 登出 → 踢设备 → 降级演练。

### 10.3 回滚

| 层级 | 动作 | 数据影响 |
|------|------|---------|
| 会话切换 | 回退 backend 到 JWT 版本 | 新 token 失效，用户重登；DB `auth_session` 保留可清理 |
| Boot/AI 升级 | `pom.xml` 回退 + 重新部署 | 无 schema 变更 |
| 工作流 | 停止 worker + 回退版本 | 新表独立，可保留 |
| Redis | 删除 `auth:*` key | 全员重登 |

- 回滚不使用「JWT/Redis 双模开关」；紧急情况宁可 503 也不降级到不可撤销的旧认证。
- `user.token_version` 列保留，代码停止读取；不写迁移删除，降低回滚成本。

---

## 11. 风险登记册

| 级别 | 风险 | 影响 | 缓解 |
|------|------|------|------|
| P0 | Controller 级 token 解析漏改（30+ 处） | 主链路 500/401 | `BaseController` 统一改 `TokenContext`；CI 禁 `jwtUtil.get*FromToken` |
| P0 | 撤销未闭合（无 epoch） | 旧会话活到绝对过期 | 用户级 epoch + Lua 校验；全端撤销 INCR epoch |
| P0 | Redis Cluster 跨 slot | 登录 CROSSSLOT | hash tag `{u:<uid>}`；禁跨 slot pipeline |
| P0 | 事务边界错误 | session 写入丢失/孤儿 | login 可写事务；session 同事务；Redis afterCommit |
| P0 | 会话管理接口未进 SecurityConfig | 越权/全部 denyAll | 显式 `authenticated()` / `USER_MANAGE` 规则 |
| P0 | Jackson 3 迁移 | 全局序列化/SSE/领域 YAML 行为变化 | `JsonMapperBuilderCustomizer`；契约测试；8 处 `new ObjectMapper()` |
| P0 | Hibernate 7 `validate`（78 实体） | 启动失败 | 空库+历史库双跑；逐实体核对列类型 |
| P0 | 2.0 删除 `OpenAiApi` / `completionsPath` 失效 | 13 厂商路由 404/编译失败 | 官方 SDK + 路径折进 baseUrl + provider 矩阵测试 |
| P0 | 异步丢 `TokenContext` | 跨租户用平台 Key/RAG 隔离失效 | 显式 `TenantContext` + Decorator + 跨租户测试 |
| P0 | SSE 不 dispose 上游 | 客户端断开后继续计费 | 保存 Disposable + error 帧 + 契约测试 |
| P1 | Spring AI 2.x API 变化 | 对话/报告链路不可用 | 编译驱动迁移；五种对话全回归 |
| P1 | 生产单节点 Redis 故障 | 认证降级到 DB，容量/RT 承压 | DB 兜底 + 熔断 + 回源限流；内存/连接监控；后续可迁 Cluster |
| P1 | 单会话 DEL 失败 | 旧会话多活 | 单会话 retry/outbox；全端走 epoch |
| P1 | `.retry(1)` 已输出后重放 | 重复内容 + 重复计费 | 仅在未输出 content 时重试 |
| P1 | 長报告 RestClient 超时包路径漏改 | 报告 10s 超时 | 显式验证 180s 配置生效 |
| P2 | 多设备淘汰竞态 | 短暂超过 5 台 | 用户级短锁；下次登录自愈 |
| P2 | CORS 未允许 `token` 头 | 浏览器预检失败 | 显式 allowedHeaders + 预发验证 |
| P2 | 第三方依赖 Jackson 2 冲突 | 启动 `NoSuchMethodError` | dependency:tree + 兼容模块 |
| P2 | 工作流骨架范围膨胀 | 拖慢主线 | 只做执行器 + 一条示例 |

---

## 12. 待核实项与审核记录

### 12.1 核实结果与剩余待办

已确认（审核 2026-11-16 / 2026-11-18）：
- Spring AI **2.0.0 GA 已于 2026-06-12 发布**，2.0.1/2.0.2 可用；2.x 硬依赖 Boot 4/Framework 7；1.x 只配 Boot 3.5。
- 目标锁定 **Boot 4.1.x + Spring AI 2.0.2 BOM**；删除「Boot 4 + 1.x」分支。
- Flyway 当前最大版本 **V99**，新迁移从 **V100** 起。
- `@Entity` 实际 **78** 个，不是 37。
- 全仓无 `.entity(Class)` 调用；无 `@Tool`/`ToolCallback`/`ToolConfig`。
- `TenantChatClientProxy` 是动态代理；`SecurityConfig` 已是 lambda DSL。
- `DashScopeEmbeddingService` 不是 `EmbeddingModel` 实现；`PromptLoader` 无 Spring AI import；`RagPipelineService` 不直接调 ChatClient。
- `SseAdapter` 入参是 `Flux<ChatChunk>`；`ChatChunk` 无 `usage`/单数 `grainId`。
- 测试环境 Docker 不可用，不引入 Testcontainers。
- 合作方当前无存量接入，按 greenfield code exchange 设计。
- `PartnerApp` 现有字段不含 CSP/配额/TTL；`PartnerJwtFilter` 引用面仅 `JwtAuthFilter` + 测试 + `PartnerException`。

仍需实施首日核对：
- [ ] Boot 4.1.x 精确补丁与 Spring AI 2.0.x 的 BOM 兼容（避开 Boot 4.0.4/4.0.5 的 Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 回归）
- [ ] 13 个 provider 的最终 baseUrl 前缀清单（逐个对厂商文档）
- [ ] openai-java SDK 的 `OpenAiChatModel.builder()` / `OpenAiSetup` 精确构造方式
- [ ] 2.0 `ToolCallingAdvisor` / `ToolCallbackProvider` 的自动注册条件
- [ ] `spring-ai-starter-vector-store-pgvector` 移除后的 condition report
- [ ] 全仓 `jwtUtil.*` 调用点清单（认证审核已给出 30+ 处，实施时逐个核对）
- [ ] 是否有前端/第三方解析 JWT payload
- [ ] 是否存在用户级权限覆盖（当前判断：无）
- [x] 生产 Redis **单节点 + DB 兜底**；`noeviction` + 内存告警；代码保持 Cluster 兼容
- [ ] 是否有 WebSocket 认证依赖 token（当前仅 SSE）
- [ ] 业务端是否依赖 `auth:ver:*` / `auth:revoked:jti:*` 做运维操作

### 12.2 技术专家审核记录

**审核 1：升级 / Spring AI / MCP / Workflow（2026-11-16）**
- 结论：需修改后执行。
- 已采纳：P0-1 `OpenAiApi` 删除与 `CompanyChatClientFactory` 遗漏；P0-2 三个 API 断点；P0-3 版本矩阵修正（2.0 GA 已发布、1.x 不配 Boot 4）；P0-4 `completionsPath` 失效与 13 厂商路由；P0-5 异步丢 `TokenContext` 的租户隔离；P1-1 超时改 OkHttp/`OpenAiChatOptions`；P1-2 SSE dispose/error 帧/字段修正；P1-3 `.retry(1)` 重复计费；P1-4 Jackson 3 `JacksonConfig` 失效；P1-5 2.0 工具链重构；P1-6 工作流状态字段补齐；P2-1 pgvector starter 副作用；P2-2 测试环境不引入 Testcontainers。
- 事实修正：78 实体、12 个 Spring AI import 文件、`DashScopeEmbeddingService` 非 `EmbeddingModel`、`PromptLoader` 无 Spring AI、无 `.entity()`、`SseAdapter` 入参、`ChatChunk` 字段、SecurityConfig 已是 lambda、`TenantChatClientProxy` 动态代理、Flyway V99。
- 遗留争议：无。

**审核 2：认证 / 会话 / 安全 / 降级（2026-11-18）**
- 结论：需修改后执行；6 个 P0、4 个 P1。
- 已采纳：P0-1 Controller 级 token 解析、P0-2 register/hasValidToken、P0-3 用户级 epoch、P0-4 Cluster hash tag、P0-5 事务边界、P0-6 SecurityConfig 会话接口；P1-1 回填 TTL、P1-2 合作方 JWT、P1-3 Redis 熔断、P1-4 回滚与 Cookie。
- 事实修正：见 13.4；正文冲突以第 13 节为准。

**审核 3：三方 App / H5 greenfield 方案（2026-11-18）**
- 外部评审 agent 因 API 402 中断；由主设计人按同一清单完成，结论：需修改后执行。
- 已采纳：code 改 fragment + `history.replaceState`；`GETDEL` 原子消费；并发首登冲突处理；`PartnerApp` 补 CSP/配额/TTL 字段；`auth_session` 补合作方维度索引；HMAC 常量时间/请求 ID；删除 `PartnerJwtFilter` 的引用面确认。
- 详见 3.14.1。

---

## 13. 认证审核修正清单（覆盖前文）

> 2026-11-18 认证/安全专家审核结论。与正文冲突时，**以本节为准**。

### 13.1 P0（必须先修）

**P0-1 Controller 级 token 解析必须全部迁移**
- 事实：`BaseController.getUserIdFromToken/getRoleFromToken` 被 `SkillController`（20+ 处）、`OrganizationSkillController`、`AdminShareController`、`InterviewController`（15 处）、`SkillMaterialController`、`AuthController`、`CAuthController`、`InternalShareController`、`PublicShareController`、`GrainController`、`WorkbenchController`、`ReportController`、`SpaceController`、`AdminController` 等 **30+ 处**调用；绝大多数取的是 `userId`，文档 3.9 只提 role/permissions，覆盖不足。
- 修复：新增 `SessionInfo` 挂 request / `TokenContext`；`getUserIdFromToken` → `TokenContext.get()`；role → `SecurityContext` authority。
- 契约：`UsernamePasswordAuthenticationToken.principal` 必须继续是 `UUID`，`credentials` 必须继续是原始 token 字符串（`AdminGrainService:363-364`、`KnowledgeCaseService:1644`、`AuthController:264`、`WorkbenchController:33` 依赖）。
- 门禁：CI/ArchUnit 禁止再出现 `jwtUtil.get*FromToken`。

**P0-2 `AuthController.register` / `hasValidToken` / `currentUserIdOrNull`**
- 事实：register 从 token 解 `callerRole/companyId/permissions`；`hasValidToken` 只解析 Bearer JWT；`PublicShareController.currentUserIdOrNull` 同样失效 → 管理员建号被判匿名（403）、Cookie 被覆盖成新用户；已登录 C 用户打开分享页被当新访客，重置游客额度。
- 修复：调用方身份从 `SecurityContext`/`SessionInfo` 取；`hasValidToken` 改 `Authentication != null && isAuthenticated()`；只在真正匿名注册时下发 Cookie；`currentUserIdOrNull` → `TokenContext.get()`；登出按当前 session 撤销。
- 测试：Cookie 管理员建号后仍是管理员；已登录 C 用户打开分享页不新建 guest。

**P0-3 撤销正确性必须引入用户级 epoch**
- 事实：Redis 命中时永不回源；DEL 失败/主从异步复制回滚会让旧会话活到**绝对过期**（B 12h、C 90d），不是「一个 idle TTL」；`revokeAll` 只 DEL 5 个会漏；ZSET 在淘汰/登出时只 DEL 会话 key、不 `ZREM`，成员只增不减。
- 修复：session hash 存 `epoch`；每请求校验 `session.epoch == auth:user:{uid}:epoch`；`revokeAll` bump DB epoch + 刷新 Redis epoch（复用现有 `token_version + auth:ver` 成熟模式）；单会话撤销保留 DEL + retry/outbox；`revokeAll` 按 DB active 行全删，不设 5 上限；DEL/淘汰同步 `ZREM`；DB 点查显式过滤 `revoked_at IS NULL AND absolute_expires_at > now()`，防止回填复活。
- 见 P0-4：epoch、session、索引需同 slot，才能用单 Lua 一次完成。

**P0-4 会话 key 的 Cluster 兼容（生产单节点，但代码必须 cluster-safe）**
- 事实：登录 pipeline 同时写不同 slot 的 key，一旦迁 Cluster 会 `CROSSSLOT`。
- 修复：统一 hash tag：`auth:{u:<uid>}:sess:<hash>`、`auth:{u:<uid>}:sessions`、`auth:{u:<uid>}:epoch`；`auth:tok:<hash> -> uid` 单独 GET；禁止跨 slot pipeline。
- 生产单节点不触发，但代码/测试保持兼容，避免未来迁移返工；Cluster 实测作为可选回归。

**P0-5 事务边界**
- 事实：`AuthService.login` 是 `@Transactional(readOnly=true)`；session INSERT 会不 flush 或报错；register/changePassword 已有事务；游客发证无事务。
- 修复：login 改可写事务；session INSERT 与登录业务**同事务**；注册/升级/改密同事务写 session；游客发证加事务；Redis 一律 `afterCommit`；注意 `afterCommit` 只在存在事务同步时生效；`auth_session.user_id` 加 FK（或明确不加的理由）。

**P0-6 会话管理接口接入 SecurityConfig**
- 事实：`/auth/sessions/**` 无规则 → 落到 `denyAll()`；`DELETE /admin/users/{id}/sessions` 会先命中 `/admin/**` 的 `DASHBOARD_VIEW`，而不是 `USER_MANAGE`。
- 修复：在 `/admin/**` 之前显式加 `DELETE /admin/users/*/sessions -> hasAuthority(USER_MANAGE)`；`/auth/sessions`、`/auth/sessions/**` 加 `authenticated()`；Service 校验目标用户同企业（super_admin 除外），跨租户返回 404；单会话校验 `session.userId == currentUserId`；补 CSRF/越权测试。

### 13.2 P1（上线前应修）

**P1-1 DB 回填 TTL**
- 回填固定用 `min(idle, exp-now)`，不要用陈旧的 `last_seen_at` 反推剩余 idle；`seen` 低频（5 分钟）回写 DB；FLUSHALL 测试必须用「登录 3 小时后」的会话验证不误杀。

**P1-2 合作方 JWT**
- 已存在用户补 `isActive/status` 校验；`?token=` 收窄到 H5/SSE 白名单；增加 `iat` app 级截止/可选 `jti` 撤销；文档明确「独立认证域，不参与用户会话撤销」。详见 3.14。

**P1-3 Redis 故障熔断**
- `AuthRateLimiter` 在 Redis 异常时 fail-open；点号 token 会绕过负缓存去解析合作方；2s command timeout 会让请求先卡 2s 再回源，线程池先耗尽。
- 修复：Redis 熔断（连续 N 次失败跳过冷却窗口）、更短 command timeout、认证回源 QPS 限流/并发舱壁、点号 token 单独限流；连接池统一 ≥30 并压测。

**P1-4 硬切/回滚与 Cookie**
- 发布时执行一次全量 `UPDATE "user" SET token_version = token_version + 1` 并清理 `auth:ver:*`，防止回滚到 JWT 版本后旧 JWT 复活；同批部署或维护窗口，避免新旧实例混跑；`token_version` 过渡期保留写入。
- Cookie：生产 `Secure=true` 必填；保持现状 `SameSite=Strict`（正文写 Lax 是降级，应改回 Strict）。

### 13.3 P2（可后续）

- 设备语义：按 `(user_id, device_id)` 去重登录；`lastSeenAt` 读 Redis `seen` 或低频回写 DB；ZSET 只服务 `revokeAll` 时可删，改由 DB 驱动。
- Redis 隔离：`volatile-lru` 会影响同实例的 `rate:*`、`skill:clean:*`、IM 锁；会话专用实例/DB，明确 `maxmemory`、监控 `evicted_keys`；`cluster.nodes` 空值条件化配置。

### 13.4 事实修正（覆盖正文）

| 正文 | 实际 |
|------|------|
| 37 实体 | **78** 个 `@Entity` |
| `DashScopeEmbeddingService` 实现 `EmbeddingModel` | 普通类，无 Spring AI import |
| `PromptLoader` 用 `PromptTemplate` | 无 Spring AI import |
| `RagPipelineService` 调 ChatClient | 注入 `ChatStreamAdapter` |
| 存在 `.entity(Class)` | 0 处 |
| `SseAdapter` 入参 `Flux<ChatResponse>` | `Flux<ChatChunk>` |
| `ChatChunk` 有 `usage`/单数 `grainId` | 只有 `grainIds`；`knowledgeId/caseId` 未序列化 |
| SecurityConfig 需改 lambda | 已是 lambda DSL |
| `TenantChatClientProxy` 手工实现接口 | `Proxy.newProxyInstance` |
| Cookie `SameSite=Lax` | **Strict**；`Secure` 默认 false，生产必填 |
| CORS 缺 `token` 头会预检失败 | 实际 `allowedOriginPattern("*")+allowedHeader("*")+allowCredentials(true)`，是更严重的任意 Origin+凭证风险 |
| query token 仅 SSE 白名单 | 合作方 `?token=` 任意路径都尝试；`mode=log` 时任意路径放行 |
| 登录校验企业 active | 只检查 `isActive`，归档企业用户仍可登录 |
| C 端 7d/30d 与 90d | `c_token` Cookie 固定 30d，与 90d 绝对过期不一致 |
| 前端需改 token header | B 端走 Cookie、C 端走 Bearer，Bearer 兼容，前端无需改；`sse-auth.ts` 读不到 HttpOnly Cookie 是既有问题 |
| Flyway 从当前最大递增 | 最大 **V99**，新迁移 **V100** |
| revokeAll 逐个 DEL 上限 5 | 会话可能 >5，会漏删 |
| `token_version` 停止读取 | `UserSecurityService`/`AuthService` 仍在写，需明确过渡策略 |

### 13.5 审核确认正确的设计

- 不透明 token 规范（32B SecureRandom/Base64URL/只存 SHA-256/登录换新）成立。
- DB 真相源 + Redis 主读 + DB 先提交 + 单 key Lua 原语成立（补 epoch/hash tag 后）。
- 角色权限模型兼容：无用户级覆盖，`RolePermissions` 唯一映射，每请求重算无问题。
- CSRF 双提交、`mustChangePassword` 白名单、acting company fail-closed、`TokenContext` Decorator 保留正确。
- 401/403/503 前端语义正确；过滤器直接写响应（不用 `sendError`）必须保留。
- 新表仅新增、软删审计、`VARCHAR(64)`、部分索引方向合理。
- 硬切在「全员重登公告 + 同批部署」前提下合理；合作方/邀请/IM 作为独立认证域不变正确。
- 后端无入站 WebSocket 认证面，仅 SSE，判断正确。

---

# 附录 A：三方 App / H5 接入文档（对外版 v1.0 草案）

> 面向：第三方合作方的服务端与客户端研发。
> 状态：v1.0 草案，接口以联调环境为准。
> 目标：三方 App 通过 WebView/iframe 嵌入我方 H5 聊天、访谈、报告页面，用户无感登录。

## A.1 接入模式

三方**服务端**用 `appId + SK` 调用我方接口，换取一个 **60 秒、单次使用**的 `code`；三方把 `code` 放到 H5 URL 的 **fragment** 中打开页面；H5 用 `code` 换取会话 token，后续聊天/SSE 自动携带。

- SK 只保存在三方服务端，**禁止**下发到 App、H5、日志。
- 不使用长效 JWT，不把 token 放在初始 URL。
- 会话由我方统一管理，可按用户踢下线、可查设备。

## A.2 接入前准备

联系我方开通 `PartnerApp`，获得：

| 项 | 说明 |
|---|---|
| `appId` | 合作方唯一标识（UUID） |
| `SK` | 签名密钥，仅创建时展示一次；支持轮换 |
| 嵌入域名白名单 | 用于 CSP `frame-ancestors`，未配置默认拒绝 iframe |
| H5 入口地址 | 按业务场景（聊天/访谈/报告）提供 |
| 测试/生产 Base URL | 联调时提供 |

要求：
- 服务端启用 NTP，时钟误差 < 5 分钟。
- 全链路 HTTPS。
- SK 放环境变量/密钥管理，不写代码、不进日志。

## A.3 接入流程

```
三方 App                三方服务端                    我方后端                    H5（我方域名）
   |                       |                             |                          |
   | 打开 H5/用户点击       |                             |                          |
   |---------------------->|                             |                          |
   |                       | 1. 签发 code（HMAC 签名）    |                          |
   |                       |---------------------------->|                          |
   |                       |<--- {code, expiresIn:60} ---|                          |
   |                       |                             |                          |
   | 2. 打开 H5 URL#code=...                            |                          |
   |----------------------------------------------------------------------------->|
   |                       |                             |  3. code 换会话 token     |
   |                       |                             |<-------------------------|
   |                       |                             |--- {token, expiresIn} -->|
   |                       |                             |                          |
   |                       |                             |  4. 聊天/SSE（Bearer）    |
   |                       |                             |<-------------------------|
```

关键约束：
- 一个 `code` 只能兑换一次，60 秒过期；**每次打开 H5 都重新签发**。
- `code` 放 URL fragment（`#code=`），不放 query（`?code=`）。
- H5 读取后立即清除 fragment，不写入历史记录。

## A.4 签名规范

### A.4.1 请求头

| Header | 必填 | 说明 |
|---|---|---|
| `X-Partner-App-Id` | 是 | 合作方 appId |
| `X-Partner-Timestamp` | 是 | 当前时间毫秒时间戳（13 位） |
| `X-Partner-Nonce` | 是 | 随机 UUID，10 分钟内不可重复 |
| `X-Partner-Signature` | 是 | HMAC-SHA256 小写 hex |
| `X-Request-Id` | 否 | 请求唯一 ID，便于排障 |
| `Content-Type` | 是 | `application/json` |

### A.4.2 待签名串

按 `\n` 拼接，**顺序固定**：

```
METHOD
PATH
X-Partner-Timestamp
X-Partner-Nonce
lowercase_hex(SHA256(rawBody))
```

- `METHOD`：大写，如 `POST`。
- `PATH`：含上下文路径，如 `/api/v1/internal/partner/session-codes`，不含域名和 query。
- `rawBody`：实际发送的 JSON 字节；空 body 用空串。
- 签名：`lowercase_hex(HMAC-SHA256(SK, 待签名串))`。
- 比较使用常量时间；时间窗 ±5 分钟；nonce 10 分钟内不可重复。

### A.4.3 Node.js 示例

```js
const crypto = require('crypto');

const appId = process.env.PARTNER_APP_ID;
const sk = process.env.PARTNER_SK;
const base = 'https://<host>/api/v1';
const path = '/internal/partner/session-codes';
const body = JSON.stringify({
  externalUserId: 'user-123',
  userName: '张三',
  deviceId: 'android-abc',
  ttlSeconds: 7200
});

const timestamp = Date.now().toString();
const nonce = crypto.randomUUID();
const bodyHash = crypto.createHash('sha256').update(body, 'utf8').digest('hex');
const canonical = ['POST', `/api/v1${path}`, timestamp, nonce, bodyHash].join('\n');
const signature = crypto.createHmac('sha256', sk).update(canonical, 'utf8').digest('hex');

const res = await fetch(base + path, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Partner-App-Id': appId,
    'X-Partner-Timestamp': timestamp,
    'X-Partner-Nonce': nonce,
    'X-Partner-Signature': signature,
    'X-Request-Id': crypto.randomUUID()
  },
  body
});
```

## A.5 接口一：签发会话 code

```http
POST /api/v1/internal/partner/session-codes
```

请求体：

```json
{
  "externalUserId": "user-123",
  "userName": "张三",
  "deviceId": "android-abc",
  "ttlSeconds": 7200
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `externalUserId` | 是 | 三方用户唯一 ID，≤128 字符；同一 appId 下唯一 |
| `userName` | 否 | 展示昵称，≤64 字符 |
| `deviceId` | 否 | 设备标识，≤64 字符；不传则用 UA 派生 |
| `ttlSeconds` | 否 | 会话有效期，600–43200，默认 7200 |

成功响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "code": "3f9c...（32 字节随机串）",
    "expiresIn": 60
  }
}
```

注意：
- `code` 只返回一次，不缓存、不落库、不进日志。
- 每次打开 H5 重新签发；不要复用。
- 同一用户短时间内多次打开会产生多个会话，默认最多 5 个设备，超出淘汰最早登录。

## A.6 拉起 H5

URL 格式：

```
<H5_ENTRY_URL>#code=<CODE>
```

示例：

```
https://<host>/h5/interview/m/abc123#code=3f9c...
```

要求：
- **必须使用 fragment `#code=`**，不要用 `?code=`；fragment 不会发送到服务端，也不会进 Referer。
- H5 读取后会立即 `history.replaceState` 清除 `code`；三方不要自行保存/打印完整 URL。
- 若 WebView 不支持 fragment 或使用 iframe，可改用双方联调约定的 JS bridge 传递 `code`；不要通过 `window.name`、Cookie 等隐式通道传递。
- iframe 嵌入时，页面域名必须在我方配置的 `frame-ancestors` 白名单内，否则会被浏览器拒绝。

Android WebView 参考：

```java
webView.getSettings().setJavaScriptEnabled(true);
webView.getSettings().setDomStorageEnabled(true); // H5 用 localStorage 保存会话
webView.loadUrl(h5Url + "#code=" + code);
```

iOS WKWebView 参考：

```swift
let url = URL(string: "\(h5Url)#code=\(code)")!
webView.load(URLRequest(url: url))
```

## A.7 会话生命周期

| 项 | 默认值 | 说明 |
|---|---|---|
| 闲置过期 | 2 小时 | 有请求自动续期 |
| 绝对过期 | 12 小时 | 从建立会话起算，不因活跃延长 |
| 多设备 | 5 台 | 超出淘汰最早登录 |
| 刷新页面 | 复用会话 | token 存 localStorage；不要重新签发 |
| 会话失效 | 401 | H5 提示重新进入；三方重新签发 code |
| 登出 | `POST /auth/logout` | 只撤销当前会话；也可调 A.8 踢全端 |

- 会话 token 只在 H5 内使用，不要回传三方服务端。
- 三方用户身份变更（换账号）必须重新签发 code，不能复用旧 code。
- 合作方被禁用时，所有会话下次请求 403/401。

## A.8 会话治理接口（三方服务端调用）

均使用 A.4 的 HMAC 签名，且**只能操作本 appId 下的用户**。

### 查询用户设备

```http
GET /api/v1/internal/partner/sessions?externalUserId=user-123
```

```json
{
  "code": 200,
  "data": {
    "sessions": [
      {
        "sessionId": "uuid",
        "deviceId": "android-abc",
        "userAgent": "Mozilla/5.0 ...",
        "ip": "1.2.3.4",
        "createdAt": "2026-11-18T10:00:00Z",
        "lastSeenAt": "2026-11-18T10:30:00Z"
      }
    ]
  }
}
```

### 踢单设备

```http
DELETE /api/v1/internal/partner/sessions/{sessionId}
```

### 踢该用户全端

```http
POST /api/v1/internal/partner/sessions/revoke-all
Content-Type: application/json

{"externalUserId":"user-123"}
```

- 踢下线在**下一次请求**生效；已建立的 SSE 长连接不会被立即中断。
- 越权（sessionId 不属于该 appId/externalUserId）返回 404，不泄露存在性。

## A.9 嵌入安全要求

1. **域名白名单**：iframe 嵌入域名必须提前报备，我方通过 CSP `frame-ancestors` 限制；未报备默认拒绝。
2. **不依赖第三方 Cookie**：H5 使用 `Authorization` / localStorage，Safari ITP、Chrome 第三方 Cookie 限制不影响。
3. **Referrer-Policy**：H5 页面返回 `no-referrer`；三方页面不要把我方 H5 URL 放进可被外部引用的位置。
4. **禁止记录敏感信息**：三方服务端日志不得记录 `SK`、`code`、会话 token；H5 URL 不要上报到三方埋点。
5. **WebView 安全**：关闭不必要的 JS bridge；如使用 bridge，只传 `code`，不传 `SK`。
6. **防截图/录屏**：如业务需要，由三方 App 侧处理；我方 H5 不依赖此能力。

## A.10 错误码

| HTTP | 场景 | 处理建议 |
|---|---|---|
| 200 | 成功 | — |
| 400 | 参数缺失/格式错误 | 修正参数 |
| 401 | 签名错误、时间戳超出 ±5 分钟、nonce 重复 | 校准时钟/重新生成 nonce |
| 401 | code 过期或已使用 | 重新签发 code |
| 403 | 合作方已禁用、域名未报备 | 联系我方 |
| 404 | 会话不存在或不属于该 appId | 不要重试；检查参数 |
| 429 | 配额/频率超限 | 退避后重试，使用新 nonce |
| 503 | 服务暂不可用 | 指数退避重试 |

说明：`message` 用于展示/排障，业务分支请以 HTTP 状态 + 场景为准。

## A.11 限流与配额

- 按 `appId` 限制 code 签发 QPS、日活用户数、并发会话数、单用户设备数。
- code 签发失败可重试，但每次必须使用**新的 timestamp + nonce**。
- code 兑换失败不要盲目重试；先判断是否过期/已使用，必要时重新签发。
- 超限返回 429；请做指数退避，不要高频轮询。

## A.12 联调清单

- [ ] SK 只配置在三方服务端，未下发 App/H5
- [ ] 签名串顺序、body hash、小写 hex 正确
- [ ] 时间戳为 13 位毫秒，NTP 正常
- [ ] nonce 每次唯一，重放被拒
- [ ] code 60 秒过期、只能兑换一次
- [ ] H5 使用 `#code=`，读取后清除 fragment
- [ ] H5 刷新复用会话，不重复兑换
- [ ] 聊天/SSE 正常，401 能提示重新进入
- [ ] 踢单端/踢全端下一次请求生效
- [ ] iframe 域名白名单生效，未报备域名被拒
- [ ] 三方日志无 SK/code/token
- [ ] 429/503 有退避重试

## A.13 FAQ

**Q：为什么不用 JWT？**
A：JWT 放在 URL/WebView 里泄露后无法按单用户撤销；code 短时单次，会话可统一踢下线。

**Q：H5 刷新后要重新签发 code 吗？**
A：不需要。会话 token 存在 localStorage，刷新复用；只有 401（过期/被踢）时才重新签发。

**Q：用户换设备/换账号怎么办？**
A：换账号必须重新签发 code；换设备会新建会话，默认最多 5 台。

**Q：会话快过期了，能静默续期吗？**
A：有请求会自动续期闲置时间；绝对过期（12 小时）后必须重新签发 code。暂不提供 refresh token。

**Q：三方后端能直接调我方聊天 API 吗？**
A：不能。聊天/SSE 只在 H5 内使用会话 token；三方后端只调 A.5/A.8 的 HMAC 接口。

**Q：签名总是不通过怎么排查？**
A：按顺序核对：PATH 是否含 `/api/v1`、body 是否与实际发送字节一致、时间戳是否 13 位、nonce 是否重复、SK 是否当前有效、hex 是否小写。

## A.14 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 草案 | 2026-11-18 | 首版：code exchange、会话治理、嵌入安全 |
