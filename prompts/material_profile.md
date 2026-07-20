# 变量: {grains_block}, {habits}, {diffs}, {industry}, {persona}

根据以下{domain.role_label}的技能颗粒和模式分析，生成AI分身的画像配置。

## 技能颗粒
{grains_block}

## 模式发现
核心习惯: {habits}
差异化: {diffs}

## 情境
行业: {industry} | 角色: {persona}

## 输出纯JSON
{
  "personality": "性格特征（≤80字）",
  "speakingStyle": "说话风格（≤80字）",
  "background": "从业背景概括（≤100字）",
  "commonPhrases": "口头禅/常用语（≤60字）",
  "knowledgeDomains": ["领域1","领域2"],
  "communicationPreferences": ["偏好1","偏好2"]
}