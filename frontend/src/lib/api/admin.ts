/**
 * 管理后台 API 客户端
 * @since 2026-07-01
 */

import { apiClient } from './client';

export interface SceneInfo {
  name: string; reportCount: number; avgRating: number;
  coverage: 'sufficient' | 'moderate' | 'empty';
  suggestedOwner?: { name: string; reason: string };
}

export interface SceneCoverageData {
  scenes: SceneInfo[];
}

export interface InviteResult {
  inviteCode: string; inviteUrl: string;
}

/** 场景覆盖地图 */
export function getSceneCoverage(): Promise<SceneCoverageData> {
  return apiClient<SceneCoverageData>('/admin/scene-coverage');
}

/** 生成访谈邀请码 */
export function createInvite(): Promise<InviteResult> {
  return apiClient<InviteResult>('/admin/invite', { method: 'POST', body: JSON.stringify({}) });
}

// ========== 分身对外分享 ==========

export interface SkillShareInfo {
  skillId: string;
  shareCode: string;
  channel: string;
  enabled: boolean;
  createdAt?: string;
}

/** 生成（或获取已有）分享链接 */
export function adminGetOrCreateShare(skillId: string): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/admin/skills/${skillId}/share`, { method: 'POST' });
}

/** 共享开关 */
export function adminToggleShare(skillId: string, enabled: boolean): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/admin/skills/${skillId}/share`, {
    method: 'PUT', body: JSON.stringify({ enabled }),
  });
}

/** 自定义短码 */
export function adminUpdateShareCode(skillId: string, shareCode: string): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/admin/skills/${skillId}/share/code`, {
    method: 'PUT', body: JSON.stringify({ shareCode }),
  });
}

// ========== 分身对内分享 ==========

/** 生成（或获取已有）对内分享链接 */
export function adminCreateInternalShare(skillId: string): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/i/${skillId}/share/internal`, { method: 'POST' });
}

// ========== Token 用量统计 ==========

export interface TokenSummary {
  today: { inputTokens: number; outputTokens: number; count: number };
  month: { inputTokens: number; outputTokens: number; count: number };
  total: { inputTokens: number; outputTokens: number };
}

export interface DailyTokenRow {
  date: string;
  inputTokens: number;
  outputTokens: number;
  count: number;
}

export interface TokenLogItem {
  id: string;
  userId: string | null;
  userName: string;
  usageDate: string;
  modelType: string;
  modelName: string;
  inputTokens: number;
  outputTokens: number;
  promptChars: number;
  completionChars: number;
  createdAt: string;
}

/** Token 用量汇总 */
export function getTokenSummary(): Promise<TokenSummary> {
  return apiClient<TokenSummary>('/admin/token-usage/summary');
}

/** 按天趋势 */
export function getTokenDaily(days: number = 30): Promise<DailyTokenRow[]> {
  return apiClient<DailyTokenRow[]>(`/admin/token-usage/daily?days=${days}`);
}

/** 分页明细 */
export function getTokenLogs(page: number = 0, size: number = 20): Promise<{ items: TokenLogItem[]; page: number; size: number; total: number }> {
  return apiClient(`/admin/token-usage/logs?page=${page}&size=${size}`);
}
