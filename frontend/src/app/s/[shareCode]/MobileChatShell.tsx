'use client';

import React, { useEffect, useRef } from 'react';
import type { ShareInfo } from '@/lib/api/c';
import type { useQaChat } from '@/app/skill/[skillId]/hooks/useQaChat';
import PracticeChatSection from '@/app/skill/[skillId]/PracticeChatSection';

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
}: Props) {
  const name = info.ownerName || '销冠';
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [qa.messages, qa.qaStreamText]);

  const send = () => {
    if (!qa.inputValue.trim() || qa.isStreaming) return;
    qa.handleQaSend();
    onAfterSend();
  };

  const initial = name.charAt(0);

  return (
    <div className="flex h-full min-h-0 flex-col bg-[#f7f9ff]">
      {/* 顶栏 */}
      <div className="flex-none border-b border-[#dfe6ff] bg-white/90 backdrop-blur">
        <div className="flex items-center gap-3 px-4 pb-1.5 pt-[calc(8px+env(safe-area-inset-top))]">
          <button onClick={onOpenHistory} aria-label="历史对话"
            className="flex h-[34px] w-[34px] items-center justify-center rounded-md text-foreground active:bg-surface">
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M4 6h16M4 12h16M4 18h10" /></svg>
          </button>
          <div className="relative flex h-[30px] w-[30px] flex-none items-center justify-center rounded-full shadow-sm">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={info.avatarUrl || '/def-avatar.png'} alt={name} className="h-full w-full rounded-full object-cover" />
            <span className="absolute -bottom-px -right-px h-[9px] w-[9px] rounded-full border-2 border-white bg-success" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="truncate text-[15px] font-semibold leading-tight text-foreground">{name}</div>
            <div className="truncate text-[11px] text-muted-foreground">{info.ownerTitle || 'AI 分身'}</div>
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
          <div className="flex-1 overflow-y-auto px-4 py-6">
            <div className="text-h3 font-semibold text-foreground">选择一个场景开始对练</div>
            <div className="mt-1 text-xs text-muted-foreground">{name}会扮演客户，每轮给你对比点评</div>
            <div className="mt-4 flex flex-col gap-2.5">
              {(info.sceneTags || []).map(s => (
                <button key={s.tag} onClick={() => onPickScene(s.tag)}
                  className="flex items-center justify-between rounded-lg border border-border bg-white px-4 py-3 text-left shadow-sm active:scale-[0.98]">
                  <span className="text-body font-medium text-foreground">{s.tag}</span>
                  <span className="text-[11px] text-muted-foreground-2">{s.count ? `${s.count} 条锦囊` : ''} ›</span>
                </button>
              ))}
              {(info.sceneTags || []).length === 0 && (
                <div className="py-8 text-center text-xs text-muted-foreground-2">该分身暂无对练场景</div>
              )}
            </div>
          </div>
        )
      ) : (
        <>
          {/* qa/talk 消息区 */}
          {qa.isStreaming && (
            <div className="h-0.5 w-full overflow-hidden bg-gray-100">
              <div className="h-full w-1/2 animate-[marquee_1.8s_linear_infinite] rounded-full bg-gradient-to-r from-indigo-400 via-purple-500 to-pink-400" />
            </div>
          )}
          <div className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
            {/* 紧凑自我介绍卡片 — 首次打开且无消息时展示 */}
            {qa.messages.length === 0 && !qa.qaStreamText && (
              <div className="rounded-2xl border border-[#dfe6ff] bg-white/80 px-4 py-4 shadow-sm">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 flex-none items-center justify-center rounded-full shadow-sm overflow-hidden">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={info.avatarUrl || '/def-avatar.png'} alt={name} className="h-full w-full object-cover" />
                  </div>
                  <div>
                    <p className="text-[14px] font-semibold text-foreground">
                      你好，我是 {name} 的 AI 分身 👋
                    </p>
                    <p className="text-[12px] text-muted-foreground leading-relaxed mt-0.5">
                      {mode === 'qa' ? '销售上的难题，随时问我。' : '随便聊聊，就像老同事一样。'}
                    </p>
                  </div>
                </div>
                <div className="mt-3 grid grid-cols-3 gap-2">
                  <div className="rounded-xl bg-[#f7f9ff] px-2 py-2 text-center">
                    <div className="text-xs mb-0.5">💬</div>
                    <div className="text-[10px] font-semibold text-foreground">即问即答</div>
                    <div className="text-[9px] text-muted-foreground">AI 教你怎么说</div>
                  </div>
                  <div className="rounded-xl bg-[#f7f9ff] px-2 py-2 text-center">
                    <div className="text-xs mb-0.5">🎯</div>
                    <div className="text-[10px] font-semibold text-foreground">场景对练</div>
                    <div className="text-[9px] text-muted-foreground">模拟实战对话</div>
                  </div>
                  <div className="rounded-xl bg-[#f7f9ff] px-2 py-2 text-center">
                    <div className="text-xs mb-0.5">📋</div>
                    <div className="text-[10px] font-semibold text-foreground">溯源可查</div>
                    <div className="text-[9px] text-muted-foreground">每句话有据可依</div>
                  </div>
                </div>
              </div>
            )}

            {/* 消息列表 */}
            {qa.messages.map(m => (
              m.role === 'user' ? (
                <div key={m.id} className="flex justify-end">
                  <div className="max-w-[85%] rounded-2xl rounded-tr-sm bg-gradient-to-br from-[#2147ff] to-[#345dff] px-4 py-3 text-[14px] leading-relaxed text-white shadow-sm">
                    {m.content}
                  </div>
                </div>
              ) : (
                <div key={m.id} className="flex gap-2">
                  <Avatar name={name} avatarUrl={info.avatarUrl} />
                  <div className="max-w-[85%] rounded-2xl rounded-tl-sm bg-white px-4 py-3 shadow-sm">
                    <div className="whitespace-pre-wrap text-[14px] leading-relaxed text-foreground">{m.content}</div>
                    {(m.source || m.grainTags) && (
                      <div className="mt-2 border-t border-[#dfe6ff] pt-2">
                        <details>
                          <summary className="cursor-pointer list-none text-[11px] text-muted-foreground">
                            ▸ 来源：{m.source || '经验锦囊'}{m.grainCount ? ` · ${m.grainCount} 条锦囊` : ''}
                          </summary>
                          <div className="mt-1.5 rounded-md bg-[#eef2ff] px-2.5 py-1.5 text-[11px] text-muted-foreground">
                            {m.grainTags ? `场景：${m.grainTags}` : ''}{m.avgScore ? ` · 质量分 ${m.avgScore}` : ''}
                          </div>
                        </details>
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
              )
            ))}

            {/* 流式气泡 — 带打字动画 */}
            {qa.qaStreamText && (
              <div className="flex gap-2 animate-[fadeIn_200ms_ease-out]">
                <Avatar name={name} avatarUrl={info.avatarUrl} />
                <div className="max-w-[85%] rounded-2xl rounded-tl-sm bg-white px-4 py-3 shadow-sm">
                  <span className="whitespace-pre-wrap text-[14px] leading-relaxed text-foreground">{qa.qaStreamText}</span>
                  <span className="ml-0.5 inline-block h-4 w-1 animate-pulse rounded-full bg-[#2147ff] align-middle" />
                </div>
              </div>
            )}

            {/* 思考中 — ThinkingCard */}
            {qa.isStreaming && !qa.qaStreamText && (
              <div className="flex gap-2">
                <Avatar name={name} avatarUrl={info.avatarUrl} />
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
                  <button key={q} onClick={() => { qa.handleQuestionClick(q); onAfterSend(); }}
                    className="rounded-full border border-[#dfe6ff] bg-white px-4 py-2 text-[13px] text-foreground shadow-sm transition-colors hover:border-[#2147ff] hover:bg-[#eef2ff] active:scale-[0.97]">
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* 输入条 */}
          <div className="flex-none border-t border-[#dfe6ff] bg-white px-4 pb-[calc(10px+env(safe-area-inset-bottom))] pt-3">
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
          </div>
        </>
      )}
    </div>
  );
}

function Avatar({ name, avatarUrl }: { name: string; avatarUrl?: string }) {
  return (
    <div className="flex h-[28px] w-[28px] flex-none items-center justify-center overflow-hidden rounded-full shadow-sm">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={avatarUrl || '/def-avatar.png'} alt={name} className="h-full w-full object-cover" />
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
