# 批次 0 验证记录

环境：本地 `sh dev.sh` 启动，后端 :8080、ai-service :8000、前端 :3000。
账号：`demo_admin / DEMO2026`（演示企业 `d0000000-…0001`）。
时间：2026-11-02。

## 已验证（已关闭）

### M1 粘贴文本不再被自己判重
- 操作：用全新文本 POST `/admin/materials/text`，`spaceId=d200…0001`。
- 结果：`status=uploaded`，`acceptance.passed=true`，无 `DUPLICATE`。
- 对照：再次提交同一段文本时返回 `DUPLICATE`（相似度 100%），说明判重仍生效，只是不再包含自身。

### M2 文本上传复用已有分身，不再 500
- 操作：同上，空间下已存在分身 `d300…0001`。
- 结果：`skillCreated=false`，`skillId` 复用 `d300…0001`，HTTP 200。
- 修复前：该路径 `new Skill` 撞 `skill(space_id)` 唯一索引，返回 500。

### M3 `text://` 素材进入清洗，不再卡在 uploaded
- 操作：等待调度器 45 秒后查询素材。
- 结果：`status=analyzing`，`analysisNotes=文本素材，无需解析，直接进入清洗`。
- 修复前：调度器把 `text://` 当文件路径调 ai-service，404 后永久停在 uploaded。

### M4 补录文字进入清洗队列
- 操作：上传 1×1 PNG → 等解析（`uploaded` + 需人工补充）→ PUT `/admin/materials/{id}/manual-text`。
- 结果：接口 200，状态立即变为 `parsed`，`analysisNotes=人工补录文字，长度: 133字`。
- 修复前：只写正文不改状态，下一次解析又改回「需人工补充」，永不清洗。

### K1 / K2 手动补录颗粒与批量入库同向量空间，且向量确实写入
- 操作：POST `/admin/grains` 创建颗粒 `28716016-…7cba`。
- 结果：SQL `SELECT status, embedding IS NOT NULL` → `active|t`。
- 说明：`reEmbed` 改用 `grainToText`（场景标签+描述+适用条件），与批量入库一致；`updateEmbedding` 开启 `flushAutomatically` 并断言影响行数。

### K3 补颗粒自动关闭知识缺口
- 操作：为 demo skill 建临时缺口 `aaaaaaaa-…0001` → POST `/admin/grains` 带 `gapId`。
- 结果：缺口状态 `resolved`，`resolved_at` 非空。
- 三处入口已统一携带 `skillId + gapId`。

### R1 自任教练不再静默失败
- 操作：GET `/sales/reviews/d500…0001/coach-supplement`（该复盘发起人与教练所有者同为 demo_admin）。
- 结果：`selfCoach=true`；前端改为提示「你本人就是这个分身的教练」并给出直达教练工作台的链接。

## 待运行时验证

| ID | 原因 |
|---|---|
| M5 | 需要构造解析服务失败，验证重试计数与 `parse_failed` |
| M6 | 需要模拟 ai-service 挂起，验证 120s 超时 |
| M7 | 需要伪造过期锁，验证可被抢占 |
| A1 | 员工账号打开溯源，验证 403 文案；员工轨道将在导览实现时改为概念卡 |
| A3 | 需要跑一次自动演示评估，验证 `SkillEvaluation` 落库 |
| A4 / A5 | 需要已发布分身与一次校验失败的发布 |
| A6 | 需要停用/激活一个分身并确认列表分组 |
| A8 | 需要历史会话刷新后检查匹配标签与溯源按钮 |
| A10 | 需要两个企业的账号做跨租户越权用例 |
| K4 | 需要模拟向量服务失败，验证接口不再提示成功 |
| K10 | 分身主打开颗粒列表，验证不再崩溃且 403 有文案 |

## 第二批验证（格式、权限、导览）

### M9 / M10 文件格式与大小
- `.doc` 上传返回 400，提示「暂不支持 ".doc" 格式。支持格式：txt / md / csv / html / htm / pdf / docx / pptx / png / jpg / jpeg / mp3 / wav」。
- `.txt` 上传返回 200，状态 `uploaded`。
- 上传指南同步为「单文件 ≤ 50MB，单次总量 ≤ 50MB，最多 5 个」，不再出现 100MB。

