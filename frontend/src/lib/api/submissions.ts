import { apiClient } from './client';
import type { PageResult } from './training';

export interface MySubmission {
  id: string;
  title: string;
  category?: string | null;
  sceneTag?: string | null;
  result?: string | null;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export function listMyKnowledgeSubmissions(page = 1, size = 20) {
  return apiClient<PageResult<MySubmission>>(
    `/me/knowledge/submissions?page=${page}&size=${size}`,
  );
}

export function submitKnowledge(body: {
  title: string;
  category: string;
  content: string;
  source?: string;
}) {
  return apiClient<MySubmission>('/me/knowledge/submissions', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function withdrawKnowledge(id: string) {
  return apiClient<MySubmission>(`/me/knowledge/submissions/${id}/withdraw`, {
    method: 'POST',
  });
}

export function listMyCaseSubmissions(page = 1, size = 20) {
  return apiClient<PageResult<MySubmission>>(
    `/me/cases/submissions?page=${page}&size=${size}`,
  );
}

export function submitCase(body: {
  title: string;
  customerProfile?: string;
  funnelStage?: string;
  sceneTag?: string;
  objection?: string;
  keyActions?: string;
  scripts?: string;
  reusablePoints?: string;
  failurePoints?: string;
  result?: string;
  source?: string;
}) {
  return apiClient<MySubmission>('/me/cases/submissions', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function withdrawCase(id: string) {
  return apiClient<MySubmission>(`/me/cases/submissions/${id}/withdraw`, {
    method: 'POST',
  });
}

export const SUBMISSION_STATUS: Record<string, string> = {
  draft: '已撤回/草稿',
  pending: '待审核',
  active: '已生效',
  deprecated: '已下架',
};
