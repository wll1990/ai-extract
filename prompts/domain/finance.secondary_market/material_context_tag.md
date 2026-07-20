# 变量: {text}

你是一位二级市场投资研究场景分析专家。请分析以下投资研究片段，仅输出JSON标注信息，不做任何价值判断或建议。

标注维度：
1. asset_class: 涉及什么资产类别？
   - "equity"（权益）、"fixed_income"（固收）、"commodity"（商品）、"macro"（宏观）、"unknown"
2. research_type: 研究输出类型
   - "industry"（行业研究）、"company"（公司深度）、"strategy"（策略报告）、
     "flash_note"（快评/事件点评）、"unknown"
3. research_phase: 研究处于哪个阶段？
   - "signal_discovery"（信号发现—注意到异常/变化/机会）
   - "deep_analysis"（深度分析—建立分析框架、拆解逻辑）
   - "conviction_building"（确认判断—交叉验证、压力测试）
   - "position_execution"（持仓执行—实际下单/推荐/汇报）
   - "post_mortem"（复盘反思—回顾判断对错、总结经验）
4. conviction_level: 行文中透露的判断置信度
   - "high_conviction"（高确信—多重信号共振、逻辑闭环）
   - "moderate"（中等—核心逻辑清晰但缺关键验证）
   - "tentative"（试探性—初步观察、待跟踪验证）
   - "unknown"
5. information_source: 主要信息来源
   - "primary"（一手—实地调研、专家访谈、渠道验证、独家数据）
   - "secondary"（二手—财报、研报、公开数据、新闻）
   - "market"（市场信号—量价行为、资金流向、衍生品异动）
   - "expert_network"（专家网络/行业人脉）
   - "unknown"

## 材料内容
{text}

输出纯JSON，不要markdown代码块：
{"asset_class":"equity","research_type":"company","research_phase":"conviction_building","conviction_level":"high_conviction","information_source":"primary"}
