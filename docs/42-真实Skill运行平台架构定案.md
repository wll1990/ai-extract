# 真实Skill运行平台：架构定案

> 方案更新：43号文档按用户后续明确要求，采用同库独立业务表、现有系统课程工作台入口、复用IM发布、统一升级backend与Runtime至兼容的Spring AI 2.0.x。本文“旧backend暂留旧版”不再是当前目标；业务与执行的职责边界仍保留。

2026-11-13。技术设计，未实施。替代41号文档含混的运行边界；不修改现有数字分身Skill语义。

## 1. 只采用这一条路线

现有backend承担业务控制面，新增独立agent-runtime承担真实Skill执行；sandbox-worker专门执行脚本。
继续同一仓库管理，但agent-runtime有独立pom、依赖与镜像；不与旧backend放在同一Spring进程，不共用BOM。
新Runtime采用Spring AI 2.0.x稳定线与官方兼容Spring Boot，复用spring-ai-agent-utils的SkillsTool。锁定经集成测试的具体版本组合，不直接跟随main。
旧backend暂留现有版本；其后续升级是独立维护任务，不作为首条Skill链路前置。
首版Runtime单部署单团队试点，任务协议携带授权上下文以便扩展；并非未经验证就声明已满足大规模多租户。

## 2. 严格职责

| 组件 | 唯一职责 | 明确不做 |
|---|---|---|
| 现有backend | 资料与知识、成员权限、Skill包登记/版本、运行账本、产物权限、企业模型配置 | 解释SKILL.md、执行Python/Bash、维护Agent循环 |
| agent-runtime | 加载原Skill、上下文、模型与工具循环、调用记录、产物清单 | 自建用户/知识库、用SQL读业务表、定义课程业务审批 |
| sandbox-worker | 按run隔离的文件、批准的Python/Node/Bash执行、资源限制 | 判断业务权限、持有全平台Key、任意访问宿主文件 |
| frontend | 资料与真实Skill导入、输入、进度和产物 | 自行计算运行状态或授权 |
| IM桥 | 以后转发文字查询到同一知识服务 | 成为另一套知识库或先行Skill执行入口 |

真实Skill登记命名AgentSkillPackage/AgentSkillVersion，与model.Skill（数字分身）无继承和表复用。
backend是run状态与产物登记的唯一账本；Runtime只有执行工作区与租约，不另建业务数据库。

## 3. 原包兼容策略

完整zip导入SKILL.md及scripts/references/assets，保持相对结构；包版本不可变，存OSS。
不把原Skill翻译为一套自定义工作流，不为每个课程Skill写Java业务分支。
平台生成独立执行配置：批准工具、运行镜像、资源上限、网络规则、输入/输出约定；不改写原SKILL.md。
SkillsTool负责加载与按需展开；受限文件工具和shell工具由Runtime绑定到sandbox，禁止直接使用不受限宿主执行器。
首版兼容SKILL.md+包内参考/模板+Python/Node脚本的已验证组合。Codex专有工具、浏览器登录等缺失依赖显示needs_adaptation，不能标ready。
首批只维护固定Python和Node运行镜像，依赖在镜像构建时固化；不允许Skill在运行时任意安装系统依赖。

## 4. 调用协议

用户点击运行→backend鉴权、锁定版本/输入资料范围、幂等建run→立即返回runId。
Runtime通过内部API租约领取任务，backend返回不可变RunSpec及短期运行凭证；未就绪包拒绝建run。
Runtime下载固定包和授权输入到隔离工作区→调用SkillsTool/模型→工具请求经范围检查→sandbox执行→结果进入Agent上下文。
生成结束后检查产物清单、登记输出→backend提交最终状态→网页读取结果。
backend负责生成预签名文件地址；Runtime不拿OSS主密钥。模型使用企业/用途配置的短期访问路径，优先经过后端模型代理，避免长期Key落入脚本环境。
搜索工具通过backend授权检索API查现有资料库；RunToken在服务端绑定范围，模型不能通过修改companyId扩大权限。

