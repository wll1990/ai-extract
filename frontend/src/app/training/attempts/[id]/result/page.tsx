'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getAttemptResult, startAttempt,
  ATTEMPT_STATUS_LABELS, TERMINATION_LABELS, TASK_TYPE_LABELS, statusCls,
  type AttemptResultView,
} from '@/lib/api/training';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';

const RETRY_BLOCKED_LABELS: Record<string, string> = {
  pending_review: '本次评分已转主管复核，请等待裁决',
  attempt_limit: '次数已用尽，请联系主管安排补训',
  assignment_closed: '培训已结束，不能再重试',
  deadline: '已过截止时间，请联系主管延期',
  attempt_in_flight: '还有一次作答未结束，请先完成或等待评分',
  task_terminal: '该任务已完成',
  task_state: '任务状态异常，请联系主管',
};

export default function TrainingResultPage() {
  const router = useRouter();
  const params = useParams();
  const attemptId = String(params.id);

  const [view, setView] = useState<AttemptResultView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [pollFailed, setPollFailed] = useState(false);
  const failuresRef = useRef(0);

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);
    try {
      setView(await getAttemptResult(attemptId));
      failuresRef.current = 0;
      setPollFailed(false);
    } catch (e) {
      if (!silent) {
        setError(e instanceof Error ? e.message : '加载失败');
      } else {
        // 静默轮询不能无限吞错：连续失败后提示手动刷新
        failuresRef.current += 1;
        if (failuresRef.current >= 3) setPollFailed(true);
      }
    } finally {
      if (!silent) setLoading(false);
    }
  }, [attemptId]);

  useEffect(() => { load(); }, [load]);

  // 非终态：服务端状态会自己变（评分、复核），前端定时取
  const attemptStatus = view?.attempt.status;
  useEffect(() => {
    const polling = attemptStatus === 'submitted' || attemptStatus === 'grading'
      || attemptStatus === 'pending_review';
    if (!polling) return;
    // 待复核可能持续较久，拉长轮询间隔
    const interval = attemptStatus === 'pending_review' ? 20000 : 5000;
    const timer = setInterval(() => load(true), interval);
    return () => clearInterval(timer);
  }, [attemptStatus, load]);

  const onRetry = async () => {
    if (!view) return;
    setRetrying(true);
    try {
      const attempt = await startAttempt(view.attempt.taskRecordId);
      if (attempt.attempt.status !== 'in_progress') {
        setToast('当前不能开始新的尝试，请稍后刷新');
        return;
      }
      if (view.task.type === 'exam') {
        router.push(`/training/attempts/${attempt.attempt.id}`);
      } else {
        router.push(`/training/assignments/${view.attempt.assignmentId}/tasks/${view.attempt.taskId}`);
      }
    } catch (e) {
      setToast(e instanceof Error ? e.message : '无法开始新的尝试');
    } finally {
      setRetrying(false);
    }
  };

  if (loading) return <div className="p-8"><LoadingSpinner /></div>;
  if (error) {
    return (
      <div className="p-8">
        <div className="rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
          {error}
          <button onClick={() => load()} className="ml-3 underline">重试</button>
        </div>
      </div>
    );
  }
  if (!view) return null;

  const { attempt, task, score, questions, answers, reviews } = view;
  const finalized = attempt.status === 'finalized';
  const passing = finalized && attempt.result === 'pass';
  const items = score?.items || [];

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[860px]">
        <button onClick={() => router.push('/training')}
          className="mb-2 text-xs text-muted-foreground hover:underline">← 返回我的培训</button>

        <div className="mb-4">
          <div className="mb-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <span className="rounded bg-primary-light px-2 py-0.5 text-primary">
              {TASK_TYPE_LABELS[task.type] || task.type}
            </span>
            <span>第 {attempt.attemptNo} 次尝试</span>
            <span className={`inline-flex rounded-full border px-2 py-0.5 ${statusCls(attempt.status)}`}>
              {ATTEMPT_STATUS_LABELS[attempt.status] || attempt.status}
            </span>
            {attempt.terminationReason && (
              <span className="text-amber-700">
                {TERMINATION_LABELS[attempt.terminationReason] || attempt.terminationReason}
              </span>
            )}
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h1 className="text-[26px] font-bold text-foreground">{task.title} · 成绩</h1>
            <button onClick={() => load()} disabled={loading}
              className="rounded-full border border-border px-4 py-1.5 text-xs text-foreground hover:bg-primary-light disabled:opacity-50">
              刷新
            </button>
          </div>
        </div>

        {pollFailed && (
          <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            自动刷新失败，上方状态可能已过期，请点「刷新」重试。
          </div>
        )}

        {/* 状态卡 */}
        {finalized ? (
          <section className={`mb-5 rounded-xl border p-6 ${
            passing ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'}`}>
            <div className={`text-2xl font-bold ${passing ? 'text-green-700' : 'text-red-700'}`}>
              {passing ? '通过' : '未通过'}
            </div>
            <div className="mt-2 flex flex-wrap items-end gap-6">
              <div>
                <div className="text-xs text-muted-foreground">总分</div>
                <div className="text-3xl font-semibold tabular-nums text-foreground">
                  {score?.total_score ?? '—'}
                </div>
              </div>
              <div className="space-y-1 text-sm text-muted-foreground">
                <div>及格线 {score?.pass_threshold ?? task.passCriteria?.minScore ?? '—'} 分</div>
                {score?.point_coverage != null && (
                  <div>要点覆盖 {(Number(score.point_coverage) * 100).toFixed(0)}%</div>
                )}
                {score?.answered_items != null && (
                  <div>作答 {score.answered_items}/{score.total_items ?? questions.length} 题</div>
                )}
                {score?.confidence != null && (
                  <div>AI 置信度 {Number(score.confidence).toFixed(2)}</div>
                )}
              </div>
            </div>
            {score?.human_reviewed && (
              <div className="mt-3 rounded-lg bg-white/60 px-3 py-2 text-xs text-foreground">
                本次结论经主管人工复核
                {score.ai_total_score != null && score.ai_total_score !== score.total_score
                  ? `（AI 原始总分 ${score.ai_total_score}，复核后 ${score.total_score}）`
                  : ''}
              </div>
            )}
            {typeof score?.reason === 'string' && score.reason && (
              <div className="mt-3 text-sm leading-6 text-foreground">{score.reason}</div>
            )}
            {typeof score?.improvement === 'string' && score.improvement && (
              <div className="mt-3 rounded-lg border border-primary/30 bg-primary-light/40 px-3 py-2 text-sm leading-6 text-foreground">
                {score.improvement}
              </div>
            )}
            {Array.isArray(score?.missed_points) && score.missed_points.length > 0 && (
              <div className="mt-2 text-xs text-muted-foreground">
                未覆盖：{score.missed_points.slice(0, 5).map((p: string) => `「${p}」`).join('、')}
              </div>
            )}
          </section>
        ) : attempt.status === 'pending_review' ? (
          <section className="mb-5 rounded-xl border border-amber-200 bg-amber-50 p-6">
            <div className="text-xl font-semibold text-amber-800">等待主管复核</div>
            <p className="mt-2 text-sm leading-6 text-amber-800">
              本次尝试需要人工确认（例如临界分、命中红线或系统判定的边界情况）。复核完成后这里会显示最终结论。
            </p>
            {attempt.submittedAt && (
              <p className="mt-2 text-xs text-amber-700">
                交卷时间 {new Date(attempt.submittedAt).toLocaleString()}
              </p>
            )}
          </section>
        ) : attempt.status === 'error' ? (
          <section className="mb-5 rounded-xl border border-red-200 bg-red-50 p-6">
            <div className="text-xl font-semibold text-red-700">评分失败</div>
            <p className="mt-2 text-sm leading-6 text-red-700">{attempt.errorMessage || '评分过程中出现异常，已通知管理员处理。'}</p>
          </section>
        ) : attempt.status === 'in_progress' ? (
          <section className="mb-5 rounded-xl border border-blue-200 bg-blue-50 p-6">
            <div className="text-xl font-semibold text-blue-800">本次尝试还在进行中</div>
            <p className="mt-2 text-sm leading-6 text-blue-800">尚未交卷，没有成绩。</p>
            <button
              onClick={() => router.push(task.type === 'exam'
                ? `/training/attempts/${attempt.id}`
                : `/training/assignments/${attempt.assignmentId}/tasks/${attempt.taskId}`)}
              className="mt-3 rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90">
              继续作答
            </button>
          </section>
        ) : (
          <section className="mb-5 rounded-xl border border-border bg-surface-2 p-6">
            <div className="text-xl font-semibold text-foreground">已交卷，正在评分</div>
            <p className="mt-2 text-sm text-muted-foreground">评分通常在几秒内完成，本页会自动刷新。</p>
          </section>
        )}

        {/* 微练场景 */}
        {task.type === 'drill' && view.scenario?.scenario && (
          <section className="mb-5 rounded-xl border border-border bg-surface-2 p-5">
            <h2 className="mb-2 text-base font-semibold text-foreground">练习场景</h2>
            <p className="text-sm leading-6 text-foreground">{view.scenario.scenario}</p>
            {view.scenario.clientRole && (
              <p className="mt-1 text-xs text-muted-foreground">客户角色：{view.scenario.clientRole}</p>
            )}
          </section>
        )}

        {/* 考试/微练逐题结果 */}
        {(task.type === 'exam' || task.type === 'drill') && items.length > 0 && (
          <section className="mb-5 rounded-xl border border-border bg-surface-2 p-5">
            <h2 className="mb-3 text-base font-semibold text-foreground">逐题结果</h2>
            <div className="space-y-3">
              {[...items].sort((a, b) => a.orderNo - b.orderNo).map(item => {
                const q = questions.find(x => x.itemId === item.itemId);
                const answer = answers[item.itemId] || '';
                return (
                  <div key={item.itemId} className="rounded-lg border border-border bg-surface p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="text-sm font-medium leading-6 text-foreground">
                        第 {item.orderNo} 题{item.answered ? '' : '（未作答）'}
                      </div>
                      <div className="shrink-0 text-sm tabular-nums text-foreground">{item.total_score} 分</div>
                    </div>
                    {q?.question && <div className="mt-1 text-xs text-muted-foreground">{q.question}</div>}
                    <div className="mt-2 rounded bg-surface-2 px-3 py-2 text-xs leading-5 text-foreground">
                      我的答案：{answer.trim() || <span className="text-muted-foreground">未作答</span>}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        )}

        {/* 陪练对话轮次 */}
        {task.type === 'practice' && score?.rounds != null && (
          <div className="mb-5 text-sm text-muted-foreground">本次陪练共 {String(score.rounds)} 轮对话</div>
        )}

        {/* 复核记录 */}
        {reviews.length > 0 && (
          <section className="mb-5 rounded-xl border border-border bg-surface-2 p-5">
            <h2 className="mb-3 text-base font-semibold text-foreground">复核记录</h2>
            <div className="space-y-2">
              {reviews.map((r, i) => (
                <div key={i} className="rounded-lg bg-surface px-3 py-2 text-sm">
                  <span className={`mr-2 font-medium ${r.action === 'pass' ? 'text-green-700' : 'text-red-700'}`}>
                    {r.action === 'pass' ? '通过' : '退回'}
                  </span>
                  <span className="text-foreground">{r.reason}</span>
                  <span className="ml-2 text-xs text-muted-foreground">
                    {new Date(r.createdAt).toLocaleString()}
                  </span>
                </div>
              ))}
            </div>
          </section>
        )}

        <div className="mb-8 flex flex-wrap items-center justify-between gap-2">
          <div className="text-xs text-muted-foreground">
            已用 {view.usedAttempts} 次，剩余 {view.remainingAttempts} 次
            {attempt.finishedAt ? ` · 完成于 ${new Date(attempt.finishedAt).toLocaleString()}` : ''}
            {!passing && view.retryBlockedReason && (
              <span className="ml-2 text-amber-700">
                {RETRY_BLOCKED_LABELS[view.retryBlockedReason] || '暂时不能重试'}
              </span>
            )}
          </div>
          <div className="flex gap-2">
            <button onClick={() => router.push('/training')}
              className="rounded-full border border-border px-5 py-2.5 text-sm text-foreground hover:bg-primary-light">
              返回我的培训
            </button>
            {view.canRetry && (!finalized || !passing) && (
              <button onClick={onRetry} disabled={retrying}
                className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {retrying ? '准备中…' : '再试一次'}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