### K7 分身接入权限
- `company_admin` 的 `/config/nav` 不再下发 `/admin/im-bots`，避免点进去 403。

### A6 状态白名单
- `PUT /skills/{id}/status {"status":"failed"}` 返回 400「不支持的分身状态: failed」。
- 前端停用写 `paused`、激活写 `reviewing`，列表新增「已停用」分组。

### 导览端到端（Playwright，1440×900）
- 售前轨道 8 章 24 步：全部步骤命中锚点，无「没找到」，无控制台错误；跨路由自动跳转（`/admin → /skills → /skill/{id} → /interview/create → /admin/skills/upload → /materials → /audit → /sales/reviews → /admin/insights → /admin/grains/new → /admin/knowledge → /admin/cases → /admin/users → /admin/training/plans`）。
- 员工轨道 5 章：`demo_learner` 全部走通，无 403；溯源步骤为概念卡。
- 深链 `/admin?tour=review`：直接进入「复盘闭环」，地址栏 `tour` 参数被移除。
- 演示企业限制：售前轨道仅在 `d0000000-…0001` 启动；其他企业会提示禁止投屏。

### 导览端到端测试（`frontend/e2e/tour.spec.ts`）
- 售前轨道：完整走通 24 步，无「没找到」，无导览相关控制台错误。
- 员工轨道：完整走通 11 步，无 403；员工启动时不再请求知识缺口接口。
- 深链 `/admin?tour=review`：直达复盘章，参数被移除。
- 续播：退出/刷新后入口显示「继续导览」，可回到原章原步。
- 「从头开始」：重置进度后从第 1 章第 1 步开始。
- 最小化：页面可正常操作，恢复后回到原步骤。
- 键盘：仅焦点在导览内时 Esc/←/→ 生效；输入框内不劫持。
- reduced-motion：聚光与卡片过渡为 0.01ms。
- 768 宽：卡片会根据目标位置切到顶部或底部抽屉，输入框不再被完全遮挡；大容器仍有 28–37% 部分重叠，属不可避免。
- 账号切换：登出清空 `tour.progress.*` 并广播 `auth:logout`，登录页不再残留导览。

## 第三批验证（P1 体验与权限）

### 素材预检（M11 / M12）
- 复现 M1 时暴露出预检根因：`StructureAnalyzer.matchRole` 与 `MaterialPreChecker.countDialogueTurns` 对 `null` 角色标签直接 `startsWith`，NPE 被旧逻辑吞掉；上传文本因此停在 `uploaded`。
- 修复：领域配置缺失或 `roleLabel/counterpartyLabel` 为空时跳过标签匹配；`checkAcceptance/checkDuplicate/evaluate` 对 `config == null` 做兜底；文件与文本上传的 `evaluate` 改用 `effectiveDomain`。
- 预检真正异常时统一置 `rejected` 并写 `rejectCode=PRECHECK_ERROR` / `rejectReason`，前端不再显示空白红框。
- 文本上传失败展示后端 message（413 单独提示文件过大），不再一律「网络错误」。

### 分身主数据与颗粒（K9 / K10）
- 新增 `SkillOwnerInsightController`（`/skills/{skillId}/insights/*`），只读，仅空间属主或具备 `DASHBOARD_VIEW` 的管理员可访问。
- 验证（`demo_admin`）：overview / scene-top / rag-distribution / grains-top / knowledge-gaps / grains 全部 200，返回真实数据（对话 61、反馈 100）。
- 分身主颗粒列表与编辑改走属主接口；员工保存后仍触发重新向量化；无 `GRAIN_MANAGE` 时不显示「新增颗粒」「补充颗粒」「查看反馈」入口。
- 前端员工视角不再出现「点进去 403」的链接。

### 素材列表（M13 / M14 / M15 / M16）
- 状态映射补 `parsed / parse_failed / cleaning_failed`，筛选与颜色同步。
- 轮询在全部素材进入终态时停止，并设 10 分钟上限，避免卡死素材永久轮询。
- 全部 chunk 抽取失败时抛错，不再把 0 颗粒素材标成「已萃取」。
- 新增独立 `grainExtractExecutor`，清洗线程不再向 `cleanExecutor` 自己提交并 `join`，消除自锁。

