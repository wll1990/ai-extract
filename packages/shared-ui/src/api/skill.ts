/**
 * 分身 API 客户端 — @aiextract/shared-ui
 *
 * 所有 API 函数，前端/企业端/H5 共享。
 * 使用 configureApi() 设置 baseUrl 和认证头。
 */

import { apiClient, getApiBaseUrl } from './client';
import { connectSse, type SseCallbacks } from './sse';

// ═══ Types ═══

export interface SceneTag { tag: string; count: number; }

export interface PublicSkillInfo {
  id: string; displayName: string; ownerName: string; ownerTitle: string;
  avatarUrl?: string; department?: string; tags: string[];
  grainCount: number; openingMessage?: string; domain?: string;
}

export interface SkillDetail {
  id: string; displayName: string; ownerName: string; ownerTitle: string;
  avatarUrl?: string; department?: string; tags: string[];
  sceneTags: SceneTag[]; grainCount: number; openingMessage?: string;
  domain?: string; talkConfig?: string; status: string;
}

export interface ConversationItem { id: string; title: string; mode: string; updatedAt: string; }

export interface ConversationMessage {
  id: string; role: string; content: string; createdAt: string;
  grainId?: string; reportId?: string; grainTags?: string;
  grainCount?: number; avgScore?: string; reportTitle?: string;
}

export interface PracticeSceneData { label: string; title: string; setting: string; customerLine: string; grainCount?: number; }

export interface PracticeStartData {
  practiceId: string; conversationId?: string;
  scene: { title: string; setting: string; customerLine: string };
  practiceAngles?: string[]; totalAngles?: number;
}

export interface RoundEval {
  championAnswer: string; comparison: string; hits: string[];
  misses: string[]; technique: string; offTopic?: boolean;
  grains?: Array<{ sceneTag?: string; qualityScore?: number; matchLevel?: string; fileName?: string }>;
  matchLevel?: string; fullAnswer?: string; isLastRetry?: boolean;
}

// ═══ Public APIs ═══

export function fetchPublicStats(): Promise<{ publishedSkills: number; totalGrains: number; totalConversations: number }> {
  return apiClient('/public/stats');
}

export function fetchPublicSkills(search?: string, topic?: string): Promise<PublicSkillInfo[]> {
  const params = new URLSearchParams();
  if (search) params.set('search', search);
  if (topic) params.set('topic', topic);
  return apiClient(`/public/skills?${params.toString()}`);
}

export function fetchSkillDetail(skillId: string): Promise<SkillDetail> {
  return apiClient(`/skills/${skillId}/detail`);
}

// ═══ Recommended Questions ═══

export function fetchRecommendedQuestions(skillId: string, sceneTag?: string): Promise<string[]> {
  const params = sceneTag ? `?sceneTag=${encodeURIComponent(sceneTag)}` : '';
  return apiClient(`/skills/${skillId}/recommended-questions${params}`);
}

// ═══ Chat (SSE) ═══

export function chat(
  skillId: string, message: string, callbacks: SseCallbacks,
  conversationId?: string, mode?: string, history?: string,
): AbortController {
  return connectSse({
    url: `${getApiBaseUrl()}/skills/${skillId}/chat`,
    method: 'POST',
    body: { message, conversationId, channel: 'web', mode, history },
  }, callbacks);
}

// ═══ Conversations ═══

export function listConversations(skillId: string): Promise<ConversationItem[]> {
  return apiClient(`/skills/${skillId}/conversations`);
}

export function getConversationMessages(conversationId: string): Promise<ConversationMessage[]> {
  return apiClient(`/skills/conversations/${conversationId}/messages`);
}

export function deleteConversation(conversationId: string): Promise<void> {
  return apiClient(`/skills/conversations/${conversationId}`, { method: 'DELETE' });
}

// ═══ Feedback ═══

export function submitFeedback(params: {
  skillId: string; sessionId?: string; grainId?: string;
  helpful: boolean; conversationId?: string; query?: string;
  aiResponse?: string; ragScore?: number; messageId?: string;
}): Promise<void> {
  return apiClient(`/skills/${params.skillId}/feedback`, {
    method: 'POST', body: JSON.stringify(params),
  });
}

// ═══ Scene Tags ═══

export function fetchSceneTags(skillId: string): Promise<SceneTag[]> {
  return apiClient(`/skills/${skillId}/scene-tags`);
}

// ═══ Practice ═══

export function fetchPracticeScenes(skillId: string): Promise<PracticeSceneData[]> {
  return apiClient(`/skills/${skillId}/practice-scenes`);
}

export function startPractice(skillId: string, scene?: string, customScene?: string): Promise<PracticeStartData> {
  return apiClient(`/skills/${skillId}/practice/start`, {
    method: 'POST', body: JSON.stringify({ scene, customScene }),
  });
}

export function respondPractice(
  skillId: string, practiceId: string, message: string, callbacks: SseCallbacks,
  sceneContext?: string, history?: string, conversationId?: string, sceneTag?: string,
): AbortController {
  return connectSse({
    url: `${getApiBaseUrl()}/skills/${skillId}/practice/respond`,
    method: 'POST',
    body: { practiceId, message, sceneContext, history, conversationId, sceneTag },
  }, callbacks);
}

export function evaluatePractice(
  skillId: string, conversation: string, scene: string, callbacks: SseCallbacks,
): AbortController {
  return connectSse({
    url: `${getApiBaseUrl()}/skills/${skillId}/practice/evaluate`,
    method: 'POST',
    body: { conversation, scene },
  }, callbacks);
}

export function evaluatePracticeRound(
  skillId: string,
  body: { sceneTag: string; customerMessage: string; myResponse: string; previousChampionAnswer?: string; retryCount?: number },
): Promise<RoundEval> {
  return apiClient(`/skills/${skillId}/practice/evaluate-round`, {
    method: 'POST', body: JSON.stringify(body),
  });
}

export function fetchPracticeTrend(skillId: string): Promise<any[]> {
  return apiClient(`/skills/${skillId}/practice/trend`);
}
