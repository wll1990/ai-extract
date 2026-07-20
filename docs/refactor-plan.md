# 审核页 vs 分身广场 — 代码重复分析 & 统一改版计划

> 2026-07-11 分析，待测试稳定后执行。

## 现状：两份代码做同样的事，几乎没有共享

### 共享的（极少）

| 共享内容 | 方式 |
|---|---|
| `SkillChatView` | 唯一共享组件 — 只提供消息滚动区 + 输入框骨架，不渲染任何气泡 |
| `lib/sse.ts` | SSE 连接工具 |
| `api/client.ts` | `API_BASE`、`authHeaders()`、`apiClient()` |

### 独立的（重复的）

| 逻辑 | ProductDemoModal | Skill page | 重复度 |
|---|---|---|---|
| 发送→评估→客户回应 | `sendPractice()` ~170行 | `handlePracticeSend()` ~80行 | 100% |
| Retry 计数 + 状态 | `retryPractice()` + `retryCountRef` | 相同逻辑 | 100% |
| Advance round | `advanceRound()` | 相同逻辑 | 100% |
| Hits/misses/technique 映射 | 手写字段赋值 | 手写字段赋值 | 100% |
| fullAnswer 展示 | 刚同步 | 刚实现 | 已对齐 |
| 类型定义 | `ChatMessage` 本地 interface | `PracticeMessage` 另一个 interface (PracticeView.tsx) | 100% |
| API 封装 | 直接 `fetch()` + 硬编码路径 | `@/lib/api/skill.ts` 封装函数 | 0% 共享 |

### API 端点重复

| 功能 | ProductDemoModal | Skill page | 后端是否同一方法 |
|---|---|---|---|
| 练习评估 | `/admin/skills/{id}/practice-evaluate` | `/skills/{id}/practice/evaluate-round` | ✅ 都调 `evaluatePracticeResponse()` |
| 练习结束评估 | `/admin/skills/{id}/practice-score` | `/skills/{id}/practice/evaluate` | ❌ 不同 |
| 客户开场 | `/admin/skills/{id}/practice-opening` | `startPractice` 响应中自带 | ❌ 不同 |
| QA 聊天 | `/skills/{id}/chat` (SSE) | `/skills/{id}/chat` (SSE) | ✅ 共享 |
| 客户回应 | `/skills/{id}/practice/respond` (SSE) | `/skills/{id}/practice/respond` (SSE) | ✅ 共享 |

### 角色命名反转（容易晕）

| 实体 | ProductDemoModal | Skill page |
|---|---|---|
| 用户/销售 | `role: 'customer'` | `role: 'user'` |
| AI/客户 | `role: 'avatar'` | `role: 'customer'` |
| QA 用户 | `role: 'customer'` | `role: 'user'` |
| QA AI | `role: 'avatar'` | `role: 'ai'` |

### 展示差异

| 维度 | ProductDemoModal（审核页） | Skill page（分身广场） |
|---|---|---|
| **定位** | 管理员验证工具，快速过场景 | 终端用户体验，完整对练流程 |
| **模式** | practice / qa / demo / debug | qa / practice |
| **气泡** | 扁平色块，无头像，紧凑 | 圆角阴影 + 渐变色头像 + 角色标签 |
| **复盘** | 弹窗叠在聊天上 | 全页切换 |
| **独有功能** | 场景选择、模式选择、场景切换、auto-demo | 历史对话、反馈点赞、语音输入、分身锦囊、开场轮播 |
| **场景来源** | 父组件传入 `scenarioGrains` | 自己调 `fetchPracticeScenes` |

---

## 改版计划（P0 → P2）

### P0 — 统一 API 端点

- 审核页复用 `/skills/{id}/practice/evaluate-round`，删掉 `/admin/skills/{id}/practice-evaluate`
- 审核页复用 `/skills/{id}/practice/evaluate` (SSE)，删掉 `/admin/skills/{id}/practice-score`
- 审核页复用 `startPractice` 获取开场白，删掉 `/admin/skills/{id}/practice-opening`

### P1 — 抽共享 Hook

抽取 `usePractice` hook，封装：
- 状态：messages、currentAngle、retryCount、phase
- 方法：send、retry、advanceRound、endEvaluate
- 两边的 ProductDemoModal 和 Skill page 都改用这个 hook

### P2 — 统一角色命名 + 类型

- 统一用 Skill page 的命名：`user` / `customer` / `ai`
- 合并 `ChatMessage` 和 `PracticeMessage` 为一个共享类型

---

## 不改的

- ProductDemoModal 独有的 auto-demo / debug 模式
- Skill page 独有的历史对话 / 反馈 / 语音输入 / 开场轮播
- 两边的 UI 风格保持各自定位（审核紧凑 vs 用户精致）

---

## TODO: 消极回答检测 & 渐进引导

> 2026-07-11 方案暂定，待测试稳定后实现。

### 问题

学员连续摆烂（"不干了""随便啊""等着吧"），AI 客户仍认真回应推进对话，不真实。

### 判定逻辑（前端判定，不增加后端）

```typescript
function isNegativeResponse(msg: PracticeMessage): boolean {
  // 零技法覆盖 + 有"放弃"类关键词 → 摆烂
  if (msg.hits && msg.hits.length === 0
      && msg.misses?.some(m => m.includes('放弃'))) return true;
  // 回复极短（≤5字）且无技法 → 敷衍
  if (msg.content.length <= 5 && msg.hits?.length === 0) return true;
  return false;
}
```

### 渐进引导

同一轮内，统计连续消极回答次数：

| 连续消极次数 | 动作 |
|---|---|
| 0-1 次 | 正常评价卡 + retry 按钮（现状） |
| **2 次** | 隐藏 retry 按钮 → 教练提醒弹窗："你已经连续偏离了，试试先认错再提出拉通需求" + [🔄 我再试试] [📋 看完整答案] [➡️ 跳过] |
| **3 次** | 本轮结束，展示完整答案卡 + "➡️ 继续下一轮"，不再生成客户回应 |

### 范围

- 仅对练模式，QA 不管
- 仅统计同一轮，advanceRound 后重置
- offTopic 不参与判定，只读 hits/misses/content

### 实现

- 前端 `PracticeView.tsx` + `ProductDemoModal.tsx`：`isNegativeResponse()` + 计数器 + 三层 UI 分支
- 后端不改
