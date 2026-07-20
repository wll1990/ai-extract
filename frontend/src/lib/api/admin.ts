/**
 * 管理后台 API 客户端
 * @since 2026-07-01
 */

import { apiClient } from './client';

export interface SceneInfo {
  name: string; reportCount: number; avgRating: number;
  coverage: 'sufficient' | 'normal' | 'empty';
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

/** 发起萃取邀请 */
export function createInvite(sceneTag: string, userId: string): Promise<InviteResult> {
  return apiClient<InviteResult>('/admin/invite', {
    method: 'POST', body: JSON.stringify({ sceneTag, userId }),
  });
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

