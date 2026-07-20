# 变量: {grains_block}, {patterns_str}

请根据以下技能颗粒和模式发现，生成一份结构化的叙事。

## 技能颗粒（编号0～N-1）
{grains_block}

## 已发现的模式
{patterns_str}

## 输出纯JSON
{
  "storyline": {
    "title": "故事标题（≤20字）",
    "phases": [
      {"order":1,"name":"阶段名≤10字","summary":"≤80字","grainIndices":[0,3,5]}
    ]
  },
  "linkedStrategies": [
    {"habit":"核心习惯","principle":"策略原理≤60字","tacticName":"对应秘招","grainIndices":[1,7]}
  ],
  "linkedTactics": [
    {"name":"秘招名","howTo":"怎么做≤80字","grainIndices":[2,4]}
  ]
}

storyline 生成4-6个阶段（按{domain.counterparty_label}旅程排序）。
linkedStrategies 和 linkedTactics 中的 grainIndices 必须是上面颗粒的实际编号。