### 复盘报告引用（R2 / R3 / R20）
- 报告 schema 增加 `summaryEvidence`，解析器要求一句话结论至少一条可回看引用，payload 一并返回。
- 前端一句话结论、客户信号、下一步都补「查看引用(N)」，复用 `CitationPopover`；旧报告无 `summaryEvidence` 时自动隐藏入口。
- 三个 Section 空态文案按区块区分。
- 详情页监听 `sales:refresh`，教练补充后销售端自动刷新；报告轮询加 10 分钟上限。

### 复盘列表与审核步骤条（A20 / A21）
- 审核四步的「已完成」按真实数据判断（素材终态、颗粒数、场景数、发布状态），不再按当前 Tab 序号推断。
- 分身广场与管理列表加载失败显示错误条 + 重试，空态文案按「已发布 / 我的 / 组织」区分，不再把失败伪装成「暂无」。

### 知识缺口（K5 / K6）
- 有命中但全部低于参考阈值时写入缺口，`note` 格式 `type=all_low_similarity bestSim=… threshold=…`，`MatchBadge` 分支可达。
- `knowledge_gap.skill_id` 允许为空（V57），企业总调度缺口不再因 NOT NULL 静默丢失；计数查询支持 `skillId/sceneTag` 为空。
- 运行时验证：批次 0 回归 M1/M2/M3/M4/K1/K2/K3/A6/R1 全部通过；`tour.spec.ts` 2/2 通过；`tsc --noEmit`、`next build` 通过。

## 第四批验证（复盘、知识、培训、颗粒管理）

### 复盘创建即生成报告（R4）
- `POST /sales/reviews` 返回的 `session.latestReport.status=queued`，版本 v1；无需再点「生成报告」。
- 报告中新增 `summaryEvidence`，解析器要求一句话结论至少一条可回看引用。
- 客户信号 / 下一步补「查看引用(N)」；`CitationPopover` 在原文未授权时显示「原文未授权或不可见」。
- 详情页核心详情失败才整页报错，消息/补充/版本列表改用 `allSettled`；轮询上限 10 分钟。
- 复盘列表展示态从 `latestReport` 派生，失败版本可直接「重新生成」。

### 模型生成链路（R2/R3/R5）
- 当前环境调用 DeepSeek 返回 `Error while extracting response ... ChatCompletion`（与文档评审 402 同一类外部服务问题），报告 job 3 次重试后置 `failed`，因此本轮无法用真实模型验证 `summaryEvidence` / 引用渲染；代码、schema、前端编译已通过，待模型服务恢复后补验证。

### 知识缺口（K5/K6/K14）
- 低匹配缺口写入 `type=all_low_similarity bestSim=… threshold=0.30`；同一 `(skillId, sceneTag)` 改为 upsert，不再每次 miss 新建一行。
- V57 已执行：`knowledge_gap.skill_id` 允许为空，企业总调度缺口不再因 NOT NULL 丢失。

### 知识版本（K12）
- V58 已执行：`knowledge_entry.supersedes_id`。
- AI 整理只生成 draft，旧版保持 active；审批通过时才按 `supersedes_id` 下线旧版。

### 检索（K20/K21）
- `mapGrainRow` 补齐 `qualityScore/sourceMaterialId/sourceInterviewId/helpfulCount/unhelpfulCount/difficultyLevel`。
- V59 已执行：`experience_grain.search_text` 改用 `zhparser_cfg` 生成；`SELECT count(*) ... @@ plainto_tsquery('zhparser_cfg','预算')` 返回 12 条，Hybrid 不再退化为 Dense。

### 素材（M18/M21/M22/M23/M24/M28）
- 上传先整体校验格式/大小，空文件返回逐文件「空文件」结果；结果页按文件列出通过与未通过原因。
- 判重排除 rejected/discarded 与仍处于 uploaded 的同批素材，避免 A、B 互相判重。
- 文本上传加事务，失败不再留下孤儿 Space/Skill。
- 分身重名按 `companyId` 限定的 `existsByCompanyIdAndDisplayName` 判重。
- picker 排除仅含 rejected/discarded 素材的分身；清洗查询纳入锁过期的 `cleaning/analyzing`。
- `PUT materials/{id}` 只允许标记 `discarded`，不能伪造流水线终态。

