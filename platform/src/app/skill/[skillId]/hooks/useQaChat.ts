/**
 * [平台端副本]
 * 本文件从 frontend 复制而来。
 * 设计决策：B端(8089)和平台端(8088)各自独立，互不引用对方 URL。
 * 两个 App 各自维护一份副本。
 *
 * 维护约定：
 * - 如果两端需要相同改动 → 抽到 @aiextract/shared-ui 共享库
 * - 如果各自有不同需求 → 独立演进，不改对方
 *
 * 原始文件: frontend 对应路径
 * 复制日期: 2026-08-02
 */


'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { flushSync } from 'react-dom';
import {
  chat, submitFeedback, fetchRecommendedQuestions, fetchSceneTags, type SceneTagCount,
} from '@/lib/api/skill';
import { useConversations } from './useConversations';

/** 后端未给文案时按错误码兜底 */
function aiErrorFallback(code?: string): string {
  switch (code) {
    case 'AI_KEY_INVALID':
    case 'AI_KEY_FORBIDDEN':
      return '模型 Key 配置有误，请联系管理员检查模型配置';
    case 'AI_QUOTA_EXCEEDED':
      return '模型额度已用尽，请联系管理员充值或更换 Key';
    case 'AI_RATE_LIMITED':
      return '调用过于频繁，请稍后重试';
    case 'AI_TIMEOUT':
      return '模型响应超时，请稍后重试';
    default:
      return 'AI服务暂时不可用，请稍后重试';
  }
}

interface SkillInfo {
  ownerName?: string;
  ownerTitle?: string;
  ownerQuote?: string;
}

export type ChatMode = 'qa' | 'talk' | 'practice';

interface Message {
  id: string; role: string; content: string; type?: string;
  source?: string; grainIds?: string; grainId?: string; reportId?: string;
  grainTags?: string; grainCount?: number; avgScore?: string;
  avgSimilarity?: string;
  knowledgeSources?: Array<{ title: string; source: string; similarity: string }>;
  caseSources?: Array<{ title: string; source: string; similarity: string }>;
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
  /** 游客分享页：未领证前不发起需要认证的后台请求 */
  guestMode?: boolean;
  /** 每日额度用尽（后端 limit 事件）：code 区分 GUEST_LIMIT_REACHED / USER_LIMIT_REACHED */
  onLimit?: (info: { code: string; used: number; limit: number; pendingText: string }) => void;
}

