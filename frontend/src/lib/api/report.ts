/**
 * 报告API客户端
 *
 * @since 2026-06-29
 */

import { apiClient } from './client';

/** 报告列表项 */
export interface ReportListItem {
  id: string;
  spaceId: string;
  title: string;
  subtitle: string;
  rating: number;
  viewCount: number;
  authorName?: string;
  sceneTags?: string[];
  shareCode?: string;
  hasHtml: boolean;
  createdAt: string;
}

/** 报告详情 */
export interface ReportDetail {
  id: string;
  spaceId: string;
  title: string;
  subtitle: string;
  contentJson: Record<string, unknown>;
  shareCode?: string;
  hasHtml: boolean;
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

/** 获取报告列表 */
export async function getReports(
  spaceId?: string,
  keyword?: string,
  page = 1,
  size = 12,
  tag?: string,
  sort = 'createdAt',
): Promise<PageResponse<ReportListItem>> {
  const params = new URLSearchParams();
  if (spaceId) params.set('spaceId', spaceId);
  if (keyword) params.set('keyword', keyword);
  if (tag) params.set('tag', tag);
  params.set('sort', sort);
  params.set('page', String(page));
  params.set('size', String(size));
  return apiClient<PageResponse<ReportListItem>>(`/reports?${params.toString()}`);
}

/** 获取报告详情 */
export async function getReport(reportId: string): Promise<ReportDetail> {
  return apiClient<ReportDetail>(`/reports/${reportId}`);
}

/** 生成分享链接 */
export async function shareReport(reportId: string): Promise<{ shareCode: string; shareUrl: string }> {
  return apiClient<{ shareCode: string; shareUrl: string }>(`/reports/${reportId}/share`, {
    method: 'POST',
  });
}

/** 管理员重新生成报告 */
export async function regenerateReport(reportId: string): Promise<{ success: boolean; grainCount: number }> {
  return apiClient<{ success: boolean; grainCount: number }>(`/admin/reports/${reportId}/regenerate`, {
    method: 'POST',
  });
}

/** 提交评分 */
export function rateReport(reportId: string, rating: number): Promise<void> {
  return apiClient<void>(`/reports/${reportId}/rate`, { method: 'POST', body: JSON.stringify({ rating }) });
}
