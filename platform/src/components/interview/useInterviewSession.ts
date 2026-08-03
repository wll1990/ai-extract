'use client';

import { useState, useEffect, useRef, useCallback, useReducer } from 'react';
import { useRouter } from 'next/navigation';
import {
  getSession, getMessages, sendMessage, resumeSession, restartSession,
  type InterviewMessageData, type InterviewSessionData,
} from '@/lib/api/interview';

// ---- Reducer ----

interface SessionState {
  session: InterviewSessionData | null;
  messages: InterviewMessageData[];
  isCompleted: boolean;
  completionReportId: string | null;
  completionGrainCount: number;
  showCompletionCard: boolean;
  showCollectPanel: boolean;
}

type SessionAction =
  | { type: 'INIT'; session: InterviewSessionData; messages: InterviewMessageData[] }
  | { type: 'SET_SESSION'; session: InterviewSessionData | null }
  | { type: 'SET_MESSAGES'; messages: InterviewMessageData[] }
  | { type: 'ADD_MESSAGE'; message: InterviewMessageData }
  | { type: 'UPDATE_AI_MESSAGE'; id: string; content: string }
  | { type: 'REMOVE_MESSAGE'; id: string }
  | { type: 'MARK_COMPLETED'; reportId: string | null; grainCount?: number }
  | { type: 'RESUME_CHAT' }
  | { type: 'SET_COLLECT_PANEL'; show: boolean };

const initialSessionState: SessionState = {
  session: null, messages: [], isCompleted: false,
  completionReportId: null, completionGrainCount: 0,
  showCompletionCard: false, showCollectPanel: true,
};

function sessionReducer(state: SessionState, action: SessionAction): SessionState {
  switch (action.type) {
    case 'INIT':
      return { ...state, session: action.session, messages: action.messages,
        isCompleted: action.session.status === 'completed' };
    case 'SET_SESSION':
      return { ...state, session: action.session };
    case 'SET_MESSAGES':
      return { ...state, messages: action.messages };
    case 'ADD_MESSAGE':
      return { ...state, messages: [...state.messages, action.message] };
    case 'UPDATE_AI_MESSAGE':
      return { ...state, messages: state.messages.map(m =>
        m.id === action.id ? { ...m, content: action.content } : m) };
    case 'REMOVE_MESSAGE':
      return { ...state, messages: state.messages.filter(m => m.id !== action.id) };
    case 'MARK_COMPLETED':
      return { ...state, isCompleted: true, completionReportId: action.reportId,
        completionGrainCount: action.grainCount || 0, showCompletionCard: true };
    case 'RESUME_CHAT':
      return { ...state, isCompleted: false, showCompletionCard: false,
        completionGrainCount: 0, completionReportId: null };
    case 'SET_COLLECT_PANEL':
      return { ...state, showCollectPanel: action.show };
    default:
      return state;
  }
}

/** AI 在流式回复中输出此标记时，前端点亮阶段推进按钮 */
const SUGGEST_ADVANCE_MARKER = '【建议推进】';

// ---- Hook ----

