'use client';

import React from 'react';

// ---- Types ----

export interface PracticeMessage {
  id?: string;
  role: 'customer' | 'user';
  content: string;
  championAnswer?: string;
  comparison?: string;
  hits?: string[];
  misses?: string[];
  technique?: string;
  offTopic?: boolean;
  grains?: Array<{ sceneTag?: string; qualityScore?: number; matchLevel?: string; fileName?: string }>;
  matchLevel?: string;
  levelLabel?: string;
  isRetry?: boolean;
  fullAnswer?: string;
  isLastRetry?: boolean;
  retryCount?: number;
}

export interface PracticeEval {
  score?: number;
  strengths: Array<{ point: string; quote: string }>;
  improvements: Array<{ point: string; quote: string; suggestion: string }>;
  demo_script: string;
  next_advice: string;
}

export interface PracticeSource {
  reportId: string;
  reportTitle: string;
  grainId?: string;
  grainTitle?: string;
}

export interface PracticeData {
  practiceId: string;
  scene: { title: string };
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
  /** 用技法再试 — 保留评价，重新输入 */
  onRetryWithTechnique?: () => void;
  /** 继续下一轮（下一个角度） */
  onAdvanceRound?: () => void;
  /** 点击溯源按钮 */
  onTraceClick?: (grainIds: string) => void;
  inputValue: string;
  onInputChange: (v: string) => void;
  footer?: React.ReactNode;
}

/**
 * 对练视图 — 实战对话 + 结构化评价 + 教练复盘
 *
 * 设计对齐审核页 ProductDemoModal 的对练体验：
 * - 客户气泡（琥珀色）+ 画像推断标签
 * - 销售气泡（深色）+ 销冠对比卡片
 * - hits/misses/技法 分块展示
 * - 用技法再试 / 继续下一轮
 * - 真实溯源数据
 */
