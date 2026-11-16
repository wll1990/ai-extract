# 缺陷登记册

> 来源：四条真实链路的代码普查（素材/萃取、审核/发布/对话、复盘/教练、知识/培训/权限）。
> 编号规则：`M` 素材、`A` 审核与分身、`R` 复盘、`K` 知识培训权限。
> 共 111 条，其中 4 条与其它链路重复（条目内标「合并」），去重后 107 条。
> 状态：`待修` / `修复中` / `待验证` / `已关闭`。关闭必须有复现步骤与修复后验证。

## 批次与门禁

| 批次 | 范围 | 门禁 |
|---|---|---|
| 0 | 13 条 P0 + 直接依赖 | 全部关闭才进入批次 4 |
| 1 | 安全与租户隔离 | 跨企业越权用例全部 403 |
| 2 | 其余 P1 | 每条验证通过，导览章节逐个解锁 |
| 3 | P2 技术债 | 状态/格式/大小/导航收敛为单一来源，回归全绿 |
| 4 | 导览、手册、说明书 | 见计划第九至十三节 |

导览章节声明 `blockedBy`。对应缺陷未关闭，该章不出现在轨道里。

---

## 一、P0（13 条）

| ID | 链路 | 位置 | 现象 | 修法 | 状态 |
|---|---|---|---|---|---|
| M1 | 素材 | `backend/.../service/MaterialCleaningService.java:480-493,515-520` | 粘贴文本先落库再判重，`findBySkillId` 包含自身，5-gram 自相似 1.0 > 0.90，必定 `rejected` | 判重集合排除当前 `materialId`，或在 `save()` 前收集 | 已关闭 |
| M2 | 素材 | `MaterialCleaningService.java:416-444` | 文本上传无条件 `new Skill`，空间已有分身时撞 `skill(space_id)` 唯一索引，接口 500，前端显示「网络错误」 | 与文件路径一致，先 `findBySpaceId` 复用，抽公共 resolveSkill | 已关闭 |
| M3 | 素材 | `MaterialCleaningService.java:484,600-623` | `text://` 素材被调度器当文件调 ai-service，`os.path.exists` 404，永远停在 `uploaded` | `parseFile` 开头：`parsedContent` 非空且未超 `maxTextLength` → `STATUS_PARSED` | 已关闭 |
| M4 | 素材 | `SkillMaterialController.java:306-321` | 「补录文字」只写正文和备注，不改状态；下次解析又把备注改回「需人工补充」，永不清洗 | 提交后置 `parsed`；用状态字段承载，不用备注字符串 | 已关闭 |
| A1 | 分身 | `components/skill/TraceabilityDrawer.tsx:64-70`；`config/SecurityConfig.java:112` | 抽屉请求 `/admin/grains/traceability`，要 `GRAIN_MANAGE`；员工必然 403，抽屉空白且无 catch | 员工轨道改概念卡；抽屉补 `.catch` 与错误态；本轮不放宽 admin 接口 | 已关闭 |
| A3 | 审核 | `components/admin/ProductStep.tsx:61`；`service/PracticeDemoService.java:190-203` | 前端等 `mode='auto_demo'`，后端 `evaluateDemo` 从不落库；验证测试永远不完成，发布子步骤报告不出现 | `evaluateDemo` 落 `SkillEvaluation`；或前端改用真实写入的 mode | 已关闭 |
| A6 | 审核 | `frontend/src/app/admin/skills/page.tsx:106,149` | 停用写 `failed`、激活写 `active`，都不在列表分组，分身直接消失 | 停用写 `paused`、激活写 `reviewing`；列表新增「已停用」分组；`updateSkillStatus` 加白名单 | 已关闭 |
| A8 | 分身 | `service/ConversationService.java:91-111`；`app/skill/[skillId]/page.tsx:410-413` | 历史消息只回 `grainId`/`grainCount=1`、无 `avgSimilarity` → 刷新后显示「综合画像生成」，溯源按钮消失 | 历史消息持久化 `grainIds`；历史不按 `avgSimilarity` 判等级 | 已关闭 |
| A10 | 安全 | `controller/SkillController.java:224-243,268-285,432-437`；`service/ShareService.java:104-134,166-174`；`controller/SkillProfileController.java:31-55`；`AdminAuditController.java:696-712` | 多个按 ID 写操作只校验 URL 权限，无企业归属；可跨企业改状态、换头像、建分享、读写画像、重生成报告 | 统一 `CompanyScopeService.assertSkillOwnership`；C 端 companyId=null 时按空间属主校验；`GET /share` 已补，`GET /detail` 保留公开访问策略 | 已关闭 |
| R1 | 复盘 | `service/SalesCoachSupplementService.java:72-74` | 发起人 = 教练分身所有者时静默返回：不建请求、不发通知、无提示；演示主数据「华星科技」必现 | 返回 `selfCoach` 标记，前端引导去教练工作台；或 409 明确提示 | 已关闭 |
| K1 | 知识 | `service/AdminGrainService.java:113-124`；`controller/AdminInsightController.java:414-424`；`service/DashScopeEmbeddingService.java:168-174` | 三套向量化文本：批量=场景三字段；手动/候选=思考+话术；文档=六字段 | 抽唯一 `buildGrainText()`，三处复用，加单测 | 已关闭 |
| K3 | 知识 | `components/admin/KnowledgeGapPanel.tsx:52`；`app/admin/insights/[skillId]/page.tsx:142`；`app/skill/[skillId]/insights/page.tsx:96` | 补颗粒不关闭缺口；三个入口分别丢 `gapId` 或 `skillId` | 入口统一带 `skillId+gapId`；创建/审批时置 `resolved` | 已关闭 |
| K10 | 知识 | `app/skill/[skillId]/insights/grains/page.tsx:25-29,48,52` | 把 `PageResponse` 当数组 `map`，直接崩溃；接口又要求 `GRAIN_MANAGE`，分身主 403 | 取 `content`；owner 场景改用属主接口或移除入口 | 已关闭 |

