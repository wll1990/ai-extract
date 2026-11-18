/**
 * 试问台 / 对话式纠偏 API。
 *
 * 路径全部挂在 /skills/{skillId}/... 下：企业隔离与属主判断在后端 Service。
 *
 * @since 2026-11-18
 */
import { apiClient, API_BASE } from './client';
import { connectSse, type SseCallbacks } from '@/lib/sse';

export interface TrialSkillDetail {
  id: string;
  displayName?: string;
  ownerName?: string;
  ownerTitle?: string;
  avatarUrl?: string;
  status: string;
  grainCount?: number;
  tags?: string[];
}

export function getTrialSkill(skillId: string): Promise<TrialSkillDetail> {
  return apiClient(`/skills/${skillId}/detail`);
}

export interface TrialGrain {
  id: string;
  sceneTag?: string | null;
  sceneDescription?: string | null;
  expertThought?: string | null;
  standardScript?: string | null;
  commonMistakes?: string | null;
  applicableCondition?: string | null;
  qualityScore?: number | null;
}

/** 一轮试问结束后下发的 trial 事件 */
export interface TrialTurnResult {
  sessionId: string;
  messageId: string;
  avgSimilarity?: string;
  sourceNames?: string;
  canApplyDirectly: boolean;
  published: boolean;
  grains: TrialGrain[];
}

export interface TrialSessionItem {
  id: string;
  firstQuestion?: string | null;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface TrialMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  meta?: {
    grainIds?: string[];
    grainTags?: string[];
    avgSimilarity?: string | null;
    sourceNames?: string | null;
  } | null;
  createdAt: string;
}

export interface TrialSessionDetail {
  id: string;
  skillId: string;
  firstQuestion?: string | null;
  messageCount: number;
  createdAt: string;
  messages: TrialMessage[];
}

/** 试问一轮（SSE）：content 流式 + trial 事件带引用颗粒 */
export function trialChat(
  skillId: string,
  body: { message: string; sessionId?: string; clientMessageId?: string },
  callbacks: SseCallbacks,
): AbortController {
  return connectSse(
    { url: `${API_BASE}/skills/${skillId}/trial/chat`, method: 'POST', body },
    callbacks,
  );
}

export function listTrialSessions(
  skillId: string, page = 1, size = 20,
): Promise<{ content: TrialSessionItem[]; total: number; totalPages: number }> {
  return apiClient(`/skills/${skillId}/trial/sessions?page=${page}&size=${size}`);
}

export function getTrialSession(skillId: string, sessionId: string): Promise<TrialSessionDetail> {
  return apiClient(`/skills/${skillId}/trial/sessions/${sessionId}`);
}

export function getTrialGrain(skillId: string, grainId: string): Promise<TrialGrain> {
  return apiClient(`/skills/${skillId}/trial/grains/${grainId}`);
}

export function traceTrialGrain(
  skillId: string, grainId: string,
): Promise<Array<{ id: string; query?: string; similarity?: number; tier?: string; createdAt?: string }>> {
  return apiClient(`/skills/${skillId}/trial/grains/${grainId}/trace`);
}

/** 纠偏一轮（SSE）：content（AI 回复）+ refine（结构化结果） */
export function refineGrain(
  skillId: string,
  grainId: string,
  body: { message: string; draftId?: string },
  callbacks: SseCallbacks,
): AbortController {
  return connectSse(
    { url: `${API_BASE}/skills/${skillId}/grains/${grainId}/refine`, method: 'POST', body },
    callbacks,
  );
}

export interface RefineEvent {
  draftId: string;
  fields: Record<string, string | null>;
  changed: string[];
  confidence: string;
  reply: string;
  published: boolean;
}

export interface RefineDraftState {
  draftId: string;
  status: 'draft' | 'applied' | 'discarded' | 'reverted';
  embeddingStatus: 'pending' | 'ready' | 'failed';
  changed: string[];
  base: Record<string, string | null>;
  draft: Record<string, string | null>;
  appliedAt?: string | null;
}

export function applyDraft(
  skillId: string, grainId: string, draftId: string, confirm = false,
  fields?: Record<string, string>,
): Promise<{ draftId: string; changed: string[]; embeddingStatus: string }> {
  return apiClient(`/skills/${skillId}/grains/${grainId}/refine/${draftId}/apply`, {
    method: 'POST',
    body: JSON.stringify({ confirm, fields }),
  });
}

export function discardDraft(skillId: string, grainId: string, draftId: string): Promise<void> {
  return apiClient(`/skills/${skillId}/grains/${grainId}/refine/${draftId}/discard`, { method: 'POST' });
}

export function revertDraft(
  skillId: string, grainId: string, draftId: string,
): Promise<{ draftId: string; status: string; embeddingStatus: string }> {
  return apiClient(`/skills/${skillId}/grains/${grainId}/refine/${draftId}/revert`, { method: 'POST' });
}

export function getDraft(skillId: string, grainId: string, draftId: string): Promise<RefineDraftState> {
  return apiClient(`/skills/${skillId}/grains/${grainId}/refine/${draftId}`);
}

export function submitSuggestion(
  skillId: string, grainId: string, draftId: string, reason?: string,
): Promise<{ suggestionId: string; draftId: string }> {
  return apiClient(`/skills/${skillId}/grains/${grainId}/refine/${draftId}/submit-suggestion`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}
