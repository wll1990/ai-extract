'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getAssignmentTasks, getTaskDetail, startTask, acknowledgeTask, startAttempt,
  getAttempt, getPracticeMessages, getRecordAttempts, submitAttempt,
  sendPracticeMessage, requestHint,
  TASK_TYPE_LABELS, RECORD_STATUS_LABELS, statusCls,
  type TaskDetail, type TaskItem, type ExamAttemptView, type PracticeMessage,
} from '@/lib/api/training';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';

interface ChatBubble { id: string; role: string; content: string }

export default function TrainingTaskPage() {
  const router = useRouter();
  const params = useParams();
  const assignmentId = String(params.id);
  const taskId = String(params.taskId);

  const [item, setItem] = useState<TaskItem | null>(null);
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [acting, setActing] = useState(false);

  // 陪练
  const [attempt, setAttempt] = useState<ExamAttemptView | null>(null);
  const [messages, setMessages] = useState<ChatBubble[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [hintText, setHintText] = useState('');
  const [hinting, setHinting] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [usage, setUsage] = useState<{ used: number; remaining: number; max: number } | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const hintAbortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  /** 拉取陪练历史：失败要让人看见并可重试，不能静默当成空对话 */
  const loadHistory = useCallback(async (attemptId: string) => {
    setHistoryError(null);
    try {
      const history = await getPracticeMessages(attemptId);
      setMessages(history.map((m: PracticeMessage) => ({
        id: m.id, role: m.role, content: m.content,
      })));
    } catch (e) {
      setHistoryError(e instanceof Error ? e.message : '对话记录加载失败');
    }
  }, []);

  const resumePractice = useCallback(async (attemptId: string) => {
    const view = await getAttempt(attemptId);
    setAttempt(view);
    await loadHistory(attemptId);
  }, [loadHistory]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getAssignmentTasks(assignmentId);
      const found = (data.tasks || []).find(t => t.taskId === taskId) || null;
      setItem(found);
      if (!found) {
        setError('任务不存在或不属于该培训');
        return;
      }
      const taskDetail = await getTaskDetail(found.recordId);
      setDetail(taskDetail);
      // 考试/微练：已在进行中直接进答题页
      if ((taskDetail.task.type === 'exam' || taskDetail.task.type === 'drill')
          && found.latestAttempt?.status === 'in_progress') {
        router.replace(`/training/attempts/${found.latestAttempt.id}`);
        return;
      }
      // 次数口径由服务端计算，前端不能拿 attemptNo 推（含评分失败/在途尝试）
      getRecordAttempts(found.recordId)
        .then(h => setUsage({ used: h.usedAttempts, remaining: h.remainingAttempts, max: h.maxAttempts }))
        .catch(() => setUsage(null));
      // 陪练：进行中的尝试恢复现场
      if (taskDetail.task.type === 'practice' && found.latestAttempt?.status === 'in_progress') {
        await resumePractice(found.latestAttempt.id);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [assignmentId, taskId, router, resumePractice]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => () => {
    abortRef.current?.abort();
    hintAbortRef.current?.abort();
  }, []);

  // 陪练进行中同步服务端 allowedActions：主管中途延期后应恢复发言，不能一直停在「已停写」
  useEffect(() => {
    if (!attempt || attempt.attempt.status !== 'in_progress') return;
    const timer = setInterval(async () => {
      if (streaming) return;
      try {
        setAttempt(await getAttempt(attempt.attempt.id));
      } catch {
        // 网络抖动保持当前状态，下一轮再试
      }
    }, 15000);
    return () => clearInterval(timer);
  }, [attempt, streaming]);

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages]);

  const onStartReading = async () => {
    if (!detail) return;
    setActing(true);
    try {
      const updated = await startTask(detail.record.id);
      setDetail(updated);
      setToast('任务已开始');
    } catch (e) {
      setToast(e instanceof Error ? e.message : '开始失败');
    } finally {
      setActing(false);
    }
  };

  const onAcknowledge = async () => {
    if (!detail) return;
    if (!window.confirm('确认已阅读该材料？标记后本任务完成。')) return;
    setActing(true);
    try {
      await acknowledgeTask(detail.record.id);
      setToast('已标记完成');
      router.push('/training');
    } catch (e) {
      setToast(e instanceof Error ? e.message : '提交失败');
    } finally {
      setActing(false);
    }
  };

  const onStartAttempt = async () => {
    if (!detail) return;
    setActing(true);
    try {
      const view = await startAttempt(detail.record.id);
      if (detail.task.type === 'exam' || detail.task.type === 'drill') {
        if (view.attempt.status !== 'in_progress') {
          router.push(`/training/attempts/${view.attempt.id}/result`);
          return;
        }
        router.push(`/training/attempts/${view.attempt.id}`);
        return;
      }
      if (view.attempt.status !== 'in_progress') {
        router.push(`/training/attempts/${view.attempt.id}/result`);
        return;
      }
      setAttempt(view);
      await loadHistory(view.attempt.id);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '开始失败');
    } finally {
      setActing(false);
    }
  };

  const sendMessage = () => {
    if (!attempt || streaming) return;
    const text = input.trim();
    if (!text) return;
    const placeholderId = `c-${Date.now()}`;
    const userMsg: ChatBubble = { id: `u-${Date.now()}`, role: 'user', content: text };
    setMessages(prev => [...prev, userMsg, { id: placeholderId, role: 'assistant', content: '' }]);
    setInput('');
    setStreaming(true);
    let full = '';
    let raf = 0;
    const roundNumber = messages.filter(m => m.role === 'user').length + 1;
    abortRef.current = sendPracticeMessage(attempt.attempt.id, text, roundNumber, {
      onChunk: (chunk) => {
        full += chunk;
        if (!raf) {
          raf = requestAnimationFrame(() => {
            raf = 0;
            setMessages(prev => prev.map(m => m.id === placeholderId ? { ...m, content: full } : m));
          });
        }
      },
      onDone: () => {
        if (raf) cancelAnimationFrame(raf);
        setMessages(prev => prev.map(m => m.id === placeholderId ? { ...m, content: full } : m));
        setStreaming(false);
      },
      onError: (message) => {
        if (raf) cancelAnimationFrame(raf);
        setMessages(prev => prev.filter(m => m.id !== placeholderId));
        setStreaming(false);
        setToast(message ? `发送失败：${message}` : '发送失败，请重试');
      },
    });
  };

  const onHint = () => {
    if (!attempt || hinting) return;
    setHinting(true);
    setHintText('');
    let text = '';
    hintAbortRef.current?.abort();
    hintAbortRef.current = requestHint(attempt.attempt.id, {
      onChunk: (chunk) => { text += chunk; setHintText(text); },
      onDone: () => setHinting(false),
      onError: (message) => {
        setHinting(false);
        setToast(message ? `提示不可用：${message}` : '提示不可用');
      },
    });
  };

  const onSubmitPractice = async () => {
    if (!attempt) return;
    if (!window.confirm('提交本次陪练？提交后对话锁定，由 AI 评分并由主管复核临界情况。')) return;
    setActing(true);
    try {
      await submitAttempt(attempt.attempt.id);
      router.push(`/training/attempts/${attempt.attempt.id}/result`);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '提交失败');
    } finally {
      setActing(false);
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
  if (!detail || !item) return null;

  const task = detail.task;
  const record = detail.record;
  const locked = record.status === 'locked';
  const done = record.status === 'passed' || record.status === 'waived';
  const pending = record.status === 'pending_review';
  const resource = detail.resource;
  // 动作以服务端 allowedActions 为准（截止/额度/状态变化后前端不自行放行）
  const actions = attempt?.allowedActions || [];
  const canSend = actions.includes('send_message');
  const canHint = actions.includes('hint');
  const canSubmitPractice = actions.includes('submit');
  const hasAssistant = messages.some(m => m.role === 'assistant');

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[880px]">
        <button onClick={() => router.push('/training')}
          className="mb-2 text-xs text-muted-foreground hover:underline">← 返回我的培训</button>

        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="mb-1 flex flex-wrap items-center gap-2">
              <span className="rounded bg-primary-light px-2 py-0.5 text-xs text-primary">
                {TASK_TYPE_LABELS[task.type] || task.type}
              </span>
              <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(record.status)}`}>
                {RECORD_STATUS_LABELS[record.status] || record.status}
              </span>
              {record.roundNo != null && record.roundNo > 1 && (
                <span className="rounded bg-amber-50 px-2 py-0.5 text-xs text-amber-700">
                  第 {record.roundNo} 轮补训
                </span>
              )}
              {record.remediationReason && (
                <span className="text-xs text-amber-700">补训原因：{record.remediationReason}</span>
              )}
              <span className="text-xs text-muted-foreground">
                {task.isRequired ? '必修' : '选修'}
                {task.phaseName ? ` · ${task.phaseName}` : ` · 阶段 ${task.phaseNo}`}
                {task.type !== 'reading' && ` · 及格 ${task.passCriteria?.minScore ?? 80} 分`}
              </span>
            </div>
            <h1 className="text-[26px] font-bold text-foreground">{task.title}</h1>
            {task.description && <p className="mt-1 text-sm text-muted-foreground">{task.description}</p>}
          </div>
          {detail.assignment.deadline && (
            <div className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-xs text-muted-foreground">
              截止 {new Date(detail.assignment.deadline).toLocaleString()}
              {detail.overdue && <span className="ml-2 text-red-600">已逾期</span>}
            </div>
          )}
        </div>

        {locked && (
          <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            任务尚未解锁：{detail.blockingReasons.join('；') || '请先完成前置任务'}
          </div>
        )}

        {historyError && (
          <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            对话记录加载失败：{historyError}
            {attempt && (
              <button onClick={() => loadHistory(attempt.attempt.id)} className="ml-3 underline">重试</button>
            )}
          </div>
        )}

        {pending && (
          <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            本次尝试已交卷，等待主管复核。复核后可在成绩页查看结论。
          </div>
        )}

        {/* 阅读 */}
        {task.type === 'reading' && resource && (
          <section className="mb-4 rounded-xl border border-border bg-surface-2 p-6">
            <h2 className="mb-3 text-lg font-semibold text-foreground">{resource.title || task.title}</h2>
            {resource.type === 'knowledge' && (
              <div className="whitespace-pre-wrap text-sm leading-7 text-foreground">{resource.content || '（无正文）'}</div>
            )}
            {resource.type === 'case' && (
              <div className="space-y-2 text-sm leading-6 text-foreground">
                {resource.customerProfile && <Field label="客户画像" value={resource.customerProfile} />}
                {resource.funnelStage && <Field label="漏斗阶段" value={resource.funnelStage} />}
                {resource.dialogueQuote && <Field label="关键对话" value={resource.dialogueQuote} />}
                {resource.objection && <Field label="异议" value={resource.objection} />}
                {resource.emotionChange && <Field label="情绪变化" value={resource.emotionChange} />}
                {resource.keyActions && <Field label="关键动作" value={resource.keyActions} />}
                {resource.scripts && <Field label="话术" value={resource.scripts} />}
                {resource.advanceNodes && <Field label="推进节点" value={resource.advanceNodes} />}
                {resource.result && <Field label="结果" value={resource.result === 'win' ? '赢单' : resource.result === 'loss' ? '丢单' : resource.result === 'delay' ? '搁置' : resource.result} />}
                {resource.reusablePoints && <Field label="可复用要点" value={resource.reusablePoints} />}
                {resource.failurePoints && <Field label="失败点" value={resource.failurePoints} />}
              </div>
            )}
            {resource.type === 'skill' && (
              <div className="text-sm text-foreground">{resource.openingMessage || '（分身资源）'}</div>
            )}
            {!resource.type && <div className="text-sm text-muted-foreground">资源内容不可用，请联系管理员</div>}
          </section>
        )}

        {task.type === 'reading' && (
          <div className="flex justify-end gap-2">
            {record.status === 'available' && (
              <button onClick={onStartReading} disabled={acting || locked}
                className="rounded-full border border-border px-5 py-2.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">
                开始阅读
              </button>
            )}
            {!done && !locked && (
              <button onClick={onAcknowledge} disabled={acting}
                className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                已阅读，标记完成
              </button>
            )}
            {done && (
              <div className="rounded-full bg-green-50 px-4 py-2 text-sm text-green-700">本任务已完成</div>
            )}
          </div>
        )}

        {/* 微练入口：先看场景，再开始作答（评分要点不下发） */}
        {task.type === 'drill' && (
          <section className="rounded-xl border border-border bg-surface-2 p-6">
            <div className="mb-1 text-xs font-medium text-primary">场景微练</div>
            {resource?.scenario ? (
              <>
                <p className="text-sm leading-6 text-foreground">{String(resource.scenario)}</p>
                {resource.clientRole ? (
                  <p className="mt-1 text-xs text-muted-foreground">客户角色：{String(resource.clientRole)}</p>
                ) : null}
                {Array.isArray(resource.questions) && resource.questions.length > 0 && (
                  <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
                    {resource.questions.map((q, i) => (
                      <li key={i}>第 {i + 1} 问：{String((q as { question?: string }).question || '')}</li>
                    ))}
                  </ul>
                )}
              </>
            ) : (
              <p className="text-sm text-muted-foreground">微练内容暂不可用，请联系管理员重新生成。</p>
            )}
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
              <span className="text-xs text-muted-foreground">
                约 3 分钟 · 及格 {task.passCriteria?.minScore ?? 60} 分 · 最多 {task.maxAttempts} 次
                {usage && `（已用 ${usage.used}，剩余 ${usage.remaining}）`}
              </span>
              <div className="flex gap-2">
                {item.latestAttempt && item.latestAttempt.status !== 'in_progress' && (
                  <button onClick={() => router.push(`/training/attempts/${item.latestAttempt!.id}/result`)}
                    className="rounded-full border border-border px-5 py-2.5 text-sm text-foreground hover:bg-primary-light">
                    查看上次反馈
                  </button>
                )}
                <button onClick={onStartAttempt}
                  disabled={acting || locked || done || pending || (usage != null && usage.remaining <= 0)}
                  className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                  {usage && usage.remaining <= 0 ? '次数已用尽'
                    : item.latestAttempt ? '再练一次' : '开始微练'}
                </button>
              </div>
            </div>
          </section>
        )}

        {/* 考试入口 */}
        {task.type === 'exam' && (
          <section className="rounded-xl border border-border bg-surface-2 p-6">
            <div className="text-sm text-foreground">
              共 {task.examItemCount ?? '全部'} 题 · 限时 {task.examDurationMinutes ?? '—'} 分钟 ·
              最少 {task.passCriteria?.minScore ?? 80} 分通过 · 最多 {task.maxAttempts} 次
              {usage && `（已用 ${usage.used}，剩余 ${usage.remaining}）`}
            </div>
            <p className="mt-2 text-xs text-muted-foreground">
              开始后计时不会暂停；截止以服务端时间为准。考试不提供提示。
            </p>
            <div className="mt-4 flex justify-end gap-2">
              {item.latestAttempt && item.latestAttempt.status !== 'in_progress' && (
                <button onClick={() => router.push(`/training/attempts/${item.latestAttempt!.id}/result`)}
                  className="rounded-full border border-border px-5 py-2.5 text-sm text-foreground hover:bg-primary-light">
                  查看上次成绩
                </button>
              )}
              <button onClick={onStartAttempt}
                disabled={acting || locked || done || pending || (usage != null && usage.remaining <= 0)}
                className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {usage && usage.remaining <= 0 ? '次数已用尽' : item.latestAttempt ? '再考一次' : '开始考试'}
              </button>
              {usage && usage.remaining <= 0 && !done && (
                <span className="self-center text-xs text-red-600">请联系主管补训或调整次数</span>
              )}
            </div>
          </section>
        )}

        {/* 陪练 */}
        {task.type === 'practice' && (
          <section className="rounded-xl border border-border bg-surface-2">
            {!attempt ? (
              <div className="p-6">
                <p className="text-sm text-foreground">
                  本次为陪练任务：AI 扮演客户与你对话，提交后按对话质量判分。
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {usage
                    ? `已用 ${usage.used}/${usage.max} 次，剩余 ${usage.remaining} 次`
                    : `最多 ${task.maxAttempts} 次`}
                  {task.allowHints ? ' · 可请求提示' : ' · 不提供提示'}
                </p>
                <div className="mt-4 flex items-center justify-end gap-2">
                  {usage && usage.remaining <= 0 && !done && (
                    <span className="mr-auto text-xs text-red-600">次数已用尽，请联系主管补训</span>
                  )}
                  {item.latestAttempt && item.latestAttempt.status !== 'in_progress' && (
                    <button onClick={() => router.push(`/training/attempts/${item.latestAttempt!.id}/result`)}
                      className="rounded-full border border-border px-5 py-2.5 text-sm text-foreground hover:bg-primary-light">
                      查看上次成绩
                    </button>
                  )}
                  <button onClick={onStartAttempt}
                    disabled={acting || locked || done || pending || (usage != null && usage.remaining <= 0)}
                    className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                    {usage && usage.remaining <= 0 ? '次数已用尽' : item.latestAttempt ? '开始新一次陪练' : '开始陪练'}
                  </button>
                </div>
              </div>
            ) : (
              <>
                <div ref={scrollRef} className="max-h-[52vh] min-h-[280px] space-y-3 overflow-y-auto p-5">
                  {!hasAssistant && resource?.openingMessage && (
                    <Bubble role="assistant" content={resource.openingMessage} />
                  )}
                  {messages.map(m => (
                    <Bubble key={m.id} role={m.role} content={m.content} />
                  ))}
                  {streaming && (
                    <div className="text-xs text-muted-foreground">客户正在输入…</div>
                  )}
                </div>

                {hintText && (
                  <div className="mx-5 mb-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                    <b>提示：</b>{hintText}
                    {hinting && <span className="ml-1 animate-pulse">▍</span>}
                  </div>
                )}

                {!canSend && (
                  <div className="mx-5 mb-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                    已超过截止时间或本次尝试已结束，不能再发言。
                    {attempt.attempt.remainingSeconds != null && `（剩余 ${attempt.attempt.remainingSeconds} 秒）`}
                  </div>
                )}
                <div className="border-t border-border p-4">
                  <textarea rows={3} value={input} disabled={streaming || acting || !canSend}
                    onChange={e => setInput(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
                    }}
                    placeholder={canSend ? '输入你的回应（Enter 发送，Shift+Enter 换行）' : '已停写'}
                    className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-foreground disabled:opacity-60" />
                  <div className="mt-2 flex items-center justify-between">
                    <div className="flex gap-2">
                      {canHint && (
                        <button onClick={onHint} disabled={hinting || streaming}
                          className="rounded-full border border-border px-4 py-2 text-xs text-foreground hover:bg-primary-light disabled:opacity-50">
                          {hinting ? '生成提示中…' : '给我提示'}
                        </button>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <button onClick={onSubmitPractice} disabled={acting || streaming || messages.length === 0 || !canSubmitPractice}
                        className="rounded-full border border-border px-4 py-2 text-xs text-foreground hover:bg-primary-light disabled:opacity-40">
                        提交陪练
                      </button>
                      <button onClick={sendMessage} disabled={streaming || !input.trim() || !canSend}
                        className="rounded-full bg-primary px-5 py-2 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50">
                        {streaming ? '回复中…' : '发送'}
                      </button>
                    </div>
                  </div>
                </div>
              </>
            )}
          </section>
        )}
      </div>
    </div>
  );
}

function Bubble({ role, content }: { role: string; content: string }) {
  const isUser = role === 'user';
  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[78%] rounded-xl px-3.5 py-2 text-sm leading-6 ${
        isUser ? 'bg-primary text-white' : 'border border-border bg-surface text-foreground'}`}>
        <div className={`mb-0.5 text-[10px] ${isUser ? 'text-white/70' : 'text-muted-foreground'}`}>
          {isUser ? '我' : '客户'}
        </div>
        <div className="whitespace-pre-wrap">{content || '…'}</div>
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span className="mr-2 text-xs font-medium text-muted-foreground">{label}</span>
      <span className="whitespace-pre-wrap">{value}</span>
    </div>
  );
}
