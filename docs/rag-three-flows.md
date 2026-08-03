# RAG 检索三种模式 — 完整流程对比

> 2026-07-30 · 基于实际代码，方法级粒度

---

## 配置开关

```yaml
# application.yml
app:
  rag:
    min-similarity: 0.25
    top-k: 5
    hybrid-search:
      enabled: false   # false=模式一, true=模式二/三
    rerank:
      enabled: false   # false=模式二, true=模式三 (未来)
```

---

## 模式一：纯 Dense (当前线上)

```
hybrid-search.enabled = false
```

```
用户: "POC阶段客户说太贵了怎么推进"
  │
  ▼
RagPipelineService.retrieveGrainsWithScores(query, spaceId, topK=5, domain, ragCtx)
  │
  ├─ ① GrainRetriever.retrieveWithScores(query, spaceId, 5)
  │     │
  │     ├─ DashScopeEmbeddingService.embed(query)
  │     │   → float[1024] 向量
  │     │
  │     └─ jdbc.query("""
  │           SELECT g.*, 1.0-(g.embedding <=> ?::vector) AS similarity
  │           FROM experience_grain g
  │           WHERE g.space_id = ?
  │             AND g.status = 'active'
  │             AND g.embedding IS NOT NULL
  │             AND (g.quality_score IS NULL OR g.quality_score >= 3.0)
  │           ORDER BY g.embedding <=> ?::vector
  │           LIMIT 15
  │         """, vectorStr, spaceId, vectorStr, 15)
  │         → 15 条候选，每条带 cosine similarity
  │
  │     Java 端 weight 重排:
  │       sorted by (cosine * weight) DESC, limit 5
  │       → 5 条结果
  │
  ├─ ② min-similarity 硬拦截
  │     filter(similarity >= 0.25)
  │     全部过滤 → writeKnowledgeGap → 返回空 → 触发 boundary_rules
  │
  ├─ ③ boostBySceneTagMatch(query, grains, similarities)
  │     query 拆 2-4 字 ngram:
  │       "POC" "OC阶" "阶段" "段客" "客户" "户说" "说太" "太贵"...
  │     → "价格" token 命中 sceneTag="价格谈判"
  │     → 该颗粒相似度 × 1.15 (上限 1.0)
  │
  ├─ ④ Tier 分层 (使用 boost 后的分数)
  │     >= 0.50 → "high"   🟢 高度匹配
  │     >= 0.30 → "ref"    🟡 相关匹配
  │      < 0.30 → 无标签    🟠 弱相关
  │
  └─ ⑤ GrainRetrieveLog 写入 + 返回
 
结果示例:
  #1 sim=0.72 tag=价格谈判 weight=1.5 → weighted=1.08 tier=high 🟢
  #2 sim=0.68 tag=需求挖掘 weight=1.0 → weighted=0.68 tier=high 🟢
  #3 sim=0.55 tag=POC策略  weight=1.8 → weighted=0.99 tier=high 🟢
  #4 sim=0.48 tag=客户维护 weight=1.0 → weighted=0.48 tier=ref  🟡
  #5 sim=0.42 tag=竞品分析 weight=1.0 → weighted=0.42 tier=ref  🟡
      (用户看不到这个中间态，直接进入 Prompt 组装)
```

### 模式一特点

| 优点 | 缺点 |
|------|------|
| 简单可靠 | "POC""ROI"等精确术语可能找不到 |
| 零额外基础设施 | 纯语义，有时漏掉字面匹配的颗粒 |

---

## 模式二：Dense + BM25 → RRF (开关打开后)

```
hybrid-search.enabled = true
rerank.enabled = false
```

