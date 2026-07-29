# AI 经验萃取平台 — 全端分享 & 访问架构

## 基础设施

| 组件 | 端口 | 技术栈 | 认证方式 |
|------|:--:|------|------|
| 后端 API | 8080 | Spring Boot 3 | JWT（Cookie / Bearer / URL参数） |
| B端前端 | 3000 | Next.js 14 | HttpOnly Cookie `token` |
| 平台端 | 3001 | Next.js 14 | Cookie 或 localStorage |

- 前端通过 Next.js rewrites 代理 `/api/v1/*` → `http://localhost:8080/api/v1/*`
- 生产环境通过 Nginx 反向代理统一域名
- CORS: `localhost:3000`, `localhost:3001`, `allowedOriginPattern("*")`

---

## 一、三套认证体系

### B端（企业用户）

| 项 | 值 |
|------|------|
| 用户表 | `user` (id, company_id, account, password_hash, name, role) |
| Token载体 | HttpOnly Cookie `token`（XSS安全） |
| 登录 | `POST /auth/login` → Cookie |
| 注册 | `POST /auth/register/with-code` (企业邀请码) |
| Token TTL | 24小时 |

角色及权限：`super_admin` > `company_admin` > `employee`

### C端（消费者/游客）

| 项 | 值 |
|------|------|
| 用户表 | `app_user` (id, account, password_hash, nickname, status, source) |
| Token载体 | localStorage `c_auth` → `Authorization: Bearer` |
| 登录 | `POST /c/auth/login` → JSON `{token}` |
| 注册 | `POST /c/auth/register/new`（平台）或 `POST /c/auth/register`（游客升级） |
| Token TTL | 7天（滑动续期） |

状态流转：`guest` → `registered`（同一UUID，历史继承）

### Partner端（外部系统嵌入）

| 项 | 值 |
|------|------|
| 用户表 | `app_user`（自动创建） |
| Token载体 | URL `?token=<partner_jwt>`（PartnerJwtFilter） |
| 角色 | `c_partner` |
| 签名验证 | PartnerJwtFilter 校验签名 + 密钥轮换支持 |

JwtAuthFilter 优先级：URL `?token=` > `Authorization: Bearer` > Cookie `token`

---

## 二、安全配置公开路径

以下路径无需认证（`permitAll`）：

| 路径 | 用途 |
|------|------|
| `POST /auth/login` | B端登录 |
| `POST /auth/register` | B端注册 |
| `POST /auth/register/with-code` | 企业邀请码注册 |
| `/public/**` | 对外数据：分身列表、分享信息、企业注册码、访谈邀请 |
| `POST /c/auth/login` | C端登录 |
| `POST /c/auth/register/new` | C端新用户注册 |
| `GET /i/*/info` | 对内分享落地页预览 |
| `/im/*/callback` | IM渠道回调（飞书/企微/钉钉） |
| `/swagger-ui/**`、`/actuator/health` | API文档、健康检查 |

---

## 三、各端路由 & 访问方式

### 1. B端管理后台 (`:3000/admin`)

| 页面 | 路由 | 权限 | 说明 |
|------|------|:--:|------|
| 工作台 | `/admin` | DASHBOARD_VIEW | 运营指挥中心 |
| 数据看板 | `/admin/insights` | DASHBOARD_VIEW | 全局KPI + 分身健康度 |
| 分身详情 | `/admin/insights/[skillId]` | DASHBOARD_VIEW | 单分身数据图表 |
| 分身调优 | `/admin/tuning` | DASHBOARD_VIEW | 颗粒管理 + 知识缺口 |
| 颗粒诊断 | `/admin/grains/[grainId]` | DASHBOARD_VIEW | 颗粒内容 + 检索历史 + 差评 |
| 反馈审查 | `/admin/insights/[skillId]/feedback` | DASHBOARD_VIEW | 用户打分逐条审查 |
| 企业合作 | `/admin/companies` | COMPANY_MANAGE | 企业管理 + 注册码生成 |
| 分享管理 | `/admin/skills/[skillId]/share` | PARTNER_MANAGE | 生成/管理分享码 |

### 2. B端员工 (`:3000`)

| 页面 | 路由 | 权限 | 说明 |
|------|------|:--:|------|
| 工作台 | `/workbench` | SKILL_USE | 个人对话统计 + 最近分身 |
| 分身广场 | `/skills` | SKILL_USE | 浏览可用分身 |
| 销冠访谈 | `/interview/create` | SKILL_USE | 创建AI访谈 |

### 3. C端对外公开分享 (`:3000/s/{shareCode}`)

**完整链路**：

```
B端管理员 → POST /admin/skills/{id}/share → 生成 shareCode（10位base62）
              ↓
分享链接: https://域名/s/{shareCode}
              ↓
C端用户打开 → GET /public/share/{shareCode}（无需登录）
              ↓ 返回: 分身信息 + 企业名 + owner名
点击模式 → POST /public/share/{shareCode}/guest → 返回 c_guest JWT
              ↓
进入聊天 → /skill/{skillId}（携带C端token）
              ↓ 每日25条限制
额度用完 → 弹出注册 → POST /c/auth/register（同UUID升级）
              ↓
升级为 c_user → 历史对话完整保留，无限制
```

核心端点：
- `GET /public/share/{shareCode}` — 公开，返回分身落地页信息
- `POST /public/share/{shareCode}/guest` — 公开，游客发证

前端页面：`frontend/src/app/s/[shareCode]/page.tsx`

### 4. 对内企业分享 (`:3000/i/{shareCode}`)

