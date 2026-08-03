# 颗粒萃取管线技术文档

> 版本: v2 | 更新: 2026-08-03 | 基于 MaterialCleaningService + MaterialCleaningScheduler

本文档完整描述"一个原始素材如何变成可检索经验颗粒"的全链路。每个环节标注了代码位置、关键参数、数据处理逻辑和异常兜底策略。

---

## 1. 概览：12 层管线全景图

```
素材上传 (uploadMaterial)
  │
  ├── 文件解析 (parseFile) ──── 异步，Scheduler 触发
  │
  ▼
准入预检 (Gate 1+2) ──── 拒绝则标记 rejected，管道终止
  │
  ▼
Layer 0: 情境标注 (tagContext) ──── 1 次 AI 调用，3000 字采样
  │
  ▼
Layer 1: 格式噪音清洗 (MaterialNoiseCleaner) ──── 纯规则，0 AI
Layer 2: 业务噪音过滤 (BusinessNoiseFilter) ──── 纯规则，0 AI
Layer 3: 文本规范化 (TextNormalizer) ──── 纯规则，0 AI
  │
  ▼
Layer 4: 分块 + AI 提取 (dialogueChunk/semanticChunk + extractInsights) ──── N 次 AI 调用
  │
  ▼
Layer 5: 场景归类 (classifyScenesBatch) ──── 1 次 AI 调用
  │
  ▼
Layer 6: 对抗验证 (verifyGrains) ──── N/10 次 AI 调用
  │
  ▼
Layer 7: 同标签合并 (mergeOverlapping) ──── 按需 AI 调用
  │
  ▼
Layer 8: 模式发现 (discoverPatterns) ──── 1 次 AI 调用（>= 8 候选时）
  │
  ▼
Layer 9: FAQ 提取 (extractFaq) ──── 1 次 AI 调用
  │
  ▼
Layer 10: 叙事重放 (generateNarrativeWithLinks) ──── 1 次 AI 调用
  │
  ▼
Layer 11: 画像生成 (generateProfile) ──── 1 次 AI 调用（异步，不阻塞）
  │
  ▼
落库 + 向量化 + 语义去重
```

**总 AI 调用次数**：约 `1 + N + 1 + ceil(N/10) + 0~M + 1 + 1 + 1 + 1`，其中 N = chunk 数，M = 同标签组数。

---

## 2. 素材上传与解析

### 2.1 文件上传

**代码**: `MaterialCleaningService.uploadMaterial()` (line 180)、`uploadMaterialToSpace()` (line 127)

**流程**:
1. 校验空间/分身是否存在，不存在则创建
2. 文件落盘到 `{storageBasePath}/skills/{skillId}/yyyy-MM/{uuid}_{originalName}`
3. 创建 `SkillMaterial` 记录，status = `uploaded`
4. 对纯文本文件立即执行准入预检；二进制文件（docx/pdf/audio）等 parseFile 后再预检

**状态机**:
```
uploaded → parsed → cleaning → analyzed → extracted
  │          │         │
  └──────────┴─────────┴──→ parse_failed / cleaning_failed → rejected
```

### 2.2 文件解析

**代码**: `MaterialCleaningService.parseFile()` (line 576)

- 调用 AI 服务 `/internal/parse-file` 提取纯文本
- 解析成功后设置 `parsedContent`、`analysisNotes`、`status = parsed`
- needsManual 素材不设 parsedContent，仍为 uploaded，等人工补充
- 已标记"需人工补充文字内容"的素材跳过重复解析

**调度**: `MaterialCleaningScheduler.scanAndParse()` 每 30s 扫描 `status = uploaded` 的素材

---

## 3. 准入预检（Gate 1 + Gate 2）

**代码**: `MaterialPreChecker` (`service/precheck/MaterialPreChecker.java`)

### 3.1 Gate 1: 准入检查

| 检查项 | 默认阈值 | 失败后果 |
|--------|---------|---------|
| 最小文本长度 | 50 字 | 拒绝，标记 rejected |
| 中文比例 | >= 70% | 拒绝 |
| 销售关键词密度 | >= 0.5% | 拒绝（NOT_SALES_DOMAIN） |
| 教科书/理论比例 | <= 30%（且第一人称低） | 拒绝 |
| 营销内容比例 | <= 15% | 拒绝 |
| 重复检测（Jaccard 5-gram） | <= 90% | 拒绝 |

**访谈素材豁免**: `materialType = "interview"` 或文件名含"访谈"的素材跳过 Gate 1（准入检查），因为访谈由萃取师引导，天然在题。

### 3.2 Gate 2: 质量预检