```
用户: "POC阶段客户说太贵了怎么推进"
  │
  ▼
RagPipelineService.retrieveGrainsWithScores(query, spaceId, topK=5, domain, ragCtx)
  │
  ├─ ① GrainRetriever.retrieveHybrid(query, spaceId, 5)
  │     │
  │     ├─ DashScopeEmbeddingService.embed(query) → float[1024]
  │     │
  │     ┌─────────────────────────────────────────────────────────┐
  │     │             并行执行 (CompletableFuture)                  │
  │     │                                                         │
  │     │  ┌─ Dense 路 ──────────────────────────────────┐       │
  │     │  │  jdbc.query("""                              │       │
  │     │  │    SELECT g.*,                               │       │
  │     │  │      1-(g.embedding <=> ?::vector)           │       │
  │     │  │        AS similarity                         │       │
  │     │  │    FROM experience_grain g                   │       │
  │     │  │    WHERE g.space_id = ?                      │       │
  │     │  │      AND g.status = 'active'                  │       │
  │     │  │      AND g.embedding IS NOT NULL              │       │
  │     │  │      AND (g.quality_score IS NULL             │       │
  │     │  │           OR g.quality_score >= 3.0)          │       │
  │     │  │    ORDER BY g.embedding <=> ?::vector         │       │
  │     │  │    LIMIT 15                                   │       │
  │     │  │  """)                                         │       │
  │     │  │  → 15 条 denseResults + denseSims Map        │       │
  │     │  └──────────────────────────────────────────────┘       │
  │     │                                                         │
  │     │  ┌─ Sparse 路 (BM25) ──────────────────────────┐       │
  │     │  │  jdbc.query("""                              │       │
  │     │  │    SELECT g.*,                               │       │
  │     │  │      ts_rank(g.search_text,                  │       │
  │     │  │        plainto_tsquery('simple', ?))         │       │
  │     │  │        AS bm25_score                         │       │
  │     │  │    FROM experience_grain g                   │       │
  │     │  │    WHERE g.space_id = ?                      │       │
  │     │  │      AND g.status = 'active'                  │       │
  │     │  │      AND g.embedding IS NOT NULL              │       │
  │     │  │      AND g.search_text @@                    │       │
  │     │  │        plainto_tsquery('simple', ?)           │       │
  │     │  │    ORDER BY bm25_score DESC                  │       │
  │     │  │    LIMIT 15                                   │       │
  │     │  │  """)                                         │       │
  │     │  │  → 15 条 sparseResults                       │       │
  │     │  │                                              │       │
  │     │  │  BM25 内部计算:                               │       │
  │     │  │    TF: "POC"在本条出现3次 → tf=3              │       │
  │     │  │    IDF: "POC"在500条中只出现8次 → idf=高     │       │
  │     │  │    ts_rank = TF×IDF 归一化                    │       │
  │     │  │    → GIN 倒排索引直接取倒排链，不扫全表      │       │
  │     │  └──────────────────────────────────────────────┘       │
  │     └─────────────────────────────────────────────────────────┘
  │
  │     ┌─ RRF(k=60) 融合 ─────────────────────────────────────┐
  │     │                                                       │
  │     │  颗粒A (场景="POC策略", dense#1, sparse#3):           │
  │     │    RRF = 1/(60+1) + 1/(60+3) = 0.0164 + 0.0159       │
  │     │         = 0.0323                                      │
  │     │                                                       │
  │     │  颗粒B (场景="价格谈判", dense#3, sparse#1):           │
  │     │    RRF = 1/(60+3) + 1/(60+1) = 0.0159 + 0.0164       │
  │     │         = 0.0323                                      │
  │     │                                                       │
  │     │  颗粒C (场景="需求挖掘", dense#2, 无BM25命中):        │
  │     │    RRF = 1/(60+2) + 0 = 0.0161                        │
  │     │                                                       │
  │     │  颗粒D (场景="客户维护", 无Dense, sparse#8):          │
  │     │    RRF = 0 + 1/(60+8) = 0.0147                        │
  │     │                                                       │
  │     │  → 按 RRF × weight DESC, limit 5                       │
  │     └───────────────────────────────────────────────────────┘
  │
  │     最终: 颗粒A(0.0323×1.5) 颗粒B(0.0323×1.4) 颗粒C(0.0161×1.0)
  │           颗粒E(0.0158×1.2) 颗粒F(0.0153×1.0)
  │
  ├─ ② min-similarity 硬拦截 (≥0.25)
  │
  ├─ ③ boostBySceneTagMatch (×1.15)
  │
  ├─ ④ Tier 分层 (>=0.50 high, >=0.30 ref)
  │
  └─ ⑤ 返回

结果对比模式一:
  模式一 #5 是 "客户维护" sim=0.42 (纯语义排进来的)
  模式二 #5 变成 "POC技术验证" BM25命中 (RRF捞上来的)
  模式二 #3 在两路都排前面 → RRF分最高 → 排第一
```

### 模式二与模式一的差异

