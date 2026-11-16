'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import {
  getRun, listRunItems, listItems, getRunItem, cancelRun, retryFailedRun, finalizeRun, reviewRunItem,
  getRunReport, PURPOSE_LABELS,
  RUN_STATUS_LABELS, RUN_STATUS_CLS, REVIEW_STATUS_LABELS, VERDICT_LABELS, VERDICT_CLS,
  ITEM_STATUS_LABELS, REVIEW_ACTION_LABELS, isRunTerminal,
  type EvaluationRun, type EvaluationRunItem, type EvaluationItem, type EvaluationReport,
  type EvaluationReportView,
} from '@/lib/api/evaluation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import {
  getRunDiff, promoteRunIssues, pollContentJob, startRegression,
  type EvaluationRunDiff, type ItemDiffChange,
} from '@/lib/api/content';

const POLL_MS = 3000;

const DIFF_CHANGE_LABELS: Record<ItemDiffChange, string> = {
  same: '无变化',
  improved: '分数提升',
  regressed: '分数下降',
  flipped_pass: '未过 → 通过',
  flipped_fail: '通过 → 未过',
};

const DIFF_CHANGE_CLS: Record<ItemDiffChange, string> = {
  same: 'bg-gray-100 text-gray-600',
  improved: 'bg-success-bg text-success',
  regressed: 'bg-danger-bg text-danger',
  flipped_pass: 'bg-success-bg text-success',
  flipped_fail: 'bg-danger-bg text-danger',
};

