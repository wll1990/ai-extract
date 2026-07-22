'use client';

import { useState, useEffect, useCallback, useRef, useReducer } from 'react';
import { apiClient } from '@/lib/api/client';
import { chat, submitFeedback, fetchRecommendedQuestions } from '@/lib/api/skill';
import { useConversations } from './useConversations';

interface SkillInfo {
  ownerName?: string;
  ownerTitle?: string;
  ownerQuote?: string;
}

interface SceneTag {
  tag: string;
  count: number;
}

export type ChatMode = 'qa' | 'talk' | 'practice';

interface Message {
  id: string; role: string; content: string; type?: string;
  source?: string; grainIds?: string; grainId?: string; reportId?: string;
  grainTags?: string; grainCount?: number; avgScore?: string;
  avgSimilarity?: string;
}

interface QaChatInputs {
  skillId: string;
  skillInfo: SkillInfo;
  chatMode: ChatMode;
  setChatMode: (m: ChatMode) => void;
  setModeSelected: (v: boolean) => void;
  onResetPracticeRef: React.MutableRefObject<() => void>;
  /** C 端分享页显式 Bearer（内部页不传 = 零行为变化） */
  authToken?: string;
  /** 游客免费额度用尽（后端 limit 事件）：pendingText 为被拦截的消息，注册后可重发 */
  onLimit?: (info: { used: number; limit: number; pendingText: string }) => void;
}