- 加权评分: 结构 40% + 内容 35% + 质量 25%
- 等级: good (>= 70) / warning (50-69) / poor (< 50)
- 估算颗粒数: `对话轮次 × 业务密度 × 0.3 × 100`，范围 `[-3, +8]`
- 仅记录，不阻断管道

### 3.3 "prefix is null" 异常

**位置**: 预检阶段。来自 `StructureAnalyzer.matchRole()` 方法。当文本格式异常导致角色匹配失败时触发。管道捕获后仅 warn，继续执行。

---

## 4. 三层规则清洗（Layer 1-3）

### Layer 1: 格式噪音清洗

**代码**: `MaterialNoiseCleaner.cleanFormatNoise()` + `detectMaterialType()`

**类型检测逻辑**:
1. `.mp3/.wav/.m4a` 扩展名 → voice_transcript
2. 时间戳行比例 > 30% AND 发送者行比例 > 20% → chat_log
3. 语气词密度 > 0.5 → voice_transcript
4. 其余 → document

**清洗规则（按类型）**:
- **CHAT**: 去时间戳、发送者前缀、系统消息、媒体占位符
- **VOICE**: 去语气词（嗯啊呃）、犹豫短语（就是/那个）、暂停标记、短行（< 10 字）
- **DOCUMENT**: 去纯数字行、目录点划线、图表标题、页码、版权声明

### Layer 2: 业务噪音过滤

**代码**: `BusinessNoiseFilter.filterBusinessNoise()`

过滤 5 类噪音行：寒暄、闲聊、回声、结束语、语气填充词。纯肯定行（`^[嗯对好是行哦可]{1,5}$`）也被移除。白名单关键词不受影响。

### Layer 3: 文本规范化

**代码**: `TextNormalizer.normalize()`

- 去除非 ASCII/CJK 字符
- 合并连续换行（3+ → 2）、连续空格（2+ → 1）
- 半角标点转全角
- 修正硬编码的转录错误（"销售一团"→"销售一部"等 4 条）
- 段落强制以中文句号结尾

---

## 5. 情境标注（Layer 0）

**代码**: `MaterialCleaningService.tagContext()` (line 629)

**Prompt**: `material_context_tag.md`（领域版本在 `domain/sales.b2b_enterprise/`）

**输入**: 清洗前原文，均匀采样头部+中部+尾部共 3000 字

**输出**: `ContextTags` 包含 buyingStage、buyerPersona、competitiveContext、dealSizeHint、industrySignals

用于后续 Layer 4 full prompt 的情境注入。

---

## 6. 分块策略（Layer 4 前半）

**代码**: `MaterialCleaningService` lines 1094-1099, 1193-1319

### 6.1 对话检测（isDialogueText）

三重判断，任一命中即为对话：
1. `materialType == "interview"`
2. `detectedType` 为 `chat_log` 或 `voice_transcript`
3. 启发式：`^.{1,10}[：:].{2,}` 匹配行数 > 总行数 × 40% AND 总行数 > 10

### 6.2 dialogueChunk（对话分块）

**策略**: 按发言人切分 → 攒到 800-1500 字 → 出一个 chunk。**有 50 字上文重叠。**

```
参数: CHUNK_TARGET=800, CHUNK_MAX=1500
发言人匹配: ^.{1,10}[：:].*$
客户识别: ^.{0,5}(客户|甲|Q|用户|买方|对方)[：:].*
```

### 6.3 semanticChunk（文档分块）

**策略**: 按句子边界切分 → 去噪句 → 攒到 800-1500 字 → 出 chunk。**有 50 字上文重叠。**

句子边界: `。！？\n.!?`  
最小 chunk: 500 字

---

## 7. AI 提取（Layer 4-5 后半）

**代码**: `MaterialCleaningService.extractInsights()` (line 1405)

### 7.1 Full vs Short Prompt

- **Full prompt** (`material_extract_full.md`): 含完整角色定义 + 核心原则 + 情境上下文 + 6 个必须回答的问题。**仅 1 个 chunk 使用。**
- **Short prompt** (`material_extract_short.md`): 含自包含的核心约束（不含完整角色定义）。其余 chunk 使用。

### 7.2 fullPromptIdx 选择

选**技巧信号最多**的 chunk（非最长）。信号统计维度：因果关系词（因为/所以/由于/导致/因此）+ 判断策略动词（判断/发现/决定/策略/方法/配合/借力）+ 客户互动信号（客户说/我觉得/我的判断）。

### 7.3 并行控制

