'use client';

import { useState, useEffect, useCallback, useRef, useReducer } from 'react';
import { startPractice, respondPractice, evaluatePractice, evaluatePracticeRound, fetchPracticeScenes } from '@/lib/api/skill';
import type { PracticeStartData, PracticeSceneData, RoundEval } from '@/lib/api/skill';

// ---- Types ----

export interface PracticeMessage {
  id?: string; role: 'customer' | 'user'; content: string;
  championAnswer?: string; comparison?: string; hits?: string[]; misses?: string[];
  technique?: string; offTopic?: boolean; grains?: RoundEval['grains'];
  matchLevel?: string; levelLabel?: string; isRetry?: boolean;
  fullAnswer?: string; isLastRetry?: boolean; retryCount?: number;
  // 溯源字段（SSE source event）
  grainIds?: string;
  grainTags?: string;
  grainCount?: number;
  avgSimilarity?: string;
  avgScore?: string;
  reportTitle?: string;
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
  grainId: string;
  grainTitle: string;
}

// ---- Reducer: Practice 显式状态机 ----

type PracticePhase = 'idle' | 'active' | 'evaluate';

interface PracticeState {
  phase: PracticePhase;
  data: PracticeStartData | null;
  messages: PracticeMessage[];
  evaluation: PracticeEval | null;
  sources: PracticeSource[];
  angles: { current: number; total: number };
  sceneLabel: string;
  showEndConfirm: boolean;
}

type PracticeAction =
  | { type: 'START'; data: PracticeStartData; sceneLabel: string }
  | { type: 'ADD_MESSAGE'; message: PracticeMessage }
  | { type: 'UPDATE_LAST_USER'; updates: Partial<PracticeMessage> }
  | { type: 'UPDATE_MESSAGE_BY_ID'; id: string; updates: Partial<PracticeMessage> }
  | { type: 'UPDATE_LAST_CUSTOMER'; updates: Partial<PracticeMessage> }
  | { type: 'REMOVE_LAST_CUSTOMER' }
  | { type: 'SET_ANGLES'; angles: { current: number; total: number } }
  | { type: 'SET_EVALUATION'; evaluation: PracticeEval }
  | { type: 'SHOW_CONFIRM'; show: boolean }
  | { type: 'RESET' };

const initialState: PracticeState = {
  phase: 'idle',
  data: null,
  messages: [],
  evaluation: null,
  sources: [],
  angles: { current: 1, total: 3 },
  sceneLabel: '',
  showEndConfirm: false,
};

