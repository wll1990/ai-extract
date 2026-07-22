'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { SkillModeSelector } from '@/components/skill/SkillModeSelector';
import { SkillOpeningView } from '@/components/skill/SkillOpeningView';
import { SkillChatView } from '@/components/skill/SkillChatView';
import RecommendedQuestions from '@/components/skill/RecommendedQuestions';
import HistorySidebar from '@/components/skill/HistorySidebar';
import { API_BASE } from '@/lib/api/client';
import PracticeChatSection from './PracticeChatSection';
import { useQaChat } from './hooks/useQaChat';
import ShareModal from '@/components/admin/ShareModal';
import { TraceabilityDrawer } from '@/components/skill/TraceabilityDrawer';
import SceneTagBar from '@/components/skill/SceneTagBar';
import { getOrCreateSkillShare, toggleSkillShare } from '@/lib/api/skill';
import { TrustBadge, MODE_GUIDE, TALK_NAME_CARD } from '@aiextract/shared-ui';

type ChatMode = 'qa' | 'talk' | 'practice';

export default function SkillChatPage() {
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const skillId = (params.skillId as string) || '';
  const ownerName = searchParams.get('name') || 'AI分身';
  const ownerTitle = searchParams.get('title') || '';
  const initial = (ownerName || '?')[0];

  const [chatMode, setChatMode] = useState<ChatMode>('talk');
  const [modeSelected, setModeSelected] = useState(true);
  const [shareTarget, setShareTarget] = useState(false);

  const practiceAbortRef = useRef<AbortController | null>(null);
  const [practiceSceneTag, setPracticeSceneTag] = useState<string | undefined>(undefined);
  const [practiceKey, setPracticeKey] = useState(0);
  const resetPractice = useCallback(() => { setPracticeKey(k => k + 1); setPracticeSceneTag(undefined); }, []);

  const startPracticeWithScene = useCallback((tag: string) => {
    setPracticeSceneTag(tag);
    setChatMode('practice');
    setModeSelected(true);
  }, []);

  const onResetPracticeRef = useRef<() => void>(resetPractice);
  onResetPracticeRef.current = resetPractice;

  const [openingMessage, setOpeningMessage] = useState<string | null>(null);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [detailFetched, setDetailFetched] = useState(false);
  const [traceGrainIds, setTraceGrainIds] = useState('');

  useEffect(() => {
    if (!skillId) return;
    fetch(`${API_BASE}/skills/${skillId}/detail`, { credentials: 'include' })
      .then(r => r.json())
      .then(d => {
        if (d?.data?.openingMessage) setOpeningMessage(d.data.openingMessage);
        if (d?.data?.avatarUrl) setAvatarUrl(d.data.avatarUrl);
      })
      .catch(() => {})
      .finally(() => setDetailFetched(true));
  }, [skillId]);

  const qa = useQaChat({ skillId, skillInfo: { ownerName, ownerTitle, ownerQuote: '' }, chatMode, setChatMode, setModeSelected, onResetPracticeRef });

  useEffect(() => { return () => { qa.stop(); practiceAbortRef.current?.abort(); }; }, []);

  // ── 自动进入 Talk 模式 ──
  useEffect(() => {
    if (skillId && chatMode === 'talk') {
      qa.handleTalkStart();
    }
  }, [skillId]);

  return (
    <div className="flex h-screen bg-surface-2">
      {/* ── 侧边栏：历史 ── */}
      {qa.showHistory && (
        <HistorySidebar conversations={qa.conversations} currentConvId={qa.currentConvId}
          onClose={() => qa.setShowHistory(false)}
          onSwitch={(id) => { qa.switchConversation(id); if (chatMode === 'practice') { setChatMode('qa'); resetPractice(); } }}
          onDelete={qa.handleDeleteConversation}
          onNew={() => { qa.setCurrentConvId(''); qa.setMessages([]); if (chatMode === 'practice') { setChatMode('qa'); resetPractice(); } }}
        />
      )}

      {/* ── 主区域 ── */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* 顶栏 */}
        <header className="flex items-center gap-3 px-5 py-3 border-b border-border bg-surface-2 flex-shrink-0">
          <div className="w-8 h-8 rounded-full bg-gradient-to-br from-navy to-primary flex items-center justify-center text-xs font-semibold text-white flex-shrink-0 overflow-hidden shadow-sm">
            {initial}
          </div>

          <div className="min-w-0 leading-tight">
            <p className="text-sm font-semibold text-foreground truncate">{ownerName}</p>
            {ownerTitle && <p className="text-[11px] text-muted-foreground-2 truncate">{ownerTitle}</p>}
          </div>

          <div className="flex-1" />

          {/* 模式 tabs（Practice 模式不显示，它有独立 UI） */}
          {chatMode !== 'practice' && (
            <div className="flex items-center gap-0.5 bg-surface rounded-lg p-0.5">
              {[
                { key: 'qa' as ChatMode, icon: '💬', label: '经验请教' },
                { key: 'talk' as ChatMode, icon: '☕', label: '轻松交流' },
              ].map(m => (
                <button key={m.key}
                  onClick={() => { qa.setMessages([]); qa.setCurrentConvId(''); setChatMode(m.key); if (m.key === 'talk') qa.handleTalkStart(); if (m.key === 'qa') qa.handleQaModeSelect(); }}
                  className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors ${
                    chatMode === m.key ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground-2 hover:text-foreground'
                  }`}>
                  {m.icon} {m.label}
                </button>
              ))}
            </div>
          )}

          <button onClick={() => setShareTarget(true)}
            className="text-muted-foreground-2 hover:text-primary text-sm transition-colors" title="分享">🔗</button>
          <button onClick={() => { qa.setShowHistory(!qa.showHistory); if (!qa.showHistory) qa.loadConversations(); }}
            className="text-muted-foreground-2 hover:text-foreground text-sm transition-colors">📋</button>
        </header>

        {/* 内容区 */}
        <div className="flex-1 overflow-auto">
          {/* ── Practice 模式 ── */}
          {chatMode === 'practice' && !practiceSceneTag && (
            <SkillOpeningView ownerName={ownerName} ownerTitle={ownerTitle} ownerIntro={ownerTitle} sceneTags={qa.sceneTags}
              defaultMode="practice" onQaStart={qa.handleQaStart} onPracticeStart={(tag) => startPracticeWithScene(tag)} />
          )}

          {chatMode === 'practice' && practiceSceneTag && (
            <PracticeChatSection key={practiceKey} skillId={skillId}
              initialSceneTag={practiceSceneTag} setChatMode={setChatMode} abortRef={practiceAbortRef} />
          )}

          {/* ── QA / Talk 模式 ── */}
          {(chatMode === 'qa' || chatMode === 'talk') && (
            <SkillChatView inputValue={qa.inputValue} onInputChange={qa.setInputValue} onSend={qa.handleQaSend}
              isStreaming={qa.isStreaming} streamText={qa.qaStreamText} ownerName={ownerName}
              placeholder={chatMode === 'talk' ? '聊聊你的想法...' : '问我任何销售问题...'}
              footer={
                <div className="flex items-center justify-center gap-2">
                  <span className="text-[11px] text-muted-foreground-2">按 Enter 发送，Shift+Enter 换行</span>
                </div>
              }>
              <div className="mx-auto max-w-[720px] space-y-4 px-4">

                {/* ── QA 未选场景时：场景 pill 条 ── */}
                {chatMode === 'qa' && qa.messages.length === 0 && !qa.qaSceneContext && qa.sceneTags.length > 0 && (
                  <div className="pt-6">
                    <p className="text-xs text-muted-foreground text-center mb-2">擅长领域</p>
                    <SceneTagBar sceneTags={qa.sceneTags} activeTag="" chatMode="qa"
                      onQaTagClick={qa.handleQaStart}
                      onTalkTagClick={() => {}} />
                  </div>
                )}

                {/* ── QA 场景上下文标签（含上/下一场景翻页） ── */}
                {qa.messages.length === 0 && qa.qaSceneContext && (() => {
                  const sorted = [...qa.sceneTags].sort((a, b) => (b.count || 0) - (a.count || 0));
                  const curIdx = sorted.findIndex(s => s.tag === qa.qaSceneContext);
                  const prevTag = curIdx > 0 ? sorted[curIdx - 1].tag : null;
                  const nextTag = curIdx >= 0 && curIdx < sorted.length - 1 ? sorted[curIdx + 1].tag : null;
                  return (
                    <div className="pt-12 pb-4 text-center">
                      <div className="inline-flex items-center gap-2">
                        {prevTag ? (
                          <button onClick={() => qa.handleQaStart(prevTag)}
                            className="flex items-center gap-1 rounded-full border border-border bg-surface-2 px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:border-primary/30 transition-all"
                            title={`上一场景：${prevTag}`}>
                            ← {prevTag}
                          </button>
                        ) : (
                          <span className="w-20" />
                        )}
                        <div className="inline-flex items-center gap-2 rounded-full border-2 border-primary/30 bg-primary-light px-4 py-2 text-sm shadow-sm">
                          <span className="text-primary font-medium">{qa.qaSceneContext}</span>
                          <button onClick={qa.clearContext} className="text-muted-foreground-2 hover:text-foreground ml-1 transition-colors">✕</button>
                        </div>
                        {nextTag ? (
                          <button onClick={() => qa.handleQaStart(nextTag)}
                            className="flex items-center gap-1 rounded-full border border-border bg-surface-2 px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:border-primary/30 transition-all"
                            title={`下一场景：${nextTag}`}>
                            {nextTag} →
                          </button>
                        ) : (
                          <span className="w-20" />
                        )}
                      </div>
                      <RecommendedQuestions questions={qa.contextQuestions} onQuestionClick={qa.handleQuestionClick} />
                    </div>
                  );
                })()}

                {/* ── Hero 区 — detailFetched 后一次性渲染，防布局抖动 ── */}
                {qa.messages.length === 0 && detailFetched && (
                  <>
                    {/* ① 名片卡片 — Talk 独有：头像+文案+信任卡片，一整块 */}
                    {chatMode === 'talk' && (
                      <div className="animate-[messageArrive_500ms_ease-out] rounded-3xl bg-white py-7 px-7" style={{ boxShadow: '0 4px 24px rgba(0,0,0,0.05)' }}>
                        {/* 头部：左头像 + 右文案 */}
                        <div className="flex items-start gap-6 mb-5">
                          <div className="w-[80px] h-[80px] rounded-full bg-gradient-to-br from-blue-100 to-blue-50 ring-2 ring-blue-100/50 flex items-center justify-center text-[32px] font-bold text-[#2563EB] shadow-sm flex-shrink-0 overflow-hidden">
                            {avatarUrl ? (
                              <img src={avatarUrl} alt={ownerName} className="w-full h-full object-cover" />
                            ) : initial}
                          </div>
                          <div className="flex-1 pt-1">
                            {/* 第一行：你好我是 + 名字蓝色 */}
                            <h3 className="text-[19px] font-bold text-foreground leading-tight">
                              {TALK_NAME_CARD.greeting}<span className="text-[#2563EB]">{ownerName}</span><span className="text-base ml-0.5">✨</span>
                            </h3>
                            {/* 第二行：定位标签 */}
                            <span className="inline-block mt-2 text-[13px] text-[#64748B] bg-[#f1f5f9] rounded-full px-3 py-1">
                              {TALK_NAME_CARD.roleTag}
                            </span>
                            {/* 第三行：价值主张，关键词红色 */}
                            <p className="mt-3 text-[14px] text-foreground/85 leading-relaxed whitespace-pre-wrap">
                              {TALK_NAME_CARD.valueProp.split(TALK_NAME_CARD.valuePropHighlight).map((part, i, arr) =>
                                i < arr.length - 1
                                  ? <React.Fragment key={i}>{part}<span className="text-[#DC2626] font-medium">{TALK_NAME_CARD.valuePropHighlight}</span></React.Fragment>
                                  : <React.Fragment key={i}>{part}</React.Fragment>
                              )}
                            </p>
                            {ownerTitle && (
                              <p className="text-[13px] text-muted-foreground mt-2.5 font-medium">{ownerTitle}</p>
                            )}
                          </div>
                        </div>

                        {/* 分隔 + 信任卡片 */}
                        <div className="pt-4 border-t border-[#E8ECF1]/40">
                          <TrustBadge />
                        </div>
                      </div>
                    )}

                    {/* QA 模式 — 开场白气泡 */}
                    {chatMode === 'qa' && openingMessage && (
                      <div className="flex justify-start animate-[messageArrive_400ms_ease-out]">
                        <div className="max-w-[75%] rounded-2xl rounded-bl-md bg-primary-light px-5 py-3.5">
                          <p className="text-xs text-muted-foreground mb-1">{ownerName}</p>
                          <p className="text-sm text-foreground whitespace-pre-wrap leading-relaxed">{openingMessage}</p>
                        </div>
                      </div>
                    )}

                    {/* ② 引导语气泡 — 带头像的聊天气泡 */}
                    {chatMode === 'talk' && (
                      <div className="flex items-start gap-2.5 animate-[messageArrive_400ms_ease-out_500ms] opacity-0 [animation-fill-mode:forwards]">
                        <div className="w-7 h-7 rounded-full bg-gradient-to-br from-navy to-primary flex items-center justify-center text-[11px] font-semibold text-white flex-shrink-0 shadow-sm mt-0.5">
                          {initial}
                        </div>
                        <div className="max-w-[78%] rounded-2xl rounded-tl-sm bg-[#f8f7ff] border border-[#e8e6ff] px-4 py-3">
                          <p className="text-xs text-muted-foreground mb-1">{ownerName}</p>
                          <p className="text-sm text-foreground whitespace-pre-wrap leading-relaxed">
                            {MODE_GUIDE.talk}
                          </p>
                        </div>
                      </div>
                    )}

                    {/* ③ 精选话题 — 逐条浮现 */}
                    {chatMode === 'talk' && qa.contextQuestions.length > 0 && (
                      <div className="animate-[messageArrive_350ms_ease-out_1000ms] opacity-0 [animation-fill-mode:forwards]">
                        <RecommendedQuestions questions={qa.contextQuestions} onQuestionClick={qa.handleQuestionClick}
                          label="精选话题" />
                      </div>
                    )}
                  </>
                )}

                {/* ── 骨架屏 ── */}
                {qa.messages.length === 0 && !detailFetched && (
                  <div className="min-h-[320px] flex items-center justify-center">
                    <div className="w-8 h-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                  </div>
                )}

                {/* ── 聊天气泡 ── */}
                {qa.messages.map((msg) => {
                  const isAi = msg.role === 'ai' || msg.role === 'assistant';
                  const sim = msg.avgSimilarity ? Number(msg.avgSimilarity) : 0;
                  const matchLevel = sim >= 50 ? 'precise' : sim >= 30 ? 'related' : 'synthetic';
                  const hasTrace = !!(msg.grainTags && msg.grainCount);
                  // 拆分正文和溯源分析区
                  const sepIndex = (msg.content || '').indexOf('━━━━━━');
                  const mainText = sepIndex >= 0 ? (msg.content || '').substring(0, sepIndex).trim() : (msg.content || '');
                  const sourceText = sepIndex >= 0 ? (msg.content || '').substring(sepIndex + 6).trim() : '';

                  return (
                    <div key={msg.id}>
                      {isAi ? (
                        <div className="flex items-start gap-2.5">
                          <div className="w-7 h-7 rounded-full bg-gradient-to-br from-navy to-primary flex items-center justify-center text-[11px] font-semibold text-white flex-shrink-0 shadow-sm mt-0.5">
                            {ownerName[0]}
                          </div>
                          <div className="max-w-[80%]">
                            {/* 主气泡 */}
                            <div className="rounded-2xl rounded-tl-sm bg-white border border-[#E8ECF1] px-4 py-3 text-sm text-[#1A1D23] leading-relaxed shadow-[0_1px_3px_rgba(0,0,0,0.04)]">
                              {msg.content ? (
                                <div>
                                  {mainText && <p className="whitespace-pre-wrap break-words">{mainText}</p>}
                                  {sourceText && (
                                    <div className={`${mainText ? 'mt-3' : ''} rounded-xl bg-gradient-to-br from-[#F8FAFE] to-[#F1F5FB] border border-[#D7E3F8] overflow-hidden`}>
                                      <div className="flex items-center gap-1.5 px-3 py-2 bg-white/60 border-b border-[#E8EFF9]">
                                        <span className="w-1.5 h-1.5 rounded-full bg-[#2563EB]" />
                                        <span className="text-[11px] font-medium text-[#475569]">经验溯源</span>
                                      </div>
                                      <div className="px-3 py-2.5">
                                        <p className="whitespace-pre-wrap break-words text-[12px] text-[#64748B] leading-relaxed">{sourceText}</p>
                                      </div>
                                    </div>
                                  )}
                                </div>
                              ) : (
                                <span className="inline-flex items-end gap-[3px] h-5">
                                  {[6, 10, 14, 18].map((h, i) => (
                                    <span key={i}
                                      className="w-[3px] rounded-full bg-gradient-to-t from-[#2563EB] to-[#93C5FD]"
                                      style={{ height: `${h}px`, animation: `pulse 0.7s ease-in-out ${i * 0.12}s infinite alternate` }} />
                                  ))}
                                </span>
                              )}
                            </div>

                            {/* 元信息行：匹配度 + 溯源 + 反馈 */}
                            <div className="flex items-center gap-3 mt-1.5 ml-1">
                              {/* 匹配度指示器 */}
                              {matchLevel === 'precise' && (
                                <span className="inline-flex items-center gap-1 rounded-md border border-amber-500/30 px-2 py-0.5 text-[11px] font-medium text-amber-700"
                                  style={{ background: 'linear-gradient(135deg, rgba(201,164,75,0.08), rgba(201,164,75,0.02))' }}>
                                  🏅 精准匹配
                                </span>
                              )}
                              {matchLevel === 'related' && (
                                <span className="inline-flex items-center gap-1 rounded-r-md border-l-2 border-[#8b9dc3] bg-[#f8f9fb] px-2 py-0.5 text-[11px] font-medium text-[#5a6d8a]">
                                  📎 关联匹配
                                </span>
                              )}
                              {matchLevel === 'synthetic' && (
                                <span className="inline-flex items-center gap-1 text-[11px] italic text-[#b0b7c3]">
                                  ✦ 综合画像生成
                                </span>
                              )}

                              {/* 溯源按钮 — synthetic 不展示 */}
                              {hasTrace && matchLevel !== 'synthetic' && (
                                <button onClick={() => setTraceGrainIds(msg.grainIds || msg.grainId || '')}
                                  className="inline-flex items-center gap-1 text-[11px] text-[#94A3B8] hover:text-[#64748B] transition-colors">
                                  溯源 · {msg.grainCount} 条 →
                                </button>
                              )}

                              <div className="flex-1" />

                              {/* 反馈 */}
                              <div className="flex items-center gap-1">
                                <button onClick={() => qa.handleFeedback(msg.id, msg.grainId || '', true)}
                                  className={`inline-flex items-center gap-0.5 text-[11px] px-1.5 py-0.5 rounded transition-colors ${
                                    qa.feedbackState[msg.id] === 'up'
                                      ? 'text-[#16A34A] bg-[#F0FDF4]'
                                      : 'text-[#94A3B8] hover:text-[#64748B] hover:bg-[#F1F5F9]'
                                  }`}>👍</button>
                                <button onClick={() => qa.handleFeedback(msg.id, msg.grainId || '', false)}
                                  className={`inline-flex items-center gap-0.5 text-[11px] px-1.5 py-0.5 rounded transition-colors ${
                                    qa.feedbackState[msg.id] === 'down'
                                      ? 'text-[#DC2626] bg-[#FEF2F2]'
                                      : 'text-[#94A3B8] hover:text-[#64748B] hover:bg-[#F1F5F9]'
                                  }`}>👎</button>
                              </div>
                            </div>

                          </div>
                        </div>
                      ) : (
                        <div className="flex justify-end gap-2">
                          <div className="max-w-[80%] rounded-2xl rounded-tr-sm bg-[#2563EB] text-white px-4 py-2.5 text-sm leading-relaxed shadow-[0_1px_3px_rgba(37,99,235,0.15)]">
                            <p className="whitespace-pre-wrap break-words">{msg.content}</p>
                          </div>
                          <div className="mt-0.5 flex h-7 w-7 flex-none items-center justify-center rounded-full shadow-sm"
                            style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}>
                            <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" strokeLinecap="round" className="h-3.5 w-3.5">
                              <circle cx="12" cy="8" r="4" /><path d="M4 20c0-4 4-7 8-7s8 3 8 7" />
                            </svg>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}

                {/* 流式文本 */}
                {qa.qaStreamText && (
                  <div className="flex items-start gap-2.5">
                    <div className="w-7 h-7 rounded-full bg-gradient-to-br from-navy to-primary flex items-center justify-center text-[11px] font-semibold text-white flex-shrink-0 shadow-sm mt-0.5">
                      {ownerName[0]}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="rounded-2xl rounded-tl-sm bg-white border border-[#E8ECF1] px-4 py-3 text-sm text-[#1A1D23] leading-relaxed shadow-[0_1px_3px_rgba(0,0,0,0.04)]">
                        <p className="whitespace-pre-wrap break-words">{qa.qaStreamText}
                          <span className="ml-0.5 inline-block w-0.5 h-4 rounded-full bg-[#2563EB] align-text-bottom animate-pulse" />
                        </p>
                      </div>
                    </div>
                  </div>
                )}

                {/* RAG 无匹配推荐问题（仅后端推送时显示） */}
                <RecommendedQuestions questions={qa.suggestedQuestions}
                  onQuestionClick={(q) => { qa.setSuggestedQuestions([]); qa.sendMessageImmediate(q); }}
                  label="🤔 这个问题我暂时不太了解，试试这些" max={3} />
              </div>
            </SkillChatView>
          )}
        </div>

        {/* 分享弹窗 */}
        {shareTarget && (
          <ShareModal skillId={skillId} ownerName={ownerName} onClose={() => setShareTarget(false)}
            getOrCreate={getOrCreateSkillShare} toggleShare={toggleSkillShare} />
        )}

        {/* 溯源抽屉 */}
        {traceGrainIds && (
          <TraceabilityDrawer grainIds={traceGrainIds} open={!!traceGrainIds}
            onClose={() => setTraceGrainIds('')} />
        )}

        {/* Practice 底部返回按钮 */}
        {chatMode === 'practice' && (
          <div className="flex-shrink-0 px-4 py-2 border-t border-border text-center">
            <button onClick={() => { setChatMode('talk'); resetPractice(); }}
              className="text-xs text-muted-foreground-2 hover:text-foreground transition-colors">↩ 返回对话</button>
          </div>
        )}
      </div>
    </div>
  );
}
