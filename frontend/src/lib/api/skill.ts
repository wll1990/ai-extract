/**
 * AI分身Skill API客户端
 *
 * @since 2026-06-29
 */

import { apiClient, API_BASE } from './client';
import { connectSse, type SseCallbacks } from '@/lib/sse';

/**
 * C 端分享页显式 Bearer 头（B 端页面不传 authToken = 现行为不变，走 HttpOnly Cookie）。
 * JwtAuthFilter 取凭证 Bearer 优先于 Cookie —— 员工浏览器同时存在后台 Cookie 时也不会串身份。
 */
function bearer(authToken?: string): Record<string, string> | undefined {
  return authToken ? { Authorization: `Bearer ${authToken}` } : undefined;
}

export interface PracticeScene { title: string; setting: string; customerLine: string; }
export interface PracticeStartData {
  practiceId: string;
  conversationId?: string;
  scene: PracticeScene;
  practiceAngles?: string[];
  totalAngles?: number;
}
export interface PracticeSceneData { label: string; title: string; setting: string; customerLine: string; grainCount?: number; }

/** Skill 互动统计 */
export interface SkillStats {
  conversationCount: number;
  userCount: number;
  satisfactionRate: number;
  lastActive?: string;
}

/** Skill 列表项 */
export interface SkillInfo {
  id: string; spaceId: string; ownerName: string; ownerTitle: string;
  status: string; modelName?: string; grainCount?: number;
  avatarUrl?: string;
  tags?: string[];
  openingMessage?: string;
  domain?: string;
  stats?: SkillStats;
}

/** 获取分身列表（分页，可选状态过滤 + userId 过滤"我的分身"） */
export function listSkills(page = 1, size = 50, status?: string, userId?: string): Promise<{ content: SkillInfo[]; total: number; totalPages: number }> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set('status', status);
  if (userId) params.set('userId', userId);
  return apiClient<{ content: SkillInfo[]; total: number; totalPages: number }>(`/skills/list?${params.toString()}`);
}

/** 更新分身状态 */
export function updateSkillStatus(skillId: string, status: string): Promise<void> {
  return apiClient<void>(`/skills/${skillId}/status`, { method: 'PUT', body: JSON.stringify({ status }) });
}

/** 推荐问题 */
export function fetchRecommendedQuestions(skillId: string, sceneTag?: string, authToken?: string): Promise<string[]> {
  const params = sceneTag ? `?sceneTag=${encodeURIComponent(sceneTag)}` : '';
  return apiClient<string[]>(`/skills/${skillId}/recommended-questions${params}`, { headers: bearer(authToken) });
}

/** 分身问答（SSE流式） */
export function chat(skillId: string, message: string, callbacks: SseCallbacks, conversationId?: string, channel?: string, mode?: string, history?: string, authToken?: string): AbortController {
  return connectSse({ url: `${API_BASE}/skills/${skillId}/chat`, method: 'POST', body: { message, conversationId, channel, mode, history }, headers: bearer(authToken) }, callbacks);
}

/** 会话历史 */
export interface ConversationItem { id: string; title: string; mode: string; updatedAt: string; }
export interface ConversationMessage { id: string; role: string; content: string; createdAt: string; grainId?: string; reportId?: string; reportTitle?: string; grainTags?: string; grainCount?: number; avgScore?: string; }

export function listConversations(skillId: string, authToken?: string): Promise<ConversationItem[]> {
  return apiClient<ConversationItem[]>(`/skills/${skillId}/conversations`, { headers: bearer(authToken) });
}
export function getConversationMessages(conversationId: string, authToken?: string): Promise<ConversationMessage[]> {
  return apiClient<ConversationMessage[]>(`/skills/conversations/${conversationId}/messages`, { headers: bearer(authToken) });
}
export function deleteConversation(conversationId: string, authToken?: string): Promise<void> {
  return apiClient<void>(`/skills/conversations/${conversationId}`, { method: 'DELETE', headers: bearer(authToken) });
}