内部最小API职责：
- POST /internal/agent-runs/claim：原子领取租约，返回RunSpec。
- POST /internal/agent-runs/{id}/heartbeat：续约、查询取消标志。
- POST /internal/agent-runs/{id}/events：追加带序号的事件，重复序号幂等。
- POST /internal/agent-runs/{id}/artifacts：登记已上传文件，不直接公开。
- POST /internal/agent-runs/{id}/finish：凭租约版本终结，拒绝过期worker覆盖。
- POST /internal/agent-runs/{id}/knowledge-search：按run授权检索。

RunSpec包含runId、能力版本、包hash、输入、资料授权、模型配置版本、工具集合、运行镜像、超时/轮数/用量限制和输出约定。
以上接口为设计协议，细化时固定请求/响应与错误码后实施，不由客户端任意提供内部任务字段。

## 5. 首批工具

Skill：加载当前获准包；Read/List/Search：当前run工作区与包内文件；KnowledgeSearch：共享资料；Shell：当前sandbox批准环境；Artifacts：登记产物。
工具名可作本地Skill适配别名，但每个别名调用同一受限实现。缺少依赖返回明确工具错误，不默默跳过。
工具循环复用框架，不自研完整Agent框架；权限、预算、事件记录、取消检查使用统一包装层。

## 6. 状态与恢复

包：imported→needs_adaptation/ready_for_test→tested→published。
运行：queued→running→succeeded/failed；取消cancel_requested→cancelled。
租约超时的执行标interrupted，显式创建新attempt重试，保留旧工作区/产物；不承诺任意脚本能从中间行继续。
每次领取和提交携带fencing token；旧worker不能在租约失效后提交成果。
模型调用、脚本与网络在数据库事务外；只读检索和本地文件操作优先，首版不接邮件发送等外部副作用工具。
结果验证分两层：文件/结构/引用可用由程序验证；课程专业质量由真实任务对照与人工评价。

## 7. 数据与页面最小变更

backend新增AgentSkillPackage/Version、AgentRun/Attempt、AgentRunEvent、AgentRunArtifact；既有资料登记/原文索引能力按40/41号文档补齐。
不新建课程业务表，不挂到销售分身审核状态，不强制新建客户项目才能查询或试运行。
能力库：上传zip→显示目录和缺依赖→配置输入与环境→试运行→检查产物→发布。
执行页：输入需求/选授权资料→运行→真实进度→错误重试/取消→查看/下载产物。
版本更新重新试运行，历史run固定旧版。

## 8. 实施顺序

1. 用一份真实Skill建立独立Runtime最小工程，跑通加载SKILL.md、读references、执行包内脚本、生成文件；不先铺全部页面。
2. 增加backend运行协议、身份范围与隔离Worker，接同一份真实输入，校验进度、产物和失败恢复。
3. 完成网页导入/版本/试运行/发布、资料归集与知识检索接入。
4. 连续用3份历史需求打磨输入与输出，记录人工修改原因。
5. 最后飞书只接文字知识查询、补背景和引用链接。

第一步技术验收必须包含“脚本真实执行并输出文件”，不能用读取SKILL.md后生成一段文本代替。
旧销售系统回归只要求集成未破坏现有功能，不开展全后端升级工程。

## 9. 后续扩展规则

新增Skill：导入包和适配配置，不改销售业务代码。
新增工具：Runtime注册受控工具与授权，不给每个Skill复制服务。
新增依赖：新增版本化执行镜像，不在backend安装环境。
新增业务：业务端组织输入与结果，Runtime不增加“如果是销售/课程”的分支。
新增IM：接现有问答/业务API，不复制检索与知识存储。

资料依据（2026-11-13查询）：Spring AI 2.0.x兼容要求 https://docs.spring.io/spring-ai/reference/getting-started.html；SkillsTool社区实现 https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/docs/SkillsTool.md 。
