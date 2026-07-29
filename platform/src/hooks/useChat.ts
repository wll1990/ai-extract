'use client';

import { useReducer, useCallback, useRef } from 'react';
import { chat, type SseCallbacks } from '@aiextract/shared-ui';
import { enterpriseChat } from '@/lib/api/skill';
import type { ConversationMessage } from '@/lib/api/skill';

// ========== Types ==========

export interface Message {
  id: string;
  role: 'user' | 'ai' | 'assistant';
  content: string;
  grainIds?: string;
  grainTags?: string;
  grainCount?: number;
  avgScore?: string;
  avgSimilarity?: string;
  reportTitle?: string;
}

interface SourceInfo {
  grainIds?: string;
  grainTags?: string;
  grainCount?: number;
  avgScore?: string;
  avgSimilarity?: string;
  reportTitle?: string;
}

export type ChatPhase = 'entry' | 'streaming' | 'idle' | 'error';

interface ChatState {
  phase: ChatPhase;
  messages: Message[];
  streamText: string;
  currentConvId: string | null;
  sourceInfo: SourceInfo | null;
  error: string | null;
  warning: string | null;
  suggestedQuestions: string[];
}

type ChatAction =
  | { type: 'SEND'; userMsg: Message }
  | { type: 'CHUNK'; content: string }
  | { type: 'SOURCE'; info: SourceInfo }
  | { type: 'META'; conversationId: string }
  | { type: 'DONE'; sourceInfo?: SourceInfo }
  | { type: 'ERROR'; message: string }
  | { type: 'WARNING'; message: string }
  | { type: 'SUGGESTED'; questions: string[] }
  | { type: 'DISMISS_WARNING' }
  | { type: 'LOAD_HISTORY'; messages: Message[]; conversationId: string }
  | { type: 'RESET' };

// ========== Reducer ==========

function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case 'SEND':
      return {
        ...state, phase: 'streaming', error: null,
        messages: [...state.messages, action.userMsg],
        streamText: '', sourceInfo: null,
      };

    case 'CHUNK':
      return { ...state, streamText: state.streamText + action.content };

    case 'SOURCE':
      // 如果 DONE 已经先到，把 source 数据补到最末 AI 消息上
      if (state.messages.length > 0) {
        const last = state.messages[state.messages.length - 1];
        if (last.role === 'ai') {
          const updated = { ...last, ...action.info };
          return { ...state, sourceInfo: action.info, messages: [...state.messages.slice(0, -1), updated] };
        }
      }
      return { ...state, sourceInfo: action.info };

    case 'META':
      return { ...state, currentConvId: action.conversationId };

    case 'DONE': {
      const aiMsg: Message = {
        id: `a-${Date.now()}`, role: 'ai',
        content: state.streamText,
        ...(action.sourceInfo || state.sourceInfo || {}),
      };
      return {
        ...state, phase: 'idle',
        messages: [...state.messages, aiMsg],
        streamText: '', sourceInfo: null,
      };
    }

    case 'ERROR':
      return { ...state, phase: 'error', error: action.message };

    case 'WARNING':
      return { ...state, warning: action.message };

    case 'DISMISS_WARNING':
      return { ...state, warning: null };

    case 'SUGGESTED':
      return { ...state, suggestedQuestions: action.questions };

    case 'LOAD_HISTORY':
      return {
        ...state, phase: 'idle',
        messages: action.messages,
        currentConvId: action.conversationId,
        error: null, warning: null, streamText: '', sourceInfo: null,
      };

    case 'RESET':
      return {
        phase: 'entry', messages: [], streamText: '',
        currentConvId: null, sourceInfo: null, error: null, warning: null,
        suggestedQuestions: [],
      };

    default:
      return state;
  }
}

const initialState: ChatState = {
  phase: 'entry', messages: [], streamText: '',
  currentConvId: null, sourceInfo: null, error: null, warning: null,
  suggestedQuestions: [],
};

// ========== Hook ==========

interface UseChatOptions {
  skillId: string;
  ownerName: string;
  enterprise?: boolean;
}

export function useChat({ skillId, ownerName, enterprise }: UseChatOptions) {
  const [state, dispatch] = useReducer(chatReducer, initialState);
  const abortRef = useRef<AbortController | null>(null);
  const sourceRef = useRef<SourceInfo>({});

  const sendMessage = useCallback((text: string, mode: string = 'qa') => {
    const trimmed = text.trim();
    if (!trimmed || state.phase === 'streaming') return;

    const userMsg: Message = { id: `u-${Date.now()}`, role: 'user', content: trimmed };
    dispatch({ type: 'SEND', userMsg });

    // Build history for non-persisted conversations
    const historyText = state.currentConvId ? undefined
      : state.messages.slice(-20).map(m =>
          `${m.role === 'user' ? '用户' : ownerName}：${m.content}`
        ).join('\n');

    let sourceInfo: SourceInfo = {};

    const callbacks: SseCallbacks = {
      onChunk: (content) => { dispatch({ type: 'CHUNK', content }); },
      onSource: (_reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity) => {
        sourceInfo = { grainIds, grainTags, grainCount, avgScore, avgSimilarity, reportTitle };
        sourceRef.current = sourceInfo;
        dispatch({ type: 'SOURCE', info: sourceInfo });
      },
      onMeta: (conversationId) => {
        if (conversationId && !state.currentConvId) {
          dispatch({ type: 'META', conversationId });
        }
      },
      onDone: () => {
        dispatch({ type: 'DONE', sourceInfo: sourceRef.current });
      },
      onError: (msg) => { dispatch({ type: 'ERROR', message: msg }); },
      onEvent: (type, data) => {
        if (type === 'suggested' && Array.isArray(data.questions)) {
          dispatch({ type: 'SUGGESTED', questions: data.questions as string[] });
        }
        if (type === 'warning' && typeof data.message === 'string') {
          dispatch({ type: 'WARNING', message: data.message as string });
        }
      },
    };

    abortRef.current?.abort();
    if (enterprise) {
      abortRef.current = enterpriseChat(trimmed, callbacks, historyText);
    } else {
      abortRef.current = chat(skillId, trimmed, callbacks, state.currentConvId || undefined, mode, historyText);
    }
  }, [skillId, ownerName, enterprise, state.phase, state.messages, state.currentConvId]);

  const clearError = useCallback(() => {
    dispatch({ type: 'RESET' });
  }, []);

  const dismissWarning = useCallback(() => {
    dispatch({ type: 'DISMISS_WARNING' });
  }, []);

  const reset = useCallback(() => {
    abortRef.current?.abort();
    dispatch({ type: 'RESET' });
  }, []);

  const loadHistory = useCallback((messages: ConversationMessage[], conversationId: string) => {
    dispatch({
      type: 'LOAD_HISTORY',
      messages: messages.map(m => ({
        id: m.id,
        role: m.role === 'assistant' ? 'ai' : m.role as 'user' | 'ai',
        content: m.content,
        grainIds: m.grainId || undefined,
        grainTags: m.grainTags || undefined,
        grainCount: m.grainCount || undefined,
        avgScore: m.avgScore || undefined,
      })),
      conversationId,
    });
  }, []);

  return {
    ...state,
    stop: () => { abortRef.current?.abort(); },
    sendMessage,
    clearError,
    dismissWarning,
    reset,
    loadHistory,
  };
}
