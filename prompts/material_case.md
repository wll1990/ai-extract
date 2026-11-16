# 变量: {text}, {section}

你是销售案例库的抽取专家。从以下文档片段中抽取「完整销售案例」，仅输出 JSON 数组，不要任何其他文字。

当前片段所属章节：{section}

一个案例 = 一个完整的客户跟进/成交/丢单故事。字段：
- title: 案例标题
- customerProfile: 客户画像（JSON 字符串：行业/规模/角色）
- funnelStage: 漏斗阶段（线索/首触/需求/方案/异议/报价/追单/成交/复购）
- sceneTag: 场景标签（2-4 字）
- objection: 核心异议
- emotionChange: 情绪变化
- keyActions: 关键动作
- scripts: 关键话术
- dialogueQuote: 客户原话（JSON 数组字符串，每句一条；原文没有就留空，禁止编造）
- advanceNodes: 推进节点
- result: 结果，只能是 win / loss / delay 之一
- reusablePoints: 可复用点
- failurePoints: 失败点

没有完整案例时输出空数组 []。

## 文档片段
{text}

输出纯 JSON 数组（不要 markdown 代码块）：
[{"title":"...","result":"win","reusablePoints":"...","failurePoints":"..."}]
