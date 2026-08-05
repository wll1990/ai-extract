/**
 * 分身 API 客户端 — @synced-from frontend/src/lib/api/skill.ts
 *
 * 双端点策略：已登录走认证接口(/skills/**)，访客走公开接口(/public/skills/**)。
 *
 * @since 2026-07-20
 */

import { getToken } from '@/lib/storage';
import { apiClient, API_BASE } from './client';
import { connectSse, type SseCallbacks } from '@/lib/sse';

/** C 端 Bearer 头（平台端 /s/ 页面用，B端 Cookie 场景不传即跳过） */
function bearer(authToken?: string): Record<string, string> | undefined {
  return authToken ? { Authorization: `Bearer ${authToken}` } : undefined;
}

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
  type?: 'individual' | 'organization';
  memberCount?: number;
  stats?: {
    conversationCount: number;
    userCount: number;
    satisfactionRate: number;
  };
}

export interface PublicSkillsResponse {
  content: PublicSkillInfo[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export function fetchPublicSkills(params?: {
  search?: string;
  type?: string;
  sort?: string;
  page?: number;
  size?: number;
}): Promise<PublicSkillsResponse> {
  const qs = new URLSearchParams();
  if (params?.search) qs.set('search', params.search);
  if (params?.type) qs.set('type', params.type);
  if (params?.sort) qs.set('sort', params.sort);
  if (params?.page) qs.set('page', String(params.page));
  if (params?.size) qs.set('size', String(params.size));
  return apiClient(`/public/skills?${qs.toString()}`);
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
  spaceId?: string;  // 用于判断当前用户是否为此分身 owner
  type?: 'individual' | 'organization';
  memberCount?: number;
  members?: Array<{
    id: string; displayName: string; ownerName: string;
    ownerTitle: string; avatarUrl?: string; department?: string;
    domain?: string; conversationCount?: number;
  }>;
  inactiveMembers?: Array<{
    id: string; ownerName: string; displayName?: string; status?: string;
  }>;
  introProfile?: { headline: string; body: string; closing: string };
  recommendedQuestions?: string[];
  talkConfig?: string;
  stats?: {
    conversationCount: number;
    userCount: number;
    satisfactionRate: number;
  };
}

/**
 * 分身详情 — 企业级双端点策略：
 * 已登录（有 token）→ /skills/{id}/detail（全量数据）
 * 未登录（访客）  → /public/skills/{id}（公开子集）
 */
export function fetchSkillDetail(skillId: string): Promise<SkillDetail> {
  const token = getToken();
  if (token) {
    return apiClient(`/skills/${skillId}/detail`, { headers: { Authorization: `Bearer ${token}` } });
  }
  return apiClient(`/public/skills/${skillId}`);
}

/** 生成或获取已有分享码（需 Cookie 认证）。channel 默认 "public"，可传 "card" */
export function getOrCreateShare(skillId: string, channel?: string): Promise<{ skillId: string; shareCode: string }> {
  const body = channel ? JSON.stringify({ channel }) : undefined;
  return apiClient(`/skills/${skillId}/share`, { method: 'POST', body });
}

/** 会话历史 */
export interface ConversationItem { id: string; title: string; mode: string; updatedAt: string; }

export function listConversations(skillId: string, authToken?: string): Promise<ConversationItem[]> {
  return apiClient(`/skills/${skillId}/conversations`, { headers: bearer(authToken) });
}

export interface ConversationMessage {
  id: string; role: string; content: string; createdAt: string;
  grainId?: string; reportId?: string; reportTitle?: string; grainTags?: string; grainCount?: number; avgScore?: string;
}

export function getConversationMessages(conversationId: string, authToken?: string): Promise<ConversationMessage[]> {
  return apiClient(`/skills/conversations/${conversationId}/messages`, { headers: bearer(authToken) });
}

export function deleteConversation(conversationId: string, authToken?: string): Promise<void> {
  return apiClient(`/skills/conversations/${conversationId}`, { method: 'DELETE', headers: bearer(authToken) });
}

/** 推荐问题 */
export function fetchRecommendedQuestions(skillId: string, sceneTag?: string, authToken?: string): Promise<string[]> {
  const params = sceneTag ? `?sceneTag=${encodeURIComponent(sceneTag)}` : '';
  return apiClient(`/skills/${skillId}/recommended-questions${params}`, { headers: bearer(authToken) });
}

/** 分身问答（SSE 流式） */
export function chat(
  skillId: string, message: string, callbacks: SseCallbacks,
  conversationId?: string, channel?: string, mode?: string, history?: string, authToken?: string,
): AbortController {
  return connectSse({
    url: `${API_BASE}/skills/${skillId}/chat`,
    method: 'POST',
    body: { message, conversationId, channel: channel || 'web', mode, history },
    headers: bearer(authToken),
  }, callbacks);
}

/** 企业总调度问答（SSE 流式） — 不绑具体分身，跨全公司 space 检索 */
export function enterpriseChat(
  message: string, callbacks: SseCallbacks, history?: string,
): AbortController {
  return connectSse({
    url: `${API_BASE}/skills/enterprise/chat`,
    method: 'POST',
    body: { message, channel: 'web', history },
  }, callbacks);
}

/** 对练开始 */
export interface PracticeScene { title: string; setting: string; customerLine: string; }
export interface PracticeStartData {
  practiceId: string; conversationId?: string;
  scene: PracticeScene; practiceAngles?: string[]; totalAngles?: number;
}

export function startPractice(skillId: string, scene?: string, customScene?: string, authToken?: string): Promise<PracticeStartData> {
  return apiClient(`/skills/${skillId}/practice/start`, {
    method: 'POST', body: JSON.stringify({ scene, customScene }),
    headers: bearer(authToken),
  });
}

/** 对练场景列表 */
export interface PracticeSceneItem { label: string; title: string; setting: string; customerLine: string; grainCount?: number; }
/** 前端兼容别名 */
export type PracticeSceneData = PracticeSceneItem;

export function fetchPracticeScenes(skillId: string, authToken?: string): Promise<PracticeSceneItem[]> {
  return apiClient(`/skills/${skillId}/practice-scenes`, { headers: bearer(authToken) });
}

/** 对练回应（SSE 流式） */
export function respondPractice(
  skillId: string, practiceId: string, message: string, callbacks: SseCallbacks,
  sceneContext?: string, history?: string, conversationId?: string, sceneTag?: string, authToken?: string,
): AbortController {
  return connectSse({
    url: `${API_BASE}/skills/${skillId}/practice/respond`,
    method: 'POST',
    body: { practiceId, message, sceneContext, history, conversationId, sceneTag },
    headers: bearer(authToken),
  }, callbacks);
}

/** 对练综合评价（SSE 流式） */
export function evaluatePractice(
  skillId: string, conversation: string, scene: string, callbacks: SseCallbacks, authToken?: string,
): AbortController {
  return connectSse({
    url: `${API_BASE}/skills/${skillId}/practice/evaluate`,
    method: 'POST',
    body: { conversation, scene },
    headers: bearer(authToken),
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
  authToken?: string,
): Promise<RoundEval> {
  return apiClient(`/skills/${skillId}/practice/evaluate-round`, {
    method: 'POST',
    body: JSON.stringify(body),
    headers: bearer(authToken),
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
}, authToken?: string): Promise<void> {
  const { skillId, sessionId, grainId, helpful, messageId, conversationId, query, aiResponse, ragScore } = params;
  return apiClient(`/skills/${skillId}/feedback`, {
    method: 'POST',
    body: JSON.stringify({ sessionId, grainId, helpful, messageId, conversationId, query, aiResponse, ragScore }),
    headers: bearer(authToken),
  });
}
