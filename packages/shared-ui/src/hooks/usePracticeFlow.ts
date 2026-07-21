/**
 * usePracticeFlow — 实战演练状态机
 *
 * 管理场景选择→逐轮评价→客户回应→重试→进阶→最终复盘 完整流程。
 * 使用 useReducer 管理 9 种 action 的状态转换。
 *
 * @since 2026-07-22 — 从 frontend 迁入 @aiextract/shared-ui
 */

import { useState, useEffect, useCallback, useRef, useReducer } from 'react';
import {
  startPractice, respondPractice, evaluatePractice,
  evaluatePracticeRound, fetchPracticeScenes,
  type PracticeStartData, type PracticeSceneData, type RoundEval,
} from '../api/skill';
import { resolveConfig, type ChatConfig } from './types';

// ═══ Types ═══

export interface PracticeMessage {
  id?: string;
  role: 'customer' | 'user';
  content: string;
  championAnswer?: string;
  comparison?: string;
  hits?: string[];
  misses?: string[];
  technique?: string;
  offTopic?: boolean;
  grains?: RoundEval['grains'];
  matchLevel?: string;
  levelLabel?: string;
  isRetry?: boolean;
  fullAnswer?: string;
  isLastRetry?: boolean;
  retryCount?: number;
}

export interface PracticeEval {
  score?: number;
  strengths: Array<{ point: string; quote: string }>;
  improvements: Array<{ point: string; quote: string; suggestion: string }>;
  demo_script: string;
  next_advice: string;
}

export interface PracticeSource {
  reportId: string;
  reportTitle: string;
  grainId?: string;
  grainTitle?: string;
}

interface PracticeState {
  phase: 'idle' | 'active' | 'evaluate';
  data: PracticeStartData | null;
  messages: PracticeMessage[];
  evaluation: PracticeEval | null;
  sources: PracticeSource[];
  angles: { current: number; total: number };
  sceneLabel: string;
  showEndConfirm: boolean;
}

// ═══ Actions ═══

type PracticeAction =
  | { type: 'START'; data: PracticeStartData; sceneLabel: string }
  | { type: 'ADD_MESSAGE'; message: PracticeMessage }
  | { type: 'UPDATE_LAST_USER'; updates: Partial<PracticeMessage> }
  | { type: 'UPDATE_MESSAGE_BY_ID'; id: string; updates: Partial<PracticeMessage> }
  | { type: 'REMOVE_LAST_CUSTOMER' }
  | { type: 'SET_ANGLES'; angles: { current: number; total: number } }
  | { type: 'SET_EVALUATION'; evaluation: PracticeEval }
  | { type: 'SHOW_CONFIRM'; show: boolean }
  | { type: 'RESET' };

// ═══ Reducer ═══

const initialState: PracticeState = {
  phase: 'idle', data: null, messages: [], evaluation: null,
  sources: [], angles: { current: 1, total: 3 },
  sceneLabel: '', showEndConfirm: false,
};

export function practiceReducer(state: PracticeState, action: PracticeAction): PracticeState {
  switch (action.type) {
    case 'START':
      return {
        ...state, phase: 'active', data: action.data,
        sceneLabel: action.sceneLabel,
        messages: [{
          role: 'customer',
          content: action.data.scene.customerLine,
          levelLabel: '🎭 客户开场',
        }],
        sources: [{
          reportId: '', reportTitle: '',
          grainId: '', grainTitle: action.data.scene.title,
        }],
        angles: {
          current: 1,
          total: action.data.totalAngles || action.data.practiceAngles?.length || 3,
        },
        evaluation: null,
        showEndConfirm: false,
      };
    case 'ADD_MESSAGE':
      return { ...state, messages: [...state.messages, action.message] };
    case 'UPDATE_LAST_USER':
      return {
        ...state,
        messages: state.messages.map((m, i, arr) => {
          if (i === arr.length - 1 && m.role === 'user') return { ...m, ...action.updates };
          return m;
        }),
      };
    case 'UPDATE_MESSAGE_BY_ID':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === action.id ? { ...m, ...action.updates } : m
        ),
      };
    case 'REMOVE_LAST_CUSTOMER':
      return {
        ...state,
        messages: state.messages.filter((_, i, arr) => {
          for (let j = arr.length - 1; j >= 0; j--) {
            if (arr[j].role === 'customer') return i !== j;
          }
          return true;
        }),
      };
    case 'SET_ANGLES':
      return { ...state, angles: action.angles };
    case 'SET_EVALUATION':
      return { ...state, phase: 'evaluate', evaluation: action.evaluation, showEndConfirm: false };
    case 'SHOW_CONFIRM':
      return { ...state, showEndConfirm: action.show };
    case 'RESET':
      return initialState;
    default:
      return state;
  }
}

// ═══ Hook Inputs ═══

