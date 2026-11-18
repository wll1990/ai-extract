import { apiClient } from './client';

export type AgentSkillEffectiveStatus = 'draft' | 'published' | 'delisted';

export interface AgentSkillListItem {
  skillKey: string;
  name: string;
  source: string;
  effectiveStatus: AgentSkillEffectiveStatus;
  liveVersion: number | null;
  liveId: string | null;
  draftVersion: number | null;
  draftId: string | null;
  updatedAt: string;
  companyId: string | null;
  readOnly: boolean;
}

export interface ResourceMeta {
  id: string;
  path: string;
  size: number;
}

export interface AgentSkillDetail {
  id: string;
  skillKey: string;
  name: string;
  description: string;
  bodyMd: string;
  source: string;
  companyId: string | null;
  version: number;
  status: string;
  lockVersion: number;
  readOnly: boolean;
  resources: ResourceMeta[];
  updatedAt: string;
}

export interface ResourceInput {
  path: string;
  content: string;
}

export interface VersionItem {
  id: string;
  version: number;
  status: string;
  publishedAt: string | null;
  archivedAt: string | null;
  updatedAt: string;
}

export interface PreviewResponse {
  catalogBlock: string;
  bodyPreview: string;
  chars: number;
  tokenEstimate: number;
}

export interface AgentSkillSetting {
  enabled: boolean;
}

function qs(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '') search.set(k, v);
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function listAgentSkills(params: {
  scope: 'company' | 'platform';
  keyword?: string;
  source?: string;
}): Promise<AgentSkillListItem[]> {
  return apiClient<AgentSkillListItem[]>(`/admin/agent-skills${qs(params)}`);
}

export function getAgentSkill(id: string): Promise<AgentSkillDetail> {
  return apiClient<AgentSkillDetail>(`/admin/agent-skills/${id}`);
}

export function getAgentSkillResource(id: string, resourceId: string): Promise<{ id: string; path: string; content: string }> {
  return apiClient(`/admin/agent-skills/${id}/resources/${resourceId}`);
}

export function createAgentSkill(payload: {
  scope: 'company' | 'platform';
  skillKey: string;
  name: string;
  description: string;
  bodyMd: string;
  resources: ResourceInput[];
}): Promise<AgentSkillDetail> {
  return apiClient<AgentSkillDetail>('/admin/agent-skills', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function updateAgentSkill(id: string, payload: {
  name: string;
  description: string;
  bodyMd: string;
  resources: ResourceInput[];
  lockVersion: number;
}): Promise<AgentSkillDetail> {
  return apiClient<AgentSkillDetail>(`/admin/agent-skills/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export function publishAgentSkill(id: string): Promise<AgentSkillDetail> {
  return apiClient<AgentSkillDetail>(`/admin/agent-skills/${id}/publish`, { method: 'POST' });
}

export function archiveAgentSkill(id: string): Promise<void> {
  return apiClient<void>(`/admin/agent-skills/${id}/archive`, { method: 'POST' });
}

export function newAgentSkillVersion(id: string): Promise<AgentSkillDetail> {
  return apiClient<AgentSkillDetail>(`/admin/agent-skills/${id}/new-version`, { method: 'POST' });
}

export function deleteAgentSkillDraft(id: string): Promise<void> {
  return apiClient<void>(`/admin/agent-skills/${id}`, { method: 'DELETE' });
}

export function listAgentSkillVersions(id: string): Promise<VersionItem[]> {
  return apiClient<VersionItem[]>(`/admin/agent-skills/${id}/versions`);
}

export function previewAgentSkill(payload: {
  scope: 'company' | 'platform';
  skillKey: string;
  name: string;
  description: string;
  bodyMd: string;
  resources: ResourceInput[];
}): Promise<PreviewResponse> {
  return apiClient<PreviewResponse>('/admin/agent-skills/preview', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function getAgentSkillSetting(): Promise<AgentSkillSetting> {
  return apiClient<AgentSkillSetting>('/admin/agent-skills/settings');
}

export function updateAgentSkillSetting(enabled: boolean): Promise<void> {
  return apiClient<void>('/admin/agent-skills/settings', {
    method: 'PUT',
    body: JSON.stringify({ enabled }),
  });
}
