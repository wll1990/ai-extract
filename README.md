# AI经验萃取平台 (AI Extract)

> 通过AI深度访谈，将销冠的隐性经验转化为可传播的专业成果。
> 以"人"为单位建立经验空间，生成AI分身Skill，实现经验的精准匹配与即时调用。

---

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| **前端** | Next.js + TypeScript + Tailwind CSS | 14 |
| **后端** | Spring Boot + Java + Maven | 3 / 17 / 3.8 |
| **AI服务** | Python + FastAPI | 3.11 / 0.111 |
| **数据库** | PostgreSQL + pgvector | 16 |
| **缓存** | Redis | 7 |
| **向量存储** | pgvector | 0.7 |
| **LLM** | DeepSeek (可插拔切换) | deepseek-chat |

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
ai-extract/                            # 项目根目录 (Java74 + Python10 + TS26 = 110+文件)
│
├── frontend/                          # 前端 Next.js 14
│   └── src/
│       ├── app/                       # 页面路由 (13页)
│       │   ├── login/                 # A1 登录页
│       │   ├── page.tsx               # A2 首页工作台
│       │   ├── interview/             # B2创建访谈 + B3对话页
│       │   ├── report/                # B4生成完成 + B5报告详情
│       │   ├── skill/                 # C4 AI分身对话页
│       │   ├── explore/               # C1 经验广场
│       │   ├── tools/                 # C5 工具箱
│       │   └── admin/                 # D1管理后台 + D2覆盖地图 + D3萃取师库
│       ├── components/                # 复用组件 (7个)
│       │   ├── chat/                  # PhaseProgressBar / MessageBubble
│       │   ├── voice/                 # VoiceInput (语音降级)
│       │   ├── skill/                 # SkillChatWindow
│       │   └── modals/                # ResumeModal / UploadExpertModal / ReviewGrainsModal
│       └── lib/
│           ├── api/                   # API封装 (interview / report / skill / expert)
│           └── sse.ts                 # SSE流式读取工具
│
├── backend/                           # 后端 Spring Boot 3
│   ├── pom.xml                        # Maven依赖 (Spring Security/JPA/Flyway/Redis/JWT/WebFlux)
│   └── src/main/
│       ├── java/com/aiextract/
│       │   ├── controller/            # 8个Controller (Auth/Expert/Im/Interview/Report/Skill/Space/Tool)
│       │   ├── service/               # 10个Service (含 FeishuAdapter/SseEmitterService/ReportGeneration)
│       │   ├── repository/            # 13个Repository (全表覆盖)
│       │   ├── model/                 # 13个JPA实体 (全表覆盖)
│       │   ├── dto/                   # 18个DTO
│       │   ├── config/                # SecurityConfig + JwtAuthFilter
│       │   ├── client/                # AiServiceClient (WebClient→Python)
│       │   ├── exception/             # GlobalExceptionHandler + BusinessException
│       │   ├── common/                # ApiResponse 统一响应
│       │   └── util/                  # JwtUtil (生成/验证/解析)
│       └── resources/
│           ├── application.yml        # 数据源/Redis/JWT/AI服务/存储配置
│           ├── db/migration/          # Flyway迁移 (V1__init.sql + V2__seed_data.sql)
│           └── templates/             # report_word.ftl + report_ppt.pptx
│
├── ai-service/                        # AI服务 Python FastAPI
│   ├── main.py                        # 入口 (含 startup/shutdown 热加载事件)
│   ├── requirements.txt               # 依赖 (fastapi/uvicorn/openai/watchdog等)
│   ├── routers/
│   │   └── interview.py               # /internal/chat + /internal/report/generate + /health
│   ├── services/
│   │   ├── interview_engine.py        # 访谈引擎 (流式追问/阶段检测)
│   │   ├── report_generator.py        # 报告生成器 (六章JSON)
│   │   ├── skill_loader.py            # Skill加载器 (PromptCache + PromptWatcher热加载)
│   │   ├── expert_extractor.py        # 萃取师经验提取器
│   │   └── expert_composer.py         # 多萃取师经验组合器 (共识/独家/矛盾)
│   ├── prompts/                       # Prompt模板 (13个 .md)
│   │   ├── interview_system.md        # AI萃取专家
│   │   ├── skill_qa_system.md         # 分身问答基础模板
│   │   ├── meta_interview_system.md   # 元萃取访谈
│   │   ├── interview_opening.md       # 访谈开场
│   │   ├── interview_completion.md    # 访谈完成致谢
│   │   ├── interview_resume.md        # 访谈恢复
│   │   ├── skill_practice_customer.md # 对练客户扮演
│   │   ├── skill_practice_evaluate.md # 对练评价
│   │   ├── enterprise_system.md       # 企业分身
│   │   ├── grain_extraction.md        # 锦囊提取
│   │   ├── expert_document_extraction.md  # 萃取师材料提取
│   │   ├── expert_structure_report.md # 萃取师报告格式化
│   │   └── expert_grain_extraction.md # 萃取法则提取
│   └── scripts/
│       └── generate_ppt_template.py   # PPT母版生成 (24页)
│
├── docker-compose.yml                 # 5容器编排 (db/redis/backend/ai-service/frontend)
├── .editorconfig                      # 三语言编码风格统一
├── tests/
│   └── 手工测试用例.md                # 10个测试场景
└── README.md                          # 本文件
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

## API接口总览 (56个)

| 模块 | 数量 | 说明 |
|---|---|---|
| 认证 `/auth/*` | 3 | 登录、注册、获取当前用户 |
| 空间 `/spaces/*` | 4 | 空间CRUD |
| 访谈 `/interviews/*` | 8 | 创建、对话(SSE)、消息、恢复、暂停 |
| 报告 `/reports/*` | 4 | 列表、详情、编辑、下载 |
| 分身 `/skills/*` | 5 | 问答(SSE)、对练、企业总调度、反馈 |
| 工具 `/tools/*` | 2 | 资料列表、下载 |
| 管理 `/admin/*` | 7 | 空间管理、场景覆盖、配置、邀请 |
| 专家经验 `/admin/experts/*` | 10 | 上传、提取、审核、激活、文件管理、综合Skill |
| IM `/im/*` | 6 | 回调接收、渠道管理、测试 |

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

- `docs/13-编码规范.md` — 阿里巴巴Java规范 + PEP 8 + TypeScript规范
- `docs/05-代码实现逻辑文档.md` — 全部49个接口实现步骤
- `.editorconfig` — 统一缩进/换行/编码

### 关键约定

- **所有Java公共方法必须有Javadoc**
- **使用构造器注入 (`@RequiredArgsConstructor`)**
- **使用 SLF4J 日志，敏感信息不打日志**
- **Git提交格式**：`feat(module): 描述`
- **Git分支**：`feature/xxx` / `fix/xxx`

---

## 许可证

内部项目，仅供企业授权使用。