### 培训与颗粒管理（K16/K17/K27）
- 培训阅读任务下发时校验知识与案例的 `status=active` 且未过 `expire_at`。
- 批次状态补「已归档」标签。
- 「颗粒管理 → 查看颗粒」进入 `/admin/grains?skillId=` 列表，可排序并跳转编辑；组织分身不再显示审核/素材入口（A19）。

### 验证命令
- `mvn clean test-compile`、`tsc --noEmit`、`next build` 通过。
- `scripts/batch0-regression.sh` 全部通过。
- `E2E_NO_WEBSERVER=1 npx playwright test e2e/tour.spec.ts`：2/2 通过。

### 审核发布（A4/A5）
- `PUT /admin/skills/{id}/publish {"action":"unpublish"}` → 200，状态 `published → reviewing`。
- 再 `{"action":"publish"}` → 200，状态恢复 `published`；响应带 `passed`，前端按 `passed===false` 展示 blockers 不跳转。
- 跨租户校验未能用真实 company_admin 复测：`admintest` 密码非默认值；A10 保持「待验证」。

## 第五批验证（生产化收口：111/111 关闭）

### 非流式 LLM 超时（生产阻断级）
- 复盘报告连续 `failed` 的根因不是解析器，而是 RestClient 读超时：classpath 存在 OkHttp 时默认 10s，报告 prompt 非流式调用必然超时。
- 在 `AiConfig` 增加 `RestClientCustomizer`：连接 10s、读取 180s。
- 实测：新复盘报告 4 次轮询内 `queued → generating → ready`，`summaryEvidence` 3 条、客户信号/下一步均带 quote。

### 引用与报告（R2/R3/R5/K5/R22）
- 真实报告 `payload` 包含 `summaryEvidence`；Playwright 打开复盘详情，页面渲染 16 个「查看引用(N)」，点击后弹出 `role=dialog` 并显示「引用：」原文。
- 跨段引用校验按段落顺序拼接，`SalesReviewReportParserTest` 全部 9 条通过。
- K5 单测：低匹配命中写入 `type=all_low_similarity bestSim=0.100`。
- R22 实测：`GET /sales/reviews/{id}/reports` 的 `payload` 为 `null`；`GET /sales/reviews/{id}/reports/{reportId}` 返回完整 8 个区块，前端按需拉取。

### 安全（A10/K11）
- `CompanyScopeServiceTest` 4 条：跨企业空间、同企业空间、其他企业组织分身、C 端非属主。
- `AdminInsightCorrectionTest` 3 条：跨企业 skill、跨分身 grain、合法衰减 1.0→0.7。
- 复测时发现并修复：`submitCorrection` 原来先写矫正记录再校验颗粒，越权请求会留下脏记录；已改为「全部校验通过后再写」。

### 素材与知识（M19/M20/K6/K20）
- 素材解析统一走 `FileParseService.parseUrl`，B/C 端文件类型统一 `FileTypeUtil`（含 m4a/aac/amr）。
- 手动重试实测：`PUT /admin/materials/{id}/retry` 200，状态回到 `uploaded`、`retry_count=0`。
- V57 已执行；`RagPipelineLowMatchTest` 验证 `skillId=null` 的企业缺口可落库。
- 真实对话后 `skill_message` 持久化 `grain_ids`（3 条）与 `avg_similarity=58.67`。

### 其余技术债（M19/M20/A22/R27/K15/K17/K28/K29）
- 状态/文件类型/报告视图收敛为单一来源；删除未使用的 `SkillEvaluator` 与 `page.tsx.bak`。
- 批次状态流转新增 `PATCH /admin/training/cohorts/{id}/status`：非法状态 400，`archived` 筛选 200；前端按当前状态显示结班/取消/归档。
- 导航仍以后端 `/config/nav` 为真相源，新增 `nav.cache` 降级缓存、清除登录态缓存、补齐 `/admin/demo-requests` 图标。
- K15 聚类按 `space_id` 隔离（代码级）；当前环境没有跨企业同向量缺口样本，未做跨租户运行态复现。

### 最终验证矩阵
- `mvn clean test-compile` 通过；**全量 511 条单测通过**（20 条 DB 集成测试因环境跳过）。
- `tsc --noEmit`、`next build` 通过。
- `scripts/batch0-regression.sh` 全部通过。
- `E2E_NO_WEBSERVER=1 npx playwright test e2e/tour.spec.ts` 2/2 通过。
- `docs/defects.md`：111 条全部 `已关闭`。