export function useQaChat({
  skillId, skillInfo, chatMode,
  setChatMode, setModeSelected, onResetPracticeRef,
  authToken, guestMode, onLimit,
}: QaChatInputs) {
  const abortRef = useRef<AbortController | null>(null);

  // 问答
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [qaStreamText, setQaStreamText] = useState('');
  const [feedbackState, setFeedbackState] = useState<Record<string, 'up' | 'down' | null>>({});

  // QA 场景上下文
  const [qaSceneContext, setQaSceneContext] = useState<string>('');
  const [contextQuestions, setContextQuestions] = useState<string[]>([]);
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([]);

  // 场景标签
  const [sceneTags, setSceneTags] = useState<SceneTagCount[]>([]);

  useEffect(() => {
    // 分享落地页尚无游客身份：不发起需要认证的后台请求，避免 401 误触发注册抽屉
    if (!skillId || (guestMode && !authToken)) return;
    // 无 token 时不带 Authorization，避免 `Bearer undefined` 顶掉 Cookie；
    // 失败只降级为空标签，不把用户踢出当前页面。
    fetchSceneTags(skillId, authToken)
      .then(tags => setSceneTags(Array.isArray(tags) ? tags : []))
      .catch(() => {});
  }, [skillId, authToken, guestMode]);

  // 会话历史（内嵌 useConversations）
  const msgSetter = {
    setMessages: (msgs: Array<{id:string;role:string;content:string;source:string;grainId:string}>) =>
      setMessages(msgs as Message[]),
    clearMessages: () => setMessages([]),
  };
  const { conversations, currentConvId, setCurrentConvId, showHistory, setShowHistory,
    loadConversations, switchConversation, handleDeleteConversation } = useConversations(
      skillId, chatMode, msgSetter, authToken, guestMode);

  const handleQaSend = useCallback((overrideText?: string) => {
    const text = (overrideText ?? inputValue).trim();
    if (!text || isStreaming || !skillId) return;

    setSuggestedQuestions([]);  // 新消息 → 清掉上一轮的推荐

    const ownerName = skillInfo?.ownerName || '销冠';
    const historyText = currentConvId ? undefined : messages.slice(-20).map(m =>
      `${m.role === 'user' ? '销售员' : ownerName}：${m.content}`
    ).join('\n');

    const userMsg: Message = { id: `u-${Date.now()}`, role: 'user', content: text };
    setMessages((prev) => [...prev, userMsg]);
    setInputValue('');
    setIsStreaming(true);
    setQaStreamText('');

    const aiMsgId = `a-${Date.now()}`;
    let sourceInfo: any = {};
    const knowledgeSources: Array<{ title: string; source: string; similarity: string }> = [];
    const caseSources: Array<{ title: string; source: string; similarity: string }> = [];

    let fullContent = '';
    const clientMessageId = crypto.randomUUID();
    const controller = chat(skillId, text, {
      onChunk: (content) => {
        fullContent += content;
        flushSync(() => setQaStreamText(fullContent));
      },
      onSource: (reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity, sourceNames) => {
        sourceInfo = { reportId, grainIds: grainIds || '', grainId: (grainIds || '').split(',')[0], source: reportTitle || '', grainTags, grainCount, avgScore, avgSimilarity, sourceNames };
      },
      onKnowledgeSource: (title, source, similarity) => {
        knowledgeSources.push({ title, source, similarity });
      },
      onCaseSource: (title, source, similarity) => {
        caseSources.push({ title, source, similarity });
      },
      onMeta: (conversationId) => {
        if (conversationId && !currentConvId) setCurrentConvId(conversationId);
      },
      onDone: () => {
        setQaStreamText('');
        setMessages((prev) => [...prev, {
          id: aiMsgId, role: 'ai', content: fullContent, ...sourceInfo,
          ...(knowledgeSources.length ? { knowledgeSources } : {}),
          ...(caseSources.length ? { caseSources } : {}),
        }]);
        setIsStreaming(false);
        loadConversations();
      },
      onError: (msg, code) => {
        setIsStreaming(false);
        setMessages((prev) => prev.filter((m) => m.id !== aiMsgId));
        setMessages((prev) => [...prev, {
          id: `err-${Date.now()}`, role: 'ai',
          content: `⚠️ ${msg || aiErrorFallback(code)}`,
        }]);
      },
      // RAG 无匹配时后端推送推荐问题，前端接收并展示可点击按钮；
      // limit = 游客免费额度用尽：撤回乐观气泡，交给页面弹注册抽屉（注册后重发 pendingText）
      onEvent: (type, data) => {
        if (type === 'limit') {
          setIsStreaming(false);
          setQaStreamText('');
          setMessages((prev) => prev.filter((m) => m.id !== userMsg.id));
          onLimit?.({ code: String(data.code ?? 'GUEST_LIMIT_REACHED'), used: Number(data.used ?? 0), limit: Number(data.limit ?? 0), pendingText: text });
          return;
        }
        if (type === 'suggested' && Array.isArray(data.questions)) {
          setSuggestedQuestions(data.questions as string[]);
        }
      },
    }, currentConvId || undefined, 'web', chatMode, historyText, authToken, clientMessageId);
    abortRef.current?.abort(); abortRef.current = controller;
  }, [inputValue, isStreaming, skillId, messages, skillInfo, currentConvId, chatMode, authToken, onLimit]);

  const sendMessageImmediate = useCallback((text: string) => {
    if (isStreaming || !skillId) return;
    setInputValue('');
    setSuggestedQuestions([]);
    const ownerName = skillInfo?.ownerName || '销冠';
    const historyText = currentConvId ? undefined : messages.slice(-20).map(m =>
      `${m.role === 'user' ? '销售员' : ownerName}：${m.content}`
    ).join('\n');

    const userMsg: Message = { id: `u-${Date.now()}`, role: 'user', content: text };
    setMessages((prev) => [...prev, userMsg]);
    setIsStreaming(true);
    setQaStreamText('');

    let fullContent = '';
    let sourceInfo: any = {};
    const knowledgeSources: Array<{ title: string; source: string; similarity: string }> = [];
    const caseSources: Array<{ title: string; source: string; similarity: string }> = [];
    const clientMessageId = crypto.randomUUID();
    const controller = chat(skillId, text, {
      onChunk: (content) => {
        fullContent += content;
        flushSync(() => setQaStreamText(fullContent));
      },
      onSource: (reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity, sourceNames) => {
        sourceInfo = { reportId, grainIds: grainIds || '', grainId: (grainIds || '').split(',')[0], source: reportTitle || '', grainTags, grainCount, avgScore, avgSimilarity, sourceNames };
      },
      onKnowledgeSource: (title, source, similarity) => {
        knowledgeSources.push({ title, source, similarity });
      },
      onCaseSource: (title, source, similarity) => {
        caseSources.push({ title, source, similarity });
      },
      onMeta: (conversationId) => {
        if (conversationId && !currentConvId) setCurrentConvId(conversationId);
      },
      onDone: () => {
        const aiMsgId = `a-${Date.now()}`;
        setMessages((prev) => [...prev, {
          id: aiMsgId, role: 'ai', content: fullContent, ...sourceInfo,
          ...(knowledgeSources.length ? { knowledgeSources } : {}),
          ...(caseSources.length ? { caseSources } : {}),
        }]);
        setQaStreamText('');
        setIsStreaming(false);
        loadConversations();
      },
      onError: () => {
        setQaStreamText('');
        setIsStreaming(false);
      },
      onEvent: (type, data) => {
        if (type === 'limit') {
          setIsStreaming(false);
          setMessages((prev) => prev.filter((m) => m.id !== userMsg.id));
          onLimit?.({ code: String(data.code ?? 'GUEST_LIMIT_REACHED'), used: Number(data.used ?? 0), limit: Number(data.limit ?? 0), pendingText: text });
          return;
        }
        if (type === 'suggested' && Array.isArray(data.questions)) {
          setSuggestedQuestions(data.questions as string[]);
        }
      },
    }, currentConvId || undefined, 'web', chatMode, historyText, authToken, clientMessageId);
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
    setQaStreamText('');
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
    }, authToken).catch(() => {
      // 请求失败回滚乐观更新
      setFeedbackState((prev) => ({ ...prev, [msgId]: null }));
    });
  }, [skillId, messages, currentConvId, authToken]);

  return {
    stop: () => { abortRef.current?.abort(); },
    sceneTags, setSceneTags,
    messages, setMessages,
    inputValue, setInputValue,
    isStreaming, setIsStreaming,
    qaStreamText, setQaStreamText,
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
