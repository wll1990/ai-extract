# AI经验萃取平台 (AI Extract)

> 通过AI深度访谈，将销冠的隐性经验转化为可传播的专业成果。
> 以"人"为单位建立经验空间，生成AI分身Skill，实现经验的精准匹配与即时调用。

---

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| **前端** | Next.js + TypeScript + Tailwind CSS | 14 |
| **后端** | Spring Boot + JPA + pgvector | 3 / 17 |
| **AI服务** | Python + FastAPI | 3.11 |
| **数据库** | PostgreSQL + pgvector | 16 |
| **缓存** | Redis | 7 |
| **LLM** | DeepSeek + Qwen (可插拔) | deepseek-chat |

## 开发工具

- **Graphify** — 代码库知识图谱，`/graphify .` 构建后在任意 session 自动生效
- **CLAUDE.md** — Claude Code 项目指令，含架构原则和编码规范

---

## 快速启动

### 前置要求

| 工具 | 最低版本 |
|---|---|
| Docker + Docker Compose | 24 / 2 |
| Java + Maven | 17 / 3.8 |
| Node.js + npm | 18 / 9 |
| Python + pip | 3.11 / 23 |

### 1. 配置环境变量

项目需要配置两个环境变量文件：

**根目录 `.env`**（Docker Compose 使用）：
```bash
cp .env.example .env
# 编辑 .env，填入真实的 API Key 和 JWT 密钥
```

```env
DEEPSEEK_API_KEY=sk-your-real-api-key-here
JWT_SECRET=your-strong-random-secret
```

**AI服务 `.env`**（Python FastAPI 使用）：
```bash
cp ai-service/.env.example ai-service/.env
# 编辑 .env，填入真实的 API Key
```

```env
LLM_API_BASE=https://api.deepseek.com
LLM_API_KEY=sk-your-real-api-key-here
LLM_MODEL=deepseek-chat
```

> ⚠️ 两个文件中的 API Key 需要保持一致。`.env` 文件已被 `.gitignore` 忽略，不会提交到仓库。

### 2. Docker Compose 一键启动

```bash
docker-compose up -d
```

### 3. 验证服务

| 服务 | 地址 | 验证方式 |
|---|---|---|
| 前端 | http://localhost:3000 | 浏览器打开，看到首页 |
| 后端API | http://localhost:8080/api/v1 | `curl /api/v1/auth/me` 返回401 |
| AI服务 | http://localhost:8000/docs | Swagger文档 |
| PostgreSQL | localhost:5432 | `psql -h localhost -U postgres -d aiextract` |
| Redis | localhost:6379 | `redis-cli ping` 返回 PONG |

### 4. 演示账号

| 账号 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `admin` | `123456` | 超级管理员 | 管理企业配置、萃取师经验库 |
| `zhouming` | `123456` | 销冠 | 创建空间、接受AI访谈、生成报告 |
| `zhangsan` | `123456` | 普通员工 | 浏览经验广场、使用AI分身 |

---

## 项目结构

```
ai-extract/
│
├── frontend/                          # 前端 Next.js 14 (App Router)
│   └── src/
│       ├── app/                       # 页面路由 (17页)
│       │   ├── login/register/        # 认证
│       │   ├── page.tsx               # 首页工作台
│       │   ├── interview/             # AI访谈
│       │   ├── report/                # 报告生成与查看
│       │   ├── skill/[skillId]/       # 🔥 AI分身广场 (QA/Talk/Practice)
│       │   ├── s/                     # 分享H5 (游客即聊)
│       │   ├── explore/               # 经验广场
│       │   ├── space/                 # 销冠空间
│       │   └── admin/                 # 管理后台
│       ├── components/
│       │   ├── skill/                 # SkillChatView / PracticeView / SkillOpeningView
│       │   ├── admin/                 # 审核步骤组件
│       │   └── modals/                # ProductDemoModal / PracticeScenarioModal
│       └── lib/
│           ├── api/                   # API 客户端
│           └── sse.ts                 # SSE 流式读取
│
├── backend/                           # 后端 Spring Boot 3
│   └── src/main/java/com/aiextract/
│       ├── controller/                # 23个Controller
│       ├── service/                   # 33个Service (含 ChatStreamService/ExpertAnalysisService)
│       ├── repository/                # 31个Repository
│       ├── model/                     # 32个JPA实体
│       ├── dto/                       # DTO
│       ├── config/                    # Security / SSE / Prompt / Domain
│       ├── scheduler/                 # 定时任务 (素材清洗/颗粒萃取)
│       └── common/                    # ApiResponse / GlobalExceptionHandler
│
├── ai-service/                        # AI服务 Python FastAPI
│   ├── main.py                        # 入口
│   ├── routers/internal.py            # /internal/chat + /internal/report/generate
│   └── services/                      # 访谈引擎 / 报告生成器 / 萃取器
│
├── prompts/                           # Prompt模板 (40+个 .md)
│   ├── skill_qa_system.md             # 分身问答
│   ├── skill_practice_customer.md     # 对练客户扮演
│   ├── interview_*.md                 # 访谈系列
│   ├── expert_*.md                    # 萃取师系列
│   ├── practice_*.md                  # 对练评测系列
│   └── material_*.md                  # 素材处理系列
│
├── docs/                              # 设计文档 + API文档 + 测试用例
├── .claude/                           # Claude Code 配置
│   ├── settings.json                  # PreToolUse hooks (Graphify)
│   └── skills/graphify/               # 知识图谱技能
├── docker-compose.yml                 # 5容器编排
└── CLAUDE.md                          # Claude Code 项目指令
```