**完整链路**：

```
B端 → POST /admin/skills/{id}/share/internal → 生成内部 shareCode
              ↓
分享链接: https://域名/i/{shareCode}
              ↓
企业成员打开 → GET /i/{shareCode}/info（公开，可预览分身+企业名）
              ↓ 提示登录
登录后 → 进入聊天 /skill/{skillId}（无限额）
```

与对外分享区别：无游客模式、无消息限制、必须B端登录

核心端点：
- `GET /i/{shareCode}/info` — 公开（预览）
- `/i/**` — 需认证

前端页面：`frontend/src/app/i/[shareCode]/page.tsx`

### 5. C端分身广场 & 对话 (`:3000`)

| 页面 | 路由 | 权限 | 说明 |
|------|------|:--:|------|
| 探索发现 | `/explore` | 无 | 公开浏览已发布分身 |
| 分身对话 | `/skill/[skillId]` | SKILL_USE | QA问答 / Talk对话 / Practice对练 |
| H5注册 | `/h5/register?code=` | 无 | 企业邀请码注册 |

### 6. H5移动端访谈 (`:3000/h5`)

**完整链路**：

```
B端管理员 → POST /admin/invite {expireDays} → 生成 inviteCode（8位base62）
              ↓
邀请链接: https://域名/h5/interview/m/{inviteCode}
              ↓
受访者扫码/点击 → 无需登录 → 显示企业Logo + 访谈说明
              ↓
开始访谈 → AI语音/文字提问 → 实时萃取 → 生成报告
```

前端页面：

| 页面 | 路由 | 说明 |
|------|------|------|
| 邀请落地 | `/h5/interview/m/[inviteCode]` | 支持 `?token=` Partner嵌入 |
| 开始访谈 | `/h5/interview/start` | AI引导 |
| 访谈对话 | `/h5/interview/chat/[sessionId]` | SSE流式 |
| 访谈完成 | `/h5/interview/done` | 结果页 |
| 查看报告 | `/h5/report/[sessionId]` | 萃取结果 |

### 7. 平台端 (`:3001`)

| 页面 | 路由 | 权限 | 说明 |
|------|------|:--:|------|
| 分身广场 | `/platform` | SKILL_USE | 浏览 + 开始萃取 |
| 我的分身 | `/platform/my` | SKILL_USE | 管理自己的分身 |
| 分身详情 | `/platform/my/[skillId]` | SKILL_USE | 数据 + 素材上传 |
| 素材管理 | `/platform/my/[skillId]/materials` | SKILL_USE | 上传/查看素材 |

### 8. Partner IM对接 (`:8080/api/v1/im`)

| 端点 | 公开 | 说明 |
|------|:--:|------|
| `POST /im/{channel}/callback` | ✅ | 飞书/企微/微信/钉钉 回调 |

渠道类型：`feishu`、`wecom`、`wechat`、`dingtalk`

接入方式：第三方IM平台配置 Webhook → `https://域名/api/v1/im/{channel}/callback`

Partner嵌入H5：`https://域名/h5/interview/m/{code}?token=<partner_jwt>`

---

## 四、ShareCode 生成机制

| 属性 | 值 |
|------|------|
| 长度 | 10位 Base62 `[A-Za-z0-9]` |
| 碰撞重试 | 3次 |
| 自定义 | `PUT /admin/skills/{id}/share/code`（4-30位） |
| 渠道 | `public`（经典H5）/ `card`（名片式）/ `internal`（内部） |
| 启停 | `enabled` 字段实时控制 |

数据表：`skill_share` (id, skill_id, org_skill_id, share_code, channel, enabled, company_id)

---

## 五、游客限制与防滥用

| 限制 | 值 | 说明 |
|------|:--:|------|
| 单IP每小时创建游客 | 20次 | 防批量注册 |
| 游客每分钟消息 | 6条 | 防刷对话 |
| 游客每日消息 | 25条 | 促使用户注册 |
| 游客Token有效期 | 7天 | 滑动续期 |
| C端免费萃取次数 | 3次 | 访谈限制 |

---

## 六、各端架构关系图

```
                        ┌──────────────┐
                        │  B端管理员    │
                        │  :3000/admin  │
                        └──┬───┬───┬───┘
              shareCode   │   │   │  inviteCode
                  ┌───────┘   │   └──────────┐
                  ▼           │              ▼
    ┌── /s/{code} ──┐       │    ┌── /h5/interview/m/{code} ──┐
    │ 对外公开分享    │       │    │ H5访谈（扫码即用）         │
    │ 游客→注册升级   │       │    │ Partner ?token= 嵌入      │
    └───────┬────────┘       │    └──────────┬────────────────┘
            │                │               │
            ▼                ▼               ▼
    ┌──────────────────────────────────────────────┐
    │          C端 /skill/{skillId}                │
    │     QA问答 · Talk对话 · Practice对练         │
    └──────────────────────────────────────────────┘

    ┌── /i/{code} ──┐     ┌── 平台端 :3001 ──┐
    │ 对内企业分享    │     │ 分身Owner管理      │
    │ 需B端登录       │     │ 素材·数据·分享     │
    └───────┬────────┘     └────────┬──────────┘
            │                       │
            ▼                       ▼
    ┌──────────────┐     ┌─────────────────┐
    │  /skill/     │     │ /platform/my    │
    └──────────────┘     └─────────────────┘

    ┌──────────────────────────────┐
    │     Partner IM 对接          │
    │ /im/{channel}/callback       │
    │ 飞书·企微·微信·钉钉          │
    └──────────────────────────────┘
```
