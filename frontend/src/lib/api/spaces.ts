/**
 * 空间 API 客户端
 * @since 2026-07-01
 */

import { apiClient } from './client';

export interface SpaceInfo {
  id: string; ownerName: string; title: string; description: string;
  tags: string[]; isPublic: boolean; reportCount: number; grainCount?: number;
  skillStatus: string; createdAt: string;
}

export interface GrainDistribution {
  tag: string; count: number;
}

export interface SpaceDetail extends SpaceInfo {
  ownerTitle: string; ownerTags: string[]; oneliner: string;
  reports: Array<{ id: string; title: string; subtitle: string; rating: number; viewCount: number; stepCount: number; sceneTags: string[]; createdAt: string }>;
  skillId: string | null; skillGrainCount: number;
  grainDistribution: GrainDistribution[];
  interviewCount: number; materialCount: number;
  status: string;
  stats: { reportCount: number; viewCount: number; grainCount: number; interviewCount: number; materialCount: number };
}

export interface SpaceListData {
  content: SpaceInfo[]; page: number; size: number; total: number; totalPages: number;
}

/** 获取空间列表 */
export function getSpaces(keyword?: string, tag?: string, page = 1, size = 20, userId?: string): Promise<SpaceListData> {
  const p = new URLSearchParams();
  if (keyword) p.set('keyword', keyword);
  if (tag) p.set('tag', tag);
  if (userId) p.set('userId', userId);
  p.set('page', String(page));
  p.set('size', String(size));
  return apiClient<SpaceListData>(`/spaces?${p}`);
}

/** 获取空间详情 */
export function getSpace(spaceId: string): Promise<SpaceDetail> {
  return apiClient<SpaceDetail>(`/spaces/${spaceId}`);
}
