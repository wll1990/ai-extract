/**
 * 萃取师经验库 API客户端
 *
 * @since 2026-06-29
 */

import { apiClient } from './client';

export interface ExpertAvailable { id: string; name: string; type: string; styleTags?: string[]; industryTags?: string[]; }
export interface ExpertSkill { id: string; name: string; sourceType: string; domain?: string; styleTags: string[]; industryTags: string[]; seniority: string; grainCount: number; documentCount?: number; status: string; }
export interface ExpertDetail { id: string; name: string; grainGroups: GrainGroup[]; documents: DocumentInfo[]; status: string; }
export interface GrainGroup { category: string; grains: GrainInfo[]; }
export interface GrainInfo { id: string; category: string; knowledgeContent: string; applicationRule: string; priority: number; consensusType: string; status: string; }
export interface DocumentInfo { id: string; fileName: string; fileType: string; fileSize: number; status: string; }
export interface CompositeInfo { version: string; expertCount: number; consensusCount: number; singleCount: number; conflictCount: number; }

export const getAvailableExperts = () => apiClient<ExpertAvailable[]>('/experts/available');

export const getExperts = (page = 1, size = 20, keyword?: string, status?: string) => {
  const p = new URLSearchParams();
  p.set('page', String(page)); p.set('size', String(size));
  if (keyword) p.set('keyword', keyword);
  if (status) p.set('status', status);
  return apiClient<{ content: ExpertSkill[]; total: number; totalPages: number }>(`/admin/experts?${p}`);
};

export const getExpertDetail = (id: string) => apiClient<ExpertDetail>(`/admin/experts/${id}`);

export const uploadExpertMaterials = (body: Record<string, unknown>) =>
  apiClient<ExpertDetail>('/admin/experts/upload', { method: 'POST', body: JSON.stringify(body) });

export const extractGrains = (id: string) => apiClient<void>(`/admin/experts/${id}/extract`, { method: 'POST' });

export const deleteExpert = (id: string) =>
  apiClient<void>(`/admin/experts/${id}`, { method: 'DELETE' });

export const deleteGrain = (expertId: string, grainId: string) =>
  apiClient<void>(`/admin/experts/${expertId}/grains/${grainId}`, { method: 'DELETE' });

export const activateExpert = (id: string) => apiClient<void>(`/admin/experts/${id}/activate`, { method: 'POST' });

export const retryExpert = (id: string) => apiClient<void>(`/admin/experts/${id}/retry`, { method: 'POST' });

export const regenerateComposite = () => apiClient<void>('/admin/experts/composite/regenerate', { method: 'POST' });

export const getCompositeDetail = () => apiClient<CompositeInfo>('/admin/experts/composite');

/** 上传文档文件字节（multipart），返回创建的 ExpertDocument */
export const uploadDocumentFile = async (expertId: string, file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  const token = localStorage.getItem('token');
  const res = await fetch(`/api/v1/admin/experts/${expertId}/documents/file`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: formData,
  });
  if (!res.ok) throw new Error(`上传失败: ${res.status}`);
  return res.json();
};
