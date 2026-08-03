'use client';

import { useState, useRef, useReducer, useCallback, useEffect } from 'react';
import { connectSse, connectSseAsync } from '@/lib/sse';
import { fetchRecommendedQuestions } from '@/lib/api/skill';
import {
  fetchPracticeOpening, fetchPracticeAngles,
  evaluatePracticeRound, matchGrains,
  scorePractice, qaSummary, evaluateDemo,
} from '@/lib/api/audit';
import type { GrainTrace } from '@/lib/api/audit';

// ---- Types ----

export interface ScenarioInfo {
  tag: string; description: string; grainCount: number;
  avgScore?: number; grains: Array<{ id: string; standardScript: string; expertThought: string; }>;
}

export interface ChatMessage {
  id?: string; role: 'customer' | 'avatar'; content: string; sceneTag?: string;
  grains?: GrainTrace[]; matchLevel?: string; levelLabel?: string;
  championAnswer?: string; comparison?: string; hits?: string[]; misses?: string[]; offTopic?: boolean;
  technique?: string; isRetry?: boolean;
  fullAnswer?: string; isLastRetry?: boolean; retryCount?: number;
}

export type Phase = 'scenes' | 'mode-select' | 'chat' | 'evaluate';
export type Mode = 'practice' | 'qa' | 'demo' | 'debug';

export interface DemoEvalResult {
  techniqueDetails?: Array<{ technique: string; status: 'mastered' | 'improving' | 'next' }>;
  tryNext?: string[];
  relatedScenes?: string[];
  suggestion?: string;
  retryCount?: number;
  traceRate?: string;
  totalQuestions?: number;
  coveredTags?: string[];
  uncoveredTags?: string[];
  risks?: Array<{ round: number; detail: string; type?: string }>;
  roundReviews?: Array<{ round: number; traceable: boolean; matchedSceneTag?: string; matchLevel?: string; customerMsg?: string; avatarMsg?: string }>;
  verdict?: string;
  verdictText?: string;
  traceCoverage?: { rate: number; detail: string };
  skillCoverage?: Array<{ tag: string; status: string }>;
}

interface DemoState {
  phase: Phase;
  mode: Mode;
  currentScene: ScenarioInfo | null;
  messages: ChatMessage[];
  evalResult: DemoEvalResult | null;
  currentAngle: number;
  totalAngles: number;
  practiceAngles: string[];
  autoRunning: boolean;
}

type DemoAction =
  | { type: 'SELECT_SCENE'; scene: ScenarioInfo }
  | { type: 'START_MODE'; mode: Mode }
  | { type: 'SET_MESSAGES'; messages: ChatMessage[] }
  | { type: 'ADD_MESSAGE'; message: ChatMessage }
  | { type: 'UPDATE_LAST_CUSTOMER'; updates: Partial<ChatMessage> }
  | { type: 'REMOVE_LAST_AVATAR' }
  | { type: 'SET_ANGLES'; angles: string[] }
  | { type: 'SET_ANGLE'; angle: number }
  | { type: 'SET_EVAL_RESULT'; result: DemoEvalResult }
  | { type: 'SET_AUTO_RUNNING'; running: boolean }
  | { type: 'BACK_TO_SCENES' }
  | { type: 'BACK_TO_MODE_SELECT' }
  | { type: 'BACK_TO_CHAT' };

const initialState: DemoState = {
  phase: 'scenes', mode: 'practice', currentScene: null,
  messages: [], evalResult: null, currentAngle: 1,
  totalAngles: 0, practiceAngles: [], autoRunning: false,
};

