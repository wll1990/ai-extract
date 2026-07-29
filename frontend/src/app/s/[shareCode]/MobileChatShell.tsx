'use client';

import React, { useEffect, useRef, useState } from 'react';
import type { ShareInfo } from '@/lib/api/c';
import type { useQaChat } from '@/app/skill/[skillId]/hooks/useQaChat';
import PracticeChatSection from '@/app/skill/[skillId]/PracticeChatSection';
import { TraceabilityDrawer } from '@/components/skill/TraceabilityDrawer';
import { TrustBadge, PortraitCard, ChatAvatar, StatBadge, MODE_GUIDE, TALK_NAME_CARD } from '@aiextract/shared-ui';
import { fetchRecommendedQuestions } from '@/lib/api/skill';

type ChatMode = 'qa' | 'talk' | 'practice';
type QaHook = ReturnType<typeof useQaChat>;

interface Props {
  info: ShareInfo;
  mode: ChatMode;
  onSwitchMode: (m: ChatMode) => void;
  remainingLabel: string | null;
  onOpenHistory: () => void;
  qa: QaHook;
  onAfterSend: () => void;
  practiceSceneTag: string;
  practiceKey: number;
  onPickScene: (tag: string) => void;
  abortRef: React.MutableRefObject<AbortController | null>;
  authToken?: string;
  onPracticeLimit: (info: { used: number; limit: number; pendingText: string }) => void;
  practiceHint?: string;
  /** 是否已用完免费额度（游客 remaining === 0） */
  isLimitReached?: boolean;
  /** 点击"注册解锁"的回调 */
  onRegisterPrompt?: () => void;
}

const TABS: Array<{ mode: ChatMode; label: string }> = [
  { mode: 'qa', label: '问答' },
  { mode: 'talk', label: '聊天' },
  { mode: 'practice', label: '对练' },
];

/**
 * 移动端聊天壳 — 参考培伴·萃萃设计升级。
 * qa/talk 气泡在此实现；practice 复用 PracticeChatSection。
 */