function practiceReducer(state: PracticeState, action: PracticeAction): PracticeState {
  switch (action.type) {
    case 'START':
      return {
        ...state,
        phase: 'active',
        data: action.data,
        sceneLabel: action.sceneLabel,
        messages: [{
          id: `p-${Date.now()}`, role: 'customer',
          content: action.data.scene.customerLine,
          levelLabel: action.sceneLabel || '🎭 客户开场',
        }],
        sources: [{
          reportId: '', reportTitle: action.data.scene.setting || '',
          grainId: '', grainTitle: (action.sceneLabel || '场景') + ' · 对练',
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

    case 'UPDATE_LAST_USER': {
      const msgs = [...state.messages];
      const lastIdx = msgs.length - 1;
      if (lastIdx >= 0 && msgs[lastIdx].role === 'user') {
        msgs[lastIdx] = { ...msgs[lastIdx], ...action.updates };
      }
      return { ...state, messages: msgs };
    }

    case 'UPDATE_MESSAGE_BY_ID':
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === action.id ? { ...m, ...action.updates } : m
        ),
      };

    case 'UPDATE_LAST_CUSTOMER': {
      const cmsgs = [...state.messages];
      const cIdx = cmsgs.length - 1 - [...cmsgs].reverse().findIndex(m => m.role === 'customer');
      if (cIdx >= 0 && cIdx < cmsgs.length && cmsgs[cIdx].role === 'customer') {
        cmsgs[cIdx] = { ...cmsgs[cIdx], ...action.updates };
      }
      return { ...state, messages: cmsgs };
    }

    case 'REMOVE_LAST_CUSTOMER': {
      const lastIdx = [...state.messages].reverse().findIndex(m => m.role === 'customer');
      if (lastIdx >= 0) {
        const origIdx = state.messages.length - 1 - lastIdx;
        return { ...state, messages: [...state.messages.slice(0, origIdx), ...state.messages.slice(origIdx + 1)] };
      }
      return state;
    }

    case 'SET_ANGLES':
      return { ...state, angles: action.angles };

    case 'SET_EVALUATION':
      return { ...state, phase: 'evaluate', evaluation: action.evaluation, showEndConfirm: false };

    case 'SHOW_CONFIRM':
      return { ...state, showEndConfirm: action.show };

    case 'RESET':
      return { ...initialState };

    default:
      return state;
  }
}

// ---- Hook ----

interface PracticeFlowInputs {
  skillId: string;
  setChatMode: (m: 'qa' | 'talk' | 'practice') => void;
  abortRef: React.MutableRefObject<AbortController | null>;
  /** C 端分享页显式 Bearer（内部页不传 = 零行为变化） */
  authToken?: string;
  /** 游客免费额度用尽（后端 limit 事件），页面弹注册抽屉 */
  onLimit?: (info: { used: number; limit: number; pendingText: string }) => void;
}

export function usePracticeFlow({ skillId, setChatMode, abortRef, authToken, onLimit }: PracticeFlowInputs) {
  // 对练场景（不变数据，保持 useState）
  const [practiceScenes, setPracticeScenes] = useState<PracticeSceneData[]>([]);

  useEffect(() => {
    if (!skillId) return;
    fetchPracticeScenes(skillId, authToken)
      .then(setPracticeScenes)
      .catch(() => setPracticeScenes([]));
  }, [skillId, authToken]);

  // 核心状态机
  const [state, dispatch] = useReducer(practiceReducer, initialState);

  // UI 状态（不属于领域状态机）
  const [isStreaming, setIsStreaming] = useState(false);
  const [showHint, setShowHint] = useState(false);

  // 重试控制（mutable flags，不需要触发渲染）
  const retryRef = useRef(false);
  const retryCountRef = useRef(0);
  const conversationIdRef = useRef<string>('');

  /** 启动对练 */
  const doStartPractice = useCallback(async (sceneLabel?: string, customScene?: string) => {
    setIsStreaming(true);
    try {
      const data = await startPractice(skillId, sceneLabel, customScene, authToken);
      conversationIdRef.current = data.conversationId || '';
      dispatch({ type: 'START', data, sceneLabel: sceneLabel || customScene || '' });
    } catch (err) {
      console.error('开始对练失败:', err);
      setChatMode('qa');
    } finally {
      setIsStreaming(false);
    }
  }, [skillId, setChatMode, authToken]);

  /** 入口 — 开始对练 */
  const handlePracticeStart = useCallback((sceneTag: string) => {
    setChatMode('practice');
    dispatch({ type: 'RESET' });
    doStartPractice(sceneTag);
  }, [doStartPractice, setChatMode]);

  /** 对练发送 — 接收文本参数 */
  const handlePracticeSend = useCallback(async (text: string) => {
    text = text.trim();
    if (!text || isStreaming || !state.data) return;

    const isRetry = retryRef.current;
    const currentRetryCount = isRetry ? retryCountRef.current + 1 : 0;
    retryRef.current = false;
    if (isRetry) retryCountRef.current = currentRetryCount;
    setIsStreaming(true);

    const lastCustomerMsg = [...state.messages].reverse().find(m => m.role === 'customer')?.content || '';

    // 1. 保存用户消息
    const userMsg: PracticeMessage = { id: `p-${Date.now()}`, role: 'user', content: text, isRetry };
    dispatch({ type: 'ADD_MESSAGE', message: userMsg });

    // 更新轮次
    const currentRound = state.messages.filter(m => m.role === 'user').length + 1;
    dispatch({ type: 'SET_ANGLES', angles: { current: Math.min(currentRound, state.angles.total), total: state.angles.total } });

    // 2. 调 evaluate-round
    try {
      const prevChampion = isRetry
        ? [...state.messages].reverse().find(m => m.role === 'user' && m.championAnswer)?.championAnswer || ''
        : '';
      const evalData = await evaluatePracticeRound(skillId, {
        sceneTag: state.sceneLabel || '',
        customerMessage: lastCustomerMsg,
        myResponse: text,
        previousChampionAnswer: prevChampion,
        retryCount: currentRetryCount,
      }, authToken);
      dispatch({
        type: 'UPDATE_LAST_USER',
        updates: {
          championAnswer: evalData.championAnswer,
          comparison: evalData.comparison,
          hits: evalData.hits || [],
          misses: evalData.misses || [],
          offTopic: evalData.offTopic || false,
          technique: evalData.technique || '',
          fullAnswer: evalData.fullAnswer || '',
          isLastRetry: evalData.isLastRetry || false,
          retryCount: currentRetryCount,
          grains: evalData.grains,
          matchLevel: evalData.matchLevel,
          levelLabel: evalData.matchLevel === 'EXACT' ? '精确命中'
            : evalData.matchLevel === 'SEMANTIC' ? '语义相关'
            : evalData.matchLevel === 'PROFILE_GUESS' ? '画像推断' : undefined,
        },
      });
    } catch (e) { console.error('evaluate-round error', e); }
    setIsStreaming(false);

    // 3. AI 客户回应
    {
      try {
        setIsStreaming(true);
        const placeholderId = `p-${Date.now()}`;
        dispatch({ type: 'ADD_MESSAGE', message: { id: placeholderId, role: 'customer', content: '' } });
        const histStr = state.messages.map(m =>
          `${m.role === 'customer' ? '客户' : '销售'}：${m.content}`).join('\n');
        let fullResponse = '';
        let sourceInfo: any = {};
        let rafId = 0;
        const controller = respondPractice(skillId, state.data!.practiceId, text, {
          onChunk: (content) => {
            fullResponse += content;
            if (!rafId) {
              rafId = requestAnimationFrame(() => {
                rafId = 0;
                dispatch({ type: 'UPDATE_MESSAGE_BY_ID', id: placeholderId, updates: { content: fullResponse } });
              });
            }
          },
          onSource: (_reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity, sourceNames) => {
            sourceInfo = { grainIds, grainTags, grainCount, avgScore, avgSimilarity, reportTitle, sourceNames };
          },
          onDone: () => {
            if (Object.keys(sourceInfo).length > 0) {
              dispatch({ type: 'UPDATE_LAST_CUSTOMER', updates: sourceInfo });
            }
            setIsStreaming(false);
          },
          onError: () => { setIsStreaming(false); },
          // limit = 游客免费额度用尽：撤掉客户占位气泡，页面弹注册抽屉（注册后点"下一轮"继续）
          onEvent: (type, data) => {
            if (type === 'limit') {
              setIsStreaming(false);
              dispatch({ type: 'REMOVE_LAST_CUSTOMER' });
              onLimit?.({ used: Number(data.used ?? 0), limit: Number(data.limit ?? 0), pendingText: text });
            }
          },
        }, state.sceneLabel, histStr, conversationIdRef.current || undefined, state.sceneLabel, authToken);
        abortRef.current?.abort(); abortRef.current = controller;
      } catch { setIsStreaming(false); }
    }
  }, [isStreaming, skillId, state, abortRef, authToken, onLimit]);

  const retryPractice = useCallback(() => {
    retryRef.current = true;
    dispatch({ type: 'REMOVE_LAST_CUSTOMER' });
    setTimeout(() => {
      const textarea = document.querySelector('textarea');
      if (textarea) textarea.focus();
    }, 100);
  }, []);

  const advanceRound = useCallback(() => {
    if (isStreaming || !state.data) return;
    retryCountRef.current = 0;
    const nextAngle = state.angles.current + 1;
    dispatch({ type: 'SET_ANGLES', angles: { current: Math.min(nextAngle, state.angles.total), total: state.angles.total } });
    setIsStreaming(true);
    const histStr = state.messages.map(m =>
      `${m.role === 'customer' ? '客户' : '销售'}：${m.content}`).join('\n');
    let aiResponse = '';
    const controller = respondPractice(skillId, state.data.practiceId, '（继续下一轮）', {
      onChunk: (content) => { aiResponse += content; },
      onDone: () => {
        setIsStreaming(false);
        if (aiResponse) dispatch({ type: 'ADD_MESSAGE', message: { id: `p-${Date.now()}`, role: 'customer', content: aiResponse } });
      },
      onError: () => { setIsStreaming(false); },
      onEvent: (type, data) => {
        if (type === 'limit') {
          setIsStreaming(false);
          onLimit?.({ used: Number(data.used ?? 0), limit: Number(data.limit ?? 0), pendingText: '（继续下一轮）' });
        }
      },
    }, state.sceneLabel, histStr, conversationIdRef.current || undefined, state.sceneLabel, authToken);
    abortRef.current?.abort(); abortRef.current = controller;
  }, [isStreaming, skillId, state, abortRef, authToken, onLimit]);

  const handleEndPractice = useCallback(() => {
    dispatch({ type: 'SHOW_CONFIRM', show: true });
  }, []);

  const confirmEndPractice = useCallback(() => {
    dispatch({ type: 'SHOW_CONFIRM', show: false });
    setIsStreaming(true);

    const conversationText = state.messages.map(m =>
      `${m.role === 'customer' ? '客户' : '销售员'}：${m.content}`
    ).join('\n');

    let fullEval = '';
    const controller = evaluatePractice(skillId, conversationText,
      state.data?.scene?.setting || '', {      onChunk: (content) => { fullEval += content; },
      onDone: () => {
        setIsStreaming(false);
        try {
          const cleanEval = fullEval.replace(/```json\s*/g, '').replace(/```\s*/g, '');
          const jsonMatch = cleanEval.match(/\{[\s\S]*\}/);
          if (jsonMatch) {
            dispatch({ type: 'SET_EVALUATION', evaluation: JSON.parse(jsonMatch[0]) });
          } else {
            dispatch({ type: 'SET_EVALUATION', evaluation: {
              strengths: [{ point: '对练完成', quote: '' }],
              improvements: [{ point: '评价解析失败，请重试', quote: '', suggestion: '' }],
              demo_script: '', next_advice: '再来一轮试试',
            }});
          }
        } catch {
          dispatch({ type: 'SET_EVALUATION', evaluation: {
            strengths: [{ point: '对练完成', quote: '' }],
            improvements: [{ point: '评价生成中，请稍后重试', quote: '', suggestion: '' }],
            demo_script: '', next_advice: '再来一轮试试',
          }});
        }
      },
      onError: () => {
        setIsStreaming(false);
        dispatch({ type: 'SET_EVALUATION', evaluation: {
          strengths: [{ point: '对练完成', quote: '' }],
          improvements: [{ point: 'AI服务繁忙，请稍后重试', quote: '', suggestion: '点击再来一轮重新练习' }],
          demo_script: '', next_advice: '稍后重试',
        }});
      },
    }, authToken);
    abortRef.current?.abort(); abortRef.current = controller;
  }, [state, skillId, abortRef, authToken]);

  const onResetPractice = useCallback(() => {
    dispatch({ type: 'RESET' });
    conversationIdRef.current = '';
  }, []);

  // 保持对外接口不变
  return {
    practiceScenes,
    practiceData: state.data,
    setPracticeData: (data: PracticeStartData | null) => { /* deprecated — use dispatch */ },
    practiceMessages: state.messages,
    setPracticeMessages: (() => {}) as any,
    practicePhase: state.phase === 'evaluate' ? 'evaluate' as const : 'active' as const,
    setPracticePhase: (() => {}) as any,
    practiceEval: state.evaluation,
    setPracticeEval: (() => {}) as any,
    practiceSources: state.sources,
    setPracticeSources: (() => {}) as any,
    showHint, setShowHint,
    practiceAngles: state.angles,
    setPracticeAngles: (() => {}) as any,
    selectedSceneLabel: state.sceneLabel,
    setSelectedSceneLabel: (() => {}) as any,
    showEndConfirm: state.showEndConfirm,
    setShowEndConfirm: (v: boolean) => dispatch({ type: 'SHOW_CONFIRM', show: v }),
    isStreaming, setIsStreaming,
    retryRef, retryCountRef,
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
