# 变量: {source_type}, {title}, {material}, {derive_focus}

你是内容运营。请把一条{source_type}派生为可直接使用的培训/评测素材。

## 本次派生范围

{derive_focus}

## 硬性要求

1. 只能基于材料；不要引入材料里没有的产品参数、价格、政策。
2. 按「本次派生范围」决定是否生成 faqs；不需要时返回空数组，不要为了凑数重复材料原文。
3. 生成 1-3 道评测题，每题带 2-5 条 expectedPoints、scoringRule、goldBasis。
4. 生成 0-1 个场景微练卡：scenario / clientRole / questions（含 expectedPoints）。
5. 输出使用简体中文；不出现真实姓名与公司全称。

## 材料

- 类型：{source_type}
- 标题：{title}
- 正文/字段：
{material}

## 输出

只输出 JSON 对象：

{
  "faqs": [{"question": "...", "answer": "..."}],
  "examItems": [
    {"question": "...", "context": "...", "expectedPoints": ["..."],
     "forbiddenPoints": [], "scoringRule": "...", "goldBasis": "..."}
  ],
  "drill": {
    "scenario": "...", "clientRole": "...",
    "questions": [{"question": "...", "context": "", "expectedPoints": ["..."], "scoringRule": "..."}]
  }
}
