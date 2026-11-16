# 课程工作台与真实Skill平台落地方案

日期：2026-11-13。版本：V1设计稿；尚未实施。本文为当前实施基线，替代39—42号文档中的冲突选项。

## 1. 本次明确选择

- 同一产品、同一登录、同一前端、同一PostgreSQL数据库，首版不新建数据库。
- 新业务命名“课程工作台”，做团队资料归集、查询及真实Skill执行；现有“我的培训/培训计划”继续做人员学习认证，不改其含义。
- 同库独立course_业务表，防止课程材料进入现有销售知识案例检索；不建设复杂跨空间授权体系。
- 统一升级现有backend至Spring AI 2.0.x及官方兼容Spring Boot；真实Skill Runtime仍独立子工程/进程，双方锁定兼容版本。
- 复用现有OSS、解析服务、权限基础、IM发布连接和桥接；真实Skill与数字分身Skill数据独立。
- 先网页资料归集与检索、真实Skill试运行，打磨输入输出；最后接IM文字查询和补充背景。
- 只建设实际可运行入口，不做多Agent编排画布、IM上传审批、自动采集所有聊天、复杂客户项目管理。

## 2. 现有能力如何复用

| 现有能力 | 复用方式 | 边界 |
|---|---|---|
| 用户、企业、权限和菜单 | 沿用登录，新增课程权限和导航domain | 不新建独立账号体系 |
| 文件上传与OSS | 复用存储SDK，扩展课程原件、能力包和产物的对象前缀/校验 | 文件路径不是权限，下载仍鉴权 |
| Python文件解析 | 共用解析接口 | 图片/扫描等未支持能力明确提示，不冒充成功 |
| 知识/案例管理 | 复用UI组件、结构字段、解析/检索算法 | 首版课程检索不扫描销售表 |
| 模型与向量 | 经公共模型网关复用 | 去掉全局可变凭证，按调用上下文取配置 |
| IM渠道发布/重连/验签 | 复用现有渠道与Node桥 | 新增类型路由，不能强造一个销售Skill ID |

已有销售资料若要用于课程，提供显式“复制到课程资料”操作：校验读权、确认共享、创建课程资产并保留来源类型/ID/版本；不实时跨表全量搜索，不改原资料。

## 3. 工程与职责

```
frontend/src/app/course-workspace/   新增业务页面
backend/.../course/                 课程资产、会话、运行管理、助手
backend/.../agent/                  能力包、版本和运行协议（避免依赖销售类）
agent-runtime/                     独立pom与镜像：Skill加载、模型与工具循环
sandbox-worker/                    隔离执行脚本
ai-service/                        继续负责文件解析
feishu-bot/                        继续负责IM传输
```

不要求重排既有包；新类遵循现有扫描/分层，命名和依赖明确。公共方法抽取到文件、模型、检索接口，不让课程Controller依赖销售Controller。
backend管理权限和run状态；Runtime不直接访问业务数据库，不建第二套用户或知识库；Worker不执行课程审批逻辑。
可复用SkillsTool、MCP与工具调用；多Agent和A2A只保留扩展，不进入首版验收。

## 4. 入口和角色

当前NavConfigController已有按domain分组机制。增加domain=course_workspace、显示名“课程工作台”。

| 菜单 | 路由 | 职责 |
|---|---|---|
| 团队资料 | /course-workspace/assets | 文件、知识、案例、历史方案和方法资料 |
| 资料问答 | /course-workspace/ask | 检索、回答、引用及多轮补充 |
| Skill能力 | /course-workspace/capabilities | 真实Skill、提示词、工具能力的导入/试运行/发布 |
| 我的运行 | /course-workspace/runs | 历史执行、进度、产物和错误 |
| IM助手 | /course-workspace/assistants | 课程资料助手配置、测试和复用渠道发布 |

首版不另建仪表盘：首页进入团队资料，问答/运行使用自然导航。
新增COURSE_USE（查询、已发布能力执行）、COURSE_ASSET_MANAGE、COURSE_CAPABILITY_MANAGE、COURSE_ASSISTANT_MANAGE；企业管理员默认管理，团队成员由管理员授权。
素材可见范围首版只有private和company，私人仅本人及明确授权管理员可读；不做部门知识空间。
新权限通过RolePermissions/用户授权体系实现，不能只隐藏前端。是否需要用户级授权表，细化时核对现有机制；不得靠前端localStorage新增权限。

## 5. 资料收集、处理与存储

用户填标题、资料类型、来源/原项目、适用场景、内容性质与共享范围；系统记录企业、提交人和时间。
类型：knowledge/case/proposal/course_material/method；性质：original/human_curated/ai_draft/verified。
案例编辑可提供背景、问题、行动、结果与证据；原始需求先当original资料，不能自动成为verified案例。
共享初始为private，明确点击确认共享后company；企业可先保持简单，不建多级审核流。