export function PracticeView({
  phase, currentScene, messages, evaluation, sources, isStreaming,
  angleInfo, hint, showHint, onToggleHint,
  onSend, onEnd, onRetry, onBackToQa, onRetryWithTechnique, onAdvanceRound,
  onTraceClick, inputValue, onInputChange, footer,
}: PracticeViewProps) {

  // ========== 阶段一：实战对话 ==========
  if (phase === 'active') {
    const userRounds = messages.filter(m => m.role === 'user').length || 1;
    return (
      <div className="space-y-4">
        {/* 场景标签 */}
        <div className="text-center">
          {currentScene && (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-warning-bg px-3 py-1 text-xs text-warning-text font-medium">
              🎯 {currentScene.scene.title}
              {angleInfo && (
                <span className="text-muted-foreground-2">
                  · {angleInfo.current}/{angleInfo.total} 角度
                  <span className="inline-block ml-0.5 text-muted-foreground-2/60 cursor-help" title="同一场景的不同练习切入点，由 AI 从多角度追问">ⓘ</span>
                </span>
              )}
              <span className="text-muted-foreground-2">· 第 {userRounds} 轮</span>
            </span>
          )}
        </div>

        {messages.map((msg, i) => {
          const hasEval = msg.role === 'user' && msg.championAnswer;
          const isLatestEval = hasEval && !messages.slice(i + 1).some(lm => lm.role === 'user' && lm.championAnswer);
          return (
          <div key={msg.id || `msg-${i}`}>
            {msg.role === 'customer' ? (
              /* ---- 客户消息 ---- */
              <div className="flex items-start gap-3">
                <div className="h-8 w-8 rounded-full bg-gradient-to-br from-orange to-warning flex items-center justify-center text-white text-xs font-bold flex-shrink-0 shadow-sm">
                  客
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs text-warning-text mb-1 font-medium">
                    👤 客户{msg.levelLabel ? ` · ${msg.levelLabel}` : ''}
                  </p>
                  <div className="rounded-2xl rounded-tl-sm bg-warning-bg px-4 py-3 text-sm text-foreground shadow-sm">
                    {msg.content ? (
                      <p className="whitespace-pre-wrap break-words">{msg.content}</p>
                    ) : (
                      <span className="inline-flex gap-1">
                        <span className="animate-pulse text-warning">●</span>
                        <span className="animate-pulse text-warning" style={{ animationDelay: '0.2s' }}>●</span>
                        <span className="animate-pulse text-warning" style={{ animationDelay: '0.4s' }}>●</span>
                      </span>
                    )}
                  </div>
                  {/* 溯源匹配度 + 溯源按钮 */}
                  {(() => {
                    const sim = msg.avgSimilarity ? Number(msg.avgSimilarity) : 0;
                    const hasTrace = !!(msg.grainIds && msg.grainCount && msg.grainCount > 0);
                    if (sim >= 30) return (
                      <div className="flex items-center gap-2 mt-1.5 ml-1">
                        {sim >= 50 ? (
                          <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-green-50 text-green-700 border border-green-200">
                            🏅 精准匹配
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-blue-50 text-blue-700 border border-blue-200">
                            📎 关联匹配
                          </span>
                        )}
                        {hasTrace && (
                          <button onClick={() => onTraceClick?.(msg.grainIds!)} className="text-[10px] text-muted-foreground hover:text-primary transition-colors">
                            溯源 · {msg.grainCount} 条 →
                          </button>
                        )}
                      </div>
                    );
                    if (sim > 0 && sim < 30) return (
                      <p className="text-[10px] text-muted-foreground-2 mt-1 ml-1">✦ 综合画像生成</p>
                    );
                    return null;
                  })()}
                  {/* 画像推断 — AI 对客户人设的推断 */}
                  <p className="text-[10px] text-muted-foreground-2 mt-1 ml-1">
                    💡 画像推断
                  </p>
                </div>
              </div>
            ) : (
              /* ---- 销售消息 + 评价卡片 ---- */
              <div className="flex justify-end">
                <div className="max-w-[80%]">
                  <div className="rounded-2xl rounded-tr-sm bg-gradient-to-br from-blue-500 to-indigo-600 text-white px-4 py-3 text-sm shadow-sm">
                    <p className="text-xs text-blue-100 mb-1">👤 销售（你）{msg.isRetry ? '· 再试' : ''}</p>
                    <p className="whitespace-pre-wrap break-words">{msg.content}</p>
                  </div>

                  {/* 结构化评价卡片 */}
                  {hasEval && (
                    <div className={`mt-2 rounded-lg overflow-hidden ${msg.offTopic ? 'bg-blue-50 border border-blue-200' : 'bg-amber-50 border border-amber-200'}`}>
                      {msg.offTopic ? (
                        /* 跑题提醒 */
                        <div className="p-3 space-y-2">
                          <p className="text-xs font-semibold text-blue-600">💡 教练提醒</p>
                          <p className="text-xs text-muted-foreground">{msg.comparison}</p>
                          {msg.misses && msg.misses.length > 0 && (
                            <div className="text-[10px] text-blue-500">
                              <span>建议关注：</span>
                              {msg.misses.map((ms, mi) => <span key={mi}>·{ms} </span>)}
                            </div>
                          )}
                        </div>
                      ) : (
                        /* 正常评价 */
                        <div className="p-3 space-y-2.5">
                          {/* 销冠会怎么说 */}
                          <div>
                            <p className="text-xs font-semibold text-amber-700 mb-1">⭐ 销冠会怎么说</p>
                            <p className="text-xs text-foreground leading-relaxed">{msg.championAnswer}</p>
                          </div>
                          {/* hits */}
                          {msg.hits && msg.hits.length > 0 && (
                            <div className="text-[10px] space-y-0.5">
                              <span className="text-green-600 font-medium">✅ 你说到的：</span>
                              {msg.hits.map((h, hi) => <span key={hi} className="text-green-600 ml-1">·{h} </span>)}
                            </div>
                          )}
                          {/* misses */}
                          {msg.misses && msg.misses.length > 0 && (
                            <div className="text-[10px] space-y-0.5">
                              <span className="text-amber-600 font-medium">💡 进阶建议：</span>
                              {msg.misses.map((ms, mi) => <span key={mi} className="text-red-500 ml-1">·{ms} </span>)}
                            </div>
                          )}
                          {/* comparison */}
                          {msg.comparison && (
                            <p className="text-[10px] text-muted-foreground pt-1 border-t border-amber-100">
                              📝 {msg.comparison}
                            </p>
                          )}
                        </div>
                      )}

                      {/* 技法 + 溯源 */}
                      <div className="px-3 pb-3 space-y-2">
                        {msg.technique && (
                          <div className="px-2 py-1.5 bg-purple-50 border border-purple-200 rounded text-[10px] text-purple-700">
                            🏷️ 技法：{msg.technique}
                          </div>
                        )}
                        {msg.grains && msg.grains.length > 0 && (
                          <details className="text-[10px] text-muted-foreground-2 cursor-pointer">
                            <summary className="hover:text-muted-foreground">
                              📋 溯源 · {msg.grains.length}条
                            </summary>
                            <div className="mt-1.5 space-y-1">
                              {msg.grains.filter((g, gi, arr) => arr.findIndex(x => x.fileName === g.fileName) === gi).slice(0, 5).map((g, gi) => {
                                const icon = g.matchLevel === 'EXACT' ? '📋' : g.matchLevel === 'SEMANTIC' ? '🔗' : '💡';
                                return (
                                  <div key={gi} className="bg-surface rounded px-2 py-1 border border-border">
                                    <span>{icon} {g.sceneTag || g.fileName || '真实素材'}</span>
                                    {g.fileName && g.sceneTag && <span className="ml-1 text-muted-foreground-2">· {g.fileName}</span>}
                                    {g.qualityScore != null && <span className="ml-1">⭐{g.qualityScore.toFixed(1)}</span>}
                                  </div>
                                );
                              })}
                              {msg.grains.length > 5 && (
                                <span className="text-muted-foreground-2">...共{msg.grains.length}条</span>
                              )}
                            </div>
                          </details>
                        )}
                      </div>

                      {/* 完整答案聚合卡 — 最后一轮重试后展示 */}
                      {isLatestEval && !isStreaming && msg.isLastRetry && msg.fullAnswer && (
                        <div className="mx-3 mb-3 p-3 bg-green-50 border border-green-200 rounded-lg">
                          <p className="text-xs font-semibold text-green-700 mb-1.5">📋 完整答案（所有技法角度）</p>
                          <p className="text-xs text-foreground leading-relaxed whitespace-pre-wrap">{msg.fullAnswer}</p>
                          <p className="text-[10px] text-green-600 mt-2">以上涵盖了该场景的全部技法要点，可以作为参考模板。</p>
                        </div>
                      )}

                      {/* 操作按钮 — 只在最新一条评价上显示 */}
                      {isLatestEval && !isStreaming && (
                        <div className="flex gap-2 px-3 pb-3">
                          {onRetryWithTechnique && !msg.isLastRetry && (msg.retryCount || 0) < 2 && (
                            <button onClick={onRetryWithTechnique}
                              className="px-3 py-1.5 bg-purple-100 text-purple-700 rounded-lg text-xs hover:bg-purple-200 font-medium transition-colors">
                              🔄 用技法再试一次
                            </button>
                          )}
                          {onAdvanceRound && (
                            <button onClick={onAdvanceRound}
                              className="px-3 py-1.5 bg-primary-light text-muted-foreground rounded-lg text-xs hover:bg-border transition-colors">
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
          <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
            <button type="button" onClick={onToggleHint}
              className="w-full flex items-center justify-between px-4 py-2.5 text-sm text-muted-foreground hover:bg-surface transition-colors">
              <span className="flex items-center gap-2"><span>💡</span><span>分身锦囊</span></span>
              <span className="text-xs">{showHint ? '收起 ▲' : '展开 ▼'}</span>
            </button>
            {showHint && (
              <div className="px-4 pb-3 text-sm text-warning-text border-t border-warning-bg/50 pt-3">{hint}</div>
            )}
          </div>
        )}

        {/* 结束对话 */}
        <div className="text-center pt-3">
          <button type="button" onClick={onEnd}
            disabled={messages.filter(m => m.role === 'user').length < 1}
            className="rounded-full border border-danger px-5 py-2 text-sm text-danger hover:bg-danger-bg transition-colors disabled:opacity-30">
            结束对话 · 查看复盘
          </button>
        </div>
        {footer}
      </div>
    );
  }

  // ========== 阶段二：教练复盘（四段式，无评分） ==========
  if (!evaluation) {
    return (
      <div className="text-center py-10">
        <div className="animate-spin h-8 w-8 border-2 border-foreground border-t-transparent rounded-full mx-auto" />
        <p className="mt-4 text-sm text-muted-foreground">正在生成教练复盘...</p>
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
        <h2 className="mt-3 text-xl font-bold text-foreground">教练复盘</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {currentScene?.scene?.title || '对练'} · 共 {messages.filter(m => m.role === 'user').length} 轮回应
        </p>
      </div>

      {hasWeaknesses && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-danger mb-3">
            <span>⚠️</span><span>这 {evaluation.improvements.length} 点可以更好</span>
          </h3>
          <div className="space-y-3">
            {evaluation.improvements.map((imp, i) => (
              <div key={i} className="rounded-xl bg-danger-bg border border-danger/20 p-4">
                <p className="text-sm font-medium text-foreground">{imp.point}</p>
                {imp.quote && (
                  <p className="mt-1.5 text-sm text-muted-foreground">
                    你说：&ldquo;<span className="text-foreground">{imp.quote}</span>&rdquo;
                  </p>
                )}
                {imp.suggestion && (
                  <p className="mt-2 text-sm text-danger border-t border-danger/10 pt-2">→ {imp.suggestion}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {hasStrengths && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-success mb-3">
            <span>✅</span><span>做得好的</span>
          </h3>
          <div className="space-y-2">
            {evaluation.strengths.map((s, i) => (
              <div key={i} className="rounded-xl bg-success-bg border border-success/20 p-4">
                <p className="text-sm font-medium text-foreground">{s.point}</p>
                {s.quote && <p className="mt-1 text-sm text-muted-foreground">&ldquo;{s.quote}&rdquo;</p>}
              </div>
            ))}
          </div>
        </div>
      )}

      {hasSuggestions && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-primary mb-3">
            <span>💡</span><span>销冠会这样处理</span>
          </h3>
          <div className="rounded-xl bg-primary-light border border-primary/15 p-4">
            {evaluation.demo_script && (
              <p className="text-sm text-foreground leading-relaxed whitespace-pre-wrap">{evaluation.demo_script}</p>
            )}
            {evaluation.next_advice && (
              <p className="mt-2 text-sm text-muted-foreground">📝 {evaluation.next_advice}</p>
            )}
          </div>
        </div>
      )}

      {hasSources && (
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-muted-foreground mb-3">
            <span>📎</span><span>溯源内容</span>
          </h3>
          <div className="rounded-xl bg-surface-2 border border-border p-4 space-y-3">
            {sources.filter(s => s.reportId).filter((r, i, arr) => arr.findIndex(x => x.reportId === r.reportId) === i)
              .map((r, i) => (
                <a key={i} href={`/report/${r.reportId}`} target="_blank" rel="noopener noreferrer"
                  className="flex items-center gap-2 text-sm text-primary hover:underline font-medium">
                  <span>📄</span><span>完整报告：《{r.reportTitle || '查看报告'}》</span><span>→</span>
                </a>
              ))}
            {sources.filter(s => s.grainId).filter((g, i, arr) => arr.findIndex(x => x.grainId === g.grainId) === i)
              .length > 0 && (
              <div className="border-t border-border pt-3">
                <p className="text-xs text-muted-foreground-2 mb-2">相关锦囊：</p>
                <div className="flex flex-wrap gap-1.5">
                  {sources.filter(s => s.grainId).filter((g, i, arr) => arr.findIndex(x => x.grainId === g.grainId) === i)
                    .map((g, i) => (
                      <span key={i} className="rounded bg-primary-light text-primary text-xs px-2 py-0.5">
                        {g.grainTitle || '经验锦囊'}
                      </span>
                    ))}
                </div>
              </div>
            )}
            <p className="text-xs text-muted-foreground-2 border-t border-border pt-2">
              此对练基于以上销冠真实经验，具有可信溯源
            </p>
          </div>
        </div>
      )}

      <div className="flex justify-center gap-3 pt-4">
        <button onClick={onRetry}
          className="rounded-lg bg-primary-light px-6 py-2.5 text-sm font-medium text-foreground hover:bg-primary/15 transition-colors">
          🔄 再来一轮
        </button>
        <button onClick={onBackToQa}
          className="rounded-lg bg-foreground px-6 py-2.5 text-sm font-medium text-white hover:bg-foreground/90 transition-colors">
          ↩ 返回问答
        </button>
      </div>
    </div>
  );
}