export interface PracticeFlowInputs {
  skillId: string;
  setChatMode: (m: 'qa' | 'talk' | 'practice') => void;
  abortRef: React.MutableRefObject<AbortController | null>;
  config?: ChatConfig;
  onLimit?: (info: { used: number; limit: number; pendingText: string }) => void;
}

// ═══ Hook ═══

export function usePracticeFlow({
  skillId, setChatMode, abortRef, config: userConfig, onLimit,
}: PracticeFlowInputs) {
  const cfg = resolveConfig(userConfig);
  const [practiceScenes, setPracticeScenes] = useState<PracticeSceneData[]>([]);
  const [state, dispatch] = useReducer(practiceReducer, initialState);
  const [isStreaming, setIsStreaming] = useState(false);
  const [showHint, setShowHint] = useState(false);
  const retryRef = useRef(false);
  const retryCountRef = useRef(0);
  const conversationIdRef = useRef<string | undefined>();

  useEffect(() => {
    fetchPracticeScenes(skillId).then(setPracticeScenes).catch(() => {});
  }, [skillId]);

  // ═══ Actions ═══

  const doStartPractice = useCallback(async (sceneLabel?: string, customScene?: string) => {
    try {
      const data = await startPractice(skillId, sceneLabel, customScene);
      conversationIdRef.current = data.conversationId;
      dispatch({ type: 'START', data, sceneLabel: sceneLabel || '' });
    } catch {
      // fallback: dispatch with hardcoded scene
    }
  }, [skillId]);

  const handlePracticeStart = useCallback((sceneTag: string) => {
    doStartPractice(sceneTag);
  }, [doStartPractice]);

  const handlePracticeSend = useCallback(async (text: string) => {
    if (!text.trim() || isStreaming) return;

    const isRetry = retryRef.current;
    const currentRetryCount = isRetry ? retryCountRef.current + 1 : 0;
    retryRef.current = false;
    retryCountRef.current = currentRetryCount;

    const userMsg: PracticeMessage = {
      id: cfg.platform.generateId(),
      role: 'user', content: text,
      isRetry, retryCount: currentRetryCount,
    };
    dispatch({ type: 'ADD_MESSAGE', message: userMsg });

    // 计算用户轮次
    const userRound = state.messages.filter(m => m.role === 'user').length + 1;
    dispatch({ type: 'SET_ANGLES', angles: {
      current: Math.min(userRound, state.angles.total),
      total: state.angles.total,
    }});

    // Step 1: 逐轮评价
    try {
      let previousChampionAnswer: string | undefined;
      if (isRetry) {
        for (let i = state.messages.length - 1; i >= 0; i--) {
          if (state.messages[i].role === 'user' && state.messages[i].championAnswer) {
            previousChampionAnswer = state.messages[i].championAnswer;
            break;
          }
        }
      }

      const lastCustomerMsg = [...state.messages].reverse().find(m => m.role === 'customer');
      const evalResult = await evaluatePracticeRound(skillId, {
        sceneTag: state.sceneLabel || '',
        customerMessage: lastCustomerMsg?.content || '',
        myResponse: text,
        previousChampionAnswer,
        retryCount: currentRetryCount,
      });

      dispatch({
        type: 'UPDATE_LAST_USER',
        updates: {
          championAnswer: evalResult.championAnswer,
          comparison: evalResult.comparison,
          hits: evalResult.hits,
          misses: evalResult.misses,
          technique: evalResult.technique,
          offTopic: evalResult.offTopic,
          grains: evalResult.grains,
          matchLevel: evalResult.matchLevel,
          fullAnswer: evalResult.fullAnswer,
          isLastRetry: evalResult.isLastRetry,
        },
      });
    } catch { /* 评价失败不阻塞 */ }

    // Step 2: 客户回应 (SSE)
    setIsStreaming(true);

    const historyLines: string[] = [];
    for (const m of [...state.messages, userMsg]) {
      if (m.role === 'user') historyLines.push(`${cfg.i18n.roles.salesperson}：${m.content}`);
      else historyLines.push(`${cfg.i18n.roles.customer}：${m.content}`);
    }

    const placeholderId = cfg.platform.generateId();
    const placeholder: PracticeMessage = { id: placeholderId, role: 'customer', content: '' };
    dispatch({ type: 'ADD_MESSAGE', message: placeholder });

    let fullResponse = '';
    let rafId = 0;

    respondPractice(
      skillId, state.data?.practiceId || '', text,
      {
        onChunk: (c) => {
          fullResponse += c;
          cfg.platform.scheduleUpdate(() => {
            dispatch({ type: 'UPDATE_MESSAGE_BY_ID', id: placeholderId, updates: { content: fullResponse } });
          });
        },
        onDone: () => {
          setIsStreaming(false);
          dispatch({ type: 'UPDATE_MESSAGE_BY_ID', id: placeholderId, updates: { content: fullResponse } });
        },
        onError: () => {
          setIsStreaming(false);
          dispatch({ type: 'UPDATE_MESSAGE_BY_ID', id: placeholderId, updates: { content: '（对方暂时无法回应）' } });
        },
        onEvent: (type, data) => {
          if (type === 'limit') {
            dispatch({ type: 'REMOVE_LAST_CUSTOMER' });
            const limitData = data as { used?: number; limit?: number };
            onLimit?.({ used: limitData.used || 0, limit: limitData.limit || 5, pendingText: text });
          }
        },
      },
      state.sceneLabel,
      historyLines.join('\n'),
      conversationIdRef.current,
      state.sceneLabel,
    );
  }, [skillId, state, isStreaming, cfg, onLimit]);

  const retryPractice = useCallback(() => {
    retryRef.current = true;
    dispatch({ type: 'REMOVE_LAST_CUSTOMER' });
    setTimeout(() => {
      if (typeof document !== 'undefined') {
        document.querySelector('textarea')?.focus();
      }
    }, 100);
  }, []);

  const advanceRound = useCallback(() => {
    retryCountRef.current = 0;
    const nextAngle = Math.min(state.angles.current + 1, state.angles.total);
    dispatch({ type: 'SET_ANGLES', angles: { current: nextAngle, total: state.angles.total } });
    setIsStreaming(true);

    const historyLines: string[] = [];
    for (const m of state.messages) {
      if (m.role === 'user') historyLines.push(`${cfg.i18n.roles.salesperson}：${m.content}`);
      else historyLines.push(`${cfg.i18n.roles.customer}：${m.content}`);
    }

    let fullResponse = '';
    respondPractice(
      skillId, state.data?.practiceId || '', cfg.i18n.chat.advanceMessage,
      {
        onChunk: (c) => { fullResponse += c; },
        onDone: () => {
          setIsStreaming(false);
          dispatch({ type: 'ADD_MESSAGE', message: { role: 'customer', content: fullResponse } });
        },
        onError: () => setIsStreaming(false),
      },
      state.sceneLabel,
      historyLines.join('\n'),
      conversationIdRef.current,
      state.sceneLabel,
    );
  }, [skillId, state, cfg]);

  const handleEndPractice = useCallback(() => {
    dispatch({ type: 'SHOW_CONFIRM', show: true });
  }, []);

  const confirmEndPractice = useCallback(() => {
    dispatch({ type: 'SHOW_CONFIRM', show: false });
    const conv = state.messages.map(m =>
      `${m.role === 'user' ? cfg.i18n.roles.salesperson : cfg.i18n.roles.customer}：${m.content}`
    ).join('\n');

    let fullEval = '';
    evaluatePractice(skillId, conv, state.sceneLabel, {
      onChunk: (c) => { fullEval += c; },
      onDone: () => {
        try {
          const cleaned = fullEval.replace(/```json\s*/g, '').replace(/```/g, '');
          const match = cleaned.match(/\{[\s\S]*\}/);
          const parsed = match ? JSON.parse(match[0]) : null;
          dispatch({ type: 'SET_EVALUATION', evaluation: parsed || {
            strengths: [], improvements: [],
            demo_script: fullEval, next_advice: '',
          }});
        } catch {
          dispatch({ type: 'SET_EVALUATION', evaluation: {
            strengths: [], improvements: [],
            demo_script: fullEval, next_advice: '',
          }});
        }
      },
      onError: () => {
        dispatch({ type: 'SET_EVALUATION', evaluation: {
          strengths: [], improvements: [],
          demo_script: cfg.i18n.chat.evalParseErrorMsg, next_advice: '',
        }});
      },
    });
  }, [skillId, state.messages, state.sceneLabel, cfg]);

  const onResetPractice = useCallback(() => {
    dispatch({ type: 'RESET' });
    retryCountRef.current = 0;
    retryRef.current = false;
  }, []);

  return {
    practiceScenes,
    practiceData: state.data,
    setPracticeData: () => {},
    practiceMessages: state.messages,
    setPracticeMessages: () => {},
    practicePhase: state.phase === 'evaluate' ? 'evaluate' as const : 'active' as const,
    setPracticePhase: () => {},
    practiceEval: state.evaluation,
    setPracticeEval: () => {},
    practiceSources: state.sources,
    setPracticeSources: () => {},
    showHint,
    setShowHint,
    practiceAngles: state.angles,
    setPracticeAngles: () => {},
    selectedSceneLabel: state.sceneLabel,
    setSelectedSceneLabel: () => {},
    showEndConfirm: state.showEndConfirm,
    setShowEndConfirm: (v: boolean) => dispatch({ type: 'SHOW_CONFIRM', show: v }),
    isStreaming,
    setIsStreaming,
    retryRef,
    retryCountRef,
    doStartPractice,
    handlePracticeStart,
    handlePracticeSend,
    retryPractice,
    advanceRound,
    handleEndPractice,
    confirmEndPractice,
    onResetPractice,
  };
}