/** 开始实战对练 */
export function startPractice(skillId: string, scene?: string, customScene?: string, authToken?: string): Promise<PracticeStartData> {
  return apiClient<PracticeStartData>(`/skills/${skillId}/practice/start`, { method: 'POST', body: JSON.stringify({ scene, customScene }), headers: bearer(authToken) });
}

/** 对练回应（SSE流式） */
export function respondPractice(skillId: string, practiceId: string, message: string, callbacks: SseCallbacks, sceneContext?: string, history?: string, conversationId?: string, sceneTag?: string, authToken?: string): AbortController {
  return connectSse({ url: `${API_BASE}/skills/${skillId}/practice/respond`, method: 'POST', body: { practiceId, message, sceneContext, history, conversationId, sceneTag }, headers: bearer(authToken) }, callbacks);
}

/** 对练每轮评价结果 */
export interface RoundEval {
  championAnswer: string;
  comparison: string;
  hits: string[];
  misses: string[];
  technique: string;
  offTopic?: boolean;
  grains?: Array<{ sceneTag?: string; qualityScore?: number; matchLevel?: string; fileName?: string }>;
  matchLevel?: string;
  fullAnswer?: string;
  isLastRetry?: boolean;
}

/** 对练每轮评价 — 销冠答案对比 + 技法 + 溯源 */
export function evaluatePracticeRound(skillId: string, body: {
  sceneTag: string; customerMessage: string; myResponse: string;
  previousChampionAnswer?: string; retryCount?: number;
}, authToken?: string): Promise<RoundEval> {
  return apiClient<RoundEval>(`/skills/${skillId}/practice/evaluate-round`, { method: 'POST', body: JSON.stringify(body), headers: bearer(authToken) });
}

/** 获取对练场景列表 */
export function fetchPracticeScenes(skillId: string, authToken?: string): Promise<PracticeSceneData[]> {
  return apiClient<PracticeSceneData[]>(`/skills/${skillId}/practice-scenes`, { headers: bearer(authToken) });
}

/** 对练综合评价（SSE流式） */
export function evaluatePractice(skillId: string, conversation: string, scene: string, callbacks: SseCallbacks, authToken?: string): AbortController {
  return connectSse({ url: `${API_BASE}/skills/${skillId}/practice/evaluate`, method: 'POST', body: { conversation, scene }, headers: bearer(authToken) }, callbacks);
}

// ========== 分身对外分享（与 admin.ts SkillShareInfo 同结构，走 B 端 Cookie） ==========

export interface SkillShareInfo {
  skillId: string;
  shareCode: string;
  channel: string;
  enabled: boolean;
  createdAt?: string;
}

export function getOrCreateSkillShare(skillId: string): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/skills/${skillId}/share`, { method: 'POST' });
}

export function getSkillShare(skillId: string): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/skills/${skillId}/share`);
}

export function toggleSkillShare(skillId: string, enabled: boolean): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/skills/${skillId}/share`, { method: 'PUT', body: JSON.stringify({ enabled }) });
}

/** 生成（或获取已有）对内分享链接 */
export function createInternalShare(skillId: string): Promise<SkillShareInfo> {
  return apiClient<SkillShareInfo>(`/i/${skillId}/share/internal`, { method: 'POST' });
}

/** 提交反馈 */
/** 提交用户反馈 —— 传递完整上下文供管理员审查 */
export function submitFeedback(params: {
  skillId: string; sessionId?: string; grainId?: string; helpful: boolean;
  messageId?: string; conversationId?: string; query?: string;
  aiResponse?: string; ragScore?: number;
}, authToken?: string): Promise<void> {
  const { skillId, sessionId, grainId, helpful, messageId, conversationId, query, aiResponse, ragScore } = params;
  return apiClient<void>(`/skills/${skillId}/feedback`, {
    method: 'POST',
    body: JSON.stringify({ sessionId, grainId, helpful, messageId, conversationId, query, aiResponse, ragScore }),
    headers: bearer(authToken),
  });
}
