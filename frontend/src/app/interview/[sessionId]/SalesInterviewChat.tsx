'use client';

import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { API_BASE } from '@/lib/api/client';

import React, { useCallback, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { MessageBubble } from '@/components/chat/MessageBubble';
import { ChatHero } from '@/components/chat/ChatHero';
import { QuickReplies } from '@aiextract/shared-ui/src/chat/QuickReplies';
import { ThinkingCard } from '@aiextract/shared-ui/src/chat/ThinkingCard';
import { ChatComposer } from '@aiextract/shared-ui/src/chat/ChatComposer';
import { VoiceInput } from '@/components/voice/VoiceInput';
import { ResumeModal } from '@/components/modals/ResumeModal';
import { getSession, pauseSession } from '@/lib/api/interview';
import { useInterviewSession } from './useInterviewSession';

const PHASE_ORDER = ['opening', 'storytelling', 'modeling', 'closing'];

const PHASE_NAMES: Record<string, string> = {
  opening: '开场定调', storytelling: '故事深描', modeling: '模型提炼', closing: '收网确认',
};

const COLLECT_LABELS: Record<string, string> = {
  caseStory: '案例故事', steps: '核心步骤', decision: '关键决策',
  mindset: '专家心法', boundary: '适用边界', checklist: '行动清单',
};

const PHASE_ADVANCE_LABELS: Record<string, string> = {
  opening: '我开始讲了 →', storytelling: '故事讲完了，提炼方法 →',
  modeling: '步骤清楚了，说说边界 →', closing: '都聊完了，生成报告 ✓',
};

const PHASE_ADVANCE_TIPS: Record<string, string> = {
  opening: '可以先简单介绍下自己，不用着急推进。',
  storytelling: '多分享一些具体场景和故事细节，效果会更好。',
  modeling: '再聊聊你做决策时的思考过程，不用急着收尾。',
  closing: '确认一下还有没有遗漏的重要经验？',
};

/** 从 collectStatus 计算进度文案 */
function buildProgressLine(collectStatus: Record<string, string> | undefined, currentPhase: string): string {
  if (!collectStatus) return '';
  const entries = Object.entries(collectStatus);
  const done = entries.filter(([, v]) => v === 'collected').length;
  const labels = entries.filter(([, v]) => v === 'collected').map(([k]) => COLLECT_LABELS[k] || k);
  const next = entries.filter(([k, v]) => v !== 'collected' && k !== 'caseStory' || (k === 'caseStory' && v !== 'collected')).map(([k]) => COLLECT_LABELS[k] || k);
  const phaseName = PHASE_NAMES[currentPhase] || currentPhase;
  let line = `―― ${phaseName} ――\n已采集 ${done}/6 模块`;
  if (labels.length > 0) line += `（${labels.join('、')}）`;
  if (next.length > 0) line += `  下一步：${next.slice(0, 3).join('、')}`;
  return line;
}

export function SalesInterviewChat() {
  const params = useParams();
  const router = useRouter();
  const sessionId = params.sessionId as string;
  const h = useInterviewSession(sessionId);
  const { state, dispatch } = h;

  const [ending, setEnding] = useState(false);
  const [advancing, setAdvancing] = useState(false);
  const [thinking, setThinking] = useState(false);

  const heroTraits = [
    { icon: '♡', title: '充分理解你', desc: '像朋友一样倾听，尊重你的经历与思考' },
    { icon: '✦', title: '激发更多可能', desc: '用提问与引导，帮你看见更深隐性价值' },
    { icon: '▤', title: '生成萃取报告', desc: '提炼你的经验资产，形成专属的萃取报告' },
  ];

  const quickReplies = ['我想梳理自己的销售经验', '我想分享一个具体案例', '我想提升某个销售环节'];

  // 强制完成访谈
  const handleForceComplete = useCallback(async () => {
    if (ending) return;
    setEnding(true);
    try {
      const res = await fetch(`${API_BASE}/interviews/${sessionId}/force-complete`, {
        method: 'POST', credentials: 'include',
      });
      const data = await res.json();
      if (data.code === 200) {
        dispatch({ type: 'MARK_COMPLETED', reportId: data.data?.reportId || null });
      }
    } catch {
      alert('结束访谈失败，请重试');
    }
    setEnding(false);
  }, [sessionId, ending, dispatch]);

  // 阶段推进
  const handleAdvancePhase = useCallback(async () => {
    const phase = state.session?.currentPhase || 'opening';
    if (advancing) return;
    setAdvancing(true);
    try {
      const res = await fetch(`${API_BASE}/interviews/${sessionId}/mark-phase-complete`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        body: JSON.stringify({ phase }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
    } catch {
      alert('阶段推进失败，请重试');
    }
    setAdvancing(false);
  }, [sessionId, state.session, advancing]);

  // 隐式反馈：不想聊这个话题
  const handleSkipTopic = useCallback(() => {
    h.setInputValue('[换个话题]');
    h.handleSend();
  }, [h]);

  // 隐式反馈：换个角度
  const handleNewAngle = useCallback(() => {
    h.setInputValue('[换个角度聊聊]');
    h.handleSend();
  }, [h]);

  // Pause
  const handlePause = useCallback(async () => {
    try { await pauseSession(sessionId); router.push('/interview/create'); }
    catch (err) { console.error('暂停失败:', err); }
  }, [sessionId, router]);

  const handleTranscription = useCallback((text: string) => { h.setInputValue(prev => prev + text); }, [h.setInputValue]);
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); h.handleSend(); } }, [h.handleSend]);

  const currentPhase = state.session?.currentPhase || 'opening';
  const phaseLabel = PHASE_ADVANCE_LABELS[currentPhase] || '继续 →';
  const tip = PHASE_ADVANCE_TIPS[currentPhase] || '';

  if (h.isLoading) return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <div className="flex flex-col items-center gap-4"><LoadingSpinner fullScreen={false} /><span className="text-sm text-muted-foreground">加载访谈中...</span></div>
    </div>
  );

  return (
    <div className="flex min-h-screen flex-col bg-surface">
      {!h.isOnline && <div className="flex items-center justify-center bg-warning-bg py-2 text-sm text-warning"><svg className="mr-2 h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126z" /></svg>网络连接已断开{!h.isOnline && '，恢复后将自动重连'}</div>}

      {h.errorBanner && (
        <div className="flex items-center justify-between bg-danger-bg px-4 sm:px-6 py-2">
          <span className="text-sm text-danger">{h.errorBanner}</span>
          <div className="flex items-center gap-3">
            <button type="button" onClick={() => h.setErrorBanner(null)} className="text-xs text-muted-foreground hover:text-foreground">关闭</button>
          </div>
        </div>
      )}

      {/* 顶栏 — 精简版 */}
      <div className="sticky top-0 z-30 border-b border-border bg-surface-2 backdrop-blur-sm">
        <div className="flex items-center justify-between px-4 sm:px-6 py-2">
          <span className="text-sm font-medium text-muted-foreground truncate max-w-[200px]">
            {state.session?.topic || '销冠访谈'}
          </span>
          <div className="flex items-center gap-2">
            {!state.isCompleted && (
              <button type="button" onClick={handlePause} disabled={h.isStreaming}
                className="rounded-lg p-2 text-muted-foreground hover:text-foreground hover:bg-primary-light disabled:opacity-40 transition-colors" title="暂停访谈">⏸</button>
            )}
            <div className="relative">
              <button type="button" onClick={() => h.setShowMoreMenu(!h.showMoreMenu)}
                className="rounded-lg p-2 text-muted-foreground transition-colors hover:bg-primary-light">
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}><path strokeLinecap="round" strokeLinejoin="round" d="M12 5v.01M12 12v.01M12 19v.01" /></svg>
              </button>
              {h.showMoreMenu && (
                <div className="absolute right-0 top-full mt-1 w-36 rounded-lg border border-border bg-surface-2 py-1 shadow-lg z-40">
                  <button type="button" onClick={() => { h.setShowMoreMenu(false); h.handleRestart(); }}
                    className="flex w-full items-center gap-2 px-4 py-2 text-sm text-danger transition-colors hover:bg-danger-bg">重新开始</button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* 消息区 */}
      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-6">
          <div className="mx-auto max-w-[720px] space-y-4">
            {/* Hero 区 — 首次进入展示 */}
            {state.messages.length <= 2 && (
              <ChatHero
                name="AI 专属萃取师"
                intro="你的 AI 经验萃取师，也是你的思想共创伙伴。我在这里不是告诉你答案，而是和你一起发现你未被看见的价值。"
                traits={heroTraits}
                privacyNote="我们的对话内容仅用于服务你，绝不外泄，请放心分享。"
              />
            )}

            {/* 快捷回复 — AI 开场后展示 */}
            {state.messages.length >= 2 && state.messages.length <= 4 && !h.isStreaming && (
              <QuickReplies replies={quickReplies} onSelect={(text: string) => { h.setInputValue(text); h.handleSend(); }} disabled={h.isStreaming} />
            )}

            {state.messages.map((msg) => {
              if (msg.role === 'system') return (
                <div key={msg.id} className="my-4 rounded-lg bg-primary-light py-3 px-4 text-center text-xs text-muted-foreground whitespace-pre-wrap">{msg.content}</div>
              );

              return (
                <MessageBubble key={msg.id}
                  role={msg.role}
                  content={msg.content}
                  depth={msg.depth}
                  createdAt={msg.createdAt}
                  isNew={msg.id.startsWith('temp') || msg.id.startsWith('streaming') || msg.id.startsWith('transition') || msg.id.startsWith('phase-')}
                />
              );
            })}

            {/* 思考中动画 */}
            {h.isStreaming && state.messages.length > 0 && (() => {
              const lastMsg = state.messages[state.messages.length - 1];
              if (lastMsg.role === 'ai' && !lastMsg.content) return <ThinkingCard text="正在整理你的经验…" />;
              return null;
            })()}

            {/* 完成卡片 */}
            {state.showCompletionCard && (
              <div className="mt-6 rounded-2xl bg-surface-2 p-6 sm:p-8 shadow-lg">
                <div className="text-center"><span className="text-5xl">🎉</span><h3 className="mt-4 text-xl font-bold text-foreground">访谈完成！</h3></div>
                <p className="mt-3 text-sm text-muted-foreground text-center">AI 正在分析你的访谈，预计 2-3 分钟生成萃取报告。可稍后在分身广场查看，也可以留在这里等待。</p>
                <div className="mt-6 flex flex-col sm:flex-row justify-center gap-3">
                  {state.completionReportId && (
                    <button type="button" onClick={() => router.push(`/report/${state.completionReportId}/done`)}
                      className="rounded-lg bg-foreground px-6 py-2.5 text-sm text-white hover:bg-primary transition-colors">查看萃取报告 →</button>
                  )}
                  <button type="button" onClick={() => router.push('/skill')}
                    className="rounded-lg border border-border px-6 py-2.5 text-sm text-muted-foreground hover:text-foreground hover:bg-primary-light transition-colors">返回分身广场</button>
                </div>
              </div>
            )}
            {state.isCompleted && !state.showCompletionCard && (
              <div className="mt-6 rounded-2xl bg-surface-2 p-6 text-center shadow-lg">
                <div className="mb-3 text-4xl">🎉</div>
                <h3 className="mb-2 text-lg font-bold text-foreground">访谈完成！</h3>
                <p className="mb-4 text-sm text-muted-foreground">AI 正在分析你的访谈，预计 2-3 分钟出报告。可稍后在分身广场查看。</p>
                <div className="mx-auto h-1 w-48 overflow-hidden rounded-full bg-border"><div className="h-full w-full animate-[shimmer_3s_ease-in-out] bg-primary" /></div>
              </div>
            )}
            <div ref={h.chatEndRef} />
          </div>
        </div>
      </div>

      {/* 底部输入区 */}
      {!state.isCompleted && (
        <div className="sticky bottom-0 border-t border-border bg-surface-2 px-4 sm:px-6 py-3 sm:py-4">
          {/* 隐式反馈按钮 */}
          <div className="mx-auto flex max-w-[720px] justify-center gap-2 mb-2 sm:mb-3">
            <button type="button" onClick={handleSkipTopic} disabled={h.isStreaming}
              className="rounded-full border border-border px-3 py-1 text-xs text-muted-foreground hover:text-foreground hover:border-muted-foreground transition-colors disabled:opacity-40 min-h-[36px]">
              🙅 不想聊这个
            </button>
            <button type="button" onClick={handleNewAngle} disabled={h.isStreaming}
              className="rounded-full border border-border px-3 py-1 text-xs text-muted-foreground hover:text-foreground hover:border-muted-foreground transition-colors disabled:opacity-40 min-h-[36px]">
              🔄 换个角度
            </button>
          </div>

          {/* 阶段推进按钮 */}
          <div className="mx-auto flex max-w-[720px] justify-center mb-3">
            <button
              type="button"
              onClick={() => {
                if (h.suggestAdvance) {
                  handleAdvancePhase();
                } else {
                  alert(tip);
                }
              }}
              disabled={advancing || h.isStreaming}
              className={`rounded-full border px-5 py-2 text-sm font-medium transition-all disabled:opacity-40 min-h-[44px] ${
                h.suggestAdvance
                  ? 'border-amber-400 bg-amber-50 text-amber-700 animate-pulse ring-2 ring-amber-400 hover:bg-amber-100'
                  : 'border-border text-muted-foreground hover:border-foreground hover:text-foreground'
              }`}
            >
              {advancing ? '推进中...' : phaseLabel}
            </button>
          </div>

          <div className="mx-auto flex max-w-[720px] items-end gap-3">
            <VoiceInput onTranscription={handleTranscription} disabled={h.isStreaming} />
            <textarea ref={h.inputRef} value={h.inputValue} onChange={(e) => h.setInputValue(e.target.value)} onKeyDown={handleKeyDown}
              placeholder="和萃取师一起，探索你的经验与价值…"
              disabled={h.isStreaming} rows={1}
              className="flex-1 resize-none rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-foreground placeholder-muted-foreground-2 outline-none transition-all focus:border-foreground focus:ring-1 focus:ring-foreground/20 disabled:opacity-50"
              style={{ minHeight: '52px', maxHeight: '120px' }}
              onInput={(e) => { const t = e.target as HTMLTextAreaElement; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 120) + 'px'; }} />
            <button type="button" onClick={h.handleSend} disabled={!h.inputValue.trim() || h.isStreaming}
              className="flex h-[52px] w-[52px] flex-shrink-0 items-center justify-center rounded-xl bg-foreground text-white transition-all hover:bg-primary disabled:cursor-not-allowed disabled:opacity-40">
              {h.isStreaming ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" /> : <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" /></svg>}
            </button>
          </div>

          {/* 结束访谈按钮 */}
          <div className="mx-auto flex max-w-[720px] justify-center mt-3">
            <button type="button" onClick={handleForceComplete} disabled={ending || h.isStreaming}
              className="rounded-lg bg-danger-bg px-6 py-2 text-sm font-medium text-danger hover:bg-danger-bg/80 disabled:opacity-40 transition-colors min-h-[44px]">
              {ending ? '正在生成报告...' : '结束访谈，生成报告'}
            </button>
          </div>

          <p className="mt-2 text-center text-xs text-muted-foreground-2">空格键长按录音</p>
        </div>
      )}

      {h.showResumeModal && state.session && (
        <ResumeModal open={h.showResumeModal} topic={state.session.topic} currentPhase={state.session.currentPhase}
          lastActiveAt={state.session.lastActiveAt} onResume={h.handleResume} onRestart={h.handleRestart}
          onClose={() => h.setShowResumeModal(false)} />
      )}
    </div>
  );
}
