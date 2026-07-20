# 变量: {text}

你是一位B2B销售场景分析专家。请分析以下销售对话片段，仅输出JSON标注信息，不做任何价值判断或建议。

标注维度：
1. buying_stage: 客户处于哪个购买阶段？
   - "awareness"（问题认知）、"consideration"（方案评估）、"decision"（决策签约）、
     "implementation"（实施落地）、"renewal"（续约/增购）
2. buyer_persona: 对话对象角色
   - "economic_buyer"（决策者/老板）、"technical_evaluator"（技术/IT）、
     "champion"（内部支持者）、"user"（使用者）、"procurement"（采购）
3. competitive_context: 竞争态势
   - "greenfield"（无竞品）、"competitive"（有竞品）、"replacement"（替换现有供应商）、
     "internal_build"（客户自研）、"unknown"
4. deal_size_hint: 从对话中推测的单子规模
   - "smb"（<10万）、"mid_market"（10-100万）、"enterprise"（>100万）、"unknown"
5. industry_signals: 对话中透露的行业信号（如"制造业""金融""连锁零售"等，数组）

## 材料内容
{text}

输出纯JSON，不要markdown代码块：
{"buying_stage":"consideration","buyer_persona":"economic_buyer","competitive_context":"competitive","deal_size_hint":"mid_market","industry_signals":["制造业"]}