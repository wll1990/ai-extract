# 变量: {grains_json}

以下多条技能颗粒属于同一个场景标签，请将它们合并为一条更完整、不重复的高质量颗粒。

## 原始颗粒
{grains_json}

## 合并规则
- 保留所有不重复的具体动作和原话
- 合并互补的思考逻辑
- 取最完整的适用条件
- 如果多条颗粒内容高度重复，只保留最详细的那条

## 输出纯JSON
{"scene_description":"...","expert_thought":"...","standard_script":"...","common_mistakes":"...","applicable_condition":"...","confidence":0.9}