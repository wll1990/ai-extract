'use client';

import React from 'react';

// ═══ Types ═══

export interface PracticeMessage {
  id?: string; role: 'customer' | 'user'; content: string;
  championAnswer?: string; comparison?: string;
  hits?: string[]; misses?: string[]; technique?: string;
  offTopic?: boolean;
  grains?: Array<{ sceneTag?: string; qualityScore?: number; matchLevel?: string; fileName?: string }>;
  matchLevel?: string; levelLabel?: string; isRetry?: boolean;
  fullAnswer?: string; isLastRetry?: boolean; retryCount?: number;
}

export interface PracticeEval {
  score?: number;
  strengths: Array<{ point: string; quote: string }>;
  improvements: Array<{ point: string; quote: string; suggestion: string }>;
  demo_script: string; next_advice: string;
}

export interface PracticeSource {
  reportId: string; reportTitle: string;
  grainId?: string; grainTitle?: string;
}

export interface PracticeData {
  practiceId: string; scene: { title: string };
}

export interface PracticeViewProps {
  phase: 'active' | 'evaluate';
  currentScene?: PracticeData;
  messages: PracticeMessage[];
  evaluation?: PracticeEval | null;
  sources?: PracticeSource[];
  isStreaming: boolean;
  angleInfo?: { current: number; total: number };
  hint?: string;
  showHint: boolean;
  onToggleHint: () => void;
  onSend: () => void;
  onEnd: () => void;
  onRetry: () => void;
  onBackToQa: () => void;
  onRetryWithTechnique?: () => void;
  onAdvanceRound?: () => void;
  inputValue: string;
  onInputChange: (v: string) => void;
  footer?: React.ReactNode;
}

/**
 * 对练视图 — 实战对话 + 结构化评价 + 教练复盘
 *
 * 所有颜色使用 CSS 变量，支持企业端换肤覆盖。
 * 布局使用 Tailwind 类，颜色通过 style 属性注入。
 */
