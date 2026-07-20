# 变量: {text}

你是一位{domain.name}场景分析专家。请分析以下{domain.name}对话片段，仅输出JSON标注信息，不做任何价值判断或建议。

标注维度：
1. stage: 当前处于哪个阶段？（从领域配置中获取枚举值）
2. persona: 对话对象角色（从领域配置中获取枚举值）
3. context: 环境态势（从领域配置中获取枚举值）
4. scale_hint: 从对话中推测的规模
   - 输出枚举值或 "unknown"
5. signals: 对话中透露的行业/领域信号（数组）

## 材料内容
{text}

输出纯JSON，不要markdown代码块：
{"stage":"...","persona":"...","context":"...","scale_hint":"...","signals":[]}