export default function MobileChatShell({
  info, mode, onSwitchMode, remainingLabel, onOpenHistory, qa, onAfterSend,
  practiceSceneTag, practiceKey, onPickScene, abortRef, authToken, onPracticeLimit, practiceHint,
  isLimitReached, onRegisterPrompt,
}: Props) {
  const isOrg = info.skillType === 'organization';
  const orgMembers = info.members || [];
  const previewMembers = orgMembers.slice(0, 4);
  const AVATAR_COLORS = ['#6366f1', '#8b5cf6', '#a855f7', '#d946ef'];
  const name = info.ownerName || '销冠';
  const initial = name.charAt(0);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [qa.messages, qa.qaStreamText]);

  const send = () => {
    if (!qa.inputValue.trim() || qa.isStreaming || isLimitReached) return;
    qa.handleQaSend();
    onAfterSend();
  };

  const [scenesExpanded, setScenesExpanded] = useState(false);
  const sceneTags = info.sceneTags || [];
  const INITIAL_SCENE_COUNT = 4;
  const hasMoreScenes = sceneTags.length > INITIAL_SCENE_COUNT;
  const visibleScenes = scenesExpanded ? sceneTags : sceneTags.slice(0, INITIAL_SCENE_COUNT);

  const [traceGrainIds, setTraceGrainIds] = useState('');
  const [traceAvgSim, setTraceAvgSim] = useState<number>(0);
  // Talk 模式主动加载推荐问题
  const [talkQuestions, setTalkQuestions] = useState<string[]>([]);
  useEffect(() => {
    if (mode === 'talk' && info?.skillId) {
      fetchRecommendedQuestions(info.skillId)
        .then(qs => { if (Array.isArray(qs)) setTalkQuestions(qs); })
        .catch(() => {});
    }
  }, [mode, info?.skillId]);

  const totalGrains = (info.sceneTags || []).reduce((sum, s) => sum + (s.count || 0), 0);

  return (
    <div className="flex h-full min-h-0 flex-col bg-[#f7f9ff]">
      {/* 顶栏 */}
      <div className="flex-none border-b border-[#dfe6ff] bg-white/90 backdrop-blur">
        <div className="flex items-center gap-3 px-4 pb-1.5 pt-[calc(8px+env(safe-area-inset-top))]">
          <button onClick={onOpenHistory} aria-label="历史对话"
            className="flex h-[34px] w-[34px] items-center justify-center rounded-md text-foreground active:bg-surface">
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M4 6h16M4 12h16M4 18h10" /></svg>
          </button>
          {isOrg ? (
            <div className="flex items-center gap-0.5">
              {previewMembers.length > 0 ? previewMembers.map((m, i) => (
                m.avatarUrl ? (
                  <img key={m.id} src={m.avatarUrl} alt={m.ownerName}
                    className="h-[30px] w-[30px] rounded-full border-2 border-white object-cover"
                    style={{ marginLeft: i > 0 ? -6 : 0, zIndex: 4 - i }} />
                ) : (
                  <div key={m.id} className="h-[30px] w-[30px] rounded-full border-2 border-white flex items-center justify-center text-white text-xs font-bold"
                    style={{ background: AVATAR_COLORS[i % AVATAR_COLORS.length], marginLeft: i > 0 ? -6 : 0, zIndex: 4 - i }}>
                    {(m.ownerName || '?')[0]}
                  </div>
                )
              )) : (
                <span className="text-xl">🏢</span>
              )}
              {orgMembers.length > 4 && (
                <div className="h-[30px] w-[30px] rounded-full border-2 border-white bg-white/20 flex items-center justify-center text-muted-foreground text-[10px] font-medium" style={{ marginLeft: -6 }}>
                  +{orgMembers.length - 4}
                </div>
              )}
            </div>
          ) : (
            <div className="relative">
              <ChatAvatar role="ai" src={info.avatarUrl || undefined} size={30} />
              <span className="absolute -bottom-px -right-px h-[9px] w-[9px] rounded-full border-2 border-white bg-success" />
            </div>
          )}
          <div className="min-w-0 flex-1">
            <div className="truncate text-[15px] font-semibold leading-tight text-foreground">{name}</div>
            <div className="truncate text-[11px] text-muted-foreground">
              {isOrg ? `${orgMembers.length} 位成员` : (info.ownerTitle || 'AI 分身')}
            </div>
          </div>
          {remainingLabel && (
            <span className={`flex-none rounded-full px-2.5 py-1 text-xs font-medium ${
              remainingLabel.includes('0') && remainingLabel.startsWith('剩 0')
                ? 'bg-danger-bg text-danger' : 'bg-warning-bg text-warning-text'
            }`}>{remainingLabel}</span>
          )}
        </div>
        {/* 模式分段 tab */}
        <div className="mx-4 mb-2.5 mt-0.5 flex gap-0.5 rounded-full bg-[#eef2ff] p-[3px]">
          {TABS.map(t => (
            <button key={t.mode}
              onClick={() => onSwitchMode(t.mode)}
              className={`flex-1 rounded-full py-[7px] text-[13px] transition-colors ${
                mode === t.mode ? 'bg-white font-semibold text-[#2147ff] shadow-sm' : 'font-medium text-muted-foreground'
              }`}>
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* 内容区 */}
      {mode === 'practice' ? (
        practiceSceneTag ? (
          <div className="flex min-h-0 flex-1 flex-col" key={practiceKey}>
            {practiceHint && (
              <div className="mx-4 mt-2 rounded-lg bg-success-bg px-3 py-2 text-xs text-success">{practiceHint}</div>
            )}
            <PracticeChatSection
              skillId={info.skillId}
              initialSceneTag={practiceSceneTag}
              setChatMode={onSwitchMode}
              abortRef={abortRef}
              authToken={authToken}
              onLimit={onPracticeLimit}
            />
          </div>
        ) : (
          <div className="flex-1 overflow-y-auto px-4 py-4">
            {/* ① 名片卡片 — 同 QA/Talk */}
            <div className="mb-5 animate-[messageArrive_400ms_ease-out] rounded-[26px] bg-white py-5 px-4 border border-[#e1e7ff] overflow-hidden"
              style={{
                background: 'radial-gradient(circle at 18% 28%, rgba(65,91,255,.09), transparent 24%), radial-gradient(circle at 80% 10%, rgba(255,77,95,.03), transparent 20%), rgba(255,255,255,.9)',
                boxShadow: '0 18px 50px rgba(42,74,177,.08), 0 3px 12px rgba(34,55,126,.04)',
              }}>
              <div className="flex items-center gap-4">
                {isOrg ? (
                  <div className="w-[170px] shrink-0 flex flex-wrap justify-center gap-1">
                    {previewMembers.length > 0 ? previewMembers.map((m, i) => (
                      m.avatarUrl ? (
                        <img key={m.id} src={m.avatarUrl} alt={m.ownerName}
                          className="h-[50px] w-[50px] rounded-full border-2 border-white object-cover shadow-sm"
                          style={{ marginLeft: i > 0 ? -10 : 0 }} />
                      ) : (
                        <div key={m.id} className="h-[50px] w-[50px] rounded-full border-2 border-white flex items-center justify-center text-white text-lg font-bold shadow-sm"
                          style={{ background: AVATAR_COLORS[i % AVATAR_COLORS.length], marginLeft: i > 0 ? -10 : 0 }}>
                          {(m.ownerName || '?')[0]}
                        </div>
                      )
                    )) : (
                      <span className="text-4xl">🏢</span>
                    )}
                    {orgMembers.length > 4 && (
                      <div className="h-[50px] w-[50px] rounded-full border-2 border-white bg-white/20 flex items-center justify-center text-muted-foreground text-xs font-medium" style={{ marginLeft: -10 }}>
                        +{orgMembers.length - 4}
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="w-[170px] shrink-0">
                    <PortraitCard src={info.avatarUrl || undefined} alt={name} />
                  </div>
                )}
                <div className="flex-1 min-w-0">
                  <h3 className="text-[20px] font-bold text-foreground leading-tight">
                    {TALK_NAME_CARD.greeting}<span className="text-[#2563EB]">{name}</span><span className="text-[14px] ml-0.5">✨</span>
                  </h3>
                  <span className="inline-block mt-1.5 text-[13px] text-[#64748B] bg-[#f1f5f9] rounded-full px-2.5 py-0.5">
                    {TALK_NAME_CARD.roleTag}
                  </span>
                  <p className="mt-2.5 text-[15px] text-foreground/85 leading-relaxed">
                    已采集 {totalGrains > 0 ? totalGrains : '...'} 条实战经验
                    {(info.sceneTags?.length || 0) > 0 && <>，覆盖 {info.sceneTags!.length} 个业务场景</>}
                  </p>
{info.stats && info.stats.conversationCount > 0 && (
  <div className="mt-2 flex items-center gap-3">
    <StatBadge icon="💬" value={info.stats.conversationCount} label="次" size="sm" />
    {info.stats.satisfactionRate > 0 && (
      <><span className="text-[#d4d8e0] text-xs">·</span>
      <StatBadge icon="👍" value={info.stats.satisfactionRate} label="%" size="sm" /></>
    )}
    {info.stats.userCount > 0 && (
      <><span className="text-[#d4d8e0] text-xs">·</span>
      <StatBadge icon="👤" value={info.stats.userCount} label="人" size="sm" /></>
    )}
  </div>
)}
                </div>
              </div>
              <TrustBadge
                grainCount={totalGrains > 0 ? totalGrains : undefined}
                sceneCount={(info.sceneTags?.length || 0) > 0 ? info.sceneTags!.length : undefined}
                satisfactionRate={info.stats?.satisfactionRate}
              />
            </div>

            {/* ② 引导语气泡 — Practice 专属 */}
            <div className="flex items-start gap-2 mb-5 animate-[messageArrive_350ms_ease-out_500ms] opacity-0 [animation-fill-mode:forwards]">
              <ChatAvatar role="ai" src={info.avatarUrl || undefined} size={28} />
              <div className="max-w-[82%] rounded-2xl rounded-tl-sm bg-[#f0fdf4] border border-[#dcfce7] px-4 py-3">
                <p className="text-[11px] text-muted-foreground mb-1">{name}</p>
                <p className="text-[14px] text-foreground leading-relaxed">{MODE_GUIDE.practice}</p>
              </div>
            </div>

            {/* ③ 场景列表 */}
            <div className={`flex flex-col gap-2.5 ${scenesExpanded ? 'max-h-[50vh] overflow-y-auto -mx-2 px-2' : ''}`}>
              {visibleScenes.map(s => (
                <button key={s.tag} onClick={() => onPickScene(s.tag)}
                  className="flex items-center justify-between rounded-lg border border-border bg-white px-4 py-3 text-left shadow-sm active:scale-[0.98]">
                  <span className="text-body font-medium text-foreground">{s.tag}</span>
                  <span className="text-[11px] text-muted-foreground-2">{s.count ? `${s.count} 条经验` : ''} ›</span>
                </button>
              ))}
              {sceneTags.length === 0 && (
                <div className="py-8 text-center text-xs text-muted-foreground-2">该分身暂无对练场景</div>
              )}
            </div>
            {hasMoreScenes && (
              <button
                onClick={() => setScenesExpanded(!scenesExpanded)}
                className="mt-4 flex w-full items-center justify-center gap-1.5 rounded-lg py-2.5 text-[13px] font-medium text-muted-foreground transition-colors hover:bg-[#eef2ff] hover:text-[#2147ff] active:scale-[0.98]"
              >
                {scenesExpanded ? (
                  <>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="h-4 w-4"><path d="M18 15l-6-6-6 6" /></svg>
                    收起
                  </>
                ) : (
                  <>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="h-4 w-4"><path d="M6 9l6 6 6-6" /></svg>
                    展开更多（还有 {sceneTags.length - INITIAL_SCENE_COUNT} 个）
                  </>
                )}
              </button>
            )}
          </div>
        )
      ) : (
        <>
          {/* qa/talk 消息区 */}
          {qa.isStreaming && (
            <div className="px-4">
              <div className="h-0.5 w-full max-w-[85%] overflow-hidden rounded-full bg-gray-100">
                <div className="h-full w-1/2 animate-[marquee_1.8s_linear_infinite] rounded-full bg-gradient-to-r from-indigo-400 via-purple-500 to-pink-400" />
              </div>
            </div>
          )}
          <div className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
            {/* 首次打开且无消息 — 四层入口 */}
            {qa.messages.length === 0 && !qa.qaStreamText && (
              <div>
                {/* ① 名片卡片 — Talk/QA 共享：头像+文案+信任卡片一整块 */}
                {(mode === 'qa' || mode === 'talk') && (
                  <div className="mb-5 animate-[messageArrive_400ms_ease-out] rounded-[26px] bg-white py-5 px-4 border border-[#e1e7ff] overflow-hidden"
                    style={{
                      background: 'radial-gradient(circle at 18% 28%, rgba(65,91,255,.09), transparent 24%), radial-gradient(circle at 80% 10%, rgba(255,77,95,.03), transparent 20%), rgba(255,255,255,.9)',
                      boxShadow: '0 18px 50px rgba(42,74,177,.08), 0 3px 12px rgba(34,55,126,.04)',
                    }}>
                    {/* 头部：左头像 + 右文案 */}
                    <div className="flex items-center gap-4">
                      {isOrg ? (
                        <div className="w-[170px] shrink-0 flex flex-wrap justify-center gap-1">
                          {previewMembers.length > 0 ? previewMembers.map((m, i) => (
                            m.avatarUrl ? (
                              <img key={m.id} src={m.avatarUrl} alt={m.ownerName}
                                className="h-[50px] w-[50px] rounded-full border-2 border-white object-cover shadow-sm"
                                style={{ marginLeft: i > 0 ? -10 : 0 }} />
                            ) : (
                              <div key={m.id} className="h-[50px] w-[50px] rounded-full border-2 border-white flex items-center justify-center text-white text-lg font-bold shadow-sm"
                                style={{ background: AVATAR_COLORS[i % AVATAR_COLORS.length], marginLeft: i > 0 ? -10 : 0 }}>
                                {(m.ownerName || '?')[0]}
                              </div>
                            )
                          )) : (
                            <span className="text-4xl">🏢</span>
                          )}
                          {orgMembers.length > 4 && (
                            <div className="h-[50px] w-[50px] rounded-full border-2 border-white bg-white/20 flex items-center justify-center text-muted-foreground text-xs font-medium" style={{ marginLeft: -10 }}>
                              +{orgMembers.length - 4}
                            </div>
                          )}
                        </div>
                      ) : (
                        <div className="w-[170px] shrink-0">
                          <PortraitCard src={info.avatarUrl || undefined} alt={name} />
                        </div>
                      )}
                      <div className="flex-1 min-w-0">
                        <h3 className="text-[20px] font-bold text-foreground leading-tight">
                          {TALK_NAME_CARD.greeting}<span className="text-[#2563EB]">{name}</span><span className="text-[14px] ml-0.5">✨</span>
                        </h3>
                        <span className="inline-block mt-1.5 text-[13px] text-[#64748B] bg-[#f1f5f9] rounded-full px-2.5 py-0.5">
                          {TALK_NAME_CARD.roleTag}
                        </span>
                        <p className="mt-2.5 text-[15px] text-foreground/85 leading-relaxed">
                          已采集 {totalGrains > 0 ? totalGrains : '...'} 条实战经验
                          {(info.sceneTags?.length || 0) > 0 && <>，覆盖 {info.sceneTags!.length} 个业务场景</>}
                        </p>
{info.stats && info.stats.conversationCount > 0 && (
  <div className="mt-2 flex items-center gap-3">
    <StatBadge icon="💬" value={info.stats.conversationCount} label="次" size="sm" />
    {info.stats.satisfactionRate > 0 && (
      <><span className="text-[#d4d8e0] text-xs">·</span>
      <StatBadge icon="👍" value={info.stats.satisfactionRate} label="%" size="sm" /></>
    )}
    {info.stats.userCount > 0 && (
      <><span className="text-[#d4d8e0] text-xs">·</span>
      <StatBadge icon="👤" value={info.stats.userCount} label="人" size="sm" /></>
    )}
  </div>
)}
                      </div>
                    </div>
                    <TrustBadge
                      grainCount={totalGrains > 0 ? totalGrains : undefined}
                      sceneCount={(info.sceneTags?.length || 0) > 0 ? info.sceneTags!.length : undefined}
                      satisfactionRate={info.stats?.satisfactionRate}
                    />
                  </div>
                )}

                {/* ② 引导语气泡 — QA 专属 */}
                {mode === 'qa' && (
                  <div className="flex items-start gap-2 mb-4 animate-[messageArrive_350ms_ease-out_500ms] opacity-0 [animation-fill-mode:forwards]">
                    <ChatAvatar role="ai" src={info.avatarUrl || undefined} size={28} />
                    <div className="max-w-[82%] rounded-2xl rounded-tl-sm bg-[#f8f7ff] border border-[#e8e6ff] px-4 py-3">
                      <p className="text-[11px] text-muted-foreground mb-1">{name}</p>
                      <p className="text-[14px] text-foreground leading-relaxed"
                        dangerouslySetInnerHTML={{
                          __html: MODE_GUIDE.qa.replace(/\n/g, '<br/>'),
                        }}
                      />
                    </div>
                  </div>
                )}

                {/* ② 引导语气泡 — Talk 独有 */}
                {mode === 'talk' && (
                  <div className="flex items-start gap-2 mb-4 animate-[messageArrive_350ms_ease-out_500ms] opacity-0 [animation-fill-mode:forwards]">
                    <ChatAvatar role="ai" src={info.avatarUrl || undefined} size={28} />
                    <div className="max-w-[82%] rounded-2xl rounded-tl-sm bg-[#f8f7ff] border border-[#e8e6ff] px-4 py-3">
                      <p className="text-[11px] text-muted-foreground mb-1">{name}</p>
                      <p className="text-[14px] text-foreground leading-relaxed"
                        dangerouslySetInnerHTML={{
                          __html: MODE_GUIDE.talk
                            .replace('{name}', name)
                            .replace(/\n/g, '<br/>'),
                        }}
                      />
                    </div>
                  </div>
                )}

                {/* 行动入口 — 按模式切换 */}
                {mode === 'qa' && (
                  <div>
                    <div className="flex gap-2 overflow-x-auto pb-2 -mx-4 px-4 snap-x"
                      style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
                      {(info.sceneTags || []).slice(0, 10).map((s, i) => {
                        const tints = ['#fef9f0', '#f5f3ff', '#f0fdf6', '#eff6ff'];
                        const tint = tints[i % 4];
                        return (
                          <button key={s.tag} onClick={() => qa.handleQuestionClick(`聊聊${s.tag}方面的经验？`)}
                            className="flex flex-col items-start gap-1 rounded-xl px-4 py-3 text-left shadow-sm transition-transform active:scale-[0.97] flex-shrink-0 w-[140px] snap-start"
                            style={{ background: tint, border: '1px solid rgba(0,0,0,0.04)' }}>
                            <span className="text-[13px] font-semibold text-foreground">
                              {i === 0 && <span className="inline-flex items-center rounded-pill bg-amber-100 text-[9px] font-medium text-amber-700 px-1.5 py-0.5 mr-1">推荐</span>}
                              {s.tag}
                            </span>
                            <span className="text-[11px] text-muted-foreground">{s.count ? `${s.count} 条经验` : ''}</span>
                          </button>
                        );
                      })}
                    </div>
                    {(info.sceneTags || []).length === 0 && (
                      <div className="text-center text-xs text-muted-foreground-2 py-4">暂无场景标签</div>
                    )}
                  </div>
                )}

                {mode === 'talk' && (
                  <div>
                    <div className="flex flex-col gap-2">
                      {/* 推荐话题 — talkQuestions 优先, fallback 前端预设 */}
                      {(talkQuestions.length > 0
                        ? talkQuestions
                        : ['最近在跑什么类型的客户？', '销售里最头疼的是什么？', '分享一个你最近的成交案例']
                      ).slice(0, 4).map((q, i) => (
                        <button key={q} onClick={() => qa.handleQuestionClick(q)}
                          className="flex items-center gap-3 rounded-xl border border-border bg-white px-4 py-3 text-left shadow-sm transition-all hover:border-[#2147ff] hover:bg-[#f7f9ff] active:scale-[0.98]">
                          <span className="flex h-8 w-8 flex-none items-center justify-center rounded-full bg-[#eef2ff] text-sm">💬</span>
                          <span className="flex-1 text-[13px] font-medium text-foreground">{q}</span>
                          {i === 0 && <span className="rounded-pill bg-amber-100 px-1.5 py-0.5 text-[9px] font-medium text-amber-700">推荐</span>}
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3.5 w-3.5 text-muted-foreground-2"><path d="M9 6l6 6-6 6" /></svg>
                        </button>
                      ))}
                    </div>
                    <p className="mt-3 text-center text-[11px] text-muted-foreground-2">也可以直接在下面打字说你想聊的 ↓</p>
                  </div>
                )}
              </div>
            )}

            {/* 消息列表 */}
            {qa.messages.map(m => {
              const sepIndex = (m.content || '').indexOf('━━━━━━');
              const mainText = sepIndex >= 0 ? (m.content || '').substring(0, sepIndex).trim() : (m.content || '');
              const sourceText = sepIndex >= 0 ? (m.content || '').substring(sepIndex + 6).trim() : '';
              const sim = m.avgSimilarity ? Number(m.avgSimilarity) : 0;
              const matchLabel = sim >= 50 ? '🏅精准匹配' : sim >= 30 ? '📎关联匹配' : '';
              const canTrace = !!(m.source || m.grainTags || sourceText);

              if (m.role !== 'user' && !m.content) return null;
              if (m.role === 'user') {
                return (
                  <div key={m.id} className="flex justify-end gap-2">
                    <div className="max-w-[85%] rounded-2xl rounded-tr-sm bg-gradient-to-br from-[#2147ff] to-[#345dff] px-4 py-3 text-[14px] leading-relaxed text-white shadow-sm">
                      {m.content}
                    </div>
                    <ChatAvatar role="user" size={28} />
                  </div>
                );
              }

              return (
                <div key={m.id} className="flex gap-2">
                  <ChatAvatar role="ai" src={info.avatarUrl || undefined} size={28} />
                  <div className="max-w-[85%] rounded-2xl rounded-tl-sm bg-white px-4 py-3 shadow-sm">
                    <div className="whitespace-pre-wrap text-[14px] leading-relaxed text-foreground">{mainText}</div>
                    {canTrace && (
                      <div className="mt-2 border-t border-[#dfe6ff] pt-2">
                        <div className="flex items-center gap-2 flex-wrap">
                          {matchLabel && (
                            <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-green-50 text-green-700 border border-green-200">
                              {matchLabel}
                            </span>
                          )}
                          {m.grainIds && m.grainCount && (
                            <button onClick={() => { setTraceGrainIds(m.grainIds!); setTraceAvgSim(Number(m.avgSimilarity) || 0); }}
                              className="text-[11px] text-muted-foreground hover:text-[#2147ff] transition-colors">
                              溯源 · {m.grainCount} 条 →
                            </button>
                          )}
                        </div>
                        <div className="mt-1.5 flex justify-end gap-3">
                          <FeedbackBtn active={qa.feedbackState[m.id] === 'up'} up
                            onClick={() => qa.handleFeedback(m.id, m.grainId || '', true)} />
                          <FeedbackBtn active={qa.feedbackState[m.id] === 'down'} up={false}
                            onClick={() => qa.handleFeedback(m.id, m.grainId || '', false)} />
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}

            {/* 流式气泡 — 带打字动画 */}
            {qa.qaStreamText && (
              <div className="flex gap-2 animate-[fadeIn_200ms_ease-out]">
                <ChatAvatar role="ai" src={info.avatarUrl || undefined} size={28} />
                <div className="max-w-[85%] rounded-2xl rounded-tl-sm bg-white px-4 py-3 shadow-sm">
                  <span className="whitespace-pre-wrap text-[14px] leading-relaxed text-foreground">{qa.qaStreamText}</span>
                  <span className="ml-0.5 inline-block h-4 w-1 animate-pulse rounded-full bg-[#2147ff] align-middle" />
                </div>
              </div>
            )}

            {/* 思考中 — ThinkingCard */}
            {qa.isStreaming && !qa.qaStreamText && (
              <div className="flex gap-2">
                <ChatAvatar role="ai" src={info.avatarUrl || undefined} size={28} />
                <div className="max-w-[85%] rounded-2xl rounded-tl-sm bg-white px-5 py-3 shadow-sm">
                  <div className="flex items-center gap-2">
                    <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#2147ff]" style={{ animationDelay: '0ms' }} />
                    <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#2147ff]" style={{ animationDelay: '150ms' }} />
                    <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#2147ff]" style={{ animationDelay: '300ms' }} />
                  </div>
                </div>
              </div>
            )}

            <div ref={bottomRef} />
          </div>

          {/* 推荐问题 — QuickReplies 风格 */}
          {qa.suggestedQuestions.length > 0 && !qa.isStreaming && (
            <div className="flex-none px-4 pb-1">
              <div className="flex flex-wrap gap-2">
                {qa.suggestedQuestions.slice(0, 3).map(q => (
                  <button key={q} onClick={() => {
                    if (isLimitReached) { onRegisterPrompt?.(); return; }
                    qa.handleQuestionClick(q); onAfterSend();
                  }}
                    className="rounded-full border border-[#dfe6ff] bg-white px-4 py-2 text-[13px] text-foreground shadow-sm transition-colors hover:border-[#2147ff] hover:bg-[#eef2ff] active:scale-[0.97]">
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* 输入条 */}
          <div className="flex-none border-t border-[#dfe6ff] bg-white px-4 pb-[calc(10px+env(safe-area-inset-bottom))] pt-3">
            {isLimitReached ? (
              <div className="flex items-center gap-2">
                <input
                  value=""
                  readOnly
                  placeholder="免费体验次数已用完"
                  disabled
                  className="h-[44px] flex-1 rounded-full border border-[#dfe6ff] bg-[#f7f9ff] px-5 text-[14px] outline-none disabled:opacity-50"
                />
                <button onClick={onRegisterPrompt}
                  className="flex-none rounded-full bg-gradient-to-r from-[#06b6d4] to-[#3b82f6] px-5 py-2.5 text-[13px] font-semibold text-white shadow-md transition-transform active:scale-[0.97]">
                  注册解锁
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <input
                  value={qa.inputValue}
                  onChange={e => qa.setInputValue(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); send(); } }}
                  placeholder="输入消息…"
                  disabled={qa.isStreaming}
                  className="h-[44px] flex-1 rounded-full border border-[#dfe6ff] bg-[#f7f9ff] px-5 text-[14px] text-foreground outline-none placeholder:text-muted-foreground-2 focus:border-[#2147ff] focus:ring-2 focus:ring-[#2147ff]/10 disabled:opacity-60"
                />
                <button onClick={send} disabled={qa.isStreaming || !qa.inputValue.trim()} aria-label="发送"
                  className="flex h-11 w-11 flex-none items-center justify-center rounded-full bg-gradient-to-br from-[#2147ff] to-[#345dff] shadow-md transition-transform active:scale-90 disabled:opacity-50">
                  <svg viewBox="0 0 24 24" className="h-[18px] w-[18px] fill-white"><path d="M3.4 20.4l17.8-8.4L3.4 3.6l-.01 6.53L15 12 3.39 13.87z" /></svg>
                </button>
              </div>
            )}
          </div>
        </>
      )}
      <TraceabilityDrawer grainIds={traceGrainIds} avgSimilarity={traceAvgSim} open={!!traceGrainIds} onClose={() => { setTraceGrainIds(''); setTraceAvgSim(0); }} />
    </div>
  );
}

function FeedbackBtn({ active, up, onClick }: { active: boolean; up: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick} aria-label={up ? '有帮助' : '没帮助'}
      className={active ? 'text-[#2147ff]' : 'text-muted-foreground-2'}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"
        className={`h-3.5 w-3.5 ${up ? '' : 'rotate-180'}`}>
        <path d="M7 11v9M3 11h4l3.5-7a2 2 0 012 2V9h5.5a2 2 0 012 2.4l-1.2 6A2 2 0 0116.8 19H7" />
      </svg>
    </button>
  );
}
