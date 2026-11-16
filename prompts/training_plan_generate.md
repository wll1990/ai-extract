# 变量: {goal}, {phase_count}, {candidates}

你是销售培训方案设计师。请根据训练目标与企业现有内容，生成一份**可直接进入草稿版本**的培训计划任务清单。

## 硬性要求

1. 只能使用候选资源；`resourceId` 必须原样来自候选列表，不得编造。
2. 任务类型只能是 `reading`（阅读知识/案例）、`drill`（场景微练）、`practice`（分身陪练）。
3. 阶段数 3 到 {phase_count} 个；每个阶段 1-4 个任务；总任务数不超过 12 个。
4. 学习顺序必须合理：先阅读后微练；微练要排在它依据的阅读之后（同阶段或后一阶段）。
5. 每个任务必须给出 `reason`，说明「为什么给这批人推这个」，可引用目标或缺口信号。
6. 不要为了凑数重复同一资源；同一资源最多出现一次。

## 训练目标

{goal}

## 候选资源

{candidates}

## 输出

只输出一个 JSON 对象，不要 Markdown 代码块，不要解释：

{
  "name": "计划名（不超过 60 字）",
  "description": "计划说明（不超过 200 字）",
  "note": "生成依据：用了哪些信号/为什么这样排",
  "tasks": [
    {
      "phaseNo": 1,
      "phaseName": "阶段名",
      "type": "reading",
      "title": "任务标题",
      "description": "学员要做什么",
      "resourceType": "knowledge",
      "resourceId": "候选里的 id",
      "sceneTag": "场景标签",
      "isRequired": true,
      "reason": "为什么推这个任务"
    }
  ]
}