---

## 二、素材与萃取（M）

| ID | 级别 | 位置 | 现象 | 修法 | 状态 |
|---|---|---|---|---|---|
| M5 | P1 | `MaterialCleaningService.java:625-657`；`MaterialCleaningScheduler.java:127-130` | `parseFile` catch 全部异常只写备注后返回 null，调度器收不到失败；`retry_count` 不增、状态不置 `parse_failed`，每 30 秒重扫 | catch 内写 `parse_failed` 并 rethrow，由调度器计重试 | 已关闭 |
| M6 | P1 | `MaterialCleaningService.java:618-623` | `webClient.block()` 无超时，AI 挂起时解析线程永久阻塞、锁不释放 | 增加 `.timeout(Duration.ofSeconds(120))` | 已关闭 |
| M7 | P1 | `SkillMaterialRepository.java:65,72,95`；`MaterialCleaningScheduler.java:112-114` | 查询按 `lockedAt < timeout` 捞任务，`tryLock` 只认 `lockedBy IS NULL`；崩溃留下的锁永远抢不到 | `tryLock` 条件改为 `lockedBy IS NULL OR lockedAt < :timeout` | 已关闭 |
| M8 | P1 | `MaterialCleaningService.java:315-353,1129-1150`；`SkillMaterialController.java:342-407` | 判重只在上传时可读文本执行；二进制、C 端、解析后、重试路径都不判重 | 判重收口到 `clean()` 解析后统一执行，排除自身 | 已关闭 |
| M9 | P1 | `app/admin/skills/upload/page.tsx:252`；`MaterialUploadGuide.tsx:73,82`；`application.yml:166`；`SkillMaterialController.java:356-365`；`ai-service/routers/internal.py:66-142` | 支持格式 5 处不一致；`.doc/.m4a/.json/.xml` 前端放行但解析不了 | 单一来源：后端返回格式列表；裁剪不支持项；补 `.htm` | 已关闭 |
| M10 | P1 | `MaterialUploadGuide.tsx:87`；`application.yml:89-91,164-165`；`SkillMaterialController.java:99,363` | 大小 4 套口径：指南 100MB、multipart 50MB、C 端 20MB、配置 50MB 未读取；第 99 行注释声称检查但无代码 | 读取并执行配置；按入口展示真实值 | 已关闭 |
| M11 | P1 | `MaterialCleaningService.java:382-385,553-556`；`upload/page.tsx:107` | 预检异常只置 `passed=false`，素材仍 `uploaded` 继续入库；无 `rejectReason`，前端红框空白 | 异常统一置 `rejected` 并写原因 | 已关闭 |
| M12 | P1 | `app/admin/skills/upload/page.tsx:84` | 文本上传 `catch { setError('网络错误') }`，丢弃 `ApiError.message`；413/500/400 全显示网络错误 | 使用 `e.message` | 已关闭 |
| M13 | P1 | `app/admin/skills/[id]/materials/page.tsx:7-16,70-75,107`；`[materialId]/page.tsx:29` | 状态映射缺 `parsed/parse_failed/cleaning_failed`，列表显示英文原文；详情直接渲染英文 | 补全映射，抽公共常量 | 已关闭 |
| M14 | P1 | `app/admin/skills/[id]/materials/page.tsx:36-41` | 轮询只在全部素材为 `extracted/rejected/discarded` 时停；待补录或卡死素材导致每 3 秒永久请求 | 终态补「待人工补充/重试上限」，设最大轮询次数 | 已关闭 |
| M15 | P1 | `MaterialCleaningService.java:1583-1593,1275`；`MaterialCleaningScheduler.java:245` | chunk 异常全被吞；全部失败仍走 `analyzed → extracted`，用户看到已萃取但 0 颗粒 | 全失败抛错，走 `cleaning_failed` 重试 | 已关闭 |
| M16 | P1 | `MaterialCleaningService.java:1550,1594,1597`；`config/AiConfig.java:113-125` | 清洗任务在 `cleanExecutor` 内向同一池提交 chunk 并 `join()`，两分身并发时线程池自锁 | chunk 用独立线程池或虚拟线程 | 已关闭 |
| M17 | P2 | `lib/api/materials.ts:19-21`；`materials/page.tsx:33` | 只取第 1 页 20 条，丢弃 `total/totalPages` | 拉全部分页或加控件 | 已关闭 |
| M18 | P2 | `SkillMaterialController.java:97-111`；`upload/page.tsx:90-93,124` | 空文件静默跳过；批量中途失败无法回滚；结果页只看第一个文件 | 返回逐文件结果，先整体校验格式 | 已关闭 |
| M19 | P2 | `app/admin/page.tsx:13-14`；`materials/page.tsx:7-16`；`MaterialCleaningService.java:86`；`MaterialCleaningScheduler.java:355-363`；`FileParseService.java:48-69` | 状态映射两处；死常量/死状态；`resetRetryCount` 无调用；两套 parse 语义相反 | 抽公共常量，删死代码，parse 统一 | 已关闭 |
| M20 | P2 | `SkillMaterialController.java:302,390,518-527` | admin 存 `contentType`，C 端存 `detectFileType`；m4a 归为 `other` | 统一 `detectFileType` | 已关闭 |
| M21 | P2 | `MaterialCleaningService.java:213,243,419,446`；`SkillRepository.java:104` | 分身重名检查全局跨企业，误报；文本路径 `skillName=null` 绕过检查 | 按 `companyId` 限定；文本同样校验 | 已关闭 |
| M22 | P2 | `MaterialCleaningService.java:407` | `uploadTextMaterial` 无 `@Transactional`，中途失败留下孤儿 Space/Skill | 加事务或抽独立事务方法 | 已关闭 |
| M23 | P2 | `SkillMaterialController.java:211-217`；`SkillMaterialRepository.java:91` | picker 不排除 `rejected/discarded`，全拒分身仍出现在下拉 | 查询追加 `status NOT IN (...)` | 已关闭 |
| M24 | P2 | `SkillMaterialRepository.java:72`；`MaterialCleaningScheduler.java:139-146` | 只收 `parsed/cleaning_failed`；进程在 `cleaning/analyzing` 崩溃后永不恢复 | 把锁超时的 `cleaning/analyzing` 纳入恢复查询 | 已关闭 |
| M25 | P2 | `materials/page.tsx:116,82` | 删除失败无 catch；空态文案指向不存在的入口 | 补错误提示，修正文案 | 已关闭 |
| M26 | P2 | `SkillMaterialController.java:313-321` | 补录文本无长度校验，可绕过 `max-text-length` | 复用上限并加最小长度 | 已关闭 |
| M27 | P2 | `MaterialUploadGuide.tsx:61`；`MaterialCleaningService.java:158-164` | 标注「即时处理，无需等待」，实际仍异步解析清洗 | 文案改为「立即预检，异步解析清洗」 | 已关闭 |
| M28 | P2 | `SkillMaterialController.java:281-292` | `PUT materials/{id}` 接受任意 status，可伪造终态 | 限制状态迁移或删除调试接口 | 已关闭 |

