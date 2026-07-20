<!--
  @deprecated 2026-07 — 未接入。
  对练用户意图分类能力预留（破冰/需求探询/方案呈现/异议处理/逼单促成/回避转移）。
  DomainConfig.practiceIntents 已配置意图列表，但未被 PracticeDemoService 加载。
  如需在对练中加入意图识别，可启用此文件。
-->
# 变量: {recent_history}, {message}

分析销售对练中用户的真实意图。
对话历史（最近5轮）: {recent_history}
用户最新消息: "{message}"
分类: 破冰寒暄|需求探询|方案呈现|异议处理|逼单促成|回避转移
只返回分类名称。