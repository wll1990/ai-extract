/**
 * 资料库 API 客户端
 * @since 2026-07-01
 */

import { apiClient, API_BASE, authHeaders } from './client';

export interface ToolInfo {
  id: string; name: string; type: string; description: string;
  fileUrl: string | null; spaceId: string; reportId: string; createdAt: string;
}

/** 获取资料列表 */
export function getTools(spaceId?: string, type?: string): Promise<ToolInfo[]> {
  const p = new URLSearchParams();
  if (spaceId) p.set('spaceId', spaceId);
  if (type) p.set('type', type);
  return apiClient<ToolInfo[]>(`/tools?${p}`);
}