---

## 三、审核、发布、分身对话（A）

| ID | 级别 | 位置 | 现象 | 修法 | 状态 |
|---|---|---|---|---|---|
| A2 | P1 | `TraceabilityDrawer.tsx:64-70,120-123` | fetch 只有 `finally` 无 `catch`；`data=[]` 无空态；切换消息失败仍显示上一条旧数据 | 开头清空；补 catch/空态 | 已关闭 |
| A4 | P1 | `ProductStep.tsx:248-302,292-296`；`AdminAuditController.java:368-444`；`lib/api/audit.ts:65-67` | 已发布分身仍显示「发布分身」；无撤销发布入口 | 按 `skill.status` 分支：已发布显示撤销；`publishSkill` 支持 `unpublish` | 已关闭 |
| A5 | P1 | `audit/page.tsx:111-123`；`AdminAuditController.java:407-412` | 发布失败返回 200 + `{passed:false}`，前端不检查直接跳列表，失败被当成功 | 返回完整响应；失败展示 blockers 不跳转 | 已关闭 |
| A7 | P1 | `app/skills/page.tsx:16-31,55-81`；`SkillService.java:300-316` | 切主 Tab 不重置 `subTab`；「我的分身」被请求成 `type=organization` 并追加全公司组织分身 | 切 Tab 重置 `individual`；后端 `userId != null` 时不追加公司级 | 已关闭 |
| A9 | P1 | `AdminAuditController.java:92-95,147-162,313-330,380-392`；`ProductStep.tsx:260-269`；`SkillStep.tsx:18` | 场景覆盖 5 套口径：含空串/全状态/仅 active 各不相同 | 统一 active-only 单一计算函数 | 已关闭 |
| A11 | P2 | `audit/page.tsx:73` | 轮询终止只认 `extracted/discarded`；`discarded` 不是素材状态，`rejected` 才是终态 | 纳入 `rejected` 等终态 | 已关闭 |
| A12 | P2 | `audit/page.tsx:64-89` | 加载失败只 `console.error`，`data=null` 且 `loading=false` 仍命中加载分支，永久转圈 | 增加 error 状态与重试 | 已关闭 |
| A13 | P2 | `audit/page.tsx:50-51,200`；`SceneStep.tsx:41,59,97`；`PracticeScenarioModal.tsx:151-154` | 默认展示第一个标签但未 `setSceneTag`，直接点练习得到 `0/0` | 父级派生默认 tag，或 mount 时同步 | 已关闭 |
| A14 | P2 | `app/skill/[skillId]/page.tsx:555`；`PracticeChatSection.tsx:122`；`TraceabilityDrawer.tsx:124-126` | 未传 `avgSimilarity`，抽屉全部回落「参考」，与气泡标签矛盾 | 传入真实相似度 | 已关闭 |
| A15 | P2 | `ReportPreviewModal.tsx:70`；`app/report/[reportId]/page.tsx:12`；`ReportController.java:94,116` | 「下载 HTML」拼 `/report/{skillId}`，但该页按 reportId 查库，必然 404 | 改 `/reports/by-skill/{skillId}/html` | 已关闭 |
| A16 | P2 | `ReportPreviewModal.tsx:29-32,92-119` | readiness 失败时两分支都不命中，渲染空 iframe；HTML 拉取失败只 `catch(()=>{})` | 增加 error 状态与空内容判断 | 已关闭 |
| A17 | P2 | `SkillService.java:340-344`；`app/skills/page.tsx:135`；`app/skill/[skillId]/page.tsx:39-41,76-89` | 列表 `ownerName` 取空间归属账号，忽略 `skill.ownerName`；聊天页只读 URL `name`，直链标题固定「AI分身」 | 列表优先 `skill.ownerName`；详情返回后覆盖 | 已关闭 |
| A18 | P2 | `SkillService.java:879-893,955` | 注释称已有手填值则跳过，实现只查 `introProfile`；手填开场白会被异步覆盖 | 增加 `openingMessage` 非空判断 | 已关闭 |
| A19 | P2 | `admin/skills/page.tsx:99-100`；`SkillService.java:300-316`；`CompanyScopeService.java:100-112`；`OrganizationSkillService.java:55` | 组织分身 `spaceId=null`，点审核/素材必 403 | 列表按 type 隐藏入口，或组织分身走企业级校验 | 已关闭 |
| A20 | P2 | `audit/page.tsx:91-97` | 步骤条完成态按当前索引算，点第 4 步会把前 3 步标完成 | 用真实完成度计算 | 已关闭 |
| A21 | P2 | `app/skills/page.tsx:25-38,106-114`；`admin/skills/page.tsx:23-27,57-71` | 列表失败只 console，展示「暂无」误导；空态文案与实际不符 | catch 设 error；按 Tab 区分文案 | 已关闭 |
| A22 | P2 | `ProductStep.tsx:61`；`admin/skills/page.tsx:38-43`；`audit/page.tsx:73`；`SkillService.java:397-404`；`SkillEvaluator.java:32-54`；`page.tsx.bak` | 状态/模式字符串硬编码且含死值；`SkillEvaluator` 无调用方；`.bak` 残留 | 抽常量与单一映射；删死代码 | 已关闭 |
| A23 | P2 | `TraceabilityDrawer.tsx:67`（三份）；`JwtAuthFilter.java:175-198`；`lib/api/client.ts:28-33` | 溯源三份副本都硬编码 admin 路径且无 catch；JWT 注释称 Cookie 回退但未实现；apiClient 注释与实现不符 | Drawer 收敛；修正注释或补 Cookie；补鉴权回归 | 已关闭 |