---

## 核心功能

### 销冠萃取流程

```
创建空间 → 创建访谈(选择萃取风格) → AI四阶段对话
  → 报告生成(六章) → Word/PPT下载 → AI分身激活
```

### 员工学习流程

```
浏览经验广场 → 查看销冠空间 → 阅读报告
  → 使用AI分身(问答/对练) → 下载资料 → 提交反馈
```

### AI分身三种模式

| 模式 | 触发 | 风格 |
|---|---|---|
| **快问快答** | 直接提问 (默认) | 结论先行，150字内 |
| **深度探讨** | `/讨论` 或分析性问题 | 案例先导，反问互动 |
| **实战对练** | `/对练` | 扮演客户，逐轮评判 |

### 元萃取系统

```
上传萃取师材料 → AI分析 → 生成报告 → 提取法则
  → 审核法则 → 激活Skill → 应用于访谈追问
```

---

## 开发模式启动

如果不想使用 Docker 一键启动，可以分别启动各个服务：

```bash
# 1. 仅启动数据库和缓存
docker-compose up -d db redis

# 2. 启动 AI 服务
cd ai-service
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # 填入 API Key
uvicorn main:app --reload --port 8000

# 3. 启动后端
cd backend
./mvnw spring-boot:run

# 4. 启动前端
cd frontend
npm install
npm run dev
```

---

## API接口总览 (148个)

| 模块 | 数量 | 说明 |
|---|---|---|
| 认证 `/auth/*` + `/c/*` | 9 | 登录、注册、JWT、C端用户 |
| 空间 `/spaces/*` | 5 | 空间CRUD |
| 访谈 `/interviews/*` | 11 | 创建、对话(SSE)、消息、恢复 |
| 报告 `/reports/*` | 7 | 列表、详情、编辑、下载 |
| 分身 `/skills/*` | 21 | 问答(SSE)、对练、企业调度、反馈 |
| 分身画像 `/skill-profiles/*` | 2 | 画像CRUD |
| 素材 `/materials/*` | 8 | 上传、清洗、管理 |
| 工具 `/tools/*` | 3 | 资料列表、下载 |
| 管理后台 `/admin/*` | 60+ | 审核流水线、颗粒管理、萃取师、洞察、分享管理、通知 |
| IM `/im/*` | 7 | 回调接收、渠道管理 |
| 公开接口 `/public/*` | 3 | 分享页数据、H5访问 |

完整文档见 `docs/03-API接口文档.md`

---

## 常用命令速查

```bash
# === Docker ===
docker-compose up -d              # 启动全部服务
docker-compose down               # 停止全部服务
docker-compose logs -f backend    # 查看后端日志
docker-compose up -d --build      # 重建并启动

# === 数据库 ===
docker exec -it ai-extract-db-1 psql -U postgres -d aiextract

# === 前端 ===
cd frontend && npm run dev        # 启动开发服务器
cd frontend && npm run build      # 生产构建

# === 后端 ===
cd backend && ./mvnw spring-boot:run      # 启动
cd backend && ./mvnw clean package        # 打包

# === AI服务 ===
cd ai-service && source venv/bin/activate && uvicorn main:app --reload --port 8000
cd ai-service && python3 scripts/generate_ppt_template.py   # 生成PPT母版
```

---

## 开发规范

参见项目文档：

- [CLAUDE.md](CLAUDE.md) — Claude Code 项目指令（架构原则 + 性能红线 + 反模式）
- `docs/13-编码规范.md` — 阿里巴巴Java规范 + PEP 8 + TypeScript规范
- `docs/05-代码实现逻辑文档.md` — 全部接口实现步骤
- `.editorconfig` — 统一缩进/换行/编码
- `.claude/skills/graphify/` — 知识图谱技能，`/graphify .` 构建后自动生效

### 关键约定

- **Controller 薄，Service 厚** — 业务逻辑、事务、AI调用全部在Service层
- **长耗时操作异步化** — AI调用/文件处理走 `@Async` 或 Scheduler
- **SQL只在Repository** — 禁止Scheduler/Controller里写SQL
- **零Mock零假数据** — AI调用失败抛异常，不造假降级
- **使用构造器注入 (`@RequiredArgsConstructor`)**
- **Git提交格式**：`feat: 描述` / `fix: 描述`
- **Git分支**：`feature/xxx` / `fix/xxx`

---

## 许可证

内部项目，仅供企业授权使用。