- 每 5 个 chunk 一批，批内并行，批间串行
- 每条 insight 的 `confidence < 0.7` 被丢弃
- 单个 chunk 提取失败仅 warn，不阻断其他 chunk

### 7.4 Prompt 关键约束

```
- 禁止输出空洞的通用原则
- "转介绍""微信维护""节日约访""政府关系"等策略若有具体做法也算可提取技巧
- 必须包含: 触发信号 + 动作链 + 原理 + 反模式 + 适用条件
- confidence >= 0.7 才输出，无足够信息输出 []
```

---

## 8. 场景归类（Layer 5）

**代码**: `MaterialCleaningService.classifyScenesBatch()` (line 1468)

**Prompt**: `classify_scenes.md`

**输入**: 所有提取的 insights，每个以"场景描述 + 思路前 50 字"表示，加上空间中已有的标签列表

**输出**: 每个 insight 分配一个 ≤ 10 字的标签。失败时全部兜底为"通用技巧"

---

## 9. 对抗验证（Layer 6）

**代码**: `MaterialCleaningService.verifyGrains()` (line 668)

**Prompt**: `material_verify.md`

**机制**: 每 10 个候选一批，5 维度评分（1-5 分）：

| 维度 | 权重 | 5分标准 | 1分标准 |
|------|:---:|---------|---------|
| specificity | 25% | 新人拿着就知道说什么做什么 | "建立信任"这类万能答案 |
| reproducibility | 20% | 完全可复制，有清晰步骤 | 严重依赖个人天赋 |
| causality | 20% | 行为→买方反应→结果链条清晰 | 只有销售做了什么 |
| distinctiveness | 20% | 反直觉的，大多数不会做 | 每本销售书上都有 |
| falsifiability | 15% | 清楚说明适用边界 | 没讨论任何限制条件 |

**判定规则**:
- `specificity < 3` → 直接 REJECT
- 综合分 `= specificity×0.25 + reproducibility×0.2 + causality×0.2 + distinctiveness×0.2 + falsifiability×0.15`
- 综合分 >= 3.5 AND verdict = "APPROVE" → 接受
- 对话转录/访谈类素材：若描述了"信号→判断→动作"完整回路，specificity 不低 于 3

**拒绝的颗粒记录到 `ExtractionDropLog` 表。**

---

## 10. 合并去重（Layer 7）

**代码**: `MaterialCleaningService.mergeOverlapping()` (line 776)

**触发条件**: 候选数 > 10 才执行  
**合并策略**: 按 sceneTag 分组 → 同标签多条 → AI 合并 → confidence >= 0.6 才采纳  
**失败兜底**: 保留原始组，不丢失数据

---

## 11. 模式发现（Layer 8）

**代码**: `MaterialCleaningService.discoverPatterns()` (line 878)

**触发条件**: 候选数 >= 8

**Prompt**: `material_pattern.md`

**输出**: coreHabits（核心习惯 top3）、differentiators（差异化优势 top3）、methodologyName、oneliner

---

## 12. 后处理层（Layer 9-11）

### FAQ 提取（Layer 9）
- Prompt: `material_faq.md`
- 输入: 4000 字采样
- 输出: 真实出现过的 QA 对，无则 `[]`

### 叙事重放（Layer 10）
- Prompt: `material_narrative.md`
- 将颗粒 + 模式串成有结构的叙事 story（4-6 阶段），含策略-颗粒关联

### 画像生成（Layer 11）
- Prompt: `material_profile.md`
- **异步执行** (`@Async("embeddingExecutor")`)，不阻塞管道
- 输出: personality、speakingStyle、background、knowledgeDomains 等

---

## 13. 落库与向量化

### 13.1 颗粒落库

**代码**: `MaterialCleaningScheduler.saveGrains()` (line 200)

- 短事务 `REQUIRES_NEW`
- 每个 `GrainCandidate` → 一条 `ExperienceGrain` 记录
- weight = qualityScore/5 × 2，范围 [0.1, 2.0]
- status = `active`

### 13.2 向量化

**代码**: `MaterialCleaningScheduler.embedGrains()` (line 264) + `DashScopeEmbeddingService`

- 模型: DashScope `text-embedding-v4`，1024 维
- 文本拼接 (`grainToText`): 6 字段加语义前缀（"场景:"、"描述:"、"思路:"、"话术:"、"避坑:"、"条件:"）空格连接
- 批量大小: 10 条/次 API 调用
- 写入: `PGvector` 对象直传 `jdbcTemplate.batchUpdate`
- 失败仅 warn，不阻断管道

### 13.3 语义去重

**代码**: `MaterialCleaningService.deduplicateByEmbedding()` (line 1739)