export function PracticeView({
  phase, currentScene, messages, evaluation, sources, isStreaming,
  angleInfo, hint, showHint, onToggleHint,
  onSend, onEnd, onRetry, onBackToQa, onRetryWithTechnique, onAdvanceRound,
  inputValue, onInputChange, footer,
}: PracticeViewProps) {

  // ═══ active phase ═══
  if (phase === 'active') {
    const userRounds = messages.filter(m => m.role === 'user').length || 1;
    return (
      <div className="space-y-4">
        {/* 场景标签 */}
        <div className="text-center">
          {currentScene && (
            <span className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium"
              style={{ background: 'var(--practice-scene-bg, var(--warning-bg, #fffbeb))', color: 'var(--practice-scene-text, var(--warning-text, #92400e))' }}>
              🎯 {currentScene.scene.title}
              {angleInfo && (
                <span style={{ color: 'var(--muted-foreground-2, #94877c)' }}>
                  · {angleInfo.current}/{angleInfo.total} 角度
                  <span className="inline-block ml-0.5 cursor-help" title="同一场景的不同练习切入点" style={{ opacity: 0.6 }}>ⓘ</span>
                </span>
              )}
              <span style={{ color: 'var(--muted-foreground-2, #94877c)' }}>· 第 {userRounds} 轮</span>
            </span>
          )}
        </div>

        {messages.map((msg, i) => {
          const hasEval = msg.role === 'user' && msg.championAnswer;
          const isLatestEval = hasEval && !messages.slice(i + 1).some(lm => lm.role === 'user' && lm.championAnswer);
          return (
          <div key={msg.id || `msg-${i}`}>
            {msg.role === 'customer' ? (
              <div className="flex items-start gap-3">
                <div className="h-8 w-8 rounded-full flex items-center justify-center text-white text-xs font-bold flex-shrink-0 shadow-sm"
                  style={{ background: 'var(--practice-customer-avatar, linear-gradient(135deg, var(--orange, #f97316), var(--warning, #f59e0b)))' }}>
                  客
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs mb-1 font-medium"
                    style={{ color: 'var(--practice-scene-text, var(--warning-text, #92400e))' }}>
                    👤 客户{msg.levelLabel ? ` · ${msg.levelLabel}` : ''}
                  </p>
                  <div className="rounded-2xl rounded-tl-sm px-4 py-3 text-sm shadow-sm"
                    style={{ background: 'var(--practice-eval-bg, var(--warning-bg, #fffbeb))', color: 'var(--foreground, #1f2937)' }}>
                    {msg.content ? (
                      <p className="whitespace-pre-wrap break-words">{msg.content}</p>
                    ) : (
                      <span className="inline-flex gap-1">
                        <span className="animate-pulse" style={{ color: 'var(--warning, #f59e0b)' }}>●</span>
                        <span className="animate-pulse" style={{ color: 'var(--warning, #f59e0b)', animationDelay: '0.2s' }}>●</span>
                        <span className="animate-pulse" style={{ color: 'var(--warning, #f59e0b)', animationDelay: '0.4s' }}>●</span>
                      </span>
                    )}
                  </div>
                  <p className="text-[10px] mt-1 ml-1" style={{ color: 'var(--muted-foreground-2, #94877c)' }}>💡 画像推断</p>
                </div>
              </div>
            ) : (
              <div className="flex justify-end">
                <div className="max-w-[80%]">
                  <div className="rounded-2xl rounded-tr-sm text-white px-4 py-3 text-sm shadow-sm"
                    style={{ background: 'var(--practice-user-bubble, linear-gradient(135deg, #3b82f6, #4f46e5))' }}>
                    <p className="text-xs mb-1" style={{ opacity: 0.9 }}>👤 销售（你）{msg.isRetry ? '· 再试' : ''}</p>
                    <p className="whitespace-pre-wrap break-words">{msg.content}</p>
                  </div>

                  {hasEval && (
                    <div className="mt-2 rounded-lg overflow-hidden" style={{
                      border: msg.offTopic
                        ? '1px solid var(--practice-offtopic-border, #bfdbfe)'
                        : '1px solid var(--practice-eval-border, #fde68a)',
                      background: msg.offTopic
                        ? 'var(--practice-offtopic-bg, #eff6ff)'
                        : 'var(--practice-eval-bg, #fffbeb)',
                    }}>
                      {msg.offTopic ? (
                        <div className="p-3 space-y-2">
                          <p className="text-xs font-semibold" style={{ color: 'var(--practice-offtopic-text, #2563eb)' }}>💡 教练提醒</p>
                          <p className="text-xs" style={{ color: 'var(--muted-foreground, #6b7280)' }}>{msg.comparison}</p>
                          {msg.misses && msg.misses.length > 0 && (
                            <div className="text-[10px]" style={{ color: 'var(--practice-offtopic-text, #2563eb)' }}>
                              <span>建议关注：</span>
                              {msg.misses.map((ms, mi) => <span key={mi}>·{ms} </span>)}
                            </div>
                          )}
                        </div>
                      ) : (
                        <div className="p-3 space-y-2.5">
                          <div>
                            <p className="text-xs font-semibold mb-1" style={{ color: 'var(--practice-eval-title, #b45309)' }}>⭐ 销冠会怎么说</p>
                            <p className="text-xs leading-relaxed" style={{ color: 'var(--foreground, #1f2937)' }}>{msg.championAnswer}</p>
                          </div>
                          {msg.hits && msg.hits.length > 0 && (
                            <div className="text-[10px] space-y-0.5">
                              <span className="font-medium" style={{ color: 'var(--practice-hit-text, #16a34a)' }}>✅ 你说到的：</span>
                              {msg.hits.map((h, hi) => <span key={hi} className="ml-1" style={{ color: 'var(--practice-hit-text, #16a34a)' }}>·{h} </span>)}
                            </div>
                          )}
                          {msg.misses && msg.misses.length > 0 && (
                            <div className="text-[10px] space-y-0.5">
                              <span className="font-medium" style={{ color: 'var(--practice-eval-title, #b45309)' }}>💡 进阶建议：</span>
                              {msg.misses.map((ms, mi) => <span key={mi} className="ml-1" style={{ color: 'var(--practice-miss-text, #ef4444)' }}>·{ms} </span>)}
                            </div>
                          )}
                          {msg.comparison && (
                            <p className="text-[10px] pt-1" style={{ color: 'var(--muted-foreground, #6b7280)', borderTop: '1px solid var(--practice-eval-border, #fde68a)' }}>
                              📝 {msg.comparison}
                            </p>
                          )}
                        </div>
                      )}

                      <div className="px-3 pb-3 space-y-2">
                        {msg.technique && (
                          <div className="px-2 py-1.5 rounded text-[10px]" style={{
                            background: 'var(--practice-technique-bg, #faf5ff)',
                            borderColor: 'var(--practice-technique-border, #e9d5ff)',
                            borderWidth: 1, borderStyle: 'solid',
                            color: 'var(--practice-technique-text, #7e22ce)',
                          }}>
                            🏷️ 技法：{msg.technique}
                          </div>
                        )}
                        {msg.grains && msg.grains.length > 0 && (
                          <details className="text-[10px] cursor-pointer" style={{ color: 'var(--muted-foreground-2, #94877c)' }}>
                            <summary>📋 说法依据 · {msg.grains.length}条</summary>
                            <div className="mt-1.5 space-y-1">
                              {msg.grains.filter((g, gi, arr) => arr.findIndex(x => x.fileName === g.fileName) === gi).slice(0, 5).map((g, gi) => {
                                const icon = g.matchLevel === 'EXACT' ? '📋' : g.matchLevel === 'SEMANTIC' ? '🔗' : '💡';
                                return (
                                  <div key={gi} className="rounded px-2 py-1 border"
                                    style={{ background: 'var(--surface, #fff)', borderColor: 'var(--border, #e5e7eb)', color: 'var(--foreground, #1f2937)' }}>
                                    <span>{icon} {g.sceneTag || g.fileName || '真实素材'}</span>
                                    {g.fileName && g.sceneTag && <span className="ml-1" style={{ color: 'var(--muted-foreground-2, #94877c)' }}>· {g.fileName}</span>}
                                    {g.qualityScore != null && <span className="ml-1">⭐{g.qualityScore.toFixed(1)}</span>}
                                  </div>
                                );
                              })}
                              {msg.grains.length > 5 && <span>...共{msg.grains.length}条</span>}
                            </div>
                          </details>
                        )}
                      </div>

                      {isLatestEval && !isStreaming && msg.isLastRetry && msg.fullAnswer && (
                        <div className="mx-3 mb-3 p-3 rounded-lg" style={{
                          background: 'var(--practice-full-bg, #f0fdf4)',
                          border: '1px solid var(--practice-full-border, #bbf7d0)',
                        }}>
                          <p className="text-xs font-semibold mb-1.5" style={{ color: 'var(--practice-full-title, #15803d)' }}>📋 完整答案（所有技法角度）</p>
                          <p className="text-xs leading-relaxed whitespace-pre-wrap" style={{ color: 'var(--foreground, #1f2937)' }}>{msg.fullAnswer}</p>
                          <p className="text-[10px] mt-2" style={{ color: 'var(--practice-hit-text, #16a34a)' }}>以上涵盖了该场景的全部技法要点，可以作为参考模板。</p>
                        </div>
                      )}

                      {isLatestEval && !isStreaming && (
                        <div className="flex gap-2 px-3 pb-3">
                          {onRetryWithTechnique && !msg.isLastRetry && (msg.retryCount || 0) < 2 && (
                            <button onClick={onRetryWithTechnique}
                              className="px-3 py-1.5 rounded-lg text-xs font-medium transition-colors"
                              style={{
                                background: 'var(--practice-retry-btn-bg, #f3e8ff)',
                                color: 'var(--practice-retry-btn-text, #7e22ce)',
                              }}>
                              🔄 用技法再试一次
                            </button>
                          )}
                          {onAdvanceRound && (
                            <button onClick={onAdvanceRound}
                              className="px-3 py-1.5 rounded-lg text-xs transition-colors"
                              style={{
                                background: 'var(--practice-advance-btn-bg, var(--primary-light, rgba(37,99,235,0.08)))',
                                color: 'var(--muted-foreground, #6b7280)',
                              }}>
                              ➡️ 继续下一轮
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
          );
        })}

        {/* 分身锦囊 */}
        {hint && (
          <div className="rounded-xl border overflow-hidden"
            style={{ borderColor: 'var(--border, #e5e7eb)', background: 'var(--surface-2, #f9fafb)' }}>
            <button type="button" onClick={onToggleHint}
              className="w-full flex items-center justify-between px-4 py-2.5 text-sm transition-colors"
              style={{ color: 'var(--muted-foreground, #6b7280)' }}>
              <span className="flex items-center gap-2"><span>💡</span><span>分身锦囊</span></span>
              <span className="text-xs">{showHint ? '收起 ▲' : '展开 ▼'}</span>
            </button>
            {showHint && (
              <div className="px-4 pb-3 text-sm pt-3"
                style={{ color: 'var(--practice-scene-text, var(--warning-text, #92400e))', borderTop: '1px solid rgba(251,191,36,0.15)' }}>
                {hint}
              </div>
            )}
          </div>
        )}

        {/* 结束 */}
        <div className="text-center pt-3">
          <button type="button" onClick={onEnd}
            disabled={messages.filter(m => m.role === 'user').length < 1}
            className="rounded-full border px-5 py-2 text-sm transition-colors disabled:opacity-30"
            style={{ borderColor: 'var(--danger, #dc2626)', color: 'var(--danger, #dc2626)' }}>
            结束对话 · 查看复盘
          </button>
        </div>
        {footer}
      </div>
    );
  }

  // ═══ evaluate phase ═══
  if (!evaluation) {
    return (
      <div className="text-center py-10">
        <div className="animate-spin h-8 w-8 border-2 border-t-transparent rounded-full mx-auto"
          style={{ borderColor: 'var(--foreground, #1f2937)', borderTopColor: 'transparent' }} />
        <p className="mt-4 text-sm" style={{ color: 'var(--muted-foreground, #6b7280)' }}>正在生成教练复盘...</p>
      </div>
    );
  }

  const hasWeaknesses = evaluation.improvements && evaluation.improvements.length > 0;
  const hasStrengths = evaluation.strengths && evaluation.strengths.length > 0;
  const hasSuggestions = evaluation.demo_script || evaluation.next_advice;
  const hasSources = sources && sources.length > 0;

  return (
    <div className="space-y-6 py-4">
      <div className="text-center">
        <span className="text-4xl">📋</span>
        <h2 className="mt-3 text-xl font-bold" style={{ color: 'var(--foreground, #1f2937)' }}>教练复盘</h2>
        <p className="mt-1 text-sm" style={{ color: 'var(--muted-foreground, #6b7280)' }}>
          {currentScene?.scene?.title || '对练'} · 共 {messages.filter(m => m.role === 'user').length} 轮回应
        </p>
      </div>

      {hasWeaknesses && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold mb-3"
            style={{ color: 'var(--danger, #dc2626)' }}>
            <span>⚠️</span><span>这 {evaluation.improvements.length} 点可以更好</span>
          </h3>
          <div className="space-y-3">
            {evaluation.improvements.map((imp, i) => (
              <div key={i} className="rounded-xl p-4"
                style={{ background: 'var(--danger-bg, #fef2f2)', border: '1px solid var(--danger, #dc2626)', borderColor: 'rgba(220,38,38,0.2)' }}>
                <p className="text-sm font-medium" style={{ color: 'var(--foreground, #1f2937)' }}>{imp.point}</p>
                {imp.quote && (
                  <p className="mt-1.5 text-sm" style={{ color: 'var(--muted-foreground, #6b7280)' }}>
                    你说：&ldquo;<span style={{ color: 'var(--foreground, #1f2937)' }}>{imp.quote}</span>&rdquo;
                  </p>
                )}
                {imp.suggestion && (
                  <p className="mt-2 text-sm pt-2"
                    style={{ color: 'var(--danger, #dc2626)', borderTop: '1px solid rgba(220,38,38,0.1)' }}>→ {imp.suggestion}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {hasStrengths && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold mb-3"
            style={{ color: 'var(--success, #16a34a)' }}>
            <span>✅</span><span>做得好的</span>
          </h3>
          <div className="space-y-2">
            {evaluation.strengths.map((s, i) => (
              <div key={i} className="rounded-xl p-4"
                style={{ background: 'var(--success-bg, #f0fdf4)', border: '1px solid rgba(22,163,74,0.2)' }}>
                <p className="text-sm font-medium" style={{ color: 'var(--foreground, #1f2937)' }}>{s.point}</p>
                {s.quote && <p className="mt-1 text-sm" style={{ color: 'var(--muted-foreground, #6b7280)' }}>&ldquo;{s.quote}&rdquo;</p>}
              </div>
            ))}
          </div>
        </div>
      )}

      {hasSuggestions && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold mb-3"
            style={{ color: 'var(--primary, #2563eb)' }}>
            <span>💡</span><span>销冠会这样处理</span>
          </h3>
          <div className="rounded-xl p-4"
            style={{ background: 'var(--primary-light, rgba(37,99,235,0.08))', border: '1px solid var(--primary, #2563eb)', borderColor: 'rgba(37,99,235,0.15)' }}>
            {evaluation.demo_script && (
              <p className="text-sm leading-relaxed whitespace-pre-wrap" style={{ color: 'var(--foreground, #1f2937)' }}>{evaluation.demo_script}</p>
            )}
            {evaluation.next_advice && (
              <p className="mt-2 text-sm" style={{ color: 'var(--muted-foreground, #6b7280)' }}>📝 {evaluation.next_advice}</p>
            )}
          </div>
        </div>
      )}

      {hasSources && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold mb-3"
            style={{ color: 'var(--muted-foreground, #6b7280)' }}>
            <span>📎</span><span>说法依据</span>
          </h3>
          <div className="rounded-xl p-4 space-y-3"
            style={{ background: 'var(--surface-2, #f9fafb)', border: '1px solid var(--border, #e5e7eb)' }}>
            {sources.filter(s => s.reportId).filter((r, i, arr) => arr.findIndex(x => x.reportId === r.reportId) === i)
              .map((r, i) => (
                <a key={i} href={`/report/${r.reportId}`} target="_blank" rel="noopener noreferrer"
                  className="flex items-center gap-2 text-sm hover:underline font-medium"
                  style={{ color: 'var(--primary, #2563eb)' }}>
                  <span>📄</span><span>完整报告：《{r.reportTitle || '查看报告'}》</span><span>→</span>
                </a>
              ))}
            {sources.filter(s => s.grainId).filter((g, i, arr) => arr.findIndex(x => x.grainId === g.grainId) === i).length > 0 && (
              <div className="pt-3" style={{ borderTop: '1px solid var(--border, #e5e7eb)' }}>
                <p className="text-xs mb-2" style={{ color: 'var(--muted-foreground-2, #94877c)' }}>相关锦囊：</p>
                <div className="flex flex-wrap gap-1.5">
                  {sources.filter(s => s.grainId).filter((g, i, arr) => arr.findIndex(x => x.grainId === g.grainId) === i)
                    .map((g, i) => (
                      <span key={i} className="rounded text-xs px-2 py-0.5"
                        style={{ background: 'var(--primary-light, rgba(37,99,235,0.08))', color: 'var(--primary, #2563eb)' }}>
                        {g.grainTitle || '经验锦囊'}
                      </span>
                    ))}
                </div>
              </div>
            )}
            <p className="text-xs pt-2" style={{ color: 'var(--muted-foreground-2, #94877c)', borderTop: '1px solid var(--border, #e5e7eb)' }}>
              此对练基于以上销冠真实经验，具有可信溯源
            </p>
          </div>
        </div>
      )}

      <div className="flex justify-center gap-3 pt-4">
        <button onClick={onRetry}
          className="rounded-lg px-6 py-2.5 text-sm font-medium transition-colors"
          style={{ background: 'var(--primary-light, rgba(37,99,235,0.08))', color: 'var(--foreground, #1f2937)' }}>
          🔄 再来一轮
        </button>
        <button onClick={onBackToQa}
          className="rounded-lg px-6 py-2.5 text-sm font-medium text-white transition-colors"
          style={{ background: 'var(--foreground, #1f2937)' }}>
          ↩ 返回问答
        </button>
      </div>
    </div>
  );
}