function demoReducer(state: DemoState, action: DemoAction): DemoState {
  switch (action.type) {
    case 'SELECT_SCENE':
      return { ...state, currentScene: action.scene, phase: 'mode-select' };
    case 'START_MODE':
      return { ...state, mode: action.mode, messages: [], evalResult: null, phase: 'chat', currentAngle: 1 };
    case 'SET_MESSAGES':
      return { ...state, messages: action.messages };
    case 'ADD_MESSAGE':
      return { ...state, messages: [...state.messages, action.message] };
    case 'UPDATE_LAST_CUSTOMER': {
      const msgs = [...state.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === 'customer') msgs[msgs.length - 1] = { ...last, ...action.updates };
      return { ...state, messages: msgs };
    }
    case 'REMOVE_LAST_AVATAR': {
      const idx = [...state.messages].reverse().findIndex(m => m.role === 'avatar');
      return idx >= 0 ? { ...state, messages: state.messages.slice(0, state.messages.length - 1 - idx) } : state;
    }
    case 'SET_ANGLES':
      return { ...state, practiceAngles: action.angles, totalAngles: action.angles.length };
    case 'SET_ANGLE':
      return { ...state, currentAngle: action.angle };
    case 'SET_EVAL_RESULT':
      return { ...state, evalResult: action.result, phase: action.result ? 'evaluate' : state.phase };
    case 'SET_AUTO_RUNNING':
      return { ...state, autoRunning: action.running };
    case 'BACK_TO_SCENES':
      return { ...state, phase: 'scenes', currentScene: null, messages: [], evalResult: null };
    case 'BACK_TO_MODE_SELECT':
      return { ...state, phase: 'mode-select', messages: [], evalResult: null };
    case 'BACK_TO_CHAT':
      return { ...state, phase: 'chat', evalResult: null };
    default:
      return state;
  }
}

// ---- Hook ----

