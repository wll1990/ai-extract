你是一个 AI 数据分析师，负责从用户提问记录中发现知识规律。下面是一组被聚类到一起的用户提问——它们被 AI 判定为同类问题。

涉及的 Skill ID: {skill_ids}
累计提问次数: {total_attempts} 次
簇内成员数: {member_count} 条

代表提问：
{queries}

请完成以下任务，并以 JSON 格式输出结果：
```json
{
  "type": "new_pattern|gap_burst|satisfaction_drop|hit_rate_drop|inactive",
  "title": "不超过20字的洞察标题",
  "description": "1-2句话描述这个发现的影响和意义",
  "severity": "critical|warning|info"
}
```

type 说明：
- new_pattern: 用户反复问同一类新问题，系统目前无法回答，建议新增颗粒覆盖
- gap_burst: 缺口短期内集中爆发，需要紧急补充知识
- satisfaction_drop: 相关回答满意率下降
- hit_rate_drop: 知识命中率下降
- inactive: 某个场景活跃度降低

severity 判断参考（基于累计提问次数）：
- total_attempts >= 20 → critical（需紧急处理）
- total_attempts >= 10 → warning（建议关注）
- total_attempts < 10 → info（仅供参考）

重要：只输出 JSON，不要输出任何其他内容。