export function useQaChat({
  skillId, skillInfo, chatMode,
  setChatMode, setModeSelected, onResetPracticeRef,
  authToken, onLimit,
}: QaChatInputs) {
  const abortRef = useRef<AbortController | null>(null);

  // 问答
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  // useReducer — 逐 chunk dispatch，避免 React 18 auto-batching 合并渲染
  // dispatch(null) 重置为空；dispatch(chunk) 追加
  const [qaStreamText, dispatchStream] = useReducer(
    (state: string, action: string | null) => action === null ? '' : state + action, ''
  );
  const [feedbackState, setFeedbackState] = useState<Record<string, 'up' | 'down' | null>>({});

  // QA 场景上下文
  const [qaSceneContext, setQaSceneContext] = useState<string>('');
  const [contextQuestions, setContextQuestions] = useState<string[]>([]);
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([]);

  // 场景标签
  const [sceneTags, setSceneTags] = useState<SceneTag[]>([]);

  useEffect(() => {
    if (!skillId) return;
    apiClient<SceneTag[]>(`/skills/${skillId}/scene-tags`,
      authToken ? { headers: { Authorization: `Bearer ${authToken}` } } : {})
      .then(tags => setSceneTags(Array.isArray(tags) ? tags : []))
      .catch(() => {});
  }, [skillId, authToken]);

  // 会话历史（内嵌 useConversations）
  const msgSetter = {
    setMessages: (msgs: Array<{id:string;role:string;content:string;source:string;grainId:string}>) =>
      setMessages(msgs as Message[]),
    clearMessages: () => setMessages([]),
  };
  const { conversations, currentConvId, setCurrentConvId, showHistory, setShowHistory,
    loadConversations, switchConversation, handleDeleteConversation } = useConversations(skillId, chatMode, msgSetter, authToken);

  const handleQaSend = useCallback(() => {
    const text = inputValue.trim();
    if (!text || isStreaming) return;

    setSuggestedQuestions([]);  // 新消息 → 清掉上一轮的推荐

    const ownerName = skillInfo?.ownerName || '销冠';
    const historyText = currentConvId ? undefined : messages.slice(-20).map(m =>
      `${m.role === 'user' ? '销售员' : ownerName}：${m.content}`
    ).join('\n');

    const userMsg: Message = { id: `u-${Date.now()}`, role: 'user', content: text };
    setMessages((prev) => [...prev, userMsg]);
    setInputValue('');
    setIsStreaming(true);
    dispatchStream(null);

    const aiMsgId = `a-${Date.now()}`;
    let sourceInfo: any = {};

    let fullContent = '';
    const controller = chat(skillId, text, {
      onChunk: (content) => {
        fullContent += content;
        dispatchStream(content);
      },
      onSource: (reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity, sourceNames) => {
        sourceInfo = { reportId, grainIds: grainIds || '', grainId: (grainIds || '').split(',')[0], source: reportTitle || '', grainTags, grainCount, avgScore, avgSimilarity, sourceNames };
      },
      onMeta: (conversationId) => {
        if (conversationId && !currentConvId) setCurrentConvId(conversationId);
      },
      onDone: () => {
        dispatchStream(null);
        setMessages((prev) => [...prev, { id: aiMsgId, role: 'ai', content: fullContent, ...sourceInfo }]);
        setIsStreaming(false);
        loadConversations();
      },
      onError: (msg) => {
        setIsStreaming(false);
        setMessages((prev) => prev.filter((m) => m.id !== aiMsgId));
        setMessages((prev) => [...prev, {
          id: `err-${Date.now()}`, role: 'ai',
          content: `⚠️ ${msg || 'AI服务暂时不可用，请稍后重试'}`,
        }]);
      },
      // RAG 无匹配时后端推送推荐问题，前端接收并展示可点击按钮；
      // limit = 游客免费额度用尽：撤回乐观气泡，交给页面弹注册抽屉（注册后重发 pendingText）
      onEvent: (type, data) => {
        if (type === 'limit') {
          setIsStreaming(false);
          dispatchStream(null);
          setMessages((prev) => prev.filter((m) => m.id !== userMsg.id));
          onLimit?.({ used: Number(data.used ?? 0), limit: Number(data.limit ?? 0), pendingText: text });
          return;
        }
        if (type === 'suggested' && Array.isArray(data.questions)) {
          setSuggestedQuestions(data.questions as string[]);
        }
      },
    }, currentConvId || undefined, 'web', chatMode, historyText, authToken);
    abortRef.current?.abort(); abortRef.current = controller;
  }, [inputValue, isStreaming, skillId, messages, skillInfo, currentConvId, chatMode, authToken, onLimit]);

  const sendMessageImmediate = useCallback((text: string) => {
    if (isStreaming) return;
    setInputValue('');
    setSuggestedQuestions([]);
    const ownerName = skillInfo?.ownerName || '销冠';
    const historyText = currentConvId ? undefined : messages.slice(-20).map(m =>
      `${m.role === 'user' ? '销售员' : ownerName}：${m.content}`
    ).join('\n');

    const userMsg: Message = { id: `u-${Date.now()}`, role: 'user', content: text };
    setMessages((prev) => [...prev, userMsg]);
    setIsStreaming(true);
    dispatchStream(null);

    let fullContent = '';
    let sourceInfo: any = {};
    const controller = chat(skillId, text, {
      onChunk: (content) => {
        fullContent += content;
        dispatchStream(content);
      },
      onSource: (reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity, sourceNames) => {
        sourceInfo = { reportId, grainIds: grainIds || '', grainId: (grainIds || '').split(',')[0], source: reportTitle || '', grainTags, grainCount, avgScore, avgSimilarity, sourceNames };
      },
      onMeta: (conversationId) => {
        if (conversationId && !currentConvId) setCurrentConvId(conversationId);
      },
      onDone: () => {
        const aiMsgId = `a-${Date.now()}`;
        setMessages((prev) => [...prev, { id: aiMsgId, role: 'ai', content: fullContent, ...sourceInfo }]);
        dispatchStream(null);
        setIsStreaming(false);
        loadConversations();
      },
      onError: () => {
        dispatchStream(null);
        setIsStreaming(false);
      },
      onEvent: (type, data) => {
        if (type === 'limit') {
          setIsStreaming(false);
          setMessages((prev) => prev.filter((m) => m.id !== userMsg.id));
          onLimit?.({ used: Number(data.used ?? 0), limit: Number(data.limit ?? 0), pendingText: text });
          return;
        }
        if (type === 'suggested' && Array.isArray(data.questions)) {
          setSuggestedQuestions(data.questions as string[]);
        }
      },
    }, currentConvId || undefined, 'web', chatMode, historyText, authToken);
    abortRef.current?.abort(); abortRef.current = controller;
  }, [isStreaming, skillId, messages, skillInfo, currentConvId, chatMode, authToken, onLimit]);

  const handleQaStart = useCallback((sceneTag: string) => {
    setChatMode('qa');
    setModeSelected(true);
    setQaSceneContext(sceneTag || '');
    setMessages([]);
    if (sceneTag) {
      fetchRecommendedQuestions(skillId, sceneTag, authToken)
        .then(qs => setContextQuestions(Array.isArray(qs) ? qs : []))
        .catch(() => setContextQuestions([]));
    }
  }, [skillId, setChatMode, authToken]);

  const handleTalkStart = useCallback(() => {
    setChatMode('talk');
    setModeSelected(true);
    setMessages([]);
    setQaSceneContext('');
    // 加载 Talk 模式推荐问题（不自动发消息，等用户主动开口）
    fetchRecommendedQuestions(skillId, undefined, authToken)
      .then(qs => setContextQuestions(Array.isArray(qs) ? qs : []))
      .catch(() => setContextQuestions([]));
  }, [skillId, setChatMode, setModeSelected, authToken]);

  const handleBackToModes = useCallback(() => {
    abortRef.current?.abort();
    setModeSelected(false);
    setMessages([]);
    dispatchStream(null);
    setIsStreaming(false);
    setQaSceneContext('');
    setContextQuestions([]);
    onResetPracticeRef.current();
  }, [setModeSelected]);

  const handleQaModeSelect = useCallback(() => {
    setChatMode('qa');
    setModeSelected(true);
    setMessages([]);
  }, [setChatMode, setModeSelected]);

  const clearContext = useCallback(() => {
    setQaSceneContext('');
    setContextQuestions([]);
    setMessages([]);
  }, []);

  const handleQuestionClick = useCallback((question: string) => {
    sendMessageImmediate(question);
  }, [sendMessageImmediate]);

  /**
   * 用户评分 —— 传递完整上下文（提问/AI回答/RAG分数）供管理员审查。
   * grain_id 允许为空（RAG 无匹配时用户仍可打分）。
   */
  const handleFeedback = useCallback((msgId: string, grainId: string, helpful: boolean) => {
    setFeedbackState((prev) => ({ ...prev, [msgId]: helpful ? 'up' : 'down' }));
    // 找到对应的用户消息和 AI 消息
    const msg = messages.find(m => m.id === msgId);
    if (!msg) return;
    const userMsg = messages.filter(m => m.role === 'user').pop();
    submitFeedback({
      sessionId: '', grainId: grainId || undefined as any, helpful,
      skillId,
      conversationId: currentConvId || '',
      query: userMsg?.content || '',
      aiResponse: (msg.content || '').substring(0, 500),
      ragScore: msg.avgSimilarity ? Number(msg.avgSimilarity) / 100 : undefined,
      messageId: msgId,
    }, authToken);
  }, [skillId, messages, currentConvId, authToken]);

  return {
    stop: () => { abortRef.current?.abort(); },
    sceneTags, setSceneTags,
    messages, setMessages,
    inputValue, setInputValue,
    isStreaming, setIsStreaming,
    qaStreamText,
    suggestedQuestions, setSuggestedQuestions,
    feedbackState, setFeedbackState,
    qaSceneContext, setQaSceneContext,
    contextQuestions, setContextQuestions,
    conversations, currentConvId, setCurrentConvId, showHistory, setShowHistory,
    loadConversations, switchConversation, handleDeleteConversation,
    handleQaSend, sendMessageImmediate,
    handleQaStart, handleTalkStart,
    handleBackToModes,
    handleQaModeSelect,
    clearContext, handleQuestionClick, handleFeedback,
  };
}
