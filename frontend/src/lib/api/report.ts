/**
 * 报告API客户端
 *
 * @since 2026-06-29
 */

import { apiClient, authHeaders, API_BASE } from './client';
import { getToken } from '@/lib/storage';

/** 报告列表项 */
export interface ReportListItem {
  id: string;
  spaceId: string;
  title: string;
  subtitle: string;
  rating: number;
  viewCount: number;
  fileStatus: string;
  authorName?: string;
  sceneTags?: string[];
  createdAt: string;
}

/** 报告详情 */
export interface ReportDetail {
  id: string;
  spaceId: string;
  title: string;
  subtitle: string;
  contentJson: Record<string, unknown>;
  wordUrl: string | null;
  pptUrl: string | null;
  webPublished: boolean;
  fileStatus: string;
  rating: number;
  viewCount: number;
  authorName: string;
  authorAvatar: string;
  skillId?: string;
  skillStatus?: string;
  createdAt: string;
  updatedAt: string;
}

/** 分页响应 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface UpdateReportRequest {
  chapters: ChapterUpdate[];
  regenerate: boolean;
}

export interface ChapterUpdate {
  order: number;
  content: Record<string, unknown>;
}

/** 获取报告列表 */
export async function getReports(
  spaceId?: string,
  keyword?: string,
  page = 1,
  size = 20,
): Promise<PageResponse<ReportListItem>> {
  const params = new URLSearchParams();
  if (spaceId) params.set('spaceId', spaceId);
  if (keyword) params.set('keyword', keyword);
  params.set('page', String(page));
  params.set('size', String(size));
  return apiClient<PageResponse<ReportListItem>>(`/reports?${params.toString()}`);
}

/** 获取报告详情 */
export async function getReport(reportId: string): Promise<ReportDetail> {
  return apiClient<ReportDetail>(`/reports/${reportId}`);
}

/** 编辑报告 */
export async function updateReport(
  reportId: string,
  chapters: ChapterUpdate[],
  regenerate: boolean,
): Promise<ReportDetail> {
  return apiClient<ReportDetail>(`/reports/${reportId}`, {
    method: 'PUT',
    body: JSON.stringify({ chapters, regenerate }),
  });
}

/** 构建下载 URL */
export function getDownloadUrl(reportId: string, format: 'word' | 'ppt'): string {
  return `${API_BASE}/reports/${reportId}/download?format=${format}`;
}

/** 下载报告文件 */
export async function downloadReport(reportId: string, format: 'word' | 'ppt'): Promise<void> {
  const url = getDownloadUrl(reportId, format);
  const res = await fetch(url, { headers: authHeaders() });
  if (!res.ok) throw new Error('下载失败');
  const blob = await res.blob();
  const downloadUrl = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = downloadUrl;
  a.download = `report.${format === 'ppt' ? 'pptx' : 'docx'}`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(downloadUrl);
}

/** 提交评分 */
export function rateReport(reportId: string, rating: number): Promise<void> {
  return apiClient<void>(`/reports/${reportId}/rate`, { method: 'POST', body: JSON.stringify({ rating }) });
}

/** 同步检查清单 */
export function syncChecklist(reportId: string, items: Record<string, boolean>): Promise<void> {
  return apiClient<void>(`/reports/${reportId}/checklist`, { method: 'POST', body: JSON.stringify(items) });
}