/** 安全解析 JSON 展示（解析失败原样返回） */
function pretty(json: string | null): string {
  if (!json) return '—';
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

/** 从 score_detail / rule_check_result 里取检索指标 */
function readRetrieval(json: string | null): {
  // 批次级（score_detail.retrieval）
  items?: number; recallAtK?: number; precisionAtK?: number; mrr?: number;
  fullyHitItems?: number; itemsWithMiss?: number;
  // 单题级（rule_check_result.retrieval_metrics）
  expectedCount?: number; retrievedCount?: number; hitExpected?: number; hitRetrieved?: number;
  recall?: number; missedExpected?: string[];
} | null {
  if (!json) return null;
  try {
    const root = JSON.parse(json);
    return root.retrieval || root.retrieval_metrics || null;
  } catch {
    return null;
  }
}

/** 从 ai_score 里取展示字段 */
function readScore(json: string | null): { total?: number; confidence?: number; needReview?: boolean; verdictSuggestion?: string; reason?: string } {
  if (!json) return {};
  try {
    const s = JSON.parse(json);
    return {
      total: s.total_score,
      confidence: s.confidence,
      needReview: s.need_review,
      verdictSuggestion: s.verdict_suggestion,
      reason: s.reason,
    };
  } catch {
    return {};
  }
}

export default function EvaluationRunDetailPage() {
  const params = useParams();
  const router = useRouter();
  const runId = params.id as string;

  const [run, setRun] = useState<EvaluationRun | null>(null);
  const [rows, setRows] = useState<EvaluationRunItem[]>([]);
  const [questions, setQuestions] = useState<Record<string, EvaluationItem>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [report, setReport] = useState<EvaluationReportView | null>(null);
  /** 逐题行超过单次加载上限时的总数（0=未截断） */
  const [truncatedTotal, setTruncatedTotal] = useState(0);

  // 回归对比 / 错题回流
  const [diff, setDiff] = useState<EvaluationRunDiff | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [promote, setPromote] = useState<{ created: number; knowledgeIds: string[]; errors: string[] } | null>(null);
  const [promoting, setPromoting] = useState(false);
  const [regressing, setRegressing] = useState(false);

  // 详情抽屉
  const [detail, setDetail] = useState<EvaluationRunItem | null>(null);
  const [reviewAction, setReviewAction] = useState<'confirm' | 'adjust' | 'undetermined'>('confirm');
  const [reviewReason, setReviewReason] = useState('');
  const [adjustedTotal, setAdjustedTotal] = useState('');
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState(false);

  const loadCore = useCallback(async () => {
    const r = await getRun(runId);
    setRun(r);
    const items = await listRunItems(runId, 1, 200);
    setRows(items.content || []);
    // 单次最多加载 200 行；超出时明确提示，不静默截断
    setTruncatedTotal((items.total || 0) > (items.content?.length || 0) ? items.total : 0);
    return r;
  }, [runId]);

  const loadQuestions = useCallback(async (r: EvaluationRun) => {
    try {
      const p = await listItems(r.datasetId, r.datasetVersion, 1, 200);
      const map: Record<string, EvaluationItem> = {};
      (p.content || []).forEach(i => { map[i.id] = i; });
      setQuestions(map);
    } catch {
      setQuestions({});
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    loadCore()
      .then(r => { if (!cancelled) loadQuestions(r); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载失败'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [loadCore, loadQuestions]);

  // 执行中轮询（终态停止）；页面隐藏时暂停，回到前台立即刷新
  useEffect(() => {
    if (!run || isRunTerminal(run.status)) return;
    const timer = setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      loadCore().catch(() => {});
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [run, loadCore]);

  // 已定稿报告：刷新页面后直接加载，不需要再点一次「定稿」
  useEffect(() => {
    if (!run || run.reviewStatus !== 'finalized' || report) return;
    let cancelled = false;
    getRunReport(runId)
      .then(r => { if (!cancelled) setReport(r); })
      .catch(() => { /* 报告缺失不阻塞页面 */ });
    return () => { cancelled = true; };
  }, [run, report, runId]);

  const runAction = async (fn: () => Promise<unknown>) => {
    setActing(true);
    setActionError(null);
    try {
      const result = await fn();
      if (result && typeof result === 'object' && 'content' in (result as object)) {
        const r = result as EvaluationReport;
        setReport({
          id: r.id, runId: r.runId, version: r.version, status: r.status,
          summary: r.summary, content: r.content, finalizedAt: r.finalizedAt,
        });
      }
      await loadCore();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const openDetail = async (row: EvaluationRunItem) => {
    try {
      const full = await getRunItem(runId, row.itemId);
      setDetail(full);
      setReviewAction('confirm');
      setReviewReason('');
      setAdjustedTotal('');
      setReviewError(null);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '加载单题失败');
    }
  };

  const submitReview = async () => {
    if (!detail) return;
    setReviewError(null);
    if (!reviewReason.trim()) { setReviewError('复核理由必填'); return; }
    if (reviewAction === 'adjust') {
      const n = Number(adjustedTotal);
      if (!adjustedTotal.trim() || Number.isNaN(n) || n < 0 || n > 100) {
        setReviewError('改分需填 0-100 的分数');
        return;
      }
    }
    setReviewing(true);
    try {
      await reviewRunItem(detail.id, {
        action: reviewAction,
        reason: reviewReason.trim(),
        adjustedScore: reviewAction === 'adjust' ? JSON.stringify({ total_score: Number(adjustedTotal) }) : undefined,
      });
      const refreshed = await getRunItem(runId, detail.itemId);
      setDetail(refreshed);
      setReviewReason('');
      setAdjustedTotal('');
      await loadCore();
    } catch (e) {
      setReviewError(e instanceof Error ? e.message : '复核失败');
    } finally {
      setReviewing(false);
    }
  };

  const onFinalize = () => {
    if (!window.confirm('确认定稿？定稿后冻结本版报告；若之后有人工复核，重新定稿会产生新版本。')) return;
    runAction(() => finalizeRun(runId));
  };

  const loadDiff = async () => {
    if (!run) return;
    setDiffLoading(true);
    setActionError(null);
    try {
      setDiff(await getRunDiff(runId, run.datasetId));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '对比失败');
    } finally {
      setDiffLoading(false);
    }
  };

  const onPromoteIssues = async () => {
    if (!window.confirm('把本批次未通过/执行失败的题各生成一条知识草稿（最多 3 条）？草稿进入 AI 审核台，不会自动生效。')) return;
    setPromoting(true);
    setActionError(null);
    try {
      const accepted = await promoteRunIssues(runId);
      const job = await pollContentJob(accepted.jobId);
      if (job.status === 'failed') {
        setActionError(job.errorMessage || '生成失败');
        return;
      }
      const result = (job.result ?? {}) as { ids?: string[]; errors?: string[] };
      setPromote({
        created: result.ids?.length ?? job.producedCount,
        knowledgeIds: result.ids ?? [],
        errors: result.errors ?? [],
      });
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '生成失败');
    } finally {
      setPromoting(false);
    }
  };

  const onRegression = async () => {
    if (!run) return;
    if (!window.confirm('按同题集/同分身再跑一次评测，并自动以上次已完成批次为基线？')) return;
    setRegressing(true);
    setActionError(null);
    try {
      const result = await startRegression(run.datasetId, run.skillId);
      router.push(`/admin/evaluations/runs/${result.runId}`);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '发起回归失败');
    } finally {
      setRegressing(false);
    }
  };

  if (loading) return <div className="min-h-screen bg-surface px-6 py-8"><LoadingSpinner /></div>;
  if (error || !run) {
    return (
      <div className="min-h-screen bg-surface px-6 py-8">
        <div className="mx-auto max-w-[1080px] rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">
          {error || '批次不存在'}
          <button onClick={() => loadCore()} className="ml-3 underline">重试</button>
        </div>
      </div>
    );
  }

  const duration = run.startedAt && run.finishedAt
    ? `${Math.max(0, Math.round((new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000))} 秒`
    : '—';

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <button onClick={() => router.push('/admin/evaluations/runs')}
          className="mb-3 text-sm text-muted-foreground hover:underline">← 返回运行列表</button>

        <div className="mb-4 flex items-start justify-between">
          <div>
            <h1 className="mb-1 text-[26px] font-bold text-foreground">分身测试过程与结果</h1>
            <p className="text-sm text-muted-foreground">
              运行 {run.id.slice(0, 8)} · 题集 v{run.datasetVersion} · 分身 {run.skillId.slice(0, 8)} · 用途 {PURPOSE_LABELS[run.purpose] || run.purpose} · 耗时 {duration}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <PageHelp
              title="这一页怎么看"
              intro="这一页就是分身的测试过程和结果：上面是整体情况，下面是每一道题的作答与评分。"
              steps={[
                { title: '看整体', desc: '计数卡显示总题数、执行成功/错误、通过/未通过/未判定；均分与检索指标在这一行。' },
                { title: '看单题', desc: '逐题表每一行是一道题；点右侧「看回答」查看分身实际回答、检索到的知识/案例、AI 评分理由。' },
                { title: '复核', desc: '在单题抽屉里确认、改分或标记无法判断（理由必填）。最终分以人工改分为准。' },
                { title: '定稿', desc: '复核完成后点「定稿出报告」，报告显示在下方并可导出 Markdown。' },
              ]}
              notes={[
                '运行还在执行时页面会自动刷新进度，可以离开页面稍后回来看。',
                '执行失败的题可以「重试失败题」；业务低分不能通过重试刷分。',
                '失败/未通过的题可以直接「补知识/补案例」，补完生效后再跑一次同题集。',
              ]}
            />
            <span className={`inline-flex px-3 py-1 rounded-full text-xs font-medium ${RUN_STATUS_CLS[run.status] || 'bg-gray-100 text-gray-600'}`}>
              {RUN_STATUS_LABELS[run.status] || run.status}
            </span>
            <span className="inline-flex px-3 py-1 rounded-full text-xs font-medium bg-gray-100 text-gray-600">
              {REVIEW_STATUS_LABELS[run.reviewStatus] || run.reviewStatus}
            </span>
          </div>
        </div>

        {actionError && (
          <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">{actionError}</div>
        )}

        {/* 计数卡 */}
        <div className="mb-5 grid grid-cols-6 gap-3">
          {[
            ['总题数', run.totalItems], ['执行成功', run.completedItems], ['执行错误', run.errorItems],
            ['通过', run.passedItems], ['未通过', run.failedItems], ['未判定', run.undeterminedItems],
          ].map(([label, value]) => (
            <div key={label as string} className="rounded-xl border border-border bg-surface-2 px-4 py-3">
              <p className="text-xs text-muted-foreground">{label}</p>
              <p className="mt-1 text-xl font-semibold text-foreground">{value as number}</p>
            </div>
          ))}
        </div>

        <div className="mb-5 flex flex-wrap items-center gap-3">
          <span className="text-sm text-foreground">均分：<b>{run.scoreAvg == null ? '—' : run.scoreAvg}</b></span>
          {(() => {
            const r = readRetrieval(run.scoreDetail);
            if (!r) return null;
            return (
              <span className="text-sm text-foreground">
                检索指标（{r.items} 题）：Recall@K <b>{r.recallAtK}</b> · Precision@K <b>{r.precisionAtK}</b> · MRR <b>{r.mrr}</b>
                <span className="ml-1 text-xs text-muted-foreground">（全命中 {r.fullyHitItems} / 有遗漏 {r.itemsWithMiss}）</span>
              </span>
            );
          })()}
          <span className="text-sm text-muted-foreground">用量：{run.usageTokens} tokens</span>
          {!isRunTerminal(run.status) && (
            <button onClick={() => { if (window.confirm('确认取消该批次？未执行的题目不再执行，已完成证据保留。')) runAction(() => cancelRun(runId)); }}
              disabled={acting}
              className="rounded-md border border-border px-3 py-1.5 text-sm text-danger hover:bg-danger-bg disabled:opacity-50">取消批次</button>
          )}
          {isRunTerminal(run.status) && run.errorItems > 0 && (
            <button onClick={() => runAction(() => retryFailedRun(runId))} disabled={acting}
              className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">重试失败题</button>
          )}
          {isRunTerminal(run.status) && (
            <button onClick={onFinalize} disabled={acting}
              className="rounded-md bg-primary px-4 py-1.5 text-sm text-white hover:opacity-90 disabled:opacity-50">
              {run.reviewStatus === 'finalized' ? '查看已定稿报告' : '定稿出报告'}
            </button>
          )}
        </div>

        {isRunTerminal(run.status) && (
          <div className="mb-5 rounded-xl border border-border bg-surface-2 p-5">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <p className="text-sm font-semibold text-foreground">回归对比与错题回流</p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  对比同题集/同分身上一次已完成批次；把本批次未通过的题转成知识草稿（需审核后生效）。
                </p>
              </div>
              <div className="flex gap-2">
                <button onClick={loadDiff} disabled={diffLoading}
                  className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">
                  {diffLoading ? '对比中…' : diff ? '重新对比' : '与上次批次对比'}
                </button>
                <button onClick={onPromoteIssues} disabled={promoting}
                  className="rounded-md border border-primary px-3 py-1.5 text-sm text-primary hover:bg-primary-light disabled:opacity-50">
                  {promoting ? '生成中…' : '错题补知识'}
                </button>
                <button onClick={onRegression} disabled={regressing}
                  className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">
                  {regressing ? '发起中…' : '再跑一次并对比基线'}
                </button>
              </div>
            </div>

            {diff && (() => {
              const changed = diff.items.filter(i => i.change !== 'same');
              return (
                <div className="mt-3 rounded-lg border border-border bg-surface p-4">
                  <p className="text-sm text-foreground">
                    基线均分 <b>{diff.baselineAvg}</b> → 本次 <b>{diff.currentAvg}</b>
                    <span className="ml-3 text-xs text-muted-foreground">
                      提升 {diff.improved} · 下降 {diff.regressed} · 翻盘通过 {diff.flippedPass} · 翻盘未过 {diff.flippedFail}
                    </span>
                  </p>
                  {changed.length === 0 ? (
                    <p className="mt-2 text-xs text-muted-foreground">逐题结论与分数没有变化。</p>
                  ) : (
                    <div className="mt-2 space-y-1">
                      {changed.map(d => (
                        <div key={d.itemId} className="flex items-center gap-2 rounded bg-surface-2 px-3 py-1.5 text-xs">
                          <span className="min-w-0 flex-1 truncate text-foreground" title={d.question || d.itemId}>
                            {d.question || d.itemId.slice(0, 8)}
                          </span>
                          <span className="text-muted-foreground">{d.baselineScore ?? '—'} → {d.currentScore ?? '—'}</span>
                          <span className={`rounded-full px-2 py-0.5 ${DIFF_CHANGE_CLS[d.change] || 'bg-gray-100 text-gray-600'}`}>
                            {DIFF_CHANGE_LABELS[d.change] || d.change}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })()}

            {promote && (
              <div className="mt-3 rounded-lg border border-border bg-surface p-4 text-xs text-foreground">
                {promote.created === 0 ? (
                  <p className="text-muted-foreground">本批次暂没有可回流的失败题。</p>
                ) : (
                  <>
                    <p>已生成 <b>{promote.created}</b> 条知识草稿：</p>
                    <div className="mt-1 flex flex-wrap gap-3">
                      {promote.knowledgeIds.map(kid => (
                        <Link key={kid} href={`/admin/knowledge/${kid}`} className="text-primary hover:underline">
                          草稿 {kid.slice(0, 8)}
                        </Link>
                      ))}
                    </div>
                  </>
                )}
                {promote.errors.length > 0 && (
                  <p className="mt-1 text-warning-text">部分题目未生成：{promote.errors.join('；')}</p>
                )}
                <p className="mt-1 text-muted-foreground">草稿进入 AI 审核台，通过后才会被分身引用。</p>
              </div>
            )}
          </div>
        )}

        {report && (
          <div className="mb-5 rounded-xl border border-border bg-surface-2 p-5">
            <div className="mb-2 flex items-center justify-between">
              <p className="text-sm font-semibold text-foreground">报告 v{report.version}（{report.status === 'finalized' ? '已定稿' : '草稿'}）</p>
              <button
                onClick={() => {
                  const blob = new Blob([report.content || ''], { type: 'text/markdown;charset=utf-8' });
                  const url = URL.createObjectURL(blob);
                  const a = document.createElement('a');
                  a.href = url;
                  a.download = `评测报告-v${report.version}.md`;
                  a.click();
                  URL.revokeObjectURL(url);
                }}
                className="rounded border border-border px-2 py-1 text-xs text-muted-foreground hover:bg-surface"
              >导出 Markdown</button>
            </div>
            <pre className="max-h-72 overflow-auto whitespace-pre-wrap text-xs text-foreground">{report.content}</pre>
          </div>
        )}

        {/* 逐题表 */}
        {truncatedTotal > 0 && (
          <div className="mb-2 rounded-lg bg-warning-bg px-4 py-2 text-xs text-warning-text">
            本批次共 {truncatedTotal} 题，当前只显示前 {rows.length} 题。
          </div>
        )}
        <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
          <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border">
            <span className="col-span-6">题目</span>
            <span className="col-span-2">执行态</span>
            <span className="col-span-2">结论</span>
            <span className="col-span-1 text-right">分数</span>
            <span className="col-span-1 text-right">操作</span>
          </div>
          {rows.length === 0 ? (
            <div className="px-5 py-10 text-center text-sm text-muted-foreground">暂无逐题记录</div>
          ) : rows.map(row => {
            const q = questions[row.itemId];
            const score = readScore(row.aiScore);
            return (
              <div key={row.id} className="grid grid-cols-12 gap-2 px-5 py-3 border-b border-border last:border-0 items-center hover:bg-primary-light">
                <span className="col-span-6 truncate text-sm text-foreground" title={q?.question || row.itemId}>
                  {q?.question || row.itemId.slice(0, 8)}
                </span>
                <span className="col-span-2 text-xs text-muted-foreground">
                  {ITEM_STATUS_LABELS[row.status] || row.status}
                  {row.errorMessage ? <span className="ml-1 text-danger" title={row.errorMessage}>!</span> : null}
                </span>
                <span className="col-span-2">
                  {row.verdict ? (
                    <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${VERDICT_CLS[row.verdict] || 'bg-gray-100 text-gray-600'}`}>
                      {VERDICT_LABELS[row.verdict] || row.verdict}
                    </span>
                  ) : <span className="text-xs text-muted-foreground">—</span>}
                  {score.needReview && <span className="ml-1 text-xs text-warning-text">必审</span>}
                </span>
                <span className="col-span-1 text-right text-sm text-foreground">
                  {row.finalScore ?? score.total ?? '—'}
                  {row.scoreSource === 'manual' && (
                    <span className="ml-1 text-[10px] text-primary">人工</span>
                  )}
                </span>
                <div className="col-span-1 flex justify-end">
                  <button onClick={() => openDetail(row)} className="text-xs text-primary font-medium hover:underline">看回答</button>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* 详情抽屉 */}
      {detail && (
        <div className="fixed inset-0 z-50 flex justify-end bg-black/30" onClick={() => setDetail(null)}>
          <div className="h-full w-[560px] overflow-y-auto bg-surface p-6" onClick={e => e.stopPropagation()}>
            <div className="mb-4 flex items-start justify-between">
              <div>
                <h2 className="text-lg font-semibold text-foreground">单题详情</h2>
                <p className="mt-1 text-xs text-muted-foreground">
                  尝试 #{detail.attemptNo} · 执行态 {ITEM_STATUS_LABELS[detail.status] || detail.status} · 结论 {detail.verdict ? (VERDICT_LABELS[detail.verdict] || detail.verdict) : '—'}
                  {detail.finalScore != null && (
                    <> · 最终分 {detail.finalScore}{detail.scoreSource === 'manual' ? '（人工）' : '（AI）'}</>
                  )}
                </p>
                {detail.errorMessage && (
                  <div className="mt-2 rounded-md bg-danger-bg px-3 py-2 text-xs text-danger">
                    失败原因：{detail.errorMessage}
                  </div>
                )}
                {(detail.verdict === 'fail' || detail.status === 'error') && (
                  <div className="mt-2 flex gap-2 text-xs">
                    <Link
                      href={`/admin/knowledge/new?title=${encodeURIComponent((questions[detail.itemId]?.question || '').slice(0, 60))}&content=${encodeURIComponent(`问题：${questions[detail.itemId]?.question || ''}\n\n（分身当前回答不足，请补充权威口径）`)}`}
                      className="rounded-full border border-primary px-3 py-1 text-primary hover:bg-primary-light"
                    >补一条知识</Link>
                    <Link
                      href={`/admin/cases/new?title=${encodeURIComponent((questions[detail.itemId]?.question || '').slice(0, 60))}&objection=${encodeURIComponent(questions[detail.itemId]?.question || '')}`}
                      className="rounded-full border border-primary px-3 py-1 text-primary hover:bg-primary-light"
                    >补一个案例</Link>
                  </div>
                )}
              </div>
              <button onClick={() => setDetail(null)} className="text-sm text-muted-foreground hover:underline">关闭</button>
            </div>

            <section className="mb-4">
              <p className="mb-1 text-sm font-semibold text-foreground">题目</p>
              <p className="text-sm text-foreground">{questions[detail.itemId]?.question || '（题目已删除或不在本版本）'}</p>
            </section>

            <section className="mb-4">
              <p className="mb-1 text-sm font-semibold text-foreground">分身回答</p>
              <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded-md border border-border bg-surface-2 p-3 text-xs text-foreground">
                {detail.aiAnswer || '—'}
              </pre>
            </section>

            <section className="mb-4">
              <p className="mb-1 text-sm font-semibold text-foreground">检索证据</p>
              <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-border bg-surface-2 p-3 text-xs text-foreground">
                {pretty(detail.retrievalEvidence)}
              </pre>
            </section>

            {(() => {
              const r = readRetrieval(detail.ruleCheckResult);
              if (!r) return null;
              return (
                <section className="mb-4">
                  <p className="mb-1 text-sm font-semibold text-foreground">检索指标</p>
                  <p className="text-sm text-foreground">
                    Recall@K <b>{r.recallAtK}</b> · Precision@K <b>{r.precisionAtK}</b> · MRR <b>{r.mrr}</b>
                    <span className="ml-2 text-xs text-muted-foreground">
                      命中 {r.hitExpected}/{r.expectedCount}（检回 {r.retrievedCount} 条）
                    </span>
                  </p>
                  {(r.missedExpected?.length ?? 0) > 0 && (
                    <p className="mt-1 text-xs text-warning-text">未检回：{r.missedExpected!.join('、')}</p>
                  )}
                </section>
              );
            })()}

            <section className="mb-4">
              <p className="mb-1 text-sm font-semibold text-foreground">规则检查</p>
              <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-md border border-border bg-surface-2 p-3 text-xs text-foreground">
                {pretty(detail.ruleCheckResult)}
              </pre>
            </section>

            <section className="mb-4">
              <p className="mb-1 text-sm font-semibold text-foreground">评分</p>
              <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-md border border-border bg-surface-2 p-3 text-xs text-foreground">
                {pretty(detail.aiScore)}
              </pre>
            </section>

            {(detail.reviews?.length ?? 0) > 0 && (
              <section className="mb-4">
                <p className="mb-1 text-sm font-semibold text-foreground">复核历史</p>
                {detail.reviews!.map(r => (
                  <div key={r.id} className="mb-2 rounded-md border border-border bg-surface-2 p-3 text-xs text-foreground">
                    <p><b>{REVIEW_ACTION_LABELS[r.action] || r.action}</b> · {new Date(r.createdAt).toLocaleString()}</p>
                    <p className="mt-1 text-muted-foreground">{r.reason}</p>
                    {r.adjustedScore && <p className="mt-1">改分：{pretty(r.adjustedScore)}</p>}
                  </div>
                ))}
              </section>
            )}

            {detail.status === 'completed' && (
              <section className="rounded-md border border-border bg-surface-2 p-4">
                <p className="mb-2 text-sm font-semibold text-foreground">人工复核</p>
                <div className="mb-2 flex gap-3 text-sm">
                  {(['confirm', 'adjust', 'undetermined'] as const).map(a => (
                    <label key={a} className="flex items-center gap-1 text-foreground">
                      <input type="radio" checked={reviewAction === a} onChange={() => setReviewAction(a)} />
                      {a === 'confirm' ? '确认 AI 结论' : a === 'adjust' ? '改分' : '无法判断'}
                    </label>
                  ))}
                </div>
                {reviewAction === 'adjust' && (
                  <input value={adjustedTotal} onChange={e => setAdjustedTotal(e.target.value)}
                    placeholder="人工分数 0-100"
                    className="mb-2 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
                )}
                <textarea value={reviewReason} onChange={e => setReviewReason(e.target.value)}
                  placeholder="复核理由（必填）" rows={3}
                  className="mb-2 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
                {reviewError && <p className="mb-2 text-sm text-danger">{reviewError}</p>}
                <div className="flex justify-end">
                  <button onClick={submitReview} disabled={reviewing}
                    className="rounded-full bg-primary px-4 py-2 text-sm text-white hover:opacity-90 disabled:opacity-50">
                    {reviewing ? '提交中…' : '提交复核'}
                  </button>
                </div>
              </section>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
