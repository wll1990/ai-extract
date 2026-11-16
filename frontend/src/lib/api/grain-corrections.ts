/**
 * 颗粒纠错 API：员工提交建议（/skills/**），管理员审核（/admin/grain-corrections/**）。
 */

import { apiClient } from './client';

export interface CorrectionSuggestion {
  id: string;
  grainId: string;
  skillId: string;
  suggestedBy: string;
  suggestedByName?: string | null;
  skillName?: string | null;
  grainSceneTag?: string | null;
  grainSceneDescription?: string | null;
  reason: string;
  sceneDescription: string | null;
  expertThought: string | null;
  standardScript: string | null;
  commonMistakes: string | null;
  status: 'pending' | 'approved' | 'rejected';
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  createdAt: string;
}

export interface CorrectionPage {
  content: CorrectionSuggestion[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface CorrectionPayload {
  reason: string;
  sceneDescription?: string;
  expertThought?: string;
  standardScript?: string;
  commonMistakes?: string;
}

/** 员工提交纠错建议 */
export function submitGrainCorrection(
  skillId: string,
  grainId: string,
  payload: CorrectionPayload,
): Promise<{ id: string; status: string }> {
  return apiClient(`/skills/${skillId}/grains/${grainId}/corrections`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function listGrainCorrections(params: { status?: string; page?: number; size?: number } = {}) {
  const q = new URLSearchParams();
  if (params.status) q.set('status', params.status);
  q.set('page', String(params.page ?? 1));
  q.set('size', String(params.size ?? 20));
  return apiClient<CorrectionPage>(`/admin/grain-corrections?${q.toString()}`);
}

export function approveGrainCorrection(id: string, note?: string) {
  return apiClient<CorrectionSuggestion>(`/admin/grain-corrections/${id}/approve`, {
    method: 'POST', body: JSON.stringify(note ? { note } : {}),
  });
}

export function rejectGrainCorrection(id: string, note?: string) {
  return apiClient<CorrectionSuggestion>(`/admin/grain-corrections/${id}/reject`, {
    method: 'POST', body: JSON.stringify(note ? { note } : {}),
  });
}
