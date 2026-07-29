# AI 经验萃取平台 — 完整技术流程文档

> 2026-07-30 · 从素材上传到分身聊天的全链路代码级流程

---

## 目录

1. [架构总览](#1-架构总览)
2. [第一部分：素材上传 → 颗粒萃取](#2-第一部分素材上传--颗粒萃取)
3. [第二部分：审核 → 发布分身](#3-第二部分审核--发布分身)
4. [第三部分：用户聊天（RAG 全链路）](#4-第三部分用户聊天rag-全链路)
5. [第四部分：Prompt 渲染](#5-第四部分prompt-渲染)
6. [第五部分：后处理与反馈闭环](#6-第五部分后处理与反馈闭环)

---

## 1. 架构总览

```
┌────────────────────────────────────────────────────────────────┐
│                        素材 → 颗粒 管线                         │
│                                                                │
│  素材上传 → 准入检查 → Layer0~11萃取 → saveGrains → embedGrains │
│      ↓                                                         │
│  审核流水线 → 发布分身 → 生成推荐问题                            │
│      ↓                                                         │
│  用户聊天 → Query改写 → RAG检索 → Prompt组装 → LLM流式 → 后处理  │
└────────────────────────────────────────────────────────────────┘
```

**核心组件映射**：

| 组件 | 文件 | 职责 |
|------|------|------|
| 素材清洗调度器 | `MaterialCleaningScheduler.java` | 30s 扫描 + 乐观锁抢任务 |
| 11 层萃取管道 | `MaterialCleaningService.java` | 规则清洗 → AI 萃取 → 对抗验证 |
| 颗粒向量化 | `DashScopeEmbeddingService.java` | DashScope text-embedding-v4, 1024 维 |
| RAG 检索管线 | `RagPipelineService.java` | Query 改写 → 检索 → tier 分层 → 拦截 |
| 向量检索器 | `GrainRetriever.java` | pgvector HNSW + BM25 + RRF 融合 |
| Prompt 组装 | `PromptAssemblyService.java` | 画像 + 经验 + 边界 + 分数透传 |
| 流式聊天 | `ChatStreamService.java` | SSE 三阶段：Setup → Stream → Post-stream |

---

## 2. 第一部分：素材上传 → 颗粒萃取

### 2.1 触发方式

```
MaterialCleaningScheduler.scanAndProcess() 每 30 秒
  → SQL: UPDATE skill_material SET locked_by=workerId WHERE locked_by IS NULL
    → 乐观锁抢到 → processMaterial(materialId)
      → cleaningService.clean(materialId) → 11 层处理
        → saveGrains() → embedGrains() → markMaterialExtracted()
```

### 2.2 准入检查 (PreChecker)

```
文件: MaterialCleaningService.clean() L1006-1037

Gate 1: checkAcceptance(rawText, domain)
  → 关键词密度检查、领域匹配度
  → 不通过 → material.status = "rejected" → 返回空列表
  
Gate 2: evaluate(rawText, domain, spaceId) 
  → 质量评分: overallScore, grade, estimatedGrainMin/Max, detectedScenes
  → 仅记录，不阻断

访谈素材 (materialType="interview") 跳过 Gate 1
```

### 2.3 Layer 0: 情境标注 (1 次 AI 调用)

```
文件: MaterialCleaningService.tagContext() L600-632

Prompt: material_context_tag.md
输入: 素材文本前 3000 字
输出: 5 维商业情境信号
  - buying_stage: awareness/consideration/decision/implementation/renewal
  - buyer_persona: economic_buyer/technical_evaluator/champion/user/procurement
  - competitive_context: greenfield/competitive/replacement/internal_build/unknown
  - deal_size_hint: smb/mid_market/enterprise/unknown
  - industry_signals: 行业字符串数组

用途: 作为 Layer 4 首个 chunk 的 context_prefix
```

### 2.4 Layer 1-3: 规则清洗 (0 次 AI 调用)

```
文件: MaterialCleaningService.clean() L1029-1040

Layer 1: MaterialNoiseCleaner.cleanFormatNoise(rawText, detectedType)
  → 根据素材类型 (chat_log/voice_transcript/document) 选规则集
  → 去除时间戳、系统消息、格式噪声

Layer 2: BusinessNoiseFilter.filterBusinessNoise(deNoised, domain)
  → 领域特定业务噪声过滤

Layer 3: TextNormalizer.normalize(filtered)
  → 文本规范化（全角半角、空白符等）

日志: "三层清洗完成: X字→Y字→Z字→W字"
```

### 2.5 智能分块 (0 次 AI 调用)

```
文件: MaterialCleaningService.isDialogueText() + dialogueChunk() + semanticChunk()

P2-9: 对话检测（三重判断）
  1. materialType == "interview" → 对话
  2. detectedType ∈ {chat_log, voice_transcript} → 对话
  3. 文本内说话人行 > 40% → 对话（捕获无时间戳聊天记录）
  以上都不满足 → 文档

对话类分块 (dialogueChunk):
  → splitBySpeaker(text): 按 "角色：内容" 正则分割
  → 攒回合到 chunk: 保持"客户发言 + 销售回应"在同一块
  → 目标 800 字/chunk，上限 1500 字

文档类分块 (semanticChunk):
  → 按句子边界切分 (。！？\n .!?)
  → 目标 800 字，上限 1500 字
  → P2-8: 相邻 chunk 加 50 字重叠窗口 "[上文] ...\n---\n当前内容"
  → 过滤噪声行: 纯数字、时间戳、短外文行

isNoise(): 过滤 <5 字符行、纯数字、时间戳、非中文短行
```

### 2.6 去重 (0 次 AI 调用)

```
文件: MaterialCleaningService.deduplicate() L1206-1237

Jaccard 3-gram 字符相似度 vs 同空间已有颗粒
  → buildGrainText(): 拼接 sceneDescription + expertThought + standardScript
  → textSimilarity(a, b): |A ∩ B| / |A ∪ B| (3-gram)
  → sim > 0.7 → 跳过此 chunk
  → 淘汰记录写入 ExtractionDropLog (stage="dedup")

P2-2: 嵌入后语义去重
  → MaterialCleaningScheduler.deduplicateByEmbedding()
  → embedGrains 后执行 pgvector ANN 查同空间存量
  → cosine > 0.95 → 标记为 deprecated
```

### 2.7 Layer 4: AI 萃取 (每个 chunk 1 次 AI 调用)

```
文件: MaterialCleaningService.extractInsights() L1358-1397

并行策略: 每 5 个 chunk 一批，CompletableFuture.runAsync 并行

P2-6: 首个 chunk 智能选择
  → 选最长 chunk (信息密度最高) 用 material_extract_full.md
  → 后续 chunk 用 material_extract_short.md

Full prompt: material_extract_full.md
  变量: {context_prefix} = Layer 0 的商业情境 + {material_content} = chunk 文本
  输出: JSON 数组 [{scene_description, expert_thought, standard_script,
          common_mistakes, applicable_condition, confidence}]

Short prompt: material_extract_short.md
  变量: {material_content} = chunk 文本
  指令引用: "rules same as above"

过滤: confidence < 0.7 丢弃
```

### 2.8 Layer 5: 场景分类 (1 次 AI 调用)

```
文件: MaterialCleaningService.classifyScenesBatch() L1421-1470

Prompt: classify_scenes.md
输入:
  - P2-7: sceneDescription + expertThought 前 50 字 (提升标签准确度)
  - 已有标签列表 (existingTags): "价格谈判,需求挖掘,..."
  - 领域名称 (domain_name)

输出: [{index: 0, tag: "价格谈判"}, ...]
  → 尝试复用已有标签
  → 新场景自动归为"通用技巧"
```

### 2.9 Layer 6: 对抗验证 (每 10 条 1 次 AI 调用)

```
文件: MaterialCleaningService.verifyGrains() L639-735

Prompt: material_verify.md
批量: 每批 10 条，构建 preview: "场景:... | 思考:... | 话术:..."

5 维打分 (各 1-5 分):
  ┌────────────────┬────────┬──────────────────────────┐
  │ 维度             │ 权重    │ 评估内容                    │
  ├────────────────┼────────┼──────────────────────────┤
  │ specificity    │ 0.25   │ 新人能直接执行吗？           │
  │ reproducibility│ 0.20   │ 3个月经验的人能复现吗？       │
  │ causality      │ 0.20   │ 行为→买方反应→结果 链条清晰？  │
  │ distinctiveness│ 0.20   │ 与常识的差异度？反直觉？      │
  │ falsifiability │ 0.15   │ 什么情况下失效？边界清楚？    │
  └────────────────┴────────┴──────────────────────────┘

拒绝规则:
  - specificity < 3 → 立即 REJECT
  - composite >= 3.5 AND verdict == "APPROVE" → APPROVE
  - 否则 → REJECT

通过后附上:
  - qualityScore = composite 加权分
  - difficultyLevel = reproducibility >= 4 ? "beginner"
                     : >= 3 ? "intermediate"
                     : >= 2 ? "advanced" : "master"
  - verificationNotes = 完整评分 JSON

淘汰记录: 写入 ExtractionDropLog (stage="verification")
```

### 2.10 Layer 7: 同标签合并 (条件触发)

```
文件: MaterialCleaningService.mergeOverlapping() L747-830

触发条件: candidates > 10 条
不触发: 直接返回 candidates (≤10 条时重叠概率极低)

流程:
  1. 按 sceneTag 分组
  2. 每组 >= 2 条 → 送 material_merge.md
  3. AI 合成一条合并颗粒
  4. confidence < 0.6 → 放弃合并，保留原组

P0-5: merge 后保留 qualityScore
  → avgScore = group.stream().filter(qs != null).mapToDouble(qs).average()
  → difficultyLevel 和 verificationNotes 取第一个非空值
  → 合并前: qualityScore=null (旧行为)
  → 合并后: qualityScore=成员平均分 (不丢失质量信号)
```

### 2.11 Layer 8-11: 模式发现 → FAQ → 叙事 → 画像

```
Layer 8 (discoverPatterns): ≥8 条候选时触发
  Prompt: material_pattern.md (最多用 20 条)
  输出: core_habits(3条), differentiators(3条), methodology_name, oneliner

Layer 9 (extractFaq): 从原文提取客户异议 + 销售回应 Q&A 对
  Prompt: material_faq.md

Layer 10 (generateNarrativeWithLinks): 叙事重放 + 策略→颗粒索引
  Prompt: material_narrative.md
  输出: storyline(phases+grain indices), linkedStrategies, linkedTactics

Layer 11 (generateProfile): @Async 异步，不阻塞管道
  Prompt: material_profile.md
  输入: top10 颗粒 + pattern + contextSignals
  输出: SkillProfile { personality, speakingStyle, background,
          commonPhrases, knowledgeDomains, communicationPreferences }
```

### 2.12 颗粒持久化

```
文件: MaterialCleaningScheduler.saveGrains() L199-219

ExperienceGrain 字段映射:
  id=UUID, spaceId, sourceMaterialId=materialId
  sceneTag, sceneDescription, expertThought, standardScript,
  commonMistakes, applicableCondition
  qualityScore, difficultyLevel, verificationNotes
  status="active", helpfulCount=0, unhelpfulCount=0
  P0-4: weight = qualityScore/5×2, clamp[0.1, 2.0]
  
向量化 (embedGrains):
  grainToText() = sceneTag + sceneDescription + expertThought
                + standardScript + commonMistakes
                + applicableCondition (P0-3 新增)
  → DashScope text-embedding-v4 → 1024 维向量
  → pgvector INSERT INTO experience_grain (embedding) VALUES (?)
```

---

## 3. 第二部分：审核 → 发布分身

### 3.1 审核流水线

```
文件: AdminAuditController

审核步骤:
  1. 显性审核 (ExplicitStep): 检查敏感词、违规内容
  2. 分身审核 (SkillStep): 审核画像完整性、标签准确性
  3. 场景审核 (SceneStep): 审核场景分类和颗粒覆盖
  4. 产品审核 (ProductStep): 审核产品关联

管理员操作:
  - approve(step): 通过当前步骤 → 进入下一步
  - reject(step, reason): 驳回 → skill.status = "draft"
  - publish(skillId): 所有步骤通过 → 发布
```

### 3.2 发布流程

```
文件: AdminAuditController.updateStatus() L408-418

POST /admin/skills/{skillId}/status  body: { status: "published" }

执行:
  1. skill.setStatus("published")
  2. skillRepository.save(skill)
  3. @Async: skillService.generateRecommendedQuestions(skillId)
     → 收集 top6 场景标签 + grain 样本
     → LLM 调用 skill_recommended_questions.md
     → 生成 6-8 条自然语言推荐问题
     → 存入 skill.recommended_questions JSONB
  4. 组织分身: orgSkillService.generateOrgIntroProfile(id)
     → 汇总成员 introProfile → 生成组织级简介
```

---

## 4. 第三部分：用户聊天（RAG 全链路）

### 4.1 入口：ChatStreamService.chatIndividual()

```
文件: ChatStreamService.java L156-262

POST /skills/{skillId}/chat (SSE 流式)

Phase 0 — 参数校验:
  - 消息不能为空
  - 分身状态检查 (published/active 或 admin/owner)

Phase 1 — Setup (同步):
  ┌──────────────────────────────────────────────┐
  │ 1. resolveMode: qa_quick/qa_discuss/talk      │
  │ 2. upsertConversation: 获取/创建会话           │
  │ 3. buildRagHistory: 最近 12 条消息(每条≤200字) │
  │ 4. rewriteQuery: LLM 指代消解 + 代词还原       │
  │ 5. retrieveGrainsWithScores: RAG 检索 (见 4.2) │
  │ 6. buildSkillSystemPrompt: Prompt 组装 (见 5)  │
  │ 7. buildChatMessages: 历史+当前消息 (见 5.3)   │
  └──────────────────────────────────────────────┘

Phase 2 — Stream (Flux):
  chatStreamAdapter.chatStream(messages) → SSE 逐 token

Phase 3 — Post-stream (doFinally):
  - saveAiMessage: 持久化 AI 回复
  - buildMultiSpaceSourceChunkFlux: 溯源信息 SSE 推送
  - 写入 ConversationStats (ragHighCount/RefCount/NoneCount/avgSimilarity)
```

### 4.2 RAG 检索：RagPipelineService

#### 4.2.1 Query 改写 (rewriteQuery)

```
文件: RagPipelineService.rewriteQuery() L93-114

开关: app.rag.query-rewrite.enabled (默认 true)

输入: 用户消息 + 最近 12 条对话历史(每条≤200字，最后500字)
Prompt: query_rewrite.md ("output a standalone search query, max 30 chars")
LLM: chatStreamAdapter.chat(prompt) — 小模型调用

失败回退: 返回原始消息
```

#### 4.2.2 向量检索 (GrainRetriever)

```
文件: GrainRetriever.java

单空间 (retrieveWithScores):
  1. DashScopeEmbeddingService.embed(question) → 1024 维向量
  2. pgvector HNSW ANN 检索:
     SELECT g.*, 1.0 - (g.embedding <=> ?::vector) AS similarity
     FROM experience_grain g
     WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
       AND (g.quality_score IS NULL OR g.quality_score >= 3.0)  ← P0-2
     ORDER BY g.embedding <=> ?::vector
     LIMIT topK * 3  (默认 5*3=15)
  3. Java 端 weight 重排: sort by similarity * weight DESC, limit topK

多空间 (retrieveWithScores for List<UUID>):
  P1-5: CompletableFuture.runAsync 并行执行所有空间查询
  → ConcurrentHashMap 线程安全合并 simMap
  → synchronizedList 收集 candidates
  → 去重 + weight 全局重排

Hybrid Search (retrieveHybrid, 默认关闭):
  P1-9: Dense + Sparse 并行 → RRF(k=60) 融合
  Dense 路: pgvector HNSW (同上)
  Sparse 路: bm25Search()
    → PostgreSQL ts_rank + plainto_tsquery('simple', query)
    → GIN 索引加速 @@ 匹配
    → 对产品名/编号/术语等精确匹配效果远超向量
  RRF 融合: RRF_Score(d) = 1/(60+rank_dense) + 1/(60+rank_sparse)
  开关: app.rag.hybrid-search.enabled (默认 false)
```

#### 4.2.3 后处理 (RagPipelineService)

```
文件: RagPipelineService.retrieveGrainsWithScores() L186-252

P0-1: min-similarity 硬拦截
  → filter(similarity >= app.rag.min-similarity, 默认 0.25)
  → 全部低于阈值 → 返回空列表 → 触发 boundary_rules

Tier 分层 (从 DomainConfig 读取阈值):
  → similarity >= 0.50 → tier = "high"
  → similarity >= 0.30 → tier = "ref"
  → 低于 0.30 → tier = null (不打标签)

P1-2: sceneTag 关键词匹配加权
  → boostBySceneTagMatch(): query 2-4 字 ngram 匹配 sceneTag
  → 命中 → similarities 中分数 × 1.15 (上限 1.0)

写检索日志: GrainRetrieveLog (每条命中颗粒一条)
无结果: writeKnowledgeGap (记录知识盲区)
```

---

## 5. 第四部分：Prompt 渲染

### 5.1 System Prompt 组装 (buildSkillSystemPrompt)

```
文件: PromptAssemblyService.buildSkillSystemPrompt() L83-200

输入:
  - skill: 分身实体
  - grains: RAG 检索到的颗粒列表
  - grainTiers: grainId → "high"/"ref" 
  - grainSimilarities: grainId → 0-1 相似度 (P1-10 新增)
  - mode: qa/talk
  - channel: web/h5/feishu

步骤 1: 画像加载
  SkillProfile: personality, speakingStyle, background,
                commonPhrases, knowledgeDomains, communicationPreferences

步骤 2: experience_context 构建
  groupGrainsByScene(grains) → 按 sceneTag 分组
  对每组:
    P1-4: 取组内最高 tier (非第一个)
    P1-10: 标注匹配度% + 颜色:
      🟢 ≥80% | 🟡 50-80% | 🟠 30-50% | 🔴 <30%
    每个颗粒注入:
      - 我的思考: expertThought (≤200chars)
      - 我说过的话: standardScript (≤300chars)
      - 我见过的坑: commonMistakes (≤150chars)
      - ⚠️ 适用条件: applicableCondition (≤100chars, P1-3)
  P0-6: best grain 放尾部强调 (Lost-in-the-Middle 缓解)

步骤 3: 模板选择
  talk → skill_talk.md (自由对话风格)
  qa → skill_qa_chat.md (结构化 QA 风格)

步骤 4: 变量注入
  {owner_name}, {owner_title}, {personality}, {speaking_style},
  {background}, {common_phrases}, {skill_tags}, {target_scenarios},
  {knowledge_domains}, {communication_preferences},
  {experience_context}, {mode_instruction}, {boundary_rules}
```

### 5.2 边界管控 (boundary_rules)

```
文件: PromptAssemblyService L163-210

基础边界 — ALWAYS 注入 (P0-7):
  ### 匹配度使用规则
  - 🟢 ≥80%: 直接引用原话和策略，用第一人称讲述
  - 🟡 50-80%: 参考思路，用自己的话重新表达
  - 🟠 30-50%: 仅背景参考，不当作核心答案
  - 🔴 <30%: 视为不相关，忽略
  
  ### 通用铁律
  - 禁止创造经验区中没有的策略、话术、方案
  - 禁止脱离经验区推理、拓展、脑补
  - 可在原话基础上极小幅度润色

升级边界 — highTierCount == 0 时注入:
  ## 当前情况：无高度匹配经验
  1. 诚实告知
  2. 引向擅长方向
  3. 保持个人风格
```

### 5.3 对话消息列表 (buildChatMessages)

```
文件: PromptAssemblyService.buildChatMessages() L447-470

结构: [system prompt] + [最近 20 条历史] + [当前用户消息]

P1-12: token 预检 (ContextWindowGuard)
  → 估算总 token = chars/2
  → 超 maxContextTokens×80% → 从最早消息开始裁剪
  → 保留最近的完整消息
  
Chat 和 Interview 统一使用 ContextWindowGuard
```

### 5.4 Prompt 模板文件

```
skill_talk.md:
  ┌──────────────────────────────────────────┐
  │ # 角色: {owner_name} 本人. {background}   │
  │ # 性格: {personality}                     │
  │ # 说话风格: {speaking_style}               │
  │ # 口头禅: {common_phrases}                 │
  │ # 擅长领域: {knowledge_domains}            │
  │ # 经验: {experience_context}              │
  │ # 对话模式: 自由对话                       │
  │ {boundary_rules}                          │
  │ # 边界 (7条)                               │
  │ # 社交与情感                               │
  │ {mode_instruction}                        │
  └──────────────────────────────────────────┘

skill_qa_chat.md (P0-8 补全):
  ┌──────────────────────────────────────────┐
  │ (同上画像部分)                             │
  │ # 回答结构: 判断逻辑→策略选择→话术→可迁移点  │
  │ # 规则 (6条)                               │
  │ # 社交与情感                               │
  │ {boundary_rules}                          │
  │ # 边界 (5条, 含不编造+被追问3次)            │
  │ {mode_instruction}                        │
  └──────────────────────────────────────────┘
```

---

## 6. 第五部分：后处理与反馈闭环

### 6.1 AI 响应后处理

```
SSE 流完成 → doOnComplete:
  1. convPersistence.saveAiMessage()
     → 持久化 AI 回复到 skill_message 表
     → 附带 persistedGrainId, persistedReportId (溯源)

  2. ConvStats.collect():
     → ragHighCount, ragRefCount, ragNoneCount
     → ragAvgSimilarity
     → llmDurationMs, totalDurationMs
     → isTest (admin 测试对话标记)

  3. buildMultiSpaceSourceChunkFlux():
     → 向 SSE 推送 source 事件
     → 前端溯源抽屉可点击查看颗粒来源

doFinally:
  写 ConversationStats 行
```

### 6.2 用户反馈收集

```
POST /skills/{skillId}/feedback
Body: { sessionId, grainId, helpful: true/false, messageId,
        conversationId, query, aiResponse, ragScore }

SkillService.submitFeedback():
  1. 创建 FeedbackLog (完整审计 trail)
  2. grainId 非空 → incrementHelpful(grainId) 或 incrementUnhelpful(grainId)
     → @Modifying JPQL: SET helpful_count = helpful_count + 1
```

### 6.3 自动调权闭环

```
SkillStatsScheduler:

每 5 分钟 (refreshSkillStats):
  → conversation_stats 聚合 → skill.conversation_count, user_count
  → feedback_log 聚合 → skill.satisfaction_rate

每 30 分钟 (updateGrainWeights) P1-1:
  → 扫描有反馈的 active 颗粒
  → newWeight = baseWeight × (1 + helpfulBonus - unhelpfulPenalty)
    helpfulBonus = min(0.5, helpfulRatio × 0.5)
    unhelpfulPenalty = min(0.4, unhelpfulRatio × 0.3)
  → clamp [0.1, 2.0]

每 30 分钟 (updateQualityScores) P2-3:
  → 扫描 feedback >= 5 条的颗粒
  → newScore = originalScore × 0.6 + feedbackScore × 0.4
  → feedbackScore = helpfulCount/total × 5.0

每小时 (autoDeprecateGrains) P2-1:
  → unhelpfulCount >= 10 AND helpfulCount < 3 → status = "deprecated"
```

### 6.4 回答矫正 (P1-7)

```
POST /admin/insights/corrections
Body: { skillId, conversationId, messageId, originalQuery,
        badResponse, correctedResponse, grainIds, correctedBy }

AdminInsightController.submitCorrection():
  1. 保存 AnswerCorrection 记录
  2. grainIds 中每个颗粒: weight × 0.7 衰减
  3. 写 AdminAuditLog

前端入口: Admin 对话历史页 → 每条 AI 消息旁"矫正"按钮
```

### 6.5 访谈阶段摘要 (P1-11)

```
InterviewService.markPhaseCompleteFlux():
  → 标记采集数据
  → @Async: self.generatePhaseSummary(sessionId, completedPhase)
    → 拼接阶段全部消息
    → LLM 调用 interview_phase_summary.md
    → 保存 PhaseSummary(id, sessionId, phase, phaseLabel, summary)
  
buildMessagesList():
  → 已完成阶段: 用 PhaseSummary.system 消息替代全量历史
  → 当前阶段: 保留全部消息
  
效果: 1h 访谈 50K tokens → ~12K tokens
```

---

## 附录 A: 配置项速查

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.rag.min-similarity` | 0.25 | 颗粒最低相似度阈值 |
| `app.rag.top-k` | 5 | 单次检索最大返回数 |
| `app.rag.hybrid-search.enabled` | false | Hybrid Search 开关 |
| `app.rag.query-rewrite.enabled` | true | Query 改写开关 |
| `app.rag.context-max-tokens` | 8192 | LLM 上下文窗口大小 |
| domain.precheck.rag_high_threshold | 0.50 | high tier 阈值 |
| domain.precheck.rag_ref_threshold | 0.30 | ref tier 阈值 |

## 附录 B: 新增数据表

| 表名 | Migration | 用途 |
|------|-----------|------|
| `experience_grain.search_text` | V10 | tsvector 全文检索列 + GIN 索引 |
| `interview_phase_summary` | V11 | 访谈阶段 AI 摘要 |
| `answer_correction` | V12 | Admin 回答矫正记录 |

## 附录 C: 关键 SQL 查询

```sql
-- RAG 向量检索 (单空间)
SELECT g.*, 1.0 - (g.embedding <=> ?::vector) AS similarity
FROM experience_grain g
WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
  AND (g.quality_score IS NULL OR g.quality_score >= 3.0)
ORDER BY g.embedding <=> ?::vector LIMIT ?;

-- BM25 全文检索
SELECT g.*, ts_rank(g.search_text, plainto_tsquery('simple', ?)) AS bm25_score
FROM experience_grain g
WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
  AND g.search_text @@ plainto_tsquery('simple', ?)
ORDER BY bm25_score DESC LIMIT ?;
```
