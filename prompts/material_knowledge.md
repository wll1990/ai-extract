# 变量: {text}, {section}

你是企业知识库的抽取专家。从以下文档片段中抽取「事实型知识条目」，仅输出 JSON 数组，不要任何其他文字。

当前片段所属章节：{section}

知识条目 = 客观事实（产品参数、价格、政策、合规要求、术语定义、方法论步骤、竞品信息、行业常识等），不含主观评价、销售话术和案例故事。

每条目字段：
- title: 简短标题（≤ 50 字）
- category: 内容分类，从以下枚举选一个：compliance / price / product / policy / competitor / terminology / methodology / industry_intro
- content: 事实正文（完整、客观、可脱离原文独立理解）

## 文档片段
{text}

输出纯 JSON 数组（不要 markdown 代码块）：
[{"title":"...","category":"...","content":"..."}]
