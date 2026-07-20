<!--
  @deprecated 2026-07 — 未接入。
  对练回应专业度四维评分能力预留（逻辑清晰度/说服力/专业表达/客户导向）。
  当前对练链路未加载此提示词，如需在对练中加入实时质量评估，可启用此文件。
-->
# 变量: {scene_title}, {stage}, {intent}, {response}

评估{domain.role_label}回应专业度。
场景: {scene_title}（阶段: {stage}）意图: {intent} 回应: "{response}"
评分维度: 逻辑清晰度(30%)、说服力(30%)、专业表达(20%)、{domain.counterparty_label}导向(20%)
返回JSON: {"score":<0-100>}