'use client';

import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { API_BASE } from '@/lib/api/client';

import React, { useCallback, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { MessageBubble } from '@/components/chat/MessageBubble';
import { QuickReplies, ThinkingCard } from '@aiextract/shared-ui';
import { PortraitCard } from '@aiextract/shared-ui';
import { VoiceRecorder } from '@/components/voice/VoiceRecorder';
import { ResumeModal } from '@/components/modals/ResumeModal';
import { pauseSession, getSession } from '@/lib/api/interview';
import { useInterviewSession } from './useInterviewSession';
import { connectSse } from '@/lib/sse';

const COLLECT_LABELS: Record<string, string> = {
  caseStory: '案例故事', steps: '核心步骤', decision: '关键决策',
  mindset: '专家心法', boundary: '适用边界', checklist: '行动清单',
};

const PHASE_ADVANCE_LABELS: Record<string, string> = {
  opening: '进入萃取 →', storytelling: '故事讲完了，进入下一阶段 →',
  modeling: '步骤清楚了，提炼方法论 →', closing: '聊完了，生成萃取报告 ✓',
};

const PHASE_ADVANCE_TIPS: Record<string, string> = {
  opening: '可以先简单介绍下自己，不用着急推进。',
  storytelling: '多分享一些具体场景和故事细节，效果会更好。',
  modeling: '再聊聊你做决策时的思考过程，不用急着收尾。',
  closing: '确认一下还有没有遗漏的重要经验？',
};

/** 颗粒分级阈值，与 application.yml app.interview.* 保持一致 */
const GRAIN_ENOUGH = 10;
const GRAIN_SUGGEST_MORE = 5;

export function SalesInterviewChat() {
  const params = useParams();
  const router = useRouter();
  const sessionId = params.sessionId as string;
  const h = useInterviewSession(sessionId);
  const { state, dispatch } = h;

  const [ending, setEnding] = useState(false);
  const [advancing, setAdvancing] = useState(false);
  const [skipTopicClicked, setSkipTopicClicked] = useState(false);
  const [newAngleClicked, setNewAngleClicked] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [interimVoiceText, setInterimVoiceText] = useState('');
  const toastTimerRef = useRef<ReturnType<typeof setTimeout>>();

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    clearTimeout(toastTimerRef.current);
    toastTimerRef.current = setTimeout(() => setToast(null), 1800);
  }, []);

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
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      });
      const data = await res.json();
      if (data.code !== 200) {
        showToast(data.message || '结束访谈失败，请重试');
        setEnding(false);
        return;
      }
      // forceComplete 已成功，getSession 失败不影响完成状态
      setEnding(false);
      try {
        const s = await getSession(sessionId);
        dispatch({ type: 'MARK_COMPLETED', reportId: data.data?.sessionId || null, grainCount: s.grainCount });
      } catch {
        dispatch({ type: 'MARK_COMPLETED', reportId: data.data?.sessionId || null });
      }
    } catch {
      showToast('网络错误，请重试');
      setEnding(false);
    }
  }, [sessionId, ending, dispatch, showToast]);

  // "继续补充" — 已完成会话重新打开，AI 聚焦未采集模块
  const handleSupplement = useCallback(async () => {
    h.setIsStreaming(true);
    const aiMsgId = `supplement-${Date.now()}`;
    dispatch({ type: 'ADD_MESSAGE', message: { id: aiMsgId, role: 'ai', content: '', depth: 0, phase: 'modeling', createdAt: new Date().toISOString() } });

    let fullContent = '';
    const ctrl = connectSse(
      { url: `${API_BASE}/interviews/${sessionId}/supplement`, method: 'POST' },
      {
        onChunk: (content) => {
          fullContent += content;
          dispatch({ type: 'UPDATE_AI_MESSAGE', id: aiMsgId, content: fullContent });
        },
        onDone: () => {
          h.setIsStreaming(false);
          getSession(sessionId).then(s => dispatch({ type: 'SET_SESSION', session: s })).catch(() => {});
          dispatch({ type: 'RESUME_CHAT' });
        },
        onError: () => { h.setIsStreaming(false); h.setErrorBanner('补充启动失败，请重试'); },
      },
    );
    h.abortRef.current = ctrl;
  }, [sessionId, dispatch, h.setIsStreaming, h.abortRef, h.setErrorBanner]);

  // 阶段推进 — SSE 流式接收 AI 阶段小结和新阶段引导
  const handleAdvancePhase = useCallback(() => {
    const phase = state.session?.currentPhase || 'opening';
    if (advancing || h.isStreaming) return;
    setAdvancing(true);
    h.setIsStreaming(true);
    h.setSuggestAdvance(false);

    const aiMsgId = `phase-ai-${Date.now()}`;
    dispatch({ type: 'ADD_MESSAGE', message: {
      id: aiMsgId, role: 'ai', content: '', depth: 0,
      phase, createdAt: new Date().toISOString(),
    }});

    let fullContent = '';
    const ctrl = connectSse(
      { url: `${API_BASE}/interviews/${sessionId}/mark-phase-complete`, method: 'POST', body: { phase } },
      {
        onChunk: (content) => {
          fullContent += content;
          dispatch({ type: 'UPDATE_AI_MESSAGE', id: aiMsgId, content: fullContent });
        },
        onPhaseChange: (newPhase) => {
          dispatch({ type: 'SET_SESSION', session: state.session ? { ...state.session, currentPhase: newPhase } : null });
        },
        onCollectUpdate: () => {
          getSession(sessionId).then(s => dispatch({ type: 'SET_SESSION', session: s })).catch(() => {});
        },
        onDone: () => {
          h.setIsStreaming(false);
          setAdvancing(false);
          getSession(sessionId).then(s => {
            dispatch({ type: 'SET_SESSION', session: s });
            if (s.status === 'completed') dispatch({ type: 'MARK_COMPLETED', reportId: s.reportId || null, grainCount: s.grainCount });
          }).catch(console.error);
        },
        onError: (msg) => {
          h.setIsStreaming(false);
          setAdvancing(false);
          h.setErrorBanner(msg || '阶段推进失败');
          dispatch({ type: 'REMOVE_MESSAGE', id: aiMsgId });
        },
      },
    );
    h.abortRef.current = ctrl;
  }, [sessionId, advancing, h.isStreaming, state.session, dispatch, h.setIsStreaming, h.abortRef, h.setSuggestAdvance, h.setErrorBanner]);

  // 隐式反馈：不想聊这个话题
  const handleSkipTopic = useCallback(() => {
    setSkipTopicClicked(true);
    setTimeout(() => setSkipTopicClicked(false), 1500);
    h.setInputValue('[换个话题]');
    h.handleSend();
  }, [h]);

  // 隐式反馈：换个角度
  const handleNewAngle = useCallback(() => {
    setNewAngleClicked(true);
    setTimeout(() => setNewAngleClicked(false), 1500);
    h.setInputValue('[换个角度聊聊]');
    h.handleSend();
  }, [h]);

  const isH5 = typeof window !== 'undefined' && window.location.pathname.startsWith('/h5/');

  // Pause — H5 跳完成页
  const handlePause = useCallback(async () => {
    try {
      await pauseSession(sessionId);
      if (isH5) router.push(`/h5/interview/done?sessionId=${sessionId}`);
      else router.push(`/interview/create?_=${Date.now()}`);
    } catch (err) {
      const msg = (err as Error)?.message || '';
      if (msg.includes('不允许暂停')) {
        h.setErrorBanner('访谈还没正式开始，请先发送一条消息后再暂停');
      } else {
        h.setErrorBanner('暂停失败，请重试');
      }
    }
  }, [sessionId, router, h.setErrorBanner]);

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

      {/* 顶栏 — 品牌化（对齐 preview.html .topbar） */}
      <div className="sticky top-0 z-30 border-b border-[#e1e7f8] bg-white/90 backdrop-blur-sm">
        <div className="flex items-center justify-between px-4 sm:px-6 py-2" style={{ height: 74 }}>
          {/* 左侧品牌 */}
          <div className="flex items-center gap-3 min-w-0">
            <button onClick={() => router.back()} className="text-[#63708f] hover:text-[#10162f] transition-colors flex-shrink-0" title="返回">
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" /></svg>
            </button>
            <img src="/def-avatar.png" alt="logo"
              className="h-[42px] w-[42px] rounded-[13px] object-cover flex-shrink-0"
              style={{ boxShadow: '0 8px 20px rgba(33,71,255,0.18)' }} />
            <div className="min-w-0">
              <div className="text-[16px] max-sm:text-[14px] font-extrabold tracking-[-0.5px] truncate max-w-[260px]" style={{ color: '#10162f' }}>
                {state.session?.topic || 'AI经验萃取师'}
              </div>
              <div className="text-xs text-[#63708f]">
                AI经验萃取师 <span className="inline-flex items-center px-1.5 py-0.5 rounded-md bg-[#efe7ff] text-[#8b5cf6] text-[10px] font-bold">Beta</span>
              </div>
            </div>
          </div>

          {/* 右侧操作 */}
          <div className="flex items-center gap-2">
            {state.session?.reportId && (
              <button type="button" onClick={() => router.push(isH5 ? `/h5/report/${sessionId}` : `/report/${state.session!.reportId}/done`)}
                className="flex items-center gap-2 rounded-[14px] border border-[#ffd0d6] bg-white text-[#e5384c] px-4 h-[42px] font-bold text-sm hover:bg-[#fef2f2] transition-colors"
                style={{ boxShadow: '0 4px 12px rgba(255,77,95,0.05)' }}>
                ▣ <span className="max-sm:hidden">萃取报告</span>
              </button>
            )}
            {!isH5 && !state.isCompleted && (
              <div className="flex flex-col items-center gap-0.5">
                <button type="button" onClick={handlePause} disabled={h.isStreaming}
                  className="rounded-lg p-2 text-muted-foreground hover:text-foreground hover:bg-primary-light disabled:opacity-40 transition-colors" title="保存进度并退出，随时回来继续">⏸</button>
                <span className="text-[9px] text-muted-foreground-2 leading-none">稍后继续</span>
              </div>
            )}
            {!isH5 && (
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
            )}
          </div>
        </div>
      </div>

      {/* 进度条 */}
      {state.session?.phases && state.session.phases.length > 0 && (
        <div className="sticky top-[74px] z-20 border-b border-border bg-surface px-4 sm:px-6 py-2">
          <div className="mx-auto max-w-[720px]">
            {/* 阶段行 */}
            <div className="flex items-center gap-1.5">
              {state.session.phases.map((p, i) => {
                const isDone = p.status === 'completed';
                const isCurrent = p.status === 'current' || p.name === state.session?.currentPhase;
                const dotColor = isDone ? 'text-green-500' : isCurrent ? 'text-amber-500' : 'text-muted-foreground-2';
                const dot = isDone ? '●' : isCurrent ? '◉' : '○';
                return (
                  <React.Fragment key={p.name}>
                    <span className={`text-xs ${dotColor}`}>{dot} {p.label}</span>
                    {i < state.session!.phases.length - 1 && (
                      <span className="text-muted-foreground-2 text-[10px]">─</span>
                    )}
                  </React.Fragment>
                );
              })}
            </div>
            {/* 模块行 */}
            {state.session?.collectStatus && (
              <div className="mt-1 text-[11px] text-muted-foreground">
                {(() => {
                  const cs = state.session.collectStatus;
                  const entries = Object.entries(cs || {});
                  const done = entries.filter(([,v]) => v === 'collected').map(([k]) => COLLECT_LABELS[k] || k);
                  const next = entries.filter(([,v]) => v !== 'collected').map(([k]) => COLLECT_LABELS[k] || k);
                  return (
                    <>
                      已采集 {done.length}/6 模块
                      {done.length > 0 && <span className="text-green-600"> {done.join('、')}</span>}
                      {next.length > 0 && <span className="text-muted-foreground-2"> · 下一步 </span>}
                      {next.length > 0 && <span className="text-amber-600">{next[0]}</span>}
                    </>
                  );
                })()}
              </div>
            )}
          </div>
        </div>
      )}

      {/* 消息区 */}
      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-6">
          <div className="mx-auto max-w-[720px] space-y-4">
            {/* Hero 区 — 对齐 preview.html .hero */}
            {state.messages.length <= 2 && (
              <div className="interview-hero mx-auto max-w-[720px]">
                {/* 主行：头像 + 文案 */}
                <div className="grid grid-cols-[130px_1fr] items-center gap-3 px-4 py-4"
                  style={{ minHeight: 200 }}>
                  <div className="flex justify-center">
                    <PortraitCard alt="AI萃取师" />
                  </div>
                  <div>
                    <h1 className="text-[22px] max-sm:text-[19px] font-extrabold tracking-[-0.5px] mb-2" style={{ color: '#10162f' }}>
                      你好，我是 <strong style={{ color: '#2147ff' }}>萃萃</strong> ✨
                    </h1>
                    <p className="text-sm leading-relaxed" style={{ color: '#10162f' }}>
                      你的 AI 经验萃取师，也是你的思想共创伙伴。
                    </p>
                    <p className="mt-3 font-medium text-sm" style={{ color: '#10162f' }}>
                      我在这里，不是告诉你答案，<br />而是和你一起，<em className="not-italic" style={{ color: '#ff4d5f' }}>发现你未被看见的价值。</em>
                    </p>
                  </div>
                </div>

                {/* 三段 trait */}
                <div className="grid grid-cols-3 gap-3 px-5 py-3 border-t border-[#edf0fb]">
                  {heroTraits.map((t, i) => (
                    <div key={i} className="flex gap-1 items-start px-0.5 py-0.5">
                      <span className="w-[22px] h-[22px] max-sm:w-[18px] max-sm:h-[18px] rounded-full flex items-center justify-center text-[11px] max-sm:text-[9px] flex-shrink-0"
                        style={{
                          background: i === 0 ? '#fff0f2' : '#f3f6ff',
                          color: i === 0 ? '#ff4d5f' : '#2147ff',
                        }}>
                        {t.icon}
                      </span>
                      <div>
                        <div className="text-[11px] max-sm:text-[10px] font-extrabold leading-tight" style={{ color: '#10162f' }}>{t.title}</div>
                        <div className="text-[9px] max-sm:text-[8px] leading-tight" style={{ color: '#55617d' }}>{t.desc}</div>
                      </div>
                    </div>
                  ))}
                </div>

                {/* 隐私提示 */}
                <div className="border-t border-[#edf0fb] text-center text-xs py-3 px-4" style={{ color: '#77819e' }}>
                  <span style={{ color: '#2147ff', marginRight: 7 }}>▣</span>
                  我们的对话内容仅用于服务你，绝不外泄，请放心分享。
                </div>
              </div>
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

            {/* 完成卡片 — 按颗粒数三级渲染 */}
            {state.showCompletionCard && (() => {
              const grainCount = state.completionGrainCount || 0;
              const isEnough = grainCount >= GRAIN_ENOUGH;
              const isClose = grainCount >= GRAIN_SUGGEST_MORE && grainCount < GRAIN_ENOUGH;
              const needMore = GRAIN_ENOUGH - grainCount;

              return (
                <div className="mt-6 rounded-2xl bg-surface-2 p-6 sm:p-8 shadow-lg"
                  style={{ animation: 'slideUp 0.4s ease-out' }}>
                  {/* ≥10 条：达标 */}
                  {isEnough && (
                    <>
                      <div className="text-center"><span className="text-5xl">🎉</span><h3 className="mt-4 text-xl font-bold text-foreground">访谈完成！</h3></div>
                      <p className="mt-3 text-sm text-muted-foreground text-center">已生成 {grainCount} 条经验颗粒，AI 正在分析你的访谈，预计 2-3 分钟生成萃取报告。</p>
                      <div className="mt-6 flex justify-center gap-3">
                        {state.completionReportId && (
                          <button onClick={() => router.push(isH5 ? `/h5/report/${sessionId}` : `/report/session/${sessionId}`)}
                            className="rounded-lg bg-foreground px-6 py-2.5 text-sm text-white hover:bg-primary transition-colors hover:scale-[1.02] hover:shadow-lg">📄 查看萃取报告</button>
                        )}
                      </div>
                    </>
                  )}
                  {/* 5-9 条：接近达标 */}
                  {isClose && (
                    <>
                      <div className="text-center"><span className="text-5xl">📊</span><h3 className="mt-4 text-xl font-bold text-foreground">访谈完成</h3></div>
                      <p className="mt-3 text-sm text-muted-foreground text-center">已生成 {grainCount} 条经验颗粒，距报告标准还差 {needMore} 条。再补充一些案例细节即可生成报告。</p>
                      <div className="mt-6 flex flex-col sm:flex-row justify-center gap-3">
                        <button onClick={handleSupplement}
                          className="rounded-lg bg-[#2147ff] px-6 py-2.5 text-sm text-white hover:scale-[1.02] hover:shadow-lg transition-all">💬 继续补充</button>
                        {state.completionReportId && (
                          <button onClick={() => router.push(isH5 ? `/h5/report/${sessionId}` : `/report/session/${sessionId}`)}
                            className="rounded-lg border border-border px-6 py-2.5 text-sm text-muted-foreground hover:text-foreground transition-colors">📄 查看报告（等待中）</button>
                        )}
                      </div>
                    </>
                  )}
                  {/* <5 条：内容不足 */}
                  {!isEnough && !isClose && (
                    <>
                      <div className="text-center"><span className="text-5xl">⚠️</span><h3 className="mt-4 text-xl font-bold text-foreground">内容还不够丰富</h3></div>
                      <p className="mt-3 text-sm text-muted-foreground text-center">本次仅生成 {grainCount} 条经验颗粒，建议补充更多案例和细节。报告生成需要至少 {GRAIN_ENOUGH} 条颗粒。</p>
                      <div className="mt-6 flex flex-col sm:flex-row justify-center gap-3">
                        <button onClick={handleSupplement}
                          className="rounded-lg bg-[#2147ff] px-6 py-2.5 text-sm text-white hover:scale-[1.02] hover:shadow-lg transition-all">💬 继续补充</button>
                        <button onClick={() => dispatch({ type: 'RESUME_CHAT' })}
                          className="rounded-lg border border-border px-6 py-2.5 text-sm text-muted-foreground hover:text-foreground transition-colors">下次再说</button>
                      </div>
                    </>
                  )}
                  {isH5 && (
                    <p className="mt-4 text-center text-xs text-muted-foreground">
                      💡 完整报告和审核进度请访问{' '}
                      <span className="text-primary font-medium select-all">platform.mindforce.com</span>
                    </p>
                  )}
                </div>
              );
            })()}
            {state.isCompleted && !state.showCompletionCard && (
              <div className="mt-6 rounded-2xl bg-surface-2 p-6 text-center shadow-lg">
                <div className="mb-3 text-4xl">🎉</div>
                <h3 className="mb-2 text-lg font-bold text-foreground">访谈完成！</h3>
                <p className="mb-4 text-sm text-muted-foreground">
                  {isH5
                    ? '你的经验已被记录，报告生成中。'
                    : 'AI 正在分析你的访谈，预计 2-3 分钟出报告。'}
                </p>
                {isH5 && (
                  <p className="mb-4 text-xs text-muted-foreground">
                    完整报告请访问 <span className="text-primary font-medium select-all">platform.mindforce.com</span>
                  </p>
                )}
                <div className="mx-auto h-1 w-48 overflow-hidden rounded-full bg-border"><div className="h-full w-full animate-[shimmer_3s_ease-in-out] bg-primary" /></div>
              </div>
            )}
            <div ref={h.chatEndRef} />
          </div>
        </div>
      </div>

      {/* 底部输入区 — 玻璃态（对齐 preview.html .composer-wrap） */}
      {!state.isCompleted && (
        <div className="sticky bottom-0 z-10 interview-composer-wrap px-4 sm:px-6 py-3 sm:py-4">
          {/* 隐式反馈按钮 */}
          <div className="mx-auto flex max-w-[720px] justify-center gap-2 mb-2 sm:mb-3">
            <button type="button" onClick={handleSkipTopic} disabled={h.isStreaming}
              className="interview-tool-btn rounded-full border px-3 py-1 text-xs text-muted-foreground disabled:opacity-40 min-h-[36px]"
              style={{ borderColor: '#cdd7ff', color: '#2147ff', fontWeight: 700, background: '#fff', boxShadow: '0 3px 8px rgba(33,71,255,0.035)' }}>
              {skipTopicClicked ? '✓ 已切换话题' : '🙅 不想聊这个'}
            </button>
            <button type="button" onClick={handleNewAngle} disabled={h.isStreaming}
              className="interview-tool-btn rounded-full border px-3 py-1 text-xs text-muted-foreground disabled:opacity-40 min-h-[36px]"
              style={{ borderColor: '#cdd7ff', color: '#2147ff', fontWeight: 700, background: '#fff', boxShadow: '0 3px 8px rgba(33,71,255,0.035)' }}>
              {newAngleClicked ? '✓ 已切换角度' : '🔄 换个角度'}
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
                  showToast(tip);
                }
              }}
              disabled={advancing || h.isStreaming}
              className="interview-tool-btn rounded-full border px-5 py-2 text-sm font-medium transition-all disabled:opacity-40 min-h-[44px]"
              style={{
                borderColor: h.suggestAdvance ? '#2147ff' : '#cdd7ff',
                background: h.suggestAdvance ? '#eef2ff' : '#fff',
                color: h.suggestAdvance ? '#2147ff' : undefined,
                boxShadow: h.suggestAdvance ? '0 0 0 4px rgba(33,71,255,0.14)' : '0 3px 8px rgba(33,71,255,0.035)',
              }}
            >
              {advancing ? '推进中...' : phaseLabel}
            </button>
          </div>

          {/* 输入框 + 发送 */}
          <div className="mx-auto flex max-w-[720px] items-end gap-3">
            <div className="flex flex-1 items-center gap-2 rounded-3xl border border-[#aab8ff] bg-white/97 px-3 py-2"
              style={{ boxShadow: '0 12px 28px rgba(37,67,166,0.10)' }}>
              <VoiceRecorder
                onTranscription={(text) => {
                  setInterimVoiceText('');
                  h.setInputValue(prev => prev + text);
                }}
                onInterimText={(text) => setInterimVoiceText(text)}
                disabled={h.isStreaming}
              />
              <textarea ref={h.inputRef}
                value={interimVoiceText || h.inputValue}
                onChange={(e) => { setInterimVoiceText(''); h.setInputValue(e.target.value); }}
                onKeyDown={handleKeyDown}
                placeholder={interimVoiceText ? '' : '和萃取师一起，探索你的经验与价值…'}
                disabled={h.isStreaming} rows={1}
                className="flex-1 resize-none border-0 bg-transparent px-1 py-1.5 text-[15px] text-foreground placeholder-[#a3abc0] outline-none disabled:opacity-50"
                style={{ minHeight: '42px', maxHeight: '120px', lineHeight: 1.6 }}
                onInput={(e) => { const t = e.target as HTMLTextAreaElement; t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 120) + 'px'; }} />
              <button type="button" onClick={h.handleSend} disabled={(!h.inputValue.trim() && !interimVoiceText) || h.isStreaming}
                className="interview-send-btn flex h-[40px] w-[40px] flex-shrink-0 items-center justify-center rounded-full text-white disabled:cursor-not-allowed disabled:opacity-40"
                style={{
                  background: 'linear-gradient(135deg, #2147ff, #3b60ff)',
                  boxShadow: '0 9px 18px rgba(33,71,255,0.25)',
                }}>
                {h.isStreaming ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" /> : <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" /></svg>}
              </button>
            </div>
          </div>

          {/* 结束访谈按钮 */}
          <div className="mx-auto flex max-w-[720px] justify-center mt-3">
            <button type="button" onClick={handleForceComplete} disabled={ending || h.isStreaming}
              className="rounded-lg bg-danger-bg px-6 py-2 text-sm font-medium text-danger hover:bg-danger-bg/80 disabled:opacity-40 transition-colors min-h-[44px]">
              {ending ? '正在生成报告...' : '结束访谈，生成报告'}
            </button>
          </div>

          {/* 底部友好文案（对齐 preview.html .footer-note） */}
          <p className="mt-2 text-center text-xs text-muted-foreground-2">
            <span style={{ color: '#ff4d5f' }}>♡</span> 每一段经历都值得被看见，每一个故事都能启发他人。
          </p>
        </div>
      )}

      {/* Toast（对齐 preview.html .toast） */}
      {toast && (
        <div className="fixed left-1/2 bottom-6 z-50 rounded-xl bg-[#10162f] px-4 py-2.5 text-[13px] text-white pointer-events-none"
          style={{
            transform: 'translateX(-50%)',
            animation: 'toast-in 0.25s ease',
            boxShadow: '0 10px 30px rgba(0,0,0,0.18)',
          }}>
          {toast}
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