```
场景: 用户问 "ROI怎么算"

模式一 (纯Dense):
  #1 sim=0.68  "项目价值评估方法"     ← 语义近
  #2 sim=0.55  "投资回报分析技巧"     ← 语义近，但不精确
  #3 sim=0.48  "ROI计算和报价策略" ← 🎯 真正想要的但cosine不高
  #4 sim=0.42  "预算管控经验"
  #5 sim=0.38  "合同条款谈判"

模式二 (Dense+BM25):
  Dense:  同上
  BM25:   "ROI"直接命中倒排索引
          #1 "ROI计算和报价策略"
          #2 "ROI vs TCO对比分析"
          #3 "如何向客户展示ROI"
  RRF:    "ROI计算和报价策略"
            Dense排第3 → 1/(60+3)=0.0159
            BM25排第1 → 1/(60+1)=0.0164
            RRF总分: 0.0323 → 🎯 排第一
          "项目价值评估方法"
            Dense排第1 → 1/(60+1)=0.0164
            BM25未命中 → 0
            RRF总分: 0.0164 → 排第三
```

---

## 模式三：Dense + BM25 → RRF → ReRank (未来)

```
hybrid-search.enabled = true
rerank.enabled = true
```

```
用户: "POC阶段客户说太贵了怎么推进"
  │
  ▼
RagPipelineService.retrieveGrainsWithScores(query, spaceId, topK=5, domain, ragCtx)
  │
  ├─ ① GrainRetriever.retrieveHybrid(query, spaceId, 15)   ← topK×3 多取
  │     Dense 15条 + Sparse 15条 → RRF 融合 → ~22条去重 → 取Top15
  │     (与模式二完全相同)
  │
  ├─ 🆕 ② GrainRetriever.rerank(query, top15, topK=5)
  │     │
  │     │   for each grain in Top15, 拼全文:
  │     │     grain_text = sceneTag + " " + sceneDescription + " "
  │     │                + expertThought + " " + standardScript
  │     │                + " " + commonMistakes
  │     │     → documents[] = [grain1全文, grain2全文, ..., grain15全文]
  │     │
  │     │   POST {ai.service.url}/rerank
  │     │   Body: {
  │     │     "query": "POC客户价格异议推进策略",
  │     │     "documents": [
  │     │       "价格谈判 大客户POC阶段定价策略 先算ROI再报价...",
  │     │       "POC策略 POC技术验证推进方法 演示关键指标比对...",
  │     │       "需求挖掘 通过SPIN提问法挖掘深层次需求...",
  │     │       ...
  │     │     ]
  │     │   }
  │     │
  │     │   BGE-reranker-v2-m3 内部:
  │     │     ┌─ Cross-encoder: 把 (query, doc_i) 拼接编码
  │     │     │   不同于 Dense 的 bi-encoder (query和doc分开编码)
  │     │     │   Cross-encoder 能看到 query 和 doc 之间的交互
  │     │     │
  │     │     ├─ 对每条 doc 输出一个 0-1 的相关性分数
  │     │     │   "价格谈判..." vs "POC客户价格异议推进策略" → 0.94
  │     │     │   "POC策略..." vs "POC客户价格异议推进策略" → 0.89
  │     │     │   "需求挖掘..." vs "POC客户价格异议推进策略" → 0.51
  │     │     │   "竞品分析..." vs "POC客户价格异议推进策略" → 0.23
  │     │     │   ...
  │     │     │
  │     │     └─ 返回: [
  │     │           {"index": 0, "relevance_score": 0.94},
  │     │           {"index": 1, "relevance_score": 0.89},
  │     │           {"index": 2, "relevance_score": 0.51},
  │     │           ...
  │     │         ]
  │     │
  │     │   Java 端:
  │     │     sorted by relevance_score DESC, limit 5
  │     │     → 5 条精排结果
  │     │
  │     │     (保留原始 cosine similarity 用于后续 tier 分层)
  │     │
  │  ├─ ③ min-similarity 硬拦截 (≥0.25, 用原始cosine)
  │  │     (ReRank 0.94 但 cosine 0.22 → 保留, 因为 ReRank 认为相关)
  │  │     注意: 这个逻辑可能需要调整 — ReRank分高的颗粒即使cosine低也应该保留
  │  │     → 实际应该: filter(relevance_score >= 0.3 OR cosine >= 0.25)
  │  │
  │  ├─ ④ boostBySceneTagMatch (用原始cosine)
  │  │
  │  ├─ ⑤ Tier 分层 (用原始cosine或ReRank分)
  │  │     → 建议: 用 ReRank 分代替 cosine 做 tier
  │  │        >= 0.80 → high, >= 0.50 → ref
  │  │
  │  └─ ⑥ 返回

结果对比三种模式:
  模式一 #5 "竞品分析" sim=0.42        (纯语义,不太相关但cosine排进来)
  模式二 #5 "POC技术验证" RRF高分      (BM25捞到"POC"关键词)
  模式三 #5 被 ReRank 过滤掉           (Cross-encoder判断实际不相关)
  模式三 Top3 都是真正解"太贵了+推进"的颗粒
```

