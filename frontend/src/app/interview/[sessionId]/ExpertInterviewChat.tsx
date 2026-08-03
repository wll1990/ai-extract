'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PHASE_LABELS } from '@/lib/constants';
import { API_BASE } from '@/lib/api/client';

import React, { useCallback, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { PhaseProgressBar } from '@/components/chat/PhaseProgressBar';
import { MessageBubble } from '@/components/chat/MessageBubble';
import { VoiceRecorder } from '@/components/voice/VoiceRecorder';
import { ResumeModal } from '@/components/modals/ResumeModal';
import { getSession, pauseSession } from '@/lib/api/interview';
import { connectSse } from '@/lib/sse';
import { useInterviewSession } from './useInterviewSession';

const PHASE_MAP = PHASE_LABELS;
const PHASE_ORDER = ['opening', 'storytelling', 'modeling', 'closing'];

function getNextPhase(cur: string) {
  const idx = PHASE_ORDER.indexOf(cur);
  return idx >= 0 && idx < PHASE_ORDER.length - 1 ? PHASE_ORDER[idx + 1] : cur;
}

function getPhaseButtonLabel(cur: string, interviewType: string) {
  const labels: Record<string, Record<string, string>> = {
    sales: { opening: '我开始讲了 →', storytelling: '故事讲完了，提炼方法 →', modeling: '步骤清楚了，说说边界 →', closing: '都聊完了，生成报告 ✓' },
    expert: { opening: '我想好案例了 →', storytelling: '这段经历讲完了，继续 →', modeling: '框架清楚了，说说边界 →', closing: '都聊完了，生成报告 ✓' },
  };
  return (labels[interviewType] || labels.sales)[cur] || '继续 →';
}

export function ExpertInterviewChat() {
  const params = useParams();
  const router = useRouter();
  const sessionId = params.sessionId as string;
  const h = useInterviewSession(sessionId);
  const { state, dispatch } = h;

  const interviewType = state.session?.interviewType || 'sales';
  const [interimVoiceText, setInterimVoiceText] = useState('');

  // Mark phase complete
  const markPhaseComplete = useCallback(() => {
    const phase = state.session?.currentPhase || 'opening';
    const userMsgs = state.messages.filter(m => m.role === 'user' && m.phase === phase);
    const minMsgs: Record<string, number> = { opening: 0, storytelling: 2, modeling: 1, closing: 1 };
    if (userMsgs.length < (minMsgs[phase] || 0)) {
      const tips: Record<string, string> = { opening: '还没开始聊呢，先说说你最难忘的一个客户案例吧。', storytelling: '故事还不太完整，再多聊几句细节。', modeling: '还没提炼出核心步骤，再想想。', closing: '还没聊适用的边界条件。' };
      alert(tips[phase] || '多聊几句效果更好，不着急推进。');
      return;
    }
    h.setIsStreaming(true);
    const nextP = getNextPhase(phase);
    const isLastPhase = nextP === phase;
    const dividerPhase = isLastPhase ? 'completed' : nextP;
    const dividerLabel = isLastPhase ? '访谈完成' : (PHASE_LABELS[nextP] || '');
    const aiMsgId = `transition-${Date.now()}`;
    dispatch({ type: 'ADD_MESSAGE', message: { id: `phase-divider-${Date.now()}`, role: 'system', content: `―― ${dividerLabel} ――`, depth: 0, phase: dividerPhase, createdAt: new Date().toISOString() } });
    dispatch({ type: 'ADD_MESSAGE', message: { id: aiMsgId, role: 'ai', content: '', depth: 0, phase: dividerPhase, createdAt: new Date().toISOString() } });
    h.setSuggestAdvance(false);

    let fullContent = '';
    const ctrl = connectSse(
      { url: `${API_BASE}/interviews/${sessionId}/mark-phase-complete`, method: 'POST', body: { phase } },
      {
        onChunk: (content) => { fullContent += content; dispatch({ type: 'UPDATE_AI_MESSAGE', id: aiMsgId, content: fullContent }); },
        onPhaseChange: (newPhase) => { dispatch({ type: 'SET_SESSION', session: state.session ? { ...state.session, currentPhase: newPhase } : null }); },
        onCollectUpdate: () => { getSession(sessionId).then(s => dispatch({ type: 'SET_SESSION', session: s })).catch(() => {}); },
        onDone: () => {
          h.setIsStreaming(false);
          getSession(sessionId).then(s => { dispatch({ type: 'SET_SESSION', session: s }); if (s.status === 'completed' && s.reportId) dispatch({ type: 'MARK_COMPLETED', reportId: s.reportId }); }).catch(() => {});
        },
        onError: () => { h.setIsStreaming(false); h.setErrorBanner('阶段推进失败，请重试'); },
      },
    );
    h.abortRef.current = ctrl;
  }, [state.session?.currentPhase, state.messages.length, sessionId, h.setIsStreaming, h.abortRef, h.setSuggestAdvance, h.setErrorBanner, dispatch]);

  // Pause
  const handlePause = useCallback(async () => {
    try { await pauseSession(sessionId); router.push('/interview/create'); }
    catch (err) { console.error('暂停失败:', err); }
  }, [sessionId, router]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); h.handleSend(); } }, [h.handleSend]);

  // JSX — same structure, state refs via hook

  if (h.isLoading) return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <div className="flex flex-col items-center gap-4"><LoadingSpinner fullScreen={false} /><span className="text-sm text-muted-foreground">加载访谈中...</span></div>
    </div>
  );

  return (
    <div className="flex min-h-screen flex-col bg-surface">
      {!h.isOnline && <div className="flex items-center justify-center bg-warning-bg py-2 text-sm text-warning"><svg className="mr-2 h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126z" /></svg>网络连接已断开</div>}

      {h.errorBanner && <div className="flex items-center justify-between bg-danger-bg px-6 py-2 text-sm text-danger"><span>{h.errorBanner}</span><button type="button" onClick={() => h.setErrorBanner(null)} className="ml-4 text-muted-foreground-2 hover:text-muted-foreground">✕</button></div>}

      <div className="sticky top-0 z-30 border-b border-border bg-surface-2 backdrop-blur-sm">
        <div className="flex items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <button onClick={() => router.back()} className="text-muted-foreground-2 hover:text-foreground transition-colors flex-shrink-0" title="返回">
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" /></svg>
            </button>
            <PhaseProgressBar phases={state.session?.phases || [
            { name: 'opening', label: '开场定调', status: 'current' as const }, { name: 'storytelling', label: '故事深描', status: 'pending' as const },
            { name: 'modeling', label: '模型提炼', status: 'pending' as const }, { name: 'closing', label: '收网确认', status: 'pending' as const },
          ]} />
          </div>
          <div className="flex items-center gap-2">
            {!state.isCompleted && <button type="button" onClick={handlePause} disabled={h.isStreaming} className="rounded-lg p-2 text-muted-foreground hover:text-foreground hover:bg-primary-light disabled:opacity-40 transition-colors" title="暂停访谈">⏸</button>}
            {!state.isCompleted && <button type="button" onClick={markPhaseComplete} disabled={h.isStreaming} className={`rounded-lg px-4 py-2 text-sm font-medium transition-all disabled:opacity-40 ${h.suggestAdvance ? 'bg-primary text-white animate-pulse ring-2 ring-amber-400 ring-offset-1' : 'bg-primary/10 text-primary hover:bg-primary/20'}`}>{getPhaseButtonLabel(state.session?.currentPhase || 'opening', interviewType)}</button>}
            <div className="relative">
              <button type="button" onClick={() => h.setShowMoreMenu(!h.showMoreMenu)} className="rounded-lg p-2 text-muted-foreground transition-colors hover:bg-primary-light"><svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}><path strokeLinecap="round" strokeLinejoin="round" d="M12 5v.01M12 12v.01M12 19v.01" /></svg></button>
              {h.showMoreMenu && <div className="absolute right-0 top-full mt-1 w-36 rounded-lg border border-border bg-surface-2 py-1 shadow-lg"><button type="button" onClick={() => { h.setShowMoreMenu(false); h.handleRestart(); }} className="flex w-full items-center gap-2 px-4 py-2 text-sm text-danger transition-colors hover:bg-danger-bg">重新开始</button></div>}
            </div>
          </div>
        </div>
      </div>

      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1 overflow-y-auto px-6 py-6">
          <div className="mx-auto max-w-[720px] space-y-4">
            {state.session?.topic && <h1 className="mb-6 text-center text-xl font-bold text-foreground">{state.session.topic}</h1>}
            {state.messages.map((msg, index) => {
              if (msg.role === 'system' && msg.content.startsWith('――')) return <div key={msg.id} className="flex items-center gap-4 py-2"><div className="flex-1 border-t border-border" /><span className={`text-sm font-medium ${msg.phase === 'completed' ? 'text-success' : 'text-primary'}`}>{msg.content.replace(/――/g, '').trim()}</span><div className="flex-1 border-t border-border" /></div>;
              if (msg.role === 'system') return <div key={msg.id} className="my-4 rounded-lg bg-primary-light py-2 text-center text-xs text-muted-foreground">{msg.content}</div>;

              const isFirstAiInPhase = msg.role === 'ai' && msg.depth === 0 && msg.phase !== 'completed' && !state.messages.slice(0, index).some(m => m.role === 'ai' && m.phase === msg.phase);
              const advanceIdx = msg.content.indexOf('【建议推进】');
              const hasAdvance = advanceIdx !== -1;

              return (
                <div key={msg.id}>
                  {isFirstAiInPhase && msg.phase ? (
                    <div className="rounded-2xl border-l-[3px] border-primary bg-warning-bg/30 px-5 py-4 shadow-sm">
                      <div className="flex items-center gap-2 mb-2"><span className="text-sm">🎯</span><span className="text-xs font-medium text-primary">{PHASE_MAP[msg.phase] || msg.phase}</span></div>
                      <div className="text-sm text-foreground whitespace-pre-wrap break-words">{hasAdvance ? msg.content.substring(0, advanceIdx).trim() : msg.content}</div>
                    </div>
                  ) : (
                    <MessageBubble role={msg.role} content={hasAdvance ? msg.content.substring(0, advanceIdx).trim() : msg.content} depth={msg.depth} phase={msg.phase} createdAt={msg.createdAt} isNew={msg.id.startsWith('temp') || msg.id.startsWith('streaming') || msg.id.startsWith('transition')} />
                  )}
                </div>
              );
            })}

            {state.showCompletionCard && (
              <div className="mt-6 rounded-2xl bg-surface-2 p-8 shadow-lg">
                <div className="text-center"><span className="text-5xl">🎉</span><h3 className="mt-4 text-xl font-bold text-foreground">访谈完成！</h3></div>
                {state.session?.collectStatus && (
                  <div className="mt-6 grid grid-cols-2 gap-2">
                    {[{ key: 'caseStory', label: '案例故事' }, { key: 'steps', label: '核心步骤' }, { key: 'decision', label: '关键决策' }, { key: 'mindset', label: '专家心法' }, { key: 'boundary', label: '适用边界' }, { key: 'checklist', label: '行动清单' }].map(({ key, label }) => (
                      <div key={key} className="flex items-center gap-2 rounded-lg bg-success-bg px-3 py-2 text-sm"><span>✅</span><span className="text-success">{label}</span></div>
                    ))}
                  </div>
                )}
                <p className="mt-4 text-sm text-muted-foreground text-center">报告已生成，你的经验将被更多同事学习和使用</p>
                <div className="mt-6 flex justify-center gap-3">
                  {state.completionReportId && <button type="button" onClick={() => router.push(`/report/${state.completionReportId}/done`)} className="rounded-lg bg-foreground px-6 py-2.5 text-sm text-white hover:bg-primary transition-colors">查看萃取报告 →</button>}
                </div>
              </div>
            )}
            {state.isCompleted && !state.showCompletionCard && <div className="mt-6 rounded-2xl bg-surface-2 p-6 text-center shadow-lg"><div className="mb-3 text-4xl">🎉</div><h3 className="mb-2 text-lg font-bold text-foreground">访谈完成！</h3><p className="mb-4 text-sm text-muted-foreground">正在生成萃取报告...</p><div className="mx-auto h-1 w-48 overflow-hidden rounded-full bg-border"><div className="h-full w-full animate-[shimmer_3s_ease-in-out] bg-primary" /></div></div>}
            <div ref={h.chatEndRef} />
          </div>
        </div>

        {state.showCollectPanel && state.session?.collectStatus && (
          <div className="hidden lg:flex w-[260px] flex-shrink-0 flex-col border-l border-border bg-surface-2 overflow-y-auto">
            <div className="flex items-center justify-between px-4 py-3 border-b border-primary-light/30"><h3 className="text-sm font-semibold text-foreground">📋 萃取进度</h3><button type="button" onClick={() => dispatch({ type: 'SET_COLLECT_PANEL', show: false })} className="text-xs text-muted-foreground-2 hover:text-muted-foreground">收起</button></div>
            <div className="flex-1 px-4 py-4 space-y-2">
              {[{ key: 'caseStory', label: '案例故事', phase: 'storytelling' }, { key: 'steps', label: '核心步骤模型', phase: 'modeling' }, { key: 'decision', label: '关键决策点', phase: 'modeling' }, { key: 'mindset', label: '专家心法', phase: 'modeling' }, { key: 'boundary', label: '适用边界', phase: 'closing' }, { key: 'checklist', label: '行动检查清单', phase: 'closing' }].map(({ key, label, phase }) => {
                const isDone = state.session!.collectStatus[key] === 'done';
                const isCurrentPhase = state.session!.currentPhase === phase;
                return <div key={key} className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors ${isDone ? 'bg-success-bg' : isCurrentPhase ? 'bg-warning-bg/30' : 'bg-surface'}`}><span className="text-base">{isDone ? '✅' : isCurrentPhase ? '🔄' : '⬜'}</span><div><p className={`font-medium ${isDone ? 'text-success' : isCurrentPhase ? 'text-warning' : 'text-muted-foreground-2'}`}>{label}</p><p className="text-xs text-muted-foreground-2">{isDone ? '已采集' : isCurrentPhase ? '采集中...' : '待采集'}</p></div></div>;
              })}
            </div>
            <div className="border-t border-primary-light/30 px-4 py-3"><p className="text-xs text-muted-foreground-2 mb-1">🧠 萃取风格</p><p className="text-sm font-medium text-foreground">{state.session!.expertSkillUsed || '综合 · 基础版'}</p></div>
          </div>
        )}
        {!state.showCollectPanel && state.session?.collectStatus && <button type="button" onClick={() => dispatch({ type: 'SET_COLLECT_PANEL', show: true })} className="absolute right-4 top-24 lg:hidden rounded-lg bg-surface-2 border border-border px-3 py-1.5 text-xs text-muted-foreground shadow-sm hover:text-foreground">📋 进度</button>}
      </div>

      {!state.isCompleted && (
        <div className="sticky bottom-0 border-t border-border bg-surface-2 px-6 py-4">
          <div className="mx-auto flex max-w-[720px] items-end gap-3">
            <div className="relative flex-1">
              <div className="absolute left-2 z-10" style={{ top: '50%', transform: 'translateY(-50%)' }}>
                <VoiceRecorder
                  onTranscription={(text) => {
                    setInterimVoiceText('');
                    h.setInputValue(prev => prev + text);
                  }}
                  onInterimText={(text) => setInterimVoiceText(text)}
                  disabled={h.isStreaming}
                />
              </div>
              <textarea ref={h.inputRef}
                value={interimVoiceText || h.inputValue}
                onChange={(e) => { setInterimVoiceText(''); h.setInputValue(e.target.value); }}
                onKeyDown={handleKeyDown}
                placeholder={interimVoiceText ? '' : (state.session?.currentPhase === 'opening' ? '输入你的案例故事...' : state.session?.currentPhase === 'storytelling' ? '继续讲述细节...' : state.session?.currentPhase === 'modeling' ? '总结你的核心步骤...' : '说说适用边界...')}
                disabled={h.isStreaming} rows={1}
                className="w-full resize-none rounded-xl border border-border bg-surface-2 py-3 text-sm text-foreground placeholder:text-muted-foreground-2 outline-none transition-all focus:border-foreground focus:ring-1 focus:ring-foreground/20 disabled:opacity-50"
                style={{ minHeight: '52px', maxHeight: '120px', paddingLeft: '44px', paddingRight: '12px' }}
                onInput={(e) => { const t = e.target as HTMLTextAreaElement; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 120) + 'px'; }} />
            </div>
            <button type="button" onClick={h.handleSend} disabled={(!h.inputValue.trim() && !interimVoiceText) || h.isStreaming} className="flex h-[52px] w-[52px] flex-shrink-0 items-center justify-center rounded-xl bg-foreground text-white transition-all hover:bg-primary disabled:cursor-not-allowed disabled:opacity-40">
              {h.isStreaming ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" /> : <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" /></svg>}
            </button>
          </div>
          <p className="mt-2 text-center text-xs text-muted-foreground-2">点击麦克风开始语音输入</p>
        </div>
      )}

      {h.showResumeModal && state.session && <ResumeModal open={h.showResumeModal} topic={state.session.topic} currentPhase={state.session.currentPhase} lastActiveAt={state.session.lastActiveAt} onResume={h.handleResume} onRestart={h.handleRestart} onClose={() => h.setShowResumeModal(false)} />}
    </div>
  );
}
