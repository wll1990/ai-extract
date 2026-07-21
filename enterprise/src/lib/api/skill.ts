/**
 * 分身 API 客户端 — @synced-from frontend/src/lib/api/skill.ts
 *
 * 差异：去除 authToken 参数（企业端无 C 端 Bearer 头场景）。
 * 企业端统一走 HttpOnly Cookie 认证。
 *
 * @since 2026-07-20
 */

import { apiClient, API_BASE } from './client';
import { connectSse, type SseCallbacks } from '@/lib/sse';

/** 公开数据：落地页统计 */
export function fetchPublicStats(): Promise<{ publishedSkills: number; totalGrains: number; totalConversations: number }> {
  return apiClient('/public/stats');
}

/** 公开分身列表：发现页浏览 */
export interface PublicSkillInfo {
  id: string;
  displayName: string;
  ownerName: string;
  ownerTitle: string;
  avatarUrl?: string;
  department?: string;
  tags: string[];
  grainCount: number;
  openingMessage?: string;
  domain?: string;
}

export function fetchPublicSkills(search?: string, topic?: string): Promise<PublicSkillInfo[]> {
  const params = new URLSearchParams();
  if (search) params.set('search', search);
  if (topic) params.set('topic', topic);
  return apiClient(`/public/skills?${params.toString()}`);
}

/** 分身详情：聊天页入口 */
export interface SkillDetail {
  id: string;
  displayName: string;
  ownerName: string;
  ownerTitle: string;
  avatarUrl?: string;
  department?: string;
  tags: string[];
  sceneTags: { tag: string; count: number }[];
  grainCount: number;
  openingMessage?: string;
  domain?: string;
  status: string;
}

export function fetchSkillDetail(skillId: string): Promise<SkillDetail> {
  return apiClient(`/skills/${skillId}/detail`);
}

/** 会话历史 */
export interface ConversationItem { id: string; title: string; mode: string; updatedAt: string; }

export function listConversations(skillId: string): Promise<ConversationItem[]> {
  return apiClient(`/skills/${skillId}/conversations`);
}

export interface ConversationMessage {
  id: string; role: string; content: string; createdAt: string;
  grainId?: string; reportId?: string; grainTags?: string; grainCount?: number; avgScore?: string;
}

export function getConversationMessages(conversationId: string): Promise<ConversationMessage[]> {
  return apiClient(`/skills/conversations/${conversationId}/messages`);
}

export function deleteConversation(conversationId: string): Promise<void> {
  return apiClient(`/skills/conversations/${conversationId}`, { method: 'DELETE' });
}

/** 推荐问题 */
export function fetchRecommendedQuestions(skillId: string, sceneTag?: string): Promise<string[]> {
  const params = sceneTag ? `?sceneTag=${encodeURIComponent(sceneTag)}` : '';
  return apiClient(`/skills/${skillId}/recommended-questions${params}`);
}

/** 分身问答（SSE 流式） */
export function chat(
  skillId: string, message: string, callbacks: SseCallbacks,
  conversationId?: string, mode?: string, history?: string,
): AbortController {
  return connectSse({
    url: `${API_BASE}/skills/${skillId}/chat`,
    method: 'POST',
    body: { message, conversationId, channel: 'web', mode, history },
  }, callbacks);
}

/** 对练开始 */
export interface PracticeScene { title: string; setting: string; customerLine: string; }
export interface PracticeStartData {
  practiceId: string; conversationId?: string;
  scene: PracticeScene; practiceAngles?: string[]; totalAngles?: number;
}

export function startPractice(skillId: string, scene?: string, customScene?: string): Promise<PracticeStartData> {
  return apiClient(`/skills/${skillId}/practice/start`, {
    method: 'POST', body: JSON.stringify({ scene, customScene }),
  });
}

/** 对练场景列表 */
export interface PracticeSceneItem { label: string; title: string; setting: string; customerLine: string; grainCount?: number; }

export function fetchPracticeScenes(skillId: string): Promise<PracticeSceneItem[]> {
  return apiClient(`/skills/${skillId}/practice-scenes`);
}

/** 对练回应（SSE 流式） */
export function respondPractice(
  skillId: string, practiceId: string, message: string, callbacks: SseCallbacks,
  sceneContext?: string, history?: string, conversationId?: string, sceneTag?: string,
): AbortController {
  return connectSse({
    url: `${API_BASE}/skills/${skillId}/practice/respond`,
    method: 'POST',
    body: { practiceId, message, sceneContext, history, conversationId, sceneTag },
  }, callbacks);
}

/** 对练综合评价（SSE 流式） */
export function evaluatePractice(
  skillId: string, conversation: string, scene: string, callbacks: SseCallbacks,
): AbortController {
  return connectSse({
    url: `${API_BASE}/skills/${skillId}/practice/evaluate`,
    method: 'POST',
    body: { conversation, scene },
  }, callbacks);
}

/** 逐轮评价（JSON，非 SSE） */
export interface RoundEval {
  championAnswer: string;
  comparison: string;
  hits: string[];
  misses: string[];
  technique: string;
  offTopic?: boolean;
  grains?: Array<{
    sceneTag?: string;
    qualityScore?: number;
    matchLevel?: string;
    fileName?: string;
  }>;
  matchLevel?: string;
  fullAnswer?: string;
  isLastRetry?: boolean;
}

export function evaluatePracticeRound(
  skillId: string,
  body: {
    sceneTag: string;
    customerMessage: string;
    myResponse: string;
    previousChampionAnswer?: string;
    retryCount?: number;
  },
): Promise<RoundEval> {
  return apiClient(`/skills/${skillId}/practice/evaluate-round`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** 用户反馈 */
export function submitFeedback(params: {
  skillId: string;
  sessionId?: string;
  grainId?: string;
  helpful: boolean;
  conversationId?: string;
  query?: string;
  aiResponse?: string;
  ragScore?: number;
  messageId?: string;
}): Promise<void> {
  return apiClient(`/skills/${params.skillId}/feedback`, {
    method: 'POST',
    body: JSON.stringify(params),
  });
}