---

## 四、复盘与教练（R）

| ID | 级别 | 位置 | 现象 | 修法 | 状态 |
|---|---|---|---|---|---|
| R2 | P1 | `SalesReviewPromptBuilder.java:96`；`SalesReviewReportParser.java:39-43`；`ReviewReportPanel.tsx:82-85` | 一句话结论无 evidence，与「每条结论可引用」冲突 | schema 增加 `summaryEvidence` 并渲染；或改话术 | 已关闭 |
| R3 | P1 | `SalesReviewReportParser.java:120-131,151-158`；`ReviewReportPanel.tsx:91-122` | 客户信号/下一步有 quote 但前端无引用入口 | 两类 item 复用 `CitationPopover` | 已关闭 |
| R4 | P1 | `SalesReviewService.java:70-121`；`reviews/new/page.tsx:31-38` | 新建复盘不自动入队报告，需再点一次；与文档设计不符 | create 成功后调用 `requestReport` | 已关闭 |
| R5 | P1 | `ReviewSegmenter.java:24-30,66-75`；`SalesReviewReportParser.java:184-188` | 超 2000 字长行硬切；跨切点引用导致校验失败，整份报告耗尽重试 | 按句切分并保留重叠；或整篇匹配 | 已关闭 |
| R6 | P1 | `SalesReviewReportService.java:52-57`；`SalesReviewService.java:176-204`；`reviews/[id]/page.tsx:152` | 会话状态无 `regenerating/failed`；`closed` 无写入路径，筛选永远为空 | 展示状态从 `latestReport.status` 派生；删除死状态或补接口 | 已关闭 |
| R7 | P2 | `SalesCoachLedgerService.java:52-58`；`coach/ledger/page.tsx:137-139` | 报告为 null 一律显示「生成中」，失败也说生成中 | 传 `report.status` 分状态显示 | 已关闭 |
| R8 | P2 | `CitationPopover.tsx:40`；`coach/requests/[sessionId]/page.tsx:114-125` | 未授权原文且无 quote 时显示「段落不存在」，实际存在 | 强制 quote，或显示「原文未授权」 | 已关闭 |
| R9 | P2 | `CoachSupplementPanel.tsx:32-49`；`reviews/[id]/page.tsx:157-160,246` | 勾选共享成功后页头授权状态不更新 | `onChanged` 同步更新或重载 | 已关闭 |
| R10 | P2 | `CoachSupplementPanel.tsx:84-94` | 撤回后三分支都不命中，面板只剩标题 | 增加 cancelled 文案与重新请求 | 已关闭 |
| R11 | P2 | `CoachSupplementPanel.tsx:43,51-57`；`reviews/[id]/page.tsx:241-247` | `onChanged` 未 await，刷新失败成未处理 rejection | 内部 try/catch 并提示 | 已关闭 |
| R12 | P2 | `reviews/[id]/page.tsx:41-64` | `Promise.all` 任一辅助接口失败整页失败 | 改 `allSettled`，核心失败才整页失败 | 已关闭 |
| R13 | P2 | `SalesReviewService.java:262-273`；`ReviewReportPanel.tsx:64-78` | payload 解析失败返回 null，前端显示空框 | 写 `errorMessage`；前端提示重新生成 | 已关闭 |
| R14 | P2 | `reviews/[id]/page.tsx:249-253`；`SalesReviewChatService.java:51-52` | 报告未 ready 时禁用追问，后端其实允许 | 去掉该 disabled 依赖 | 已关闭 |
| R15 | P2 | `SalesTaskService.java:74-76`；`TaskDraftModal.tsx:68-103` | 任一 title 已存在整批 409，一条不建；弹层不标已存在 | 标已存在并默认不选；后端逐条结果 | 已关闭 |
| R16 | P2 | `reviews/page.tsx:130-135,153-160` | 筛选无结果仍显示「还没创建过」；失败报告无重试入口 | 空态按筛选区分；失败行加重试 | 已关闭 |
| R17 | P2 | `StatusBadge.tsx:4,10`；`sales/leads/page.tsx:13,81` | `converted` 在复盘是「已转线索」、在线索应是「已成交」，共用徽标讲错 | 按 domain 拆分映射 | 已关闭 |
| R18 | P2 | `SalesCoachSupplementService.java:218-236`；`SalesReviewReportGenerator.java:49-52`；`SalesReviewPromptBuilder.java:63-74`；`V51__...:178-188` | 教练补充不进报告/追问，note 无 `reportVersion` | 拼进报告与追问上下文；记录版本 | 已关闭 |
| R19 | P2 | `SalesReviewReportParser.java:201-209`；`ConvertLeadModal.tsx:17-22` | `leadDraft` 无 evidence 却被直接预填 | 加 evidence，或标注「AI 草稿」并用客户名兜底 | 已关闭 |
| R20 | P2 | `ReviewReportPanel.tsx:29` | 「做得好的」空态文案是「本项没有可引用的问题」 | 按 section 传空态文案 | 已关闭 |
| R21 | P2 | `AppLayout.tsx:45`；`coach/ledger/page.tsx:71-73,124-129` | 无已发布分身的员工也看到教练台账，进去像数据为空 | 先判断有无已发布分身，无则引导发布 | 已关闭 |
| R22 | P2 | `SalesReviewService.java:226-230,242-251`；`reviews/[id]/page.tsx:49` | `/reports` 返回全部历史版本完整 payload，详情一次拉全量 | 列表只回元数据，按需拉 payload | 已关闭 |
| R23 | P2 | `StatusBadge.tsx:1-16`；`reviews/page.tsx:194-202` | 报告状态文案两处维护；`closed` 死状态 | 单一映射表 | 已关闭 |
| R24 | P2 | `reviews/new/page.tsx:24`；`ReviewCreateRequest.java:10`；`SalesReviewService.java:67-68`；`V51__...:81` | 30000 字上限四处硬编码，前后端校验基准不同 | 共用配置，统一 trim 后长度 | 已关闭 |
| R25 | P2 | `SalesReviewReportParser.java:16-20,43,75-77,103-105,144-146,184-196` | 注释称引文必须可定位，实现允许无 quote；超长/超条数静默截断 | 强制 quote 或修注释；截断记 warn | 已关闭 |
| R26 | P2 | `reviews/[id]/page.tsx:71-100`；`demo-seed.sql:322-326` | 轮询无总时长；seed 的「星河制造」`generating` 无 `lease_expires_at`，永不结束 | 加最大轮询时长；修 seed | 已关闭 |
| R27 | P2 | `SalesReviewService.java:262-273`；`SalesCoachSupplementService.java:242-253` | `toReportView` 两份实现，坏 payload 处理不一致（合并到 R23） | 抽公共 mapper | 已关闭 |
| R28 | P2 | `reviews/[id]/page.tsx:41-68,241-248`；`AppLayout.tsx:276-283` | 详情页不监听 `sales:refresh`，教练补充后销售端不更新 | 监听事件重载 supplement | 已关闭 |

