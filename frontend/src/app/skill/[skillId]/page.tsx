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
import { getOrCreateSkillShare, toggleSkillShare, createInternalShare } from '@/lib/api/skill';
import { TrustBadge, StatBadge, DefaultAvatar, PortraitCard, ChatAvatar, MODE_GUIDE, TALK_NAME_CARD } from '@aiextract/shared-ui';

type ChatMode = 'qa' | 'talk' | 'practice';

function formatRelativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min} 分钟前`;
  const hours = Math.floor(min / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} 天前`;
  return `${Math.floor(days / 30)} 个月前`;
}

export default function SkillChatPage() {
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const skillId = (params.skillId as string) || '';
  const ownerName = searchParams.get('name') || 'AI分身';
  const ownerTitle = searchParams.get('title') || '';
  const initial = (ownerName || '?')[0];

  const contentRef = useRef<HTMLDivElement>(null);
  const scrollToTop = useCallback(() => {
    contentRef.current?.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
  }, []);

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
  const [skillStats, setSkillStats] = useState<{ conversationCount: number; userCount: number; satisfactionRate: number; lastActive?: string } | null>(null);
  const [grainCount, setGrainCount] = useState(0);
  const [sceneCount, setSceneCount] = useState(0);
  const [skillTags, setSkillTags] = useState<string[]>([]);
  const [traceGrainIds, setTraceGrainIds] = useState('');

  useEffect(() => {
    if (!skillId) return;
    fetch(`${API_BASE}/skills/${skillId}/detail`, { credentials: 'include' })
      .then(r => r.json())
      .then(d => {
        if (d?.data?.openingMessage) setOpeningMessage(d.data.openingMessage);
        setAvatarUrl(d?.data?.avatarUrl || null);
        if (d?.data?.stats) setSkillStats(d.data.stats);
        if (d?.data?.grainCount) setGrainCount(d.data.grainCount);
        if (d?.data?.sceneTags) setSceneCount(d.data.sceneTags.length);
        if (d?.data?.tags) setSkillTags(d.data.tags);
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
        {/* 顶栏 — sticky 固定，滚动时不隐藏 */}
        <header className="flex items-center gap-3 px-5 py-3 border-b border-border bg-surface-2 flex-shrink-0 sticky top-0 z-10">
          <ChatAvatar role="ai" src={avatarUrl || undefined} size={32} />

          <div className="min-w-0 leading-tight">
            <p className="text-sm font-semibold text-foreground truncate">{ownerName}</p>
            {ownerTitle && <p className="text-[11px] text-muted-foreground-2 truncate">{ownerTitle}</p>}
          </div>

          <div className="flex-1" />

          {/* 模式 tabs — 三模式始终可见 */}
          <div className="flex items-center gap-0.5 bg-surface rounded-lg p-0.5">
            {[
              { key: 'qa' as ChatMode, icon: '💬', label: '经验请教' },
              { key: 'talk' as ChatMode, icon: '☕', label: '轻松交流' },
              { key: 'practice' as ChatMode, icon: '🎯', label: '实战演练' },
            ].map(m => (
              <button key={m.key}
                onClick={() => {
                  scrollToTop();
                  if (m.key === 'practice') { setChatMode('practice'); setModeSelected(true); resetPractice(); return; }
                  qa.setMessages([]); qa.setCurrentConvId(''); setChatMode(m.key);
                  if (m.key === 'talk') qa.handleTalkStart(); if (m.key === 'qa') qa.handleQaModeSelect();
                }}
                className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors ${
                  chatMode === m.key ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground-2 hover:text-foreground'
                }`}>
                {m.icon} {m.label}
              </button>
            ))}
          </div>

          <div style={{ position: 'relative' }}>
            <button onClick={() => setShareTarget(!shareTarget)}
              className="text-muted-foreground-2 hover:text-primary text-sm transition-colors" title="分享">🔗</button>
            {shareTarget && (
              <ShareModal skillId={skillId} ownerName={ownerName} onClose={() => setShareTarget(false)}
                getOrCreatePublic={getOrCreateSkillShare} togglePublic={toggleSkillShare}
                getOrCreateInternal={createInternalShare} />
            )}
          </div>
          <button onClick={() => { qa.setShowHistory(!qa.showHistory); if (!qa.showHistory) qa.loadConversations(); }}
            className="text-muted-foreground-2 hover:text-foreground text-sm transition-colors">📋</button>
        </header>

        {/* 内容区 */}
        <div ref={contentRef} className="flex-1 overflow-auto">
          {/* ── Practice 场景选择 Hero ── */}
          {chatMode === 'practice' && !practiceSceneTag && (
            <div className="mx-auto max-w-[720px] space-y-4 px-4 pt-4">
              {/* ① 名片卡片 — 同 QA/Talk */}
              <div className="animate-[messageArrive_500ms_ease-out] rounded-[26px] bg-white py-7 px-[34px] border border-[#e1e7ff] overflow-hidden"
                style={{
                  background: 'radial-gradient(circle at 18% 28%, rgba(65,91,255,.09), transparent 24%), radial-gradient(circle at 80% 10%, rgba(255,77,95,.03), transparent 20%), rgba(255,255,255,.9)',
                  boxShadow: '0 18px 50px rgba(42,74,177,.08), 0 3px 12px rgba(34,55,126,.04)',
                }}>
                <div className="grid grid-cols-[250px_1fr] items-center gap-6 max-sm:grid-cols-1 max-sm:text-center max-sm:gap-2">
                  <PortraitCard src={avatarUrl || undefined} alt={ownerName} />
                  <div>
                    <h3 className="text-[29px] font-bold text-foreground leading-tight" style={{ letterSpacing: '-1px' }}>
                      {TALK_NAME_CARD.greeting}<span className="text-[#2563EB]">{ownerName}</span><span className="text-base ml-0.5">✨</span>
                    </h3>
                    <span className="inline-block mt-2 text-[14px] text-[#64748B] bg-[#f1f5f9] rounded-full px-3 py-1">{TALK_NAME_CARD.roleTag}</span>
                    <p className="mt-3 text-[16px] text-foreground/85 leading-relaxed">
                      已采集 {grainCount > 0 ? grainCount : '...'} 条实战经验
                      {sceneCount > 0 && <>，覆盖 {sceneCount} 个业务场景</>}
                    </p>
                            {skillStats && skillStats.conversationCount > 0 && (
                              <div className="mt-2 flex items-center gap-3">
                                <StatBadge icon="💬" value={skillStats.conversationCount} label="次对话" size="md" />
                                {skillStats.satisfactionRate > 0 && (
                                  <><span className="text-[#d4d8e0] text-sm">·</span>
                                  <StatBadge icon="👍" value={skillStats.satisfactionRate} label="% 满意" size="md" /></>
                                )}
                                {skillStats.userCount > 0 && (
                                  <><span className="text-[#d4d8e0] text-sm">·</span>
                                  <StatBadge icon="👤" value={skillStats.userCount} label="人用过" size="md" /></>
                                )}
                              </div>
                            )}
                  </div>
                </div>
                {skillTags.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {skillTags.slice(0, 4).map(tag => (
                      <span key={tag} className="inline-block rounded-full bg-[#eef2ff] px-2.5 py-0.5 text-[11px] text-[#475569]">
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
                <TrustBadge
                  grainCount={grainCount > 0 ? grainCount : undefined}
                  sceneCount={sceneCount > 0 ? sceneCount : undefined}
                  satisfactionRate={skillStats?.satisfactionRate}
                  lastActive={skillStats?.lastActive ? formatRelativeTime(skillStats.lastActive) : undefined}
                />
              </div>

              {/* ② 引导语气泡 — Practice 专属 */}
              <div className="flex items-start gap-2 animate-[messageArrive_400ms_ease-out_500ms] opacity-0 [animation-fill-mode:forwards]">
                <ChatAvatar role="ai" src={avatarUrl || undefined} size={28} />
                <div className="max-w-[78%] rounded-2xl rounded-tl-sm bg-[#f0fdf4] border border-[#dcfce7] px-4 py-3">
                  <p className="text-xs text-muted-foreground mb-1">{ownerName}</p>
                  <p className="text-sm text-foreground whitespace-pre-wrap leading-relaxed">{MODE_GUIDE.practice}</p>
                </div>
              </div>

              {/* ③ 场景选择 */}
              <SkillOpeningView ownerName={ownerName} ownerTitle={ownerTitle} ownerIntro={ownerTitle} sceneTags={qa.sceneTags}
                defaultMode="practice" onPracticeStart={(tag) => startPracticeWithScene(tag)} />
            </div>
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

                {/* ── Hero 区 — detailFetched 后一次性渲染，防布局抖动 ── */}
                {qa.messages.length === 0 && detailFetched && (
                  <>
                    {/* ① 名片卡片 — QA/Talk 共享 */}
                    {(chatMode === 'qa' || chatMode === 'talk') && (
                      <div className="animate-[messageArrive_500ms_ease-out] rounded-[26px] bg-white py-7 px-[34px] border border-[#e1e7ff] overflow-hidden"
                        style={{
                          background: 'radial-gradient(circle at 18% 28%, rgba(65,91,255,.09), transparent 24%), radial-gradient(circle at 80% 10%, rgba(255,77,95,.03), transparent 20%), rgba(255,255,255,.9)',
                          boxShadow: '0 18px 50px rgba(42,74,177,.08), 0 3px 12px rgba(34,55,126,.04)',
                        }}>
                        {/* 头部：左头像 + 右文案 */}
                        <div className="grid grid-cols-[250px_1fr] items-center gap-6 max-sm:grid-cols-1 max-sm:text-center max-sm:gap-2">
                          <PortraitCard src={avatarUrl || undefined} alt={ownerName} />
                          <div>
                            {/* 第一行：你好我是 + 名字蓝色 */}
                            <h3 className="text-[29px] font-bold text-foreground leading-tight" style={{ letterSpacing: '-1px' }}>
                              {TALK_NAME_CARD.greeting}<span className="text-[#2563EB]">{ownerName}</span><span className="text-base ml-0.5">✨</span>
                            </h3>
                            {/* 第二行：定位标签 */}
                            <span className="inline-block mt-2 text-[14px] text-[#64748B] bg-[#f1f5f9] rounded-full px-3 py-1">
                              {TALK_NAME_CARD.roleTag}
                            </span>
                            {ownerTitle && (
                              <p className="text-[14px] text-muted-foreground mt-2.5 font-medium">{ownerTitle}</p>
                            )}
                            {skillStats && skillStats.conversationCount > 0 && (
                              <div className="mt-2 flex items-center gap-3">
                                <StatBadge icon="💬" value={skillStats.conversationCount} label="次对话" size="md" />
                                {skillStats.satisfactionRate > 0 && (
                                  <><span className="text-[#d4d8e0] text-sm">·</span>
                                  <StatBadge icon="👍" value={skillStats.satisfactionRate} label="% 满意" size="md" /></>
                                )}
                                {skillStats.userCount > 0 && (
                                  <><span className="text-[#d4d8e0] text-sm">·</span>
                                  <StatBadge icon="👤" value={skillStats.userCount} label="人用过" size="md" /></>
                                )}
                              </div>
                            )}
                            {skillTags.length > 0 && (
                              <div className="mt-2 flex flex-wrap gap-1.5">
                                {skillTags.slice(0, 4).map(tag => (
                                  <span key={tag} className="inline-block rounded-full bg-[#eef2ff] px-2.5 py-0.5 text-[11px] text-[#475569]">
                                    {tag}
                                  </span>
                                ))}
                              </div>
                            )}
                            <p className="mt-3 text-[16px] text-foreground/85 leading-relaxed">
                              已采集 {grainCount > 0 ? grainCount : '...'} 条实战经验
                              {sceneCount > 0 && <>，覆盖 {sceneCount} 个业务场景</>}
                            </p>
                          </div>
                        </div>
                        <TrustBadge
                          grainCount={grainCount > 0 ? grainCount : undefined}
                          sceneCount={sceneCount > 0 ? sceneCount : undefined}
                          satisfactionRate={skillStats?.satisfactionRate}
                          lastActive={skillStats?.lastActive ? formatRelativeTime(skillStats.lastActive) : undefined}
                        />
                      </div>
                    )}

                    {/* ② 引导语气泡 — QA 专属 */}
                    {chatMode === 'qa' && (
                      <div className="flex items-start gap-2 animate-[messageArrive_400ms_ease-out_500ms] opacity-0 [animation-fill-mode:forwards]">
                        <ChatAvatar role="ai" src={avatarUrl || undefined} size={28} />
                        <div className="max-w-[78%] rounded-2xl rounded-tl-sm bg-[#f8f7ff] border border-[#e8e6ff] px-4 py-3">
                          <p className="text-xs text-muted-foreground mb-1">{ownerName}</p>
                          <p className="text-sm text-foreground whitespace-pre-wrap leading-relaxed">{MODE_GUIDE.qa}</p>
                        </div>
                      </div>
                    )}

                    {/* ② 引导语气泡 — Talk 专属 */}
                    {chatMode === 'talk' && (
                      <div className="flex items-start gap-2.5 animate-[messageArrive_400ms_ease-out_500ms] opacity-0 [animation-fill-mode:forwards]">
                        <ChatAvatar role="ai" src={avatarUrl || undefined} size={28} />
                        <div className="max-w-[78%] rounded-2xl rounded-tl-sm bg-[#f8f7ff] border border-[#e8e6ff] px-4 py-3">
                          <p className="text-xs text-muted-foreground mb-1">{ownerName}</p>
                          <p className="text-sm text-foreground whitespace-pre-wrap leading-relaxed">
                            {MODE_GUIDE.talk}
                          </p>
                        </div>
                      </div>
                    )}

                    {/* ③ QA 场景选择 */}
                    {chatMode === 'qa' && (
                      <>
                        {/* 未选场景：SceneTagBar */}
                        {!qa.qaSceneContext && qa.sceneTags.length > 0 && (
                          <div>
                            <p className="text-xs text-muted-foreground text-center mb-2">擅长领域</p>
                            <SceneTagBar sceneTags={qa.sceneTags} activeTag="" chatMode="qa"
                              onQaTagClick={qa.handleQaStart}
                              onTalkTagClick={() => {}} />
                          </div>
                        )}
                        {/* 已选场景：上下文导航 + 精选话题 */}
                        {qa.qaSceneContext && (() => {
                          const sorted = [...qa.sceneTags].sort((a, b) => (b.count || 0) - (a.count || 0));
                          const curIdx = sorted.findIndex(s => s.tag === qa.qaSceneContext);
                          const prevTag = curIdx > 0 ? sorted[curIdx - 1].tag : null;
                          const nextTag = curIdx >= 0 && curIdx < sorted.length - 1 ? sorted[curIdx + 1].tag : null;
                          return (
                            <div className="text-center">
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
                      </>
                    )}

                    {/* ③ 精选话题 — Talk 专属 */}
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
                          <ChatAvatar role="ai" src={avatarUrl || undefined} size={28} />
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
                          <ChatAvatar role="user" size={28} />
                        </div>
                      )}
                    </div>
                  );
                })}

                {/* 流式文本 */}
                {qa.qaStreamText && (
                  <div className="flex items-start gap-2.5">
                    <ChatAvatar role="ai" src={avatarUrl || undefined} size={28} />
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
