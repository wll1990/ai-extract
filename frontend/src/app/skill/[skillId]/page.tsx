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
import { getOrCreateSkillShare, toggleSkillShare } from '@/lib/api/skill';

type ChatMode = 'qa' | 'talk' | 'practice';

export default function SkillChatPage() {
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const skillId = (params.skillId as string) || '';
  const ownerName = searchParams.get('name') || 'AI分身';
  const ownerTitle = searchParams.get('title') || '';
  const initial = (ownerName || '?')[0];

  const [chatMode, setChatMode] = useState<ChatMode>('qa');
  const [modeSelected, setModeSelected] = useState(false);
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

  const [openTraces, setOpenTraces] = useState<Record<string, boolean>>({});
  const [openingMessage, setOpeningMessage] = useState<string | null>(null);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!skillId) return;
    fetch(`${API_BASE}/skills/${skillId}/detail`, { credentials: 'include' })
      .then(r => r.json())
      .then(d => {
        if (d?.data?.openingMessage) setOpeningMessage(d.data.openingMessage);
        if (d?.data?.avatarUrl) setAvatarUrl(d.data.avatarUrl);
      })
      .catch(() => {});
  }, [skillId]);
  const toggleTrace = (msgId: string) => setOpenTraces(prev => ({ ...prev, [msgId]: !prev[msgId] }));

  const qa = useQaChat({ skillId, skillInfo: { ownerName, ownerTitle, ownerQuote: '' }, chatMode, setChatMode, setModeSelected, onResetPracticeRef });

  useEffect(() => { return () => { qa.abortRef.current?.abort(); practiceAbortRef.current?.abort(); }; }, []);

  // ── 模式入口（未选模式时显示三卡片选择器） ──
  if (!modeSelected) {
    return (
      <div className="flex h-screen bg-surface-2">
        <div className="flex-1 flex flex-col min-w-0 overflow-auto">
          <SkillModeSelector
            skillId={skillId}
            ownerName={ownerName}
            ownerTitle={ownerTitle}
            openingMessage={openingMessage}
            avatarUrl={avatarUrl}
            onTalkStart={qa.handleTalkStart}
            onQaStart={qa.handleQaModeSelect}
            onPracticeStart={() => { setChatMode('practice'); setModeSelected(true); }}
          />
        </div>
      </div>
    );
  }

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
          <button onClick={qa.handleBackToModes} className="text-muted-foreground-2 hover:text-foreground text-lg leading-none transition-colors">←</button>

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
                { key: 'qa' as ChatMode, icon: '💬', label: '请教专家' },
                { key: 'talk' as ChatMode, icon: '☕', label: '自由对话' },
              ].map(m => (
                <button key={m.key}
                  onClick={() => { setChatMode(m.key); }}
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

                {/* ── QA 场景选择界面 ── */}
                {chatMode === 'qa' && qa.messages.length === 0 && !qa.qaSceneContext && (
                  <SkillOpeningView ownerName={ownerName} ownerTitle={ownerTitle} ownerIntro={ownerTitle} sceneTags={qa.sceneTags}
                    defaultMode="qa" onQaStart={qa.handleQaStart} onPracticeStart={(tag) => startPracticeWithScene(tag)} />
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

                {/* ── 分身开场白 ── */}
                {qa.messages.length === 0 && openingMessage && (chatMode === 'qa' || chatMode === 'talk') && (
                  <div className="flex justify-start animate-[fadeIn_500ms_ease-out]">
                    <div className="max-w-[75%] rounded-2xl rounded-bl-md bg-primary-light px-5 py-3.5">
                      <p className="text-xs text-muted-foreground mb-1">{ownerName}</p>
                      <p className="text-sm text-foreground whitespace-pre-wrap leading-relaxed">{openingMessage}</p>
                    </div>
                  </div>
                )}

                {/* ── 聊天气泡 ── */}
                {qa.messages.map((msg) => {
                  const isAi = msg.role === 'ai' || msg.role === 'assistant';
                  const sim = msg.avgSimilarity ? Number(msg.avgSimilarity) : 0;
                  const matchLevel = sim >= 50 ? 'precise' : sim >= 30 ? 'related' : 'synthetic';
                  const hasTrace = !!(msg.source && msg.grainCount);
                  const traceOpen = !!openTraces[msg.id];
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
                          <div className="flex-1 min-w-0">
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
                                <span className="inline-flex gap-1.5 items-center h-5">
                                  <span className="w-1.5 h-1.5 rounded-full bg-[#CBD5E1] animate-pulse" />
                                  <span className="w-1.5 h-1.5 rounded-full bg-[#CBD5E1] animate-pulse" style={{ animationDelay: '0.2s' }} />
                                  <span className="w-1.5 h-1.5 rounded-full bg-[#CBD5E1] animate-pulse" style={{ animationDelay: '0.4s' }} />
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

                              {/* 溯源展开按钮 — synthetic 不展示 */}
                              {hasTrace && matchLevel !== 'synthetic' && (
                                <button onClick={() => toggleTrace(msg.id)}
                                  className="inline-flex items-center gap-1 text-[11px] text-[#94A3B8] hover:text-[#64748B] transition-colors">
                                  <span className={`transition-transform ${traceOpen ? 'rotate-90' : ''}`}>›</span>
                                  溯源 · {msg.grainCount} 条
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

                            {/* 溯源展开内容 */}
                            {hasTrace && traceOpen && (
                              <div className="mt-1.5 ml-1 rounded-lg bg-[#F8FAFC] border border-[#E8ECF1] px-3 py-2">
                                <div className="flex flex-wrap gap-1">
                                  {msg.grainTags && msg.grainTags.split(',').filter(Boolean).map((tag, i) => (
                                    <span key={i} className="inline-flex items-center gap-1 text-[11px] text-[#2563EB] bg-[#EFF6FF] rounded-full px-2.5 py-0.5">
                                      {tag.trim()}
                                    </span>
                                  ))}
                                </div>
                                {(msg as any).sourceNames && (
                                  <div className="mt-2 text-[11px] text-[#64748B]">
                                    📄 {(msg as any).sourceNames}
                                  </div>
                                )}
                              </div>
                            )}
                          </div>
                        </div>
                      ) : (
                        <div className="flex justify-end">
                          <div className="max-w-[80%] rounded-2xl rounded-tr-sm bg-[#2563EB] text-white px-4 py-2.5 text-sm leading-relaxed shadow-[0_1px_3px_rgba(37,99,235,0.15)]">
                            <p className="whitespace-pre-wrap break-words">{msg.content}</p>
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
                        <p className="whitespace-pre-wrap break-words">{qa.qaStreamText}</p>
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

        {/* Practice 底部返回按钮 */}
        {chatMode === 'practice' && modeSelected && (
          <div className="flex-shrink-0 px-4 py-2 border-t border-border text-center">
            <button onClick={() => { setChatMode('qa'); resetPractice(); }}
              className="text-xs text-muted-foreground-2 hover:text-foreground transition-colors">↩ 返回问答</button>
          </div>
        )}
      </div>
    </div>
  );
}