---

## 五、知识、培训、权限（K）

| ID | 级别 | 位置 | 现象 | 修法 | 状态 |
|---|---|---|---|---|---|
| K2 | P1 | `AdminGrainService.java:95-96,119`；`ExperienceGrainRepository.java:234-236` | 事务内先 `save` 再 native `UPDATE`，未 flush，UPDATE 可能影响 0 行，embedding 仍 NULL | `saveAndFlush` 后更新；断言更新行数 | 已关闭 |
| K4 | P1 | `admin/grains/new/page.tsx:38-45`；`grains/[grainId]/edit/page.tsx:25-47`；`AdminGrainService.java:121-123`；`AdminInsightController.java:421-423,432` | 裸 fetch 不判 `res.ok`，403/500 也提示成功；后端 reEmbed 吞异常仍 200 | 用 apiClient 并检查结果；向量失败向上抛 | 已关闭 |
| K5 | P1 | `RagPipelineService.java:461-466,505-529`；`KnowledgeGapPanel.tsx:46-48,97` | 有命中但全部低于阈值时不记缺口，注释与实现不符；`MatchBadge` 分支不可达 | `tiers.isEmpty()` 时写缺口并带 matchInfo | 已关闭 |
| K6 | P1 | `ChatStreamService.java:536`；`RagPipelineService.java:513-524`；`KnowledgeGap.java:31-33`；`V1__init.sql:1226` | 企业总调度 `skillId=null`，`knowledge_gap.skill_id NOT NULL`，insert 异常被吞，企业级缺口全丢 | 允许 null，或按 company/space 记录 | 已关闭 |
| K7 | P1 | `AppLayout.tsx:65`；`NavConfigController.java:54`；`SecurityConfig.java:176`；`lib/api/im.ts:83-89`；`admin/im-bots/page.tsx:15` | 导航标 `ORG_SKILL_MANAGE`，接口要 `IM_MANAGE`；company_admin 403 被吞成「暂无」 | 导航/接口/页面统一权限 | 已关闭 |
| K8 | P1 | `AppLayout.tsx:80-90,98-108`；`RolePermissions.java:82-89`；`SecurityConfig.java:148`；`admin/page.tsx:44-51` | 员工 `MATERIAL_MANAGE` 让 `/admin/*` 全放行；数据接口 403 被吞，`return null` 白屏 | `canAccessPath` 精确映射；页面必须有错误态 | 已关闭 |
| K9 | P1 | `skill/[skillId]/insights/page.tsx:34-44,101-105`；`SecurityConfig.java:98,112-114` | 分身主数据页调用 admin 接口，全部 403，KPI 0、图表空 | 提供 owner 只读接口或对齐权限 | 已关闭 |
| K11 | P1 | `AdminInsightController.java:538-576` | `submitCorrection` 不校验 skillId/grainIds 归属，可跨企业衰减权重、写矫正记录 | 加 `assertSkillOwnership` + 逐 grain 校验 | 已关闭 |
| K12 | P1 | `KnowledgeCaseService.java:227-257` | 「AI整理」先下线旧版、新版落 draft；审批前知识从检索与培训消失 | 旧版保持 active，审批通过再下线 | 已关闭 |
| K20 | P1 | `GrainRetriever.java:440-455`；`ChatStreamService.java:755-803` | `mapGrainRow` 未映射 `qualityScore/sourceMaterialId/sourceInterviewId`，溯源平均质量为 0、来源为空 | 补齐映射并加单测 | 已关闭 |
| K13 | P2 | `ChatStreamService.java:161-171,810-826`；`insights/[skillId]/test/page.tsx:39,62` | 测试对话仍写入 `knowledge_gap`，污染统计；页面写「不计入统计」 | 测试/培训问答传 `recordKnowledgeGap=false` | 已关闭 |
| K14 | P2 | `RagPipelineService.java:510-524`；`KnowledgeGapRepository.java:27-35`；`AdminInsightController.java:224-238` | 同场景每次 miss 新建一行，非 upsert；列表重复，解决一条其余仍 open | 按 `(skillId, sceneTag, open)` upsert | 已关闭 |
| K15 | P2 | `AutoInsightScheduler.java:143-203,249-256,299-325`；`KnowledgeGapRepository.java:86-94` | 高频缺口全表查，跨企业聚类，证据含他企业提问 | 按 company/space 分组或查询带 scope | 已关闭 |
| K16 | P2 | `TrainingPickerService.java:64-70`；`TrainingPlanService.java:493-501`；`TrainingLearningService.java:272-303` | 培训选择/下发不校验 `expire_at`，过期知识仍可学 | 统一「可学」条件 | 已关闭 |
| K17 | P2 | `lib/api/training.ts:1001-1005`；`TrainingCohort.java:86-89`；`TrainingAssignmentService.java:157-170` | 缺 `archived` 标签；批次状态无流转接口，筛选永远为空 | 补标签与流转接口，移除不可达筛选项 | 已关闭 |
| K18 | P2 | `knowledge/new/page.tsx:181`；`cases/new/page.tsx:183`；`lib/api/knowledge.ts:154-160` | 上传任务状态用条目映射，`extracting/done/failed` 显示英文 | 新增任务状态标签 | 已关闭 |
| K19 | P2 | `admin/grains/page.tsx:72`；`grains/[grainId]/page.tsx:95`；`skill/[skillId]/insights/grains/page.tsx:68` | 分身/颗粒状态显示英文，质量分显示 `-/5` | 复用状态标签 | 已关闭 |
| K21 | P2 | `GrainRetriever.java:80-87`；`V1__init.sql:538`；`application.yml:310` | `search_text` 用 `simple`，查询用 `zhparser_cfg`，中文基本不相交，Hybrid 退化 Dense | 迁移统一配置 | 已关闭 |
| K22 | P2 | `AdminInsightController.java:307-333` | 自动发现先分页后内存过滤，他企业占满第一页时本企业为空 | 查询直接带 `skillId IN` | 已关闭 |
| K23 | P2 | `AdminInsightController.java:370-434` | 候选审批无事务：candidate 已 approved 但 grain/向量失败仍返回成功 | 整体加事务，先建 grain 再置 approved | 已关闭 |
| K24 | P2 | `AdminKnowledgeController.java:145-152`；`AdminCaseController.java:133-141`；`KnowledgeCleaningService.java:241-248` | 手工新增超管用 null（公共），上传却写系统企业 | 与 create 一致 | 已关闭 |
| K25 | P2 | `insights/[skillId]/feedback/page.tsx:40-49`；`insights/page.tsx:200-220`；`grains/[grainId]/edit/page.tsx:33`；`tuning/page.tsx:132-134`；`KnowledgeGapPanel.tsx:22-30` | 大量 catch 吞错被当空数据/一切正常 | 统一 error state + 重试 | 已关闭 |
| K26 | P2 | `GrainController.java:57-97` | 属主编辑颗粒后不重新向量化、不失效缓存 | 复用统一 buildGrainText + reEmbed | 已关闭 |
| K27 | P2 | `admin/grains/page.tsx:76-81`；`AdminGrainController.java:37-57` | 「颗粒管理」只有分身列表，「审核颗粒」跳到数据看板，无颗粒列表页 | 新增 `/admin/grains?skillId=` 列表 | 已关闭 |
| K28 | P2 | `AppLayout.tsx:35-72`；`NavConfigController.java:27-61` | 导航前后端两份；`/admin/demo-requests` 无图标；权限漂移根因 | 后端单一真相源，前端只维护 path→icon | 已关闭 |
| K29 | P2 | `RagPipelineService.java:461-466,505`；`KnowledgeGapPanel.tsx:46-48,97`；`AdminNotificationController.java:55-62`；`AdminGrainService.java:10` | 注释与实现不符、死参数、死分支、未用 import | 修注释，删死代码，或补齐实现 | 已关闭 |
| K30 | P2 | `AdminGrainService.java:69-70,113-117` | 清空文本时直接 return，旧向量继续生效；只改 `sceneTag` 不影响向量 | 统一全字段 buildGrainText；空文本置 null | 已关闭 |
| K31 | P2 | `AdminGrainController.java:79-83`；`AdminGrainService.java:80-85`；`grains/new/page.tsx:13-14,41` | 前端传 `spaceId`，后端忽略；不一致时会写错空间 | 校验一致或删除参数 | 已关闭 |
| K32 | P2 | `skill/[skillId]/insights/page.tsx:61-63`；`skill/[skillId]/page.tsx:37-40` | 「测试对话」跳 `?tab=test`，页面不读该参数 | 页面读参数切模式，或改跳真实测试页 | 已关闭 |

---

## 六、验证要求

每条缺陷关闭前必须有：

1. **复现步骤**：用演示账号在真实系统里能重现。
2. **修复后验证**：同一路径再走一遍，结果符合预期。
3. **回归**：受影响的相邻流程不复坏（例如修 M1 后，文件上传的判重仍正常）。
4. **记录**：状态改为 `已关闭`，写一句验证结论。

P0 关闭前额外要求：有自动化用例或在 `docs/batch0-verification.md` 留下手工验证记录。
