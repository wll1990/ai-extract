/**
 * 访谈API客户端
 *
 * @since 2026-06-29
 */

import { apiClient, API_BASE } from './client';
import { connectSse, type SseCallbacks } from '@/lib/sse';

/** 创建访谈请求体 */
export interface CreateInterviewParams {
  spaceId: string;
  topic: string;
  inviteCode?: string;
  expertSkillId?: string;
  interviewType?: string;
}

export interface InterviewSessionData {
  sessionId: string; topic: string; status: string; currentPhase: string;
  expertSkillUsed: string; phases: PhaseInfo[];
  templatePreview: { modules: ModuleInfo[] };
  collectStatus: CollectStatusData;
  lastActiveAt?: string;
  reportId?: string;
  interviewType?: string;
  grainCount?: number;
}

export interface PhaseInfo {
  name: string; label: string; status: 'current' | 'completed' | 'pending';
}

export interface ModuleInfo {
  name: string; collected: boolean;
}

export interface CollectStatusData {
  caseStory: string; steps: string; decision: string;
  mindset: string; boundary: string; checklist: string;
  [key: string]: string;
}

export interface InterviewMessageData {
  id: string; role: 'ai' | 'user' | 'system';
  content: string; depth: number; phase: string; createdAt: string;
}

export interface ActiveSessionInfo {
  hasActive: boolean; sessions: ActiveSessionItem[];
}

export interface ActiveSessionItem {
  sessionId: string; topic: string; status: string;
  currentPhase: string; lastActiveAt: string;
}

/** 创建访谈会话 */
export function createInterview(params: CreateInterviewParams): Promise<InterviewSessionData> {
  return apiClient<InterviewSessionData>('/interviews', {
    method: 'POST', body: JSON.stringify(params),
  });
}

/** 获取会话状态 */
export function getSession(sessionId: string): Promise<InterviewSessionData> {
  return apiClient<InterviewSessionData>(`/interviews/${sessionId}`);
}

/** 发送消息（SSE流式） */
export function sendMessage(sessionId: string, message: string, callbacks: SseCallbacks): AbortController {
  return connectSse({ url: `${API_BASE}/interviews/${sessionId}/chat`, method: 'POST', body: { message } }, callbacks);
}

/** 获取历史消息 */
export function getMessages(sessionId: string): Promise<InterviewMessageData[]> {
  return apiClient<InterviewMessageData[]>(`/interviews/${sessionId}/messages`);
}

/** 中断恢复（SSE流式） */
export function resumeSession(sessionId: string, callbacks: SseCallbacks): AbortController {
  return connectSse({ url: `${API_BASE}/interviews/${sessionId}/resume`, method: 'POST' }, callbacks);
}

/** 重新开始 */
export function restartSession(sessionId: string): Promise<{ sessionId: string }> {
  return apiClient<{ sessionId: string }>(`/interviews/${sessionId}/restart`, { method: 'POST' });
}

/** 暂停访谈 */
export function pauseSession(sessionId: string): Promise<void> {
  return apiClient<void>(`/interviews/${sessionId}/pause`, { method: 'POST' });
}

/** 获取活跃会话 */
export function getActiveSessions(): Promise<ActiveSessionInfo> {
  return apiClient<ActiveSessionInfo>('/interviews/active');
}