- 新颗粒与同空间存量做 pgvector ANN 比对
- `cosine > 0.95` → 标记为 `deprecated`
- 失败仅 warn

### 13.4 报告 + Skill 状态

- 素材标记 `extracted`
- Skill 状态 `generating` → `reviewing`（当有颗粒产出时）
- 报告标记脏（Redis `report:dirty:skills`），每 2 分钟合并生成

---

## 14. 附录

### 14.1 Prompt 模板索引

| 模板文件 | 管线层 | AI 调用次数 | 领域版本 |
|---------|:-----:|:---------:|:------:|
| `material_context_tag.md` | Layer 0 | 1 | ✅ sales.b2b_enterprise |
| `material_extract_full.md` | Layer 4-5 | 1 | ✅ sales.b2b_enterprise |
| `material_extract_short.md` | Layer 4-5 | N-1 | ✅ sales.b2b_enterprise |
| `classify_scenes.md` | Layer 5 | 1 | ❌ |
| `material_verify.md` | Layer 6 | ceil(N/10) | ❌ |
| `material_merge.md` | Layer 7 | 按标签组 | ❌ |
| `material_pattern.md` | Layer 8 | 1 | ❌ |
| `material_faq.md` | Layer 9 | 1 | ❌ |
| `material_narrative.md` | Layer 10 | 1 | ❌ |
| `material_profile.md` | Layer 11 | 1 | ❌ |

### 14.2 关键常量速查表

| 常量 | 值 | 位置 |
|------|:--:|------|
| CHUNK_TARGET | 800 字 | MaterialCleaningService:1259 |
| CHUNK_MAX | 1500 字 | MaterialCleaningService:1260 |
| 语义重叠 | 50 字 | semanticChunk / dialogueChunk |
| MAX_RETRY_COUNT | 3 | MaterialCleaningScheduler:56 |
| LOCK_TIMEOUT | 5 分钟 | MaterialCleaningScheduler:57 |
| SKILL_LOCK_TTL | 600 秒 | MaterialCleaningScheduler:58 |
| 提取 batch size | 5 | extractInsights:1422 |
| 验证 batch size | 5 | verifyGrains:690 |
| 合并触发阈值 | > 10 候选 | mergeOverlapping:778 |
| 模式发现阈值 | >= 8 候选 | discoverPatterns:879 |
| RRF_K | 60 | GrainRetriever:163 |
| 向量维度 | 1024 | DashScopeEmbeddingService |
| 向量 batch | 10 | DashScopeEmbeddingService:84 |
| 语义去重阈值 | cosine > 0.95 | deduplicateByEmbedding:1761 |
| 准入中文比 | >= 70% | ContentAnalyzer |
| 准入关键词密度 | >= 0.5% | ContentAnalyzer |
| 准入营销比 | <= 15% | ContentAnalyzer |
| 准入理论比 | <= 30% | ContentAnalyzer |
| 准入重复度 | <= 90% | MaterialPreChecker |

### 14.3 素材状态流转 DAG

```
                ┌──────────────┐
                │   uploaded   │ ← 上传完成
                └──────┬───────┘
                       │ parseFile()
                  ┌────┴────┐
                  │         │
             成功  │         │  失败(重试<3)
                  │         │
          ┌───────┴──┐  ┌──┴──────────┐
          │  parsed  │  │ parse_failed │ → 重试3次→rejected
          └─────┬────┘  └──────────────┘
                │ clean()
           ┌────┴────┐
           │         │
      成功  │         │  失败(重试<3)
           │         │
    ┌──────┴──┐  ┌───┴────────────┐
    │analyzed │  │ cleaning_failed │ → 重试3次→rejected
    └────┬────┘  └────────────────┘
         │ processMaterial()
    ┌────┴─────┐
    │extracted │ ← 颗粒已落库+向量化
    └──────────┘
```

### 14.4 故障排查清单

| 现象 | 排查路径 |
|------|---------|
| 素材一直 uploaded | 检查 parseFile 日志、AI 服务是否正常、文件是否损坏 |
| 素材 rejected | 查 analysisNotes 字段，看具体 rejectCode |
| 颗粒数为 0 | 查 ExtractionDropLog 表；查 extractionMetadata.chunkResults 看是否全部 `[]`；用 audit-report API |
| "text cannot be null or empty" | 模板文件缺失，查 PROMPTS_DIR 和对应的 prompt 文件 |
| "prefix is null" | 预检 role matching 异常，通常不影响管道 |
| embedding 写入失败 | 查 pgvector 扩展、PGvector 依赖、向量格式 |
