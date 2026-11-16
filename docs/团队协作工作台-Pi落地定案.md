# 三人团队协作工作台：Pi落地定案

> 历史候选说明：Pi尚未被选为最终执行器。当前统一比较见[方案对比与选型](团队协作方案对比与选型.md)，本文保留Pi接入参考，不作为当前唯一实施基线。

> 后续共识更新：产品支持“平台Runtime+模型 / Codex / Pi”三类执行方式，按真实Skill兼容性启用。本文Pi方案保留为首个适配器候选，不再表示整个系统锁定Pi。当前交互基线见《团队协作工作台-功能与交互清单》。

2026-11-13。当前实施基线，取代39—43号文档中的执行引擎和首版范围冲突；本次只做技术核实与方案落盘，未安装依赖、调用收费模型或修改业务代码。

## 1. 已确定的产品与架构

在现有销冠平台增加一个“团队协作”导航。三个人各自登录，通过网页访问同一共享工作区。
服务端统一使用Pi执行真实Skill；首个模型验证DeepSeek，不绑定个人APP或登录态。
保留现有Java/Next.js/PostgreSQL/OSS基础。新增Node.js/TypeScript的pi-runner，使用Pi SDK，不遥控终端UI，不要求三个桌面APP支持MCP。
首版不以Spring AI升级为前置，不再并行引入Codex App Server或自研Agent循环。
未来模型在Pi provider配置层扩展；其他引擎有明确需求再加适配，不预造多个执行器。

## 2. 已核实与未验证

- 本机pi命令存在，版本0.80.2，Node v22.23.2。
- 本机Pi SDK文档提供createAgentSession、session.subscribe、abort、SessionManager、ResourceLoader和自定义模型配置。
- 官方Pi说明支持真实Skill、SDK/RPC和模型provider扩展；DeepSeek官方有Pi接入指南。
- 尚未验证DeepSeek授权、网络、模型工具调用、实际Skill依赖和输出质量。
- 第一轮以0.80.2作为明确候选基线，锁定全部依赖；若必须升级，在验证分支比较并记录迁移，不直接跟随最新版。

## 3. 职责和部署

| 组件 | 职责 |
|---|---|
| 现有前端 | 工作区、资料文件、Skill选择、聊天任务、执行进度、成果版本 |
| 现有backend | 登录和成员权限、持久任务、文件与Skill登记、结果发布、下载 |
| pi-runner（独立Node子工程） | 包装Pi SDK、模型配置、工具扩展、执行事件和会话续接 |
| 隔离执行容器 | 承载一次执行所需资料、Skill、脚本依赖和Pi进程，不挂载宿主业务目录 |
| PostgreSQL/OSS | 业务账本、原件、不可变Skill包、成果与会话检查点 |

pi-runner通过内部认证接口领取任务、读写授权文件并回报状态，不直接访问业务数据库。
内部调度端不能把Docker socket交给Agent容器；容器只取得当前任务必需的资料和短期权限。
同一个模型的多次调用直接由Pi负责；Java不再调用Spring AI包住Pi内部的模型循环。
模型Key只注入运行进程，不作为聊天内容或工作区文件；同一进程不混用不同企业凭证。

## 4. 首版页面

主菜单“团队协作”→/team-workspace。
- 首页：工作区列表、新建、加入已邀请工作区。
- 工作区：共同背景、文件、任务、成果；优先做单页左右分栏。
- Skill库：包导入、说明、依赖、试运行和发布；可在工作区内选择使用。
不做第二套平台首页、复杂项目管理或实时文档编辑。

角色仅owner/member：owner邀请成员和管理Skill，member执行任务、查看共享成果；企业隔离复用已有身份体系。
首版同一工作区每次只允许一个写入型任务执行，其他人查看、取消或等待；不同工作区可并发，先限制全局最大2任务，可配置。
工作区临时目录、共享原件和发布成果分开，避免Agent覆盖共同文件。

## 5. 文件和Skill规范

工作区资料只要求标题、类型、来源、上传人、时间；上传后即可选为任务输入，解析与检索可稍后完成。
原件上传一次存OSS。每次运行按所选资料版本下载到input/，只读；包和参考文件放skills/，只读；work/为可写目录；output/登记产物。
共享文件被更新产生新版本；任务固定输入版本，Agent写入结果不会覆盖原件。
Skill保留SKILL.md与scripts/references/assets，不转换成自定义流程。
发布前以实际输入试运行：真实读参考、执行脚本、产出文件；专属工具缺失显示需适配。
首版只收受信任团队Skill，脚本依赖固定在镜像中；不运行来源不明的扩展或运行时安装任意系统包。
不导入成员整个个人配置目录；Skill引用的外部文件必须显式纳入依赖或替换为工作区资料。