处理线：uploaded→parsing→indexing→ready；错误failed/needs_manual。共享状态独立，不用ready代替可信/已批准。
原件在OSS，解析正文和章节索引单独存。文本按章节切分、超长再细分；原文和出处与向量同在chunk记录中。
使用现有配置的embedding模型作为首版基线，记录模型/维度/索引版本；更换模型重建索引，不能直接混查。
文档原文索引独立于可选结构化萃取，先能查原文；不强迫所有上传进入销售萃取/验收调度。
更新产生不可变资料版本，索引完整后切换当前版本；旧引用保留，取消共享立即限制检索/下载。
同企业内容hash用于提示重复，但不越权泄露私有文件存在性。

## 6. 同库独立表

| 表 | 主要字段/用途 |
|---|---|
| course_asset | 企业、提交人、类型、性质、范围、来源、当前版本 |
| course_asset_version | 原件key/hash、解析状态、正文引用、索引版本、错误 |
| course_asset_chunk | 资料版本、章节顺序、正文、向量、当前索引关联 |
| course_query_session/message | 查询、补充背景、已授权引用；与销售会话分开 |
| agent_skill_package/version | 原包、目录清单、依赖、适配配置、输入输出规范、发布状态 |
| agent_run/attempt | 发起人、企业、固定能力版本、输入与资料引用、执行状态、租约和预算 |
| agent_run_event/artifact | 进度、工具事件、脱敏错误、产物key及权限继承 |
| course_assistant | 名称、企业、查询规则、启用状态；首版仅资料查询 |
| im_external_identity | 渠道租户/外部用户与内部User的验证绑定 |

先沿用现有数据库和Flyway，实际迁移编号以实施时最新仓库为准。
旧knowledge_entry/industry_case继续服务销售；课程查询只读course_表。数据库相同不会造成自动互查。
agent_表是通用执行协议，首版run.domain固定course_workspace，权限绑定课程业务，不允许调用者伪造domain。
不新建完整课程管理、课表、订单、合同或学习认证表；生成大纲在本期是artifact，不等于正式发布课程。

## 7. 真实Skill生命周期

zip导入保留SKILL.md、references/scripts/assets及相对路径，包版本不可变。
校验入口、解压路径、文件数与大小；平台补依赖清单和执行配置，不把方法重写为Java分支。
状态：imported→needs_adaptation/ready_for_test→tested→published→disabled。
试运行成功仅表示技术执行完成；管理者看实际产物确认质量后才能发布。新版重新试运行。
首版实现prompt和一个真实agent_skill的完整链路，script作为工具执行；不承诺所有桌面专有工具兼容。
Runtime加载已选包，按需读取参考文件；工具只开放授权检索、原文读取、工作区文件、受控shell和产物保存。
脚本在独立run目录和受限Worker里运行；依赖固化镜像，不在backend中执行上传命令。

## 8. 查询与运行交互

### 资料查询

网页发问题→backend验证成员→查询course资产（company+本人private）→关键词/向量检索合并→读取授权片段→模型回答→返回资料ID/版本/章节引用。
补充背景保存当前会话，参与后续检索；默认不写进资料库。
模型推断与来源事实分开，无匹配明确说明。引用链接读取时重新鉴权。

### Skill执行

用户选已发布能力→填输入/选参考资料→backend校验→幂等建run，返回runId→前端轮询。
Runtime经内部服务身份租约领取→下载固定包和授权输入→模型/工具循环→Worker执行→输出文件存储→backend登记产物及状态。
查询通过run凭证调用backend课程检索，不直接连库；输入附件/资料只能来自run批准范围。
重试创建新attempt保留旧结果；取消尽力终止当前调用，不以关闭浏览器代替取消。
租约心跳和fencing token阻止旧Worker覆盖新结果；重启中断显示interrupted/待重试，不假装任意脚本续跑。
首版轮询约3秒、失败退避、页面隐藏暂停、终态停止，刷新按ID恢复。

## 9. 页面按钮与API

路径前缀/api/v1；普通响应ApiResponse，分页PageResponse。以下是待实施契约，不是现有接口。

