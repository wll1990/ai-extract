'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getAttempt, getRecordAttempts, saveAnswers, submitAttempt,
  type ExamAttemptView,
} from '@/lib/api/training';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';

const AUTO_SAVE_MS = 15000;

export default function TrainingExamPage() {
  const router = useRouter();
  const params = useParams();
  const attemptId = String(params.id);

  const [view, setView] = useState<ExamAttemptView | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [deadlineAt, setDeadlineAt] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [usage, setUsage] = useState<{ used: number; remaining: number; max: number } | null>(null);
  const answersRef = useRef<Record<string, string>>({});
  const dirtyRef = useRef(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getAttempt(attemptId);
      // 陪练尝试没有题面，不属于答题页；直接看成绩。考试与微练共用答题页
      if (data.attempt.type !== 'exam' && data.attempt.type !== 'drill') {
        router.replace(`/training/attempts/${attemptId}/result`);
        return;
      }
      if (data.attempt.status !== 'in_progress') {
        router.replace(`/training/attempts/${attemptId}/result`);
        return;
      }
      setView(data);
      // 损坏的 answers 后端会原样返回字符串，不能直接展开成字符键
      const safeAnswers = data.answers && typeof data.answers === 'object' ? data.answers : {};
      setAnswers(safeAnswers);
      answersRef.current = safeAnswers;
      const seconds = data.attempt.remainingSeconds;
      setRemaining(seconds ?? null);
      setDeadlineAt(seconds == null ? null : Date.now() + seconds * 1000);
      // 次数口径由服务端计算（已定稿+待复核），前端不拿 attemptNo 推
      getRecordAttempts(data.attempt.taskRecordId)
        .then(h => setUsage({ used: h.usedAttempts, remaining: h.remainingAttempts, max: h.maxAttempts }))
        .catch(() => setUsage(null));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [attemptId, router]);

  useEffect(() => { load(); }, [load]);

  // 倒计时只做展示；到 0 后冻结输入，服务端扫描器按策略处理
  useEffect(() => {
    if (deadlineAt == null) return;
    const tick = () => setRemaining(Math.max(0, Math.ceil((deadlineAt - Date.now()) / 1000)));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [deadlineAt]);

  const persist = useCallback(async (silent: boolean) => {
    if (!dirtyRef.current) return;
    // 保存期间用户可能继续输入：只有内容没再变才清 dirty，否则保留给下一轮
    const snapshot = answersRef.current;
    setSaving(true);
    try {
      await saveAnswers(attemptId, snapshot);
      if (answersRef.current === snapshot) {
        dirtyRef.current = false;
        setDirty(false);
      }
      setSaveError(null);
      if (!silent) setToast('草稿已保存');
    } catch (e) {
      const msg = e instanceof Error ? e.message : '保存失败';
      setSaveError(msg);
      if (!silent) setToast(msg);
    } finally {
      setSaving(false);
    }
  }, [attemptId]);

  // 自动保存：有改动才发请求
  useEffect(() => {
    const timer = setInterval(() => {
      if (dirtyRef.current) persist(true);
    }, AUTO_SAVE_MS);
    return () => clearInterval(timer);
  }, [persist]);

  // 离开页面前尽量保存草稿（切路由时已保存的 15s 内改动不丢）
  useEffect(() => () => { if (dirtyRef.current) persist(true); }, [persist]);

  // 关闭/刷新标签页有未保存答案时提示
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, []);

  const onChange = (itemId: string, value: string) => {
    const next = { ...answersRef.current, [itemId]: value };
    answersRef.current = next;
    setAnswers(next);
    dirtyRef.current = true;
    setDirty(true);
  };

  const expired = remaining != null && remaining <= 0;
  // 逾期后不再由前端放行交卷：auto_submit 由扫描器代交，timeout_fail 直接转复核
  const canEdit = !!view && !expired
    && view.allowedActions.includes('save_answers');
  const canSubmit = !!view && !expired && view.allowedActions.includes('submit');

  // 考试期间同步服务端剩余时间：主管中途延期后页面能恢复答题，不会一直停在「时间已到」
  useEffect(() => {
    if (!view || view.attempt.status !== 'in_progress') return;
    const timer = setInterval(async () => {
      try {
        const data = await getAttempt(attemptId);
        if (data.attempt.status !== 'in_progress') {
          router.replace(`/training/attempts/${attemptId}/result`);
          return;
        }
        if (data.attempt.remainingSeconds != null) {
          setDeadlineAt(Date.now() + data.attempt.remainingSeconds * 1000);
        }
      } catch {
        // 网络抖动保持当前倒计时，下一轮再试
      }
    }, 15000);
    return () => clearInterval(timer);
  }, [view, attemptId, router]);

  // 逾期后轮询服务端状态：扫描器处理完（auto_submit/timeout_fail）后自动去成绩页
  useEffect(() => {
    if (!expired || !view) return;
    const timer = setInterval(async () => {
      try {
        const data = await getAttempt(attemptId);
        if (data.attempt.status !== 'in_progress') {
          router.replace(`/training/attempts/${attemptId}/result`);
        }
      } catch {
        // 网络抖动保持当前页，下一轮再试
      }
    }, 5000);
    return () => clearInterval(timer);
  }, [expired, view, attemptId, router]);

  const onSubmit = async () => {
    const unanswered = view ? view.questions.filter(q => !(answers[q.itemId] || '').trim()).length : 0;
    const tip = unanswered > 0
      ? `还有 ${unanswered} 题未作答。交卷后不能修改答案，确认提交？`
      : '确认交卷？提交后不能修改答案。';
    if (!window.confirm(tip)) return;
    setSubmitting(true);
    try {
      await saveAnswers(attemptId, answersRef.current);
      dirtyRef.current = false;
      setDirty(false);
      await submitAttempt(attemptId);
      router.push(`/training/attempts/${attemptId}/result`);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '交卷失败');
      setSubmitting(false);
    }
  };

  if (loading) return <div className="p-8"><LoadingSpinner /></div>;
  if (error) {
    return (
      <div className="p-8">
        <div className="rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
          {error}
          <button onClick={() => router.push('/training')} className="ml-3 underline">返回我的培训</button>
        </div>
      </div>
    );
  }
  if (!view) return null;

  const answeredCount = view.questions.filter(q => (answers[q.itemId] || '').trim()).length;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[860px]">
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="mb-1 text-xs text-muted-foreground">
              第 {view.attempt.attemptNo} 次尝试 · 共 {view.questions.length} 题
              {usage && ` · 已用 ${usage.used}/${usage.max} 次，剩余 ${usage.remaining} 次`}
            </div>
            <h1 className="text-[26px] font-bold text-foreground">{view.task.title}</h1>
            <div className="mt-1 text-xs text-muted-foreground">
              {view.attempt.type === 'drill' ? '场景微练' : '考试'}
              {' · '}及格 {view.task.passCriteria?.minScore ?? (view.attempt.type === 'drill' ? 60 : 80)} 分
              {' · '}已作答 {answeredCount}/{view.questions.length}
            </div>
          </div>
          <div className={`rounded-xl border px-4 py-2 text-center ${
            expired ? 'border-red-200 bg-red-50' : 'border-border bg-surface-2'}`}>
            <div className="text-[11px] text-muted-foreground">剩余时间</div>
            <div className={`text-xl font-semibold tabular-nums ${expired ? 'text-red-600' : 'text-foreground'}`}>
              {remaining == null ? '不限时' : formatSeconds(remaining)}
            </div>
          </div>
        </div>

        {view.attempt.type === 'drill' && view.scenario && (
          <div className="mb-4 rounded-xl border border-primary/30 bg-primary-light/40 px-5 py-4">
            <div className="mb-1 text-xs font-medium text-primary">
              场景{view.scenario.clientRole ? ` · 客户角色：${view.scenario.clientRole}` : ''}
            </div>
            <p className="text-sm leading-6 text-foreground">{view.scenario.scenario}</p>
            <p className="mt-2 text-xs text-muted-foreground">
              按你的真实做法作答，评分只看要点覆盖与表达，不看字数。
            </p>
          </div>
        )}

        {expired && (
          <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            时间已到，答案已冻结。系统会按本任务的截止策略自动交卷或转主管复核，本页会自动跳到成绩页，无需再操作。
          </div>
        )}

        <div className="space-y-4">
          {view.questions.map(q => (
            <section key={q.itemId} className="rounded-xl border border-border bg-surface-2 p-5">
              <div className="mb-2 flex items-start justify-between gap-3">
                <h2 className="text-sm font-semibold leading-6 text-foreground">
                  第 {q.orderNo} 题 · {q.question}
                </h2>
                <span className="shrink-0 text-xs text-muted-foreground">
                  {(answers[q.itemId] || '').trim() ? '已作答' : '未作答'}
                </span>
              </div>
              {q.context && (
                <div className="mb-2 rounded-lg bg-surface px-3 py-2 text-xs leading-5 text-muted-foreground">
                  {q.context}
                </div>
              )}
              {q.citationRequirement != null && (
                <div className="mb-2 text-xs text-amber-700">
                  引用要求：{renderRequirement(q.citationRequirement)}
                </div>
              )}
              <textarea rows={4} value={answers[q.itemId] || ''} disabled={!canEdit}
                onChange={e => onChange(q.itemId, e.target.value)}
                placeholder={canEdit ? '填写你的答案…' : '已停写'}
                className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm leading-6 text-foreground disabled:opacity-70" />
            </section>
          ))}
        </div>

        <div className="sticky bottom-0 mt-6 -mx-6 border-t border-border bg-surface-2/95 px-6 py-3 backdrop-blur">
          <div className="mx-auto flex max-w-[860px] items-center justify-between">
            <span className={`text-xs ${saveError ? 'text-danger' : 'text-muted-foreground'}`}>
              {saveError
                ? `自动保存失败：${saveError}`
                : saving ? '保存中…' : dirty ? '有未保存的修改（15 秒自动保存）' : '答案已保存'}
            </span>
            <div className="flex gap-2">
              <button onClick={() => persist(false)} disabled={!dirty || saving || !canEdit}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light disabled:opacity-40">
                保存草稿
              </button>
              <button onClick={onSubmit} disabled={submitting || !canSubmit}
                className="rounded-full bg-primary px-6 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {expired ? '等待系统处理' : submitting ? '提交中…' : '交卷'}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function formatSeconds(total: number): string {
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

function renderRequirement(req: unknown): string {
  if (typeof req === 'string') return req;
  try {
    return JSON.stringify(req);
  } catch {
    return String(req);
  }
}
