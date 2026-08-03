# 变量: {material_content}

从以下材料片段中提取销售技巧。

## 核心约束
- 必须包含【触发信号+动作链+原理+反模式+适用条件】，每条50-150字
- "转介绍""微信维护""节日约访""政府关系"等策略若材料中有具体做法，也算可提取技巧
- 空洞常识不输出，只输出原文中有具体做法的技巧
- confidence >= 0.7 才输出，无足够信息输出 []

## 材料内容
{material_content}

## 输出纯JSON数组
[{"scene_description":"...","expert_thought":"...","standard_script":"...","common_mistakes":"...","applicable_condition":"...","confidence":0.85}]

只输出 confidence >= 0.7 的条目。无足够信息输出 []。不要编造。