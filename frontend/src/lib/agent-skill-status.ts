/** 模型技能状态/来源的唯一展示来源，避免各页面 drift。 */

export const AGENT_SKILL_STATUS_LABELS: Record<string, string> = {
  draft: '草稿',
  published: '已发布',
  delisted: '已下架',
  archived: '已归档',
};

export const AGENT_SKILL_STATUS_CLASS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-600',
  published: 'bg-success-bg text-success',
  delisted: 'bg-gray-100 text-gray-500',
  archived: 'bg-gray-100 text-gray-500',
};

export const AGENT_SKILL_SOURCE_LABELS: Record<string, string> = {
  builtin: '内置',
  platform: '平台',
  tenant: '企业',
  mcp: 'MCP',
};

export function agentSkillStatusLabel(status: string): string {
  return AGENT_SKILL_STATUS_LABELS[status] ?? status;
}

export function agentSkillStatusClass(status: string): string {
  return AGENT_SKILL_STATUS_CLASS[status] ?? 'bg-gray-100 text-gray-600';
}

export function agentSkillSourceLabel(source: string): string {
  return AGENT_SKILL_SOURCE_LABELS[source] ?? source;
}