export function useDemoFlow(skillId: string,
  scenarioGrains: Record<string, Array<{ id: string; sceneTag: string; sceneDescription: string; qualityScore?: number; expertThought: string; standardScript: string; commonMistakes: string; }>>,
  ownerName: string) {

  const [state, dispatch] = useReducer(demoReducer, initialState);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState('');
  const [recQuestions, setRecQuestions] = useState<string[]>([]);
  const [showAllQuestions, setShowAllQuestions] = useState(false);
  const [evalLoading, setEvalLoading] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const retryRef = useRef(false);
  const retryCountRef = useRef(0);
  const messagesRef = useRef(state.messages);
  messagesRef.current = state.messages;

  useEffect(() => { return () => abortRef.current?.abort(); }, []);

  // Scene list
  const sceneList: ScenarioInfo[] = Object.entries(scenarioGrains || {})
    .map(([tag, grains]) => ({
      tag, description: grains[0]?.sceneDescription || '', grainCount: grains.length,
      avgScore: grains.reduce((s, g) => s + (g.qualityScore || 0), 0) / (grains.length || 1),
      grains: grains.map(g => ({ id: g.id, standardScript: g.standardScript, expertThought: g.expertThought })),
    }))
    .sort((a, b) => (b.avgScore || 0) - (a.avgScore || 0));

  const sceneTags = sceneList.map(s => ({ tag: s.tag, count: s.grainCount, description: s.description }));

  // Select scene
  const selectScene = useCallback((scene: ScenarioInfo) => {
    dispatch({ type: 'SELECT_SCENE', scene });
    fetchRecommendedQuestions(skillId, scene.tag)
      .then(qs => setRecQuestions(Array.isArray(qs) ? qs : []))
      .catch(() => {});
  }, [skillId]);

  // Auto demo
  const startAutoDemo = useCallback((_scene: ScenarioInfo, interactive: boolean) => {
    dispatch({ type: 'SET_AUTO_RUNNING', running: true });
    dispatch({ type: 'SET_MESSAGES', messages: [] });
    const modeStr = interactive ? 'full' : 'quick';
    const ctrl = connectSse(
      { url: `${getApiBase()}/admin/skills/${skillId}/auto-demo`, method: 'POST', body: { mode: modeStr } },
      {
        onEvent: (type, data) => {
          if (type === 'customer') {
            dispatch({ type: 'ADD_MESSAGE', message: { role: 'customer', content: data.content as string, sceneTag: data.sceneTag as string } });
          } else if (type === 'avatar') {
            dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: data.content as string, grains: data.grains as GrainTrace[], matchLevel: data.matchLevel as string } });
          }
        },
        onDone: () => dispatch({ type: 'SET_AUTO_RUNNING', running: false }),
        onError: () => dispatch({ type: 'SET_AUTO_RUNNING', running: false }),
      }
    );
    abortRef.current = ctrl;
  }, [skillId]);

  // Start mode
  const startMode = useCallback((m: Mode) => {
    abortRef.current?.abort(); abortRef.current = new AbortController();
    dispatch({ type: 'START_MODE', mode: m });
    const scene = state.currentScene;
    if (!scene) return;

    if (m === 'practice') {
      setStreamText('生成客户开场...');
      fetchPracticeAngles(skillId, scene.tag).then(angles => {
        if (Array.isArray(angles)) dispatch({ type: 'SET_ANGLES', angles });
      }).catch(() => {});
      fetchPracticeOpening(skillId, scene.tag).then(opening => {
        setStreamText('');
        dispatch({ type: 'SET_MESSAGES', messages: [{ role: 'avatar', content: opening || `你好，我是公司采购负责人，最近在考虑「${scene.tag}」相关的事情，想听听你的建议。`, sceneTag: scene.tag, levelLabel: '🎭 客户开场' }] });
      }).catch(() => {
        setStreamText('');
        dispatch({ type: 'SET_MESSAGES', messages: [{ role: 'avatar', content: `你好，我是公司采购负责人，最近在考虑「${scene.tag}」相关的事情，想听听你的建议。`, sceneTag: scene.tag, levelLabel: '🎭 客户开场' }] });
      });
    } else if (m === 'qa') {
      fetchRecommendedQuestions(skillId).then(qs => {
        setRecQuestions(Array.isArray(qs) ? qs : []);
      }).catch(() => {});
      const tags = sceneTags.slice(0, 2).map(t => t.tag).join('、');
      dispatch({ type: 'SET_MESSAGES', messages: [{ role: 'avatar', content: `你好，我是${ownerName}的AI分身。我擅长${tags}等场景。有什么想问的？`, levelLabel: '💡 开场白' }] });
    } else if (m === 'demo' || m === 'debug') {
      startAutoDemo(scene, m === 'debug');
    }
  }, [skillId, state.currentScene, sceneTags, ownerName, startAutoDemo]);

  // Practice: send response
  const sendPractice = useCallback(async () => {
    if (!input.trim() || streaming || !state.currentScene) return;
    const msg = input.trim(); setInput(''); setStreaming(true);
    const msgs = state.messages;
    const lastCustomerMsg = [...msgs].reverse().find(m => m.role === 'avatar')?.content || '';
    const isRetry = retryRef.current;
    const currentRetryCount = isRetry ? retryCountRef.current + 1 : 0;
    retryRef.current = false;
    if (isRetry) retryCountRef.current = currentRetryCount;

    dispatch({ type: 'ADD_MESSAGE', message: { role: 'customer', content: msg, isRetry } });

    // Evaluate round
    try {
      const prevChampion = isRetry ? [...msgs].reverse().find(m => m.role === 'customer' && m.championAnswer)?.championAnswer || '' : '';
      const championData = await evaluatePracticeRound(skillId, {
        sceneTag: state.currentScene.tag, customerMessage: lastCustomerMsg,
        myResponse: msg, previousChampionAnswer: prevChampion, retryCount: currentRetryCount,
      });
      if (championData) {
        dispatch({ type: 'UPDATE_LAST_CUSTOMER', updates: {
          championAnswer: championData.championAnswer, comparison: championData.comparison,
          hits: championData.hits || [], misses: championData.misses || [],
          offTopic: championData.offTopic || false, technique: championData.technique || '',
          fullAnswer: championData.fullAnswer || '', isLastRetry: championData.isLastRetry || false,
          retryCount: currentRetryCount, grains: championData.grains,
          matchLevel: championData.matchLevel,
        }});
      }
    } catch (e) {
      console.error('evaluate round error', e);
      dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: '评价生成失败，请重试', sceneTag: state.currentScene.tag } });
    }
    setStreaming(false);

    // AI customer respond — use ref for latest messages
    {
      try {
        setStreamText(''); setStreaming(true);
        const histStr = messagesRef.current.map(m => `${m.role === 'customer' ? '销售' : '客户'}: ${m.content}`).join('\n');
        const anglesStr = state.practiceAngles.length > 0 ? state.practiceAngles.join('\n') : '';
        let aiResponse = '';
        await connectSseAsync(
          { url: `${getApiBase()}/skills/${skillId}/practice/respond`, method: 'POST',
            body: { message: msg, sceneContext: state.currentScene.tag, history: histStr,
              roundNumber: state.currentAngle, totalAngles: state.totalAngles > 0 ? state.totalAngles : 3,
              practiceAngles: anglesStr },
            signal: abortRef.current?.signal },
          { onChunk: (chunk) => { aiResponse += chunk; setStreamText(aiResponse); } },
        );
        setStreamText('');
        dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: aiResponse, sceneTag: state.currentScene.tag } });
      } catch {
        setStreamText('');
        dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: 'AI 客户回应失败，请重试', sceneTag: state.currentScene.tag } });
      }
    }
    setStreaming(false);
  }, [input, streaming, skillId, state.currentScene, state.currentAngle, state.totalAngles, state.practiceAngles]);

  // Practice: retry
  const retryPractice = useCallback(() => {
    retryRef.current = true;
    dispatch({ type: 'REMOVE_LAST_AVATAR' });
    setInput('');
    setTimeout(() => document.querySelector<HTMLInputElement>('input')?.focus(), 100);
  }, []);

  // Practice: advance round
  const advanceRound = useCallback(() => {
    if (streaming || !state.currentScene) return;
    retryCountRef.current = 0;
    const nextAngle = state.currentAngle + 1;
    dispatch({ type: 'SET_ANGLE', angle: nextAngle });
    setStreaming(true); setStreamText('');
    const histStr = messagesRef.current.map(m => `${m.role === 'customer' ? '销售' : '客户'}: ${m.content}`).join('\n');
    const anglesStr = state.practiceAngles.length > 0 ? state.practiceAngles.join('\n') : '';
    let aiResponse = '';
    connectSseAsync(
      { url: `${getApiBase()}/skills/${skillId}/practice/respond`, method: 'POST',
        body: { message: '（继续下一轮）', sceneContext: state.currentScene.tag, history: histStr,
          roundNumber: nextAngle, totalAngles: state.totalAngles > 0 ? state.totalAngles : 3,
          practiceAngles: anglesStr },
        signal: abortRef.current?.signal },
      { onChunk: (chunk) => { aiResponse += chunk; setStreamText(aiResponse); } },
    ).then(() => {
      setStreamText('');
      dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: aiResponse, sceneTag: state.currentScene!.tag } });
      setStreaming(false);
    }).catch(() => {
      setStreamText(''); setStreaming(false);
      dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: 'AI 客户回应失败，请重试', sceneTag: state.currentScene!.tag } });
    });
  }, [streaming, skillId, state.currentScene, state.currentAngle, state.totalAngles, state.practiceAngles]);

  // QA: send question
  const sendQa = useCallback(async () => {
    if (!input.trim() || streaming) return;
    const msg = input.trim(); setInput(''); setStreaming(true); setStreamText('');
    dispatch({ type: 'ADD_MESSAGE', message: { role: 'customer', content: msg } });
    const histStr = messagesRef.current.map(m => `${m.role === 'customer' ? '你' : '分身'}: ${m.content}`).join('\n');
    let full = '';
    await connectSseAsync(
      { url: `${getApiBase()}/skills/${skillId}/chat`, method: 'POST',
        body: { message: msg, mode: 'quick', history: histStr },
        signal: abortRef.current?.signal },
      { onChunk: (chunk) => { full += chunk; setStreamText(full); } },
    );
    // Trace match
    try {
      const data = await matchGrains(skillId, msg);
      if (data) {
        dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: full, grains: data.grains, matchLevel: data.matchLevel } });
        setStreamText(''); setStreaming(false); return;
      }
    } catch (e) { console.error('match grains error', e); }
    dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: full, matchLevel: 'NO_DATA' } });
    setStreamText(''); setStreaming(false);
  }, [input, streaming, skillId, state.currentScene]);

  // QA: recommended question click
  const sendQaMessage = useCallback(async (msg: string) => {
    if (!msg.trim() || streaming) return;
    setInput(''); setStreaming(true); setStreamText('');
    dispatch({ type: 'ADD_MESSAGE', message: { role: 'customer', content: msg } });
    const histStr = messagesRef.current.map(m => `${m.role === 'customer' ? '你' : '分身'}: ${m.content}`).join('\n');
    let full = '';
    await connectSseAsync(
      { url: `${getApiBase()}/skills/${skillId}/chat`, method: 'POST',
        body: { message: msg, mode: 'quick', history: histStr },
        signal: abortRef.current?.signal },
      { onChunk: (chunk) => { full += chunk; setStreamText(full); } },
    );
    try {
      const data = await matchGrains(skillId, msg);
      if (data) {
        dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: full, grains: data.grains, matchLevel: data.matchLevel } });
        setStreamText(''); setStreaming(false); return;
      }
    } catch (e) { console.error('match grains error', e); }
    dispatch({ type: 'ADD_MESSAGE', message: { role: 'avatar', content: full, matchLevel: 'NO_DATA' } });
    setStreamText(''); setStreaming(false);
  }, [streaming, skillId, state.currentScene]);

  // End → evaluate
  const endAndEvaluate = useCallback(async () => {
    setEvalLoading(true);
    try {
      const msgs = state.messages;
      if (state.mode === 'practice') {
        const rounds = msgs.filter(m => m.role === 'customer' && m.championAnswer).map(m => ({
          sceneTag: state.currentScene?.tag || '', matchLevel: m.matchLevel || 'NO_DATA',
          myResponse: m.content, championAnswer: m.championAnswer || '',
          hits: m.hits || [], misses: m.misses || [], technique: m.technique || '',
          isRetry: m.isRetry || false, grains: m.grains || [],
        }));
        const result = await scorePractice(skillId, rounds);
        if (result) dispatch({ type: 'SET_EVAL_RESULT', result });
      } else if (state.mode === 'qa') {
        const rounds = msgs.filter(m => m.role === 'avatar' && m.levelLabel !== '💡 开场白').map((m, i) => {
          const prev = i > 0 && msgs.indexOf(m) > 0 && msgs[msgs.indexOf(m) - 1].role === 'customer'
            ? msgs[msgs.indexOf(m) - 1] : null;
          return { question: prev?.content || '', avatarAnswer: m.content, matchLevel: m.matchLevel || 'PROFILE_GUESS', grains: m.grains || [] };
        });
        const result = await qaSummary(skillId, rounds);
        if (result) dispatch({ type: 'SET_EVAL_RESULT', result });
      } else {
        const result = await evaluateDemo(skillId, msgs.map(m => ({ role: m.role, content: m.content })));
        if (result) dispatch({ type: 'SET_EVAL_RESULT', result });
      }
    } catch (e) { console.error('evaluate error', e); }
    setEvalLoading(false);
  }, [skillId, state.messages, state.mode, state.currentScene?.tag]);

  // Scene switch
  const switchScene = useCallback((dir: 'prev' | 'next') => {
    const idx = sceneList.findIndex(s => s.tag === state.currentScene?.tag);
    const newIdx = dir === 'prev' ? idx - 1 : idx + 1;
    if (newIdx < 0 || newIdx >= sceneList.length) return;
    const newScene = sceneList[newIdx];
    dispatch({ type: 'SELECT_SCENE', scene: newScene });
    dispatch({ type: 'SET_MESSAGES', messages: [] });
    if (state.mode === 'practice') {
      fetchPracticeOpening(skillId, newScene.tag).then(opening =>
        dispatch({ type: 'SET_MESSAGES', messages: [{ role: 'avatar', content: opening || `你好，我是公司采购负责人，最近在考虑「${newScene.tag}」相关的事情，想听听你的建议。`, sceneTag: newScene.tag, levelLabel: '🎭 客户开场' }] })
      ).catch(() => {});
    } else if (state.mode === 'qa') {
      dispatch({ type: 'SET_MESSAGES', messages: [{ role: 'avatar', content: `你好，我是${ownerName}的AI分身。有什么关于「${newScene.tag}」想问的？`, levelLabel: '💡 开场白' }] });
    } else if (state.mode === 'demo' || state.mode === 'debug') {
      dispatch({ type: 'SET_AUTO_RUNNING', running: false });
      startAutoDemo(newScene, state.mode === 'debug');
    }
  }, [sceneList, state.currentScene?.tag, state.mode, skillId, ownerName, startAutoDemo]);

  const scene = state.currentScene;
  const canPrev = scene ? sceneList.findIndex(s => s.tag === scene.tag) > 0 : false;
  const canNext = scene ? sceneList.findIndex(s => s.tag === scene.tag) < sceneList.length - 1 : false;

  return {
    state, dispatch,
    ui: { input, setInput, streaming, streamText, setStreamText, recQuestions, showAllQuestions, setShowAllQuestions, evalLoading },
    sceneList, sceneTags, abortRef,
    actions: { selectScene, startMode, startAutoDemo, endAndEvaluate, switchScene },
    practice: { sendPractice, retryPractice, advanceRound },
    qa: { sendQa, sendQaMessage },
    nav: { canPrev, canNext },
  };
}

function getApiBase() {
  // Use the same base as apiClient
  if (typeof window !== 'undefined') {
    return process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';
  }
  return '/api/v1';
}