| 页面按钮 | API | 实际行为/错误处理 |
|---|---|---|
| 上传资料 | POST /course/assets（multipart） | 返回assetId/versionId；显示解析进度，不等向量完成 |
| 资料列表/详情 | GET /course/assets；GET /course/assets/{id} | 统一权限、类型、关键词筛选 |
| 查看处理/重试 | GET /course/assets/{id}/processing；POST /course/assets/{id}/retry | 返回失败步骤；只重试处理，不重复建原件 |
| 编辑信息 | PUT /course/assets/{id} | 乐观版本校验，409提示刷新 |
| 更新文件 | POST /course/assets/{id}/versions | 新版处理完成后切换 |
| 确认/取消共享 | PUT /course/assets/{id}/visibility | 显式确认范围，立刻影响检索和下载 |
| 查看/下载原件 | GET /course/asset-versions/{id}/download | 鉴权后短期下载，原路径不可直接公开 |
| 归档资料 | POST /course/assets/{id}/archive | 停止新检索，历史run引用按保留策略授权 |
| 提问/继续问 | POST /course/query（SSE） | 会话ID、正文、来源、结束/错误事件 |
| 导入真实Skill | POST /agent/skills/import | 返回能力版本、文件清单与兼容问题 |
| 保存适配配置 | PUT /agent/skill-versions/{id}/adapter | 校验环境、入口和工具清单 |
| 试运行/正式运行 | POST /agent/runs | 前者仅管理者，后者只允许已发布版本 |
| 运行详情/取消/重试 | GET /agent/runs/{id}；POST /agent/runs/{id}/cancel或retry | 状态、真实步骤和错误；幂等防重复 |
| 发布/停用Skill | POST /agent/skill-versions/{id}/publish或disable | 校验已确认试运行，停用不丢历史 |
| 查看/下载产物 | GET /agent/artifacts/{id}/download | 继承run权限，非永久公开链接 |
| 新建/保存IM助手 | POST /course/assistants；PUT /course/assistants/{id} | 明确只读资料问答，不是销售分身 |
| 测试助手 | POST /course/assistants/{id}/test-query | 调真实课程查询，显示引用 |
| 发布/重连IM | 扩展现有/im/channels发布与重连接口 | 使用类型化目标，复用扫码/连接/验签 |

列表/详情/表单均有加载、空态、字段错误、403/404、409和网络重试反馈；操作失败不提前修改页面数据。
未实现功能不放占位按钮。上传成功不显示萃取成功，调用完成不冒充专业审核通过。

## 10. IM发布复用及数据路由

扩展im_channel为target_type=sales_skill|course_assistant与target_id；旧记录回填sales_skill，保持现有skill_id兼容过渡。
现有强制skill_id非空校验、入站路由、发布参数、重连及管理列表同步改为按类型验证。
已有扫码授权、连接、HMAC、消息去重与Node桥继续复用，不建设第二套发布服务。
首版建议一个机器人一个明确用途；不在同一聊天中自动猜销售/课程上下文。

销售机器人→现有销售聊天服务；课程机器人→CourseQueryService→仅course_资料。
成员先绑定内部身份；未绑定时提供绑定入口，不用合成UUID直接赋employee权限。
首版课程助手只开内部私聊；群聊待共享范围验证后再开放，避免个人私有资料外泄。
桥接回复保留来源标题和受控链接；首版不接文件/语音上传、审批、Skill执行或主动任务通知。
企业停用、成员禁用、助手停用、资料取消共享在下一次请求及时生效。

## 11. Spring AI升级工作包

现有backend当前pom为Spring AI 1.0.0-M5/Spring Boot 3.3.2；目标锁定Spring AI 2.0.x稳定版及官方兼容Boot版本。
先在验证分支/隔离环境做完整依赖树与API迁移，不能只替换版本号；旧starter、ChatClient响应、工具调用、模型装饰器和测试须逐项验证。
Runtime独立pom也使用经验证的兼容组合，技能工具库版本单独锁定。
升级回归：登录权限、JPA/Flyway、Redis、OSS解析、普通/流式聊天、Embedding、素材萃取、知识案例、评测培训、IM发布与回复。
向量内容不因框架升级自动重建；只有实际embedding模型/参数变化才计划重建。
升级失败回退应用版本；数据库变更采用向后兼容扩展，不先删除旧列。

## 12. 分阶段交付与验收

| 阶段 | 工作 | 验收 |
|---|---|---|
| P0 底座验证 | 框架升级、独立Runtime/Worker技术样本 | 旧功能回归；真实Skill能读参考、执行脚本、输出文件 |
| P1 资料入口 | 新菜单、course资产/索引、原件/权限、课程问答 | 3—5个历史项目、20个实际问题逐项核对；销售查询不命中course资料 |
| P2 团队能力 | 包导入/版本、任务协议、输入/产物页面 | 非原作者完成3份历史需求，生成可审查文件，错误/刷新/取消正常 |
| P3 体验与质量 | 打磨方法、模板、输入与结果；明确缺失信息 | 专家记录修改量和错误，固定样例回归 |
| P4 IM复用 | course助手发布、真实身份、文字问答与引用 | 原销售机器人正常；课程机器人仅查课程资料；补背景能继续问 |

实施时P0的框架验证和真实Skill验证可分别推进，但本设计不要求自动多Agent开发。
不预先承诺周数：P0产出迁移问题清单后按团队人力估算。

## 13. 完成定义

一个企业成员登录现有系统，在“课程工作台”收资料、查资料、使用另一个成员导入的真实Skill生成文件，并能通过已发布IM助手查询共享资料。
销售原有知识案例、分身、培训学习与IM保持原有业务含义；课程资料不自动进入销售检索；真实Skill不写入数字分身Skill表。
本方案仅增加清晰的新业务模块与独立执行服务，不把旧系统改成万能Agent平台。

官方版本依据（2026-11-13已查）：https://docs.spring.io/spring-ai/reference/getting-started.html；https://docs.spring.io/spring-ai/reference/upgrade-notes.html；https://github.com/spring-ai-community/spring-ai-agent-utils 。