export function useInterviewSession(sessionId: string) {
  const router = useRouter();
  const [state, dispatch] = useReducer(sessionReducer, initialSessionState);
  const [inputValue, setInputValue] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [isStreaming, setIsStreaming] = useState(false);
  const [isOnline, setIsOnline] = useState(true);
  const [showResumeModal, setShowResumeModal] = useState(false);
  const [showMoreMenu, setShowMoreMenu] = useState(false);
  const [errorBanner, setErrorBanner] = useState<string | null>(null);
  const [suggestAdvance, setSuggestAdvance] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const streamingContentRef = useRef('');
  const chatEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { return () => abortRef.current?.abort(); }, []);

  // Init
  useEffect(() => {
    if (!sessionId) return;
    Promise.all([getSession(sessionId), getMessages(sessionId)])
      .then(([sessionData, messagesData]) => {
        dispatch({ type: 'INIT', session: sessionData, messages: messagesData });
        if (sessionData.status === 'in_progress' || sessionData.status === 'paused') {
          const lastMsg = messagesData[messagesData.length - 1];
          if (!(lastMsg?.role === 'ai' && lastMsg.depth === -1)) setShowResumeModal(true);
        }
      }).catch(err => { console.error('加载会话失败:', err); setErrorBanner('加载会话失败，请刷新重试'); })
      .finally(() => setIsLoading(false));
  }, [sessionId]);

  // Network
  useEffect(() => {
    const h = () => { setIsOnline(true); setErrorBanner(null); };
    const o = () => { setIsOnline(false); setErrorBanner('网络连接已断开'); };
    window.addEventListener('online', h);
    window.addEventListener('offline', o);
    return () => { window.removeEventListener('online', h); window.removeEventListener('offline', o); };
  }, []);

  // Scroll
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [state.messages]);

  // Send message
  const handleSend = useCallback(async () => {
    const text = inputValue.trim();
    if (!text || isStreaming || state.isCompleted) return;

    const userMsg: InterviewMessageData = {
      id: `temp-${Date.now()}`, role: 'user', content: text, depth: 0,
      phase: state.session?.currentPhase || 'opening', createdAt: new Date().toISOString(),
    };
    dispatch({ type: 'ADD_MESSAGE', message: userMsg });
    setInputValue(''); setIsStreaming(true);
    streamingContentRef.current = '';

    const aiMsgId = `streaming-${Date.now()}`;
    dispatch({ type: 'ADD_MESSAGE', message: {
      id: aiMsgId, role: 'ai', content: '', depth: 0,
      phase: state.session?.currentPhase || 'opening', createdAt: new Date().toISOString(),
    }});

    const controller = sendMessage(sessionId, text, {
      onChunk: (content) => {
        streamingContentRef.current += content;
        if (streamingContentRef.current.includes(SUGGEST_ADVANCE_MARKER)) setSuggestAdvance(true);
        dispatch({ type: 'UPDATE_AI_MESSAGE', id: aiMsgId, content: streamingContentRef.current });
      },
      onPhaseChange: (phase) => {
        const COLLECT_LABELS: Record<string, string> = {
          caseStory: '案例故事', steps: '核心步骤', decision: '关键决策',
          mindset: '专家心法', boundary: '适用边界', checklist: '行动清单',
        };
        const PHASE_NAMES: Record<string, string> = {
          opening: '开场定调', storytelling: '故事深描', modeling: '模型提炼', closing: '收网确认',
        };
        // 构建进度系统消息
        const cs = state.session?.collectStatus;
        let progressLine = `―― ${PHASE_NAMES[phase] || phase} ――`;
        if (cs) {
          const entries = Object.entries(cs);
          const done = entries.filter(([, v]) => v === 'collected').length;
          const doneLabels = entries.filter(([, v]) => v === 'collected').map(([k]) => COLLECT_LABELS[k] || k);
          const nextLabels = entries.filter(([, v]) => v !== 'collected').map(([k]) => COLLECT_LABELS[k] || k);
          progressLine += `\n已采集 ${done}/6 模块`;
          if (doneLabels.length > 0) progressLine += `（${doneLabels.join('、')}）`;
          if (nextLabels.length > 0) progressLine += `  下一步：${nextLabels.slice(0, 3).join('、')}`;
        }
        dispatch({ type: 'SET_SESSION', session: state.session ? { ...state.session, currentPhase: phase } : null });
        dispatch({ type: 'ADD_MESSAGE', message: {
          id: `phase-${phase}-${Date.now()}`, role: 'system',
          content: progressLine, depth: 0, phase, createdAt: new Date().toISOString(),
        }});
      },
      onCollectUpdate: () => { getSession(sessionId).then(s => dispatch({ type: 'SET_SESSION', session: s })).catch(() => {}); },
      onDone: () => {
        setIsStreaming(false);
        getSession(sessionId).then(s => {
          dispatch({ type: 'SET_SESSION', session: s });
          if (s.status === 'completed') dispatch({ type: 'MARK_COMPLETED', reportId: s.reportId || null, grainCount: s.grainCount });
        }).catch(console.error);
      },
      onError: (msg) => {
        setIsStreaming(false); setErrorBanner(msg);
        dispatch({ type: 'REMOVE_MESSAGE', id: aiMsgId });
      },
    });
    abortRef.current = controller;
  }, [inputValue, isStreaming, state.isCompleted, state.session, sessionId]);

  // Resume
  const handleResume = useCallback(() => {
    setShowResumeModal(false); setIsStreaming(true);

    const aiMsgId = `resume-${Date.now()}`;
    dispatch({ type: 'ADD_MESSAGE', message: {
      id: aiMsgId, role: 'ai', content: '', depth: 0,
      phase: state.session?.currentPhase || 'opening', createdAt: new Date().toISOString(),
    }});

    let fullContent = '';
    const ctrl = resumeSession(sessionId, {
      onChunk: (content) => {
        fullContent += content;
        dispatch({ type: 'UPDATE_AI_MESSAGE', id: aiMsgId, content: fullContent });
      },
      onDone: () => {
        setIsStreaming(false);
        getMessages(sessionId).then(msgs => dispatch({ type: 'SET_MESSAGES', messages: msgs })).catch(console.error);
        getSession(sessionId).then(s => dispatch({ type: 'SET_SESSION', session: s })).catch(console.error);
      },
      onError: (msg) => {
        setIsStreaming(false); setErrorBanner(msg);
        dispatch({ type: 'REMOVE_MESSAGE', id: aiMsgId });
      },
    });
    abortRef.current = ctrl;
  }, [sessionId, state.session, dispatch]);

  const isH5 = typeof window !== 'undefined' && window.location.pathname.startsWith('/h5/');

  // Restart — 旧会话标记 abandoned，新建会话，直接进聊天
  const handleRestart = useCallback(async () => {
    setShowResumeModal(false);
    try {
      const result = await restartSession(sessionId);
      if (isH5) router.push(`/h5/interview/chat/${result.sessionId}`);
      else router.push(`/interview/${result.sessionId}`);
    }
    catch (err) { console.error('重新开始失败:', err); }
  }, [sessionId, router, isH5]);

  return {
    state, dispatch, inputValue, setInputValue, isLoading, isStreaming, setIsStreaming, isOnline,
    showResumeModal, setShowResumeModal, showMoreMenu, setShowMoreMenu,
    errorBanner, setErrorBanner, suggestAdvance, setSuggestAdvance,
    abortRef, chatEndRef, inputRef,
    handleSend, handleResume, handleRestart,
  };
}