## 6. 最小数据

team_workspace、team_workspace_member、team_file/version、team_agent_skill/version、team_agent_session、team_agent_run、team_agent_event、team_artifact。
现有销售知识和会话不混入这些表。首版任务使用显式文件，不依赖先建完整向量库。
可后续加只读检索工具引用已授权知识案例；不让课程运行默认搜索全部销售资料。
Pi原生会话检查点存受控持久存储，backend只保存其引用及执行记录；前端对话/结果从backend访问。

## 7. 一次执行的端到端流程

1. 成员选工作区、Skill版本、输入资料，填写需求。
2. backend验证成员、文件与Skill状态，原子检查工作区执行锁，幂等创建run，立即返回ID。
3. runner领取租约，准备固定版本资料、Skill和容器环境；新建或恢复该工作区的指定Pi会话。
4. Pi调用DeepSeek，按Skill读文件、执行脚本；runner订阅事件并回报脱敏文本/步骤/错误。
5. 前端从backend接收SSE；断线重连按事件序号补取。连接中断不取消任务。
6. 结果文件上传OSS，backend校验清单并登记新产物版本，释放工作区锁。
7. 另一成员输入修改意见，明确引用某成果版本，提交下一轮；保留旧版本。

多轮对话不是多人同时操纵同一Pi进程；任务串行、用户身份明确，执行过程中不合并来自不同人的未确认指令。
取消按钮→backend标记取消→runner调用abort并终止所属进程/容器；以最终观察状态为准。
心跳租约到期标interrupted，保留已保存事件和产物，人工重试新attempt；不宣称所有脚本均可无副作用断点续跑。

## 8. 关键API（待实施）

| 操作 | 路径（前缀/api/v1） |
|---|---|
| 列表/新建工作区 | GET/POST /team/workspaces |
| 工作区详情/修改背景 | GET/PUT /team/workspaces/{id} |
| 邀请现有成员 | POST /team/workspaces/{id}/members |
| 上传/列文件 | POST/GET /team/workspaces/{id}/files |
| 导入Skill | POST /team/skills/import |
| Skill详情/发布 | GET /team/skills/{id}；POST /team/skill-versions/{id}/publish |
| 试运行/正式任务 | POST /team/workspaces/{id}/runs（后端验证模式与权限） |
| 任务详情/事件/取消/重试 | GET /team/runs/{id}；GET /team/runs/{id}/events；POST /team/runs/{id}/cancel或retry |
| 产物下载 | GET /team/artifacts/{id}/download |

内部run领取、心跳、事件和产物回报使用独立短期认证。客户端不能任意指定服务器目录、命令、企业或模型Key。
每个写接口都有状态校验和幂等/版本条件，按钮不能只弹“成功”。

## 9. 明确开发顺序

### A：最小执行验证
建立agent-runtime/pi-runner独立Node工程，锁定Pi版本；验证DeepSeek工具调用及一个真实课程Skill，包含脚本和输出文件；与当前APP成果比较。
验收不依赖完整网页，不安装/改动三人电脑的Agent配置。

### B：共享工作区
新增菜单、表和接口，跑通上传、成员共享、任务开始、流式进度、成果下载、继续修改和版本记录。
验收：A上传，B生成，C修改；不手工转发生成文件；刷新/断线恢复正常。

### C：可持续使用
增加Skill管理、运行环境固定、失败取消重试、会话续接、简单全文检索，基于真实需求打磨输入与输出。
验收：三位成员各自完成真实任务，质量、错误和人工修改可追踪。

### D：后续按需
共享知识检索工具、IM文字查询、多模型设置；多Agent、外部操作和复杂流程按明确需求单独评估。

## 10. 必需样本与启动条件

一个真实Skill目录、一份有权使用的客户需求/参考资料、一份认可成果作为对照；模型访问配置由安全配置入口提供，不贴进需求文档。
样本缺失可以完成工程骨架和协议，但不能声称真实能力已经迁移成功。
先给每阶段独立验收，不在能力尚未验证时承诺完整工期或同等于所有成熟APP的效果。

依据：本机Pi 0.80.2随包docs/sdk.md与docs/models.md；https://pi.dev/；https://api-docs.deepseek.com/quick_start/agent_integrations/pi_mono/ 。