### 三种模式对比: 同一 query 的结果差异

```
Query: "ROI怎么算给客户看"

┌──────────────────────────────────────────────────────────────┐
│ 模式一: Dense only                                           │
│                                                              │
│ #1 sim=0.72 tier=high  项目价值评估方法                       │
│ #2 sim=0.65 tier=high  投资回报分析                           │
│ #3 sim=0.58 tier=high  ROI计算与报价策略                     │
│ #4 sim=0.51 tier=high  客户预算沟通                           │
│ #5 sim=0.45 tier=ref   商务谈判技巧                           │
│                                                              │
│ 问题: #1#2 语义近但不精准, 真正含"ROI"的#3排第三              │
│ 漏掉: "ROI可视化案例" (含具体数字但cosine只有0.38)            │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ 模式二: Dense + BM25 → RRF                                   │
│                                                              │
│ #1 RRF=0.033 tier=high  ROI计算与报价策略                     │
│                          (Dense#3+BM25#2, 双路高分)          │
│ #2 RRF=0.028 tier=high  项目价值评估方法                      │
│                          (Dense#1, BM25未命中)                │
│ #3 RRF=0.026 tier=high  ROI可视化案例                        │
│                          (Dense未进Top5,BM25#1, RRF捞到)     │
│ #4 RRF=0.022 tier=high  投资回报分析                          │
│ #5 RRF=0.019 tier=ref   客户预算沟通                           │
│                                                              │
│ 改善: "ROI可视化案例" 被 BM25 捞回来了                        │
│       真正的ROI颗粒排到第一                                    │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ 模式三: Dense + BM25 → RRF → ReRank                          │
│                                                              │
│ RRF Top15 → ReRank 精排:                                      │
│                                                              │
│ 候选                               RRF分   ReRank分  最终   │
│ ─────────────────────────────────────────────────────────── │
│ ROI计算与报价策略                 0.033    0.96     ✅ #1    │
│ ROI可视化案例                     0.026    0.91     ✅ #2    │
│ 投资回报分析                      0.022    0.84     ✅ #3    │
│ 项目价值评估方法                  0.028    0.72     ✅ #4    │
│ 客户预算沟通                      0.019    0.58     ✅ #5    │
│ ─────────────────────────────────────────────────────────── │
│ 商务谈判技巧                      0.017    0.31     ❌ 淘汰  │
│ 合同管理经验                      0.015    0.18     ❌ 淘汰  │
│ ...                               ...      ...      ...     │
│                                                              │
│ 改善: 商务谈判技巧在RRF中蒙混过关，ReRank直接识别不相关       │
│       每条颗粒被重新打分，噪声被彻底过滤                       │
└──────────────────────────────────────────────────────────────┘
```

---

## 延迟对比

```
模式一 (纯Dense):
  embed(100ms) + pgvector(5ms) + Java重排(<1ms) = ~105ms

模式二 (Dense+BM25):
  max(embed(100ms)+pgvector(5ms), BM25(3ms)) + RRF(<1ms) = ~105ms
  (并行, 不增加延迟)

模式三 (Dense+BM25+ReRank):
  max(embed+pgvector, BM25) + RRF + ReRank API(50-200ms) = ~155-305ms
  (ReRank 是额外的一次 API 调用, 50-200ms 取决于模型部署方式)
```

---

## 基础设施要求

| 组件 | 模式一 | 模式二 | 模式三 |
|------|:---:|:---:|:---:|
| pgvector | ✅ | ✅ | ✅ |
| Flyway V10 (search_text) | - | ✅ 必须 | ✅ 必须 |
| GIN 索引 (idx_grain_fts) | - | ✅ 必须 | ✅ 必须 |
| BGE-reranker-v2-m3 | - | - | ✅ 必须 |
| GPU (推荐) | - | - | ⚠️ CPU可用但慢 |

---

## 切换方式

```yaml
# 模式一: 当前线上
app.rag.hybrid-search.enabled: false

# 模式二: V10 migration 执行后
app.rag.hybrid-search.enabled: true

# 模式三: BGE-reranker 部署后
app.rag.hybrid-search.enabled: true
app.rag.rerank.enabled: true          # 需新增此配置
```
