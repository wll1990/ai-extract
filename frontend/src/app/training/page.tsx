'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  myAssignments, getAssignmentTasks, listMyCohorts, fetchCertificateHtml, getTrainingWorkbench,
  type TrainingWorkbench,
  ASSIGNMENT_STATUS_LABELS, RECORD_STATUS_LABELS, ATTEMPT_STATUS_LABELS,
  COHORT_STATUS_LABELS, TASK_TYPE_LABELS, statusCls,
  type MyAssignment, type TaskItem, type TrainingCohort,
} from '@/lib/api/training';
import {
  createImBindCode, listMyImBindings, unbindIm, type ImBinding,
} from '@/lib/api/im';
import { getCurrentUser } from '@/lib/api/auth';
import { Permission } from '@/lib/permissions';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import EmptyState from '@/components/ui/EmptyState';
import { PageHelp } from '@/components/ui/PageHelp';
import { Button } from '@/components/ui/Button';
import { Pagination } from '@/components/ui/Pagination';

type Tab = 'learn' | 'mentor';

const CHANNEL_LABELS: Record<string, string> = {
  feishu: '飞书',
  wecom: '企业微信',
  dingtalk: '钉钉',
};

export default function MyTrainingPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>('learn');
  const [permissions, setPermissions] = useState<string[]>([]);

  // ── 我的学习 ──
  const [assignments, setAssignments] = useState<MyAssignment[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 展开的任务清单：assignmentId → tasks
  const [expanded, setExpanded] = useState<string | null>(null);
  const [tasks, setTasks] = useState<Record<string, TaskItem[]>>({});
  const [taskLoading, setTaskLoading] = useState<string | null>(null);
  const [taskError, setTaskError] = useState<Record<string, string>>({});
  const inflightRef = useRef<Set<string>>(new Set());

  // ── 我带的批次 ──
  const [workbench, setWorkbench] = useState<TrainingWorkbench | null>(null);
  const [workbenchLoading, setWorkbenchLoading] = useState(false);
  const [workbenchError, setWorkbenchError] = useState<string | null>(null);
  const [cohortError, setCohortError] = useState<string | null>(null);
  const [cohorts, setCohorts] = useState<TrainingCohort[]>([]);
  const [cohortPage, setCohortPage] = useState(1);
  const [cohortTotal, setCohortTotal] = useState(0);
  const [cohortTotalPages, setCohortTotalPages] = useState(1);
  const [cohortLoading, setCohortLoading] = useState(false);

  useEffect(() => {
    getCurrentUser()
      .then(u => setPermissions(u.permissions || []))
      .catch(() => setPermissions([]));
  }, []);

  const canReview = permissions.includes(Permission.TRAINING_REVIEW)
    || permissions.includes(Permission.TRAINING_MANAGE);

  // ── IM 提醒绑定 ──
  const [imBindings, setImBindings] = useState<ImBinding[]>([]);
  const [imCode, setImCode] = useState<{ code: string; expiresAt: string } | null>(null);
  const [imLoading, setImLoading] = useState(false);

  const loadImBindings = useCallback(() => {
    listMyImBindings().then(setImBindings).catch(() => setImBindings([]));
  }, []);

  useEffect(() => { loadImBindings(); }, [loadImBindings]);

  const onCreateBindCode = async () => {
    setImLoading(true);
    try {
      const result = await createImBindCode();
      setImCode({ code: result.code, expiresAt: result.expiresAt });
    } catch (e) {
      alert(e instanceof Error ? e.message : '生成绑定码失败');
    } finally {
      setImLoading(false);
    }
  };

  const onUnbindIm = async (id: string) => {
    if (!window.confirm('解绑后将不再收到 IM 提醒，站内信不受影响。确认解绑？')) return;
    try {
      await unbindIm(id);
      setImCode(null);
      loadImBindings();
    } catch (e) {
      alert(e instanceof Error ? e.message : '解绑失败');
    }
  };

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    myAssignments(page, 20)
      .then(data => {
        setAssignments(data.content || []);
        setTotal(data.total || 0);
        setTotalPages(Math.max(1, data.totalPages || 1));
      })
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => { load(); }, [load]);

  // 通知面板点同路由链接不会重挂组件：由 AppLayout 派发事件触发刷新
  useEffect(() => {
    const refresh = () => { load(); setExpanded(null); setTasks({}); };
    window.addEventListener('training:refresh', refresh);
    return () => window.removeEventListener('training:refresh', refresh);
  }, [load]);

  const loadCohorts = useCallback(() => {
    setCohortLoading(true);
    setCohortError(null);
    listMyCohorts(cohortPage, 20)
      .then(data => {
        setCohorts(data.content || []);
        setCohortTotal(data.total || 0);
        setCohortTotalPages(Math.max(1, data.totalPages || 1));
      })
      .catch(e => {
        setCohorts([]);
        setCohortError(e instanceof Error ? e.message : '批次加载失败');
      })
      .finally(() => setCohortLoading(false));
  }, [cohortPage]);

  useEffect(() => { if (tab === 'mentor' && canReview) loadCohorts(); }, [tab, canReview, loadCohorts]);

  const loadWorkbench = useCallback(async () => {
    setWorkbenchLoading(true);
    setWorkbenchError(null);
    try {
      setWorkbench(await getTrainingWorkbench());
    } catch (e) {
      setWorkbench(null);
      setWorkbenchError(e instanceof Error ? e.message : '工作台加载失败');
    } finally {
      setWorkbenchLoading(false);
    }
  }, []);

  useEffect(() => {
    if (tab !== 'mentor' || !canReview) return;
    loadWorkbench();
  }, [tab, canReview, loadWorkbench]);

  /** 拉任务清单；同一分配只允许一个在途请求，失败后可单独重试 */
  const fetchTasks = async (assignmentId: string) => {
    if (inflightRef.current.has(assignmentId)) return;
    inflightRef.current.add(assignmentId);
    setTaskLoading(assignmentId);
    setTaskError(prev => ({ ...prev, [assignmentId]: '' }));
    try {
      const data = await getAssignmentTasks(assignmentId);
      setTasks(prev => ({ ...prev, [assignmentId]: data.tasks }));
    } catch (e) {
      setTaskError(prev => ({
        ...prev,
        [assignmentId]: e instanceof Error ? e.message : '任务加载失败',
      }));
    } finally {
      inflightRef.current.delete(assignmentId);
      setTaskLoading(prev => (prev === assignmentId ? null : prev));
    }
  };

  const toggle = async (assignment: MyAssignment) => {
    if (expanded === assignment.id) { setExpanded(null); return; }
    setExpanded(assignment.id);
    if (tasks[assignment.id]) return;
    await fetchTasks(assignment.id);
  };

  /** 认证证书：后端返回可打印 HTML，新窗口打开后用户可另存 PDF */
  const openCertificate = async (assignmentId: string) => {
    try {
      const html = await fetchCertificateHtml(assignmentId);
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
      window.open(url, '_blank', 'noopener');
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError(e instanceof Error ? e.message : '证书下载失败');
    }
  };

  if (loading && assignments.length === 0 && tab === 'learn') {
    return <div className="p-8"><LoadingSpinner /></div>;
  }

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]" data-tour="training-plan-list">
        <div className="mb-4 flex items-start justify-between gap-3">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">我的培训</h1>
            <p className="text-sm text-muted-foreground">
              按阶段完成阅读、陪练与考试；有前置条件的任务要先完成上一项
            </p>
          </div>
          <div className="flex items-center gap-2">
            <PageHelp
              title="我的培训使用说明"
              intro="这里是你被分配的培训任务：阅读、陪练和考试。按顺序完成，全部必修通过后会获得认证。"
              steps={[
                { title: '看任务状态', desc: '「可开始」表示现在能做；「未解锁」表示要先完成前置任务；「待复核」表示已提交等导师确认。' },
                { title: '阅读任务', desc: '点开任务看知识正文，读完后点「标记完成」。' },
                { title: '陪练 / 考试', desc: '陪练是与 AI 客户对话；考试按题目作答后交卷，由系统或导师评分。' },
                { title: '认证', desc: '所有必修任务通过后自动进入认证；认证被撤销时会回到「待认证」。' },
              ]}
              actions={[
                { label: '刷新', desc: '重新拉取分配、任务和认证状态；不会改变任何学习记录。' },
                { label: '我的学习 / 我带的批次', desc: '只有具备培训复核/管理权限的人能看到「我带的批次」。' },
                { label: '展开任务 / 收起', desc: '展开后按阶段显示任务；已结束的考试/陪练点开会先看成绩。' },
                { label: '开始 / 继续 / 再试一次', desc: '进入阅读、陪练或考试；未解锁时按钮不可点，先完成前置任务。' },
                { label: '查看内容 / 查看成绩', desc: '已通过的任务可回看正文或历史成绩，不会重新发起一次尝试。' },
                { label: '下载认证证书', desc: '必修全部通过并确认认证后出现；在新窗口打开可打印或另存 PDF。' },
                { label: '绑定 IM / 解绑', desc: '绑定后培训提醒推到飞书/企微/钉钉；解绑只停止 IM 提醒，站内信仍在。' },
              ]}
              notes={[
                '资料显示「已过期或已下线」时，请联系管理员更新后重新下发。',
                '考试和陪练有次数限制，失败后按计划配置进入补训。',
              ]}
            />
            <Button variant="neutral" onClick={load} disabled={loading}>
              {loading ? '刷新中…' : '刷新'}
            </Button>
          </div>
        </div>

        {canReview && (
          <div className="mb-5 flex gap-2">
            {([['learn', '我的学习'], ['mentor', '我带的批次']] as [Tab, string][]).map(([key, label]) => (
              <Button
                key={key}
                variant={tab === key ? 'primary' : 'neutral'}
                size="sm"
                onClick={() => setTab(key)}
                title={key === 'learn' ? '查看分配给我的培训任务' : '查看我带班的批次与待复核任务'}
              >
                {label}
              </Button>
            ))}
          </div>
        )}

        {tab === 'learn' && (
          <>
            {/* IM 提醒绑定：不绑定也能用站内信，绑定后培训和待办推到飞书/企微/钉钉 */}
            <div className="mb-4 rounded-xl border border-border bg-surface-2 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <div className="text-sm font-medium text-foreground">IM 提醒</div>
                  <div className="text-xs text-muted-foreground">
                    {imBindings.length > 0
                      ? `已绑定 ${imBindings.map(b => CHANNEL_LABELS[b.channelType] || b.channelType).join('、')}`
                      : '绑定后，每日微练、待复核、待认证会推送到飞书/企微/钉钉'}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  {imBindings.map(b => (
                    <Button key={b.id} variant="neutral" size="sm"
                      onClick={() => onUnbindIm(b.id)}
                      title="解绑后不再收到 IM 提醒，站内信不受影响">
                      {CHANNEL_LABELS[b.channelType] || b.channelType} · 解绑
                    </Button>
                  ))}
                  <Button variant="primary" size="sm" onClick={onCreateBindCode} disabled={imLoading}
                    title="生成一次性绑定码，在机器人里发送「绑定 码」即可接收培训提醒">
                    {imLoading ? '生成中…' : imBindings.length > 0 ? '重新绑定' : '绑定 IM'}
                  </Button>
                </div>
              </div>
              {imCode && (
                <div className="mt-2 rounded-lg border border-primary/30 bg-primary-light/40 px-3 py-2 text-xs text-foreground">
                  在机器人里发送：<span className="font-mono text-base font-semibold tracking-wider">绑定 {imCode.code}</span>
                  <span className="ml-2 text-muted-foreground">10 分钟内有效</span>
                </div>
              )}
            </div>

            {error && (
              <div className="mb-4 rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
                {error}
                <button onClick={load} className="ml-3 underline">重试</button>
              </div>
            )}

            {!error && assignments.length === 0 && !loading && (
              <EmptyState icon="🎓" title="还没有分配给你的培训"
                description="主管分配后，这里会出现计划、截止时间与任务清单" />
            )}

            <div className="space-y-3">
              {assignments.map(a => {
                const pct = a.totalTasks > 0 ? Math.round((a.completedTasks / a.totalTasks) * 100) : 0;
                const open = expanded === a.id;
                return (
                  <div key={a.id} className="rounded-xl border border-border bg-surface-2">
                    <button onClick={() => toggle(a)}
                      className="flex w-full items-start justify-between gap-4 px-5 py-4 text-left">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-semibold text-foreground">{a.planName || '培训计划'}</span>
                          <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(a.status)}`}>
                            {ASSIGNMENT_STATUS_LABELS[a.status] || a.status}
                          </span>
                          {a.overdue && (
                            <span className="rounded-full border border-red-200 bg-red-50 px-2 py-0.5 text-xs text-red-600">
                              已逾期
                            </span>
                          )}
                          {a.certification?.status === 'confirmed' && (
                            <span className="rounded-full border border-green-200 bg-green-50 px-2 py-0.5 text-xs text-green-700">
                              已认证
                            </span>
                          )}
                          {a.certification?.status === 'revoked' && (
                            <span className="rounded-full border border-red-200 bg-red-50 px-2 py-0.5 text-xs text-red-600">
                              认证已撤销
                            </span>
                          )}
                        </div>
                        <div className="mt-1 text-xs text-muted-foreground">
                          {a.cohortName || '培训班'} · v{a.planVersion ?? '—'}
                          {' · '}必修通过 {a.requiredPassed}/{a.requiredTotal}
                          {a.deadline && ` · 截止 ${new Date(a.deadline).toLocaleString()}`}
                        </div>
                        <div className="mt-2 flex items-center gap-2">
                          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface">
                            <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
                          </div>
                          <span className="text-xs tabular-nums text-muted-foreground">
                            {a.completedTasks}/{a.totalTasks}
                          </span>
                        </div>
                        {a.certification?.status === 'confirmed' && (
                          <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-green-700">
                            <span>
                              认证人：{a.certification.confirmedByName || a.certification.confirmedBy}
                              {a.certification.note ? ` · ${a.certification.note}` : ''}
                            </span>
                            <span
                              role="button"
                              tabIndex={0}
                              title="在新窗口打开可打印的认证证书，可另存为 PDF"
                              onClick={e => { e.stopPropagation(); void openCertificate(a.id); }}
                              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.stopPropagation(); void openCertificate(a.id); } }}
                              className="cursor-pointer font-medium text-primary underline hover:no-underline"
                            >
                              下载认证证书
                            </span>
                          </div>
                        )}
                        {a.certification?.status === 'revoked' && (
                          <div className="mt-2 text-xs text-red-600">
                            认证已撤销
                            {a.certification.revokedByName ? `（${a.certification.revokedByName}）` : ''}
                            {a.certification.revokeReason ? `：${a.certification.revokeReason}` : ''}
                            ，完成整改后可重新认证
                          </div>
                        )}
                      </div>
                      <span className="mt-1 text-xs text-muted-foreground">{open ? '收起' : '展开任务'}</span>
                    </button>

                    {open && (
                      <div className="border-t border-border px-5 py-4">
                        {taskLoading === a.id ? <LoadingSpinner /> : taskError[a.id] ? (
                          <div className="text-sm text-danger">
                            {taskError[a.id]}
                            <button onClick={() => fetchTasks(a.id)} className="ml-2 underline">重试</button>
                          </div>
                        ) : (
                          <TaskList tasks={tasks[a.id] || []}
                            onOpen={(task) => {
                              // 已结束的尝试（考试/陪练）统一先看成绩，补考从成绩页发起
                              if (task.latestAttempt && task.latestAttempt.status !== 'in_progress'
                                && (task.type === 'exam' || task.type === 'practice' || task.type === 'drill')) {
                                router.push(`/training/attempts/${task.latestAttempt.id}/result`);
                                return;
                              }
                              if (task.type === 'exam' && task.latestAttempt) {
                                router.push(`/training/attempts/${task.latestAttempt.id}`);
                                return;
                              }
                              router.push(`/training/assignments/${a.id}/tasks/${task.taskId}`);
                            }} />
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>

            {assignments.length > 0 && (
              <Pagination page={page} totalPages={totalPages} totalElements={total}
                onPageChange={setPage} loading={loading} />
            )}
          </>
        )}

        {tab === 'mentor' && (
          <>
            {workbenchError ? (
              <div className="mb-5 rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-danger">
                工作台加载失败：{workbenchError}
                <button onClick={loadWorkbench} className="ml-2 underline">重试</button>
              </div>
            ) : workbenchLoading ? (
              <div className="mb-5"><LoadingSpinner /></div>
            ) : workbench && (
              <div className="mb-5 space-y-4">
                <div className="grid grid-cols-3 gap-3">
                  <div className="rounded-xl border border-border bg-surface-2 px-4 py-3">
                    <p className="text-xs text-muted-foreground">待复核</p>
                    <p className="mt-1 text-2xl font-bold text-foreground">{workbench.counts.pendingReview}</p>
                  </div>
                  <div className="rounded-xl border border-border bg-surface-2 px-4 py-3">
                    <p className="text-xs text-muted-foreground">待认证</p>
                    <p className="mt-1 text-2xl font-bold text-foreground">{workbench.counts.pendingCertification ?? 0}</p>
                  </div>
                  <div className="rounded-xl border border-border bg-surface-2 px-4 py-3">
                    <p className="text-xs text-muted-foreground">逾期学员</p>
                    <p className="mt-1 text-2xl font-bold text-warning-text">{workbench.counts.overdue}</p>
                  </div>
                </div>

                {(workbench.pendingCertifications?.length ?? 0) > 0 && (
                  <div className="rounded-xl border border-border bg-surface-2">
                    <p className="border-b border-border px-4 py-2.5 text-xs font-semibold text-muted-foreground">
                      学员已完成必修，等待确认认证
                    </p>
                    {workbench.pendingCertifications!.map(c => (
                      <button key={c.assignmentId}
                        onClick={() => router.push(`/admin/training/cohorts/${c.cohortId}`)}
                        className="flex w-full items-center justify-between border-b border-border px-4 py-2.5 text-left last:border-0 hover:bg-primary-light/40">
                        <span className="min-w-0">
                          <span className="text-sm text-foreground">{c.learnerName || '学员'}</span>
                          <span className="ml-2 text-xs text-muted-foreground">{c.cohortName}</span>
                        </span>
                        <span className="ml-3 shrink-0 text-xs text-muted-foreground">
                          必修 {c.requiredPassed}/{c.requiredTotal}
                          {c.updatedAt ? ` · ${new Date(c.updatedAt).toLocaleString()}` : ''}
                        </span>
                      </button>
                    ))}
                  </div>
                )}

                {workbench.pendingReviews.length > 0 && (
                  <div className="rounded-xl border border-border bg-surface-2">
                    <p className="border-b border-border px-4 py-2.5 text-xs font-semibold text-muted-foreground">
                      今天要处理 · 按提交时间排序
                    </p>
                    {workbench.pendingReviews.map(r => (
                      <button key={r.attemptId}
                        onClick={() => router.push(r.cohortId ? `/admin/training/cohorts/${r.cohortId}` : '/admin/training/cohorts')}
                        className="flex w-full items-center justify-between border-b border-border px-4 py-2.5 text-left last:border-0 hover:bg-primary-light/40">
                        <span className="min-w-0">
                          <span className="text-sm text-foreground">{r.learnerName || '学员'} · {r.taskTitle || '任务'}</span>
                          <span className="ml-2 text-xs text-muted-foreground">{r.cohortName}</span>
                        </span>
                        <span className="ml-3 shrink-0 text-xs text-muted-foreground">
                          第 {r.attemptNo} 次 · {r.submittedAt ? new Date(r.submittedAt).toLocaleString() : '—'}
                        </span>
                      </button>
                    ))}
                  </div>
                )}

                {workbench.overdueLearners.length > 0 && (
                  <div className="rounded-xl border border-amber-200 bg-amber-50">
                    <p className="border-b border-amber-200 px-4 py-2.5 text-xs font-semibold text-amber-800">
                      逾期学员
                    </p>
                    {workbench.overdueLearners.map(o => (
                      <div key={o.assignmentId} className="flex items-center justify-between px-4 py-2 text-sm">
                        <span className="text-foreground">{o.learnerName || '学员'} · {o.cohortName}</span>
                        <span className="text-xs text-amber-800">
                          截止 {new Date(o.deadline).toLocaleString()} · 必修 {o.requiredPassed}/{o.requiredTotal}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {cohortError ? (
              <div className="rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-danger">
                批次加载失败：{cohortError}
                <button onClick={loadCohorts} className="ml-2 underline">重试</button>
              </div>
            ) : cohortLoading ? <LoadingSpinner /> : cohorts.length === 0 ? (
              <EmptyState icon="👥" title="你还没有带班"
                description="企业管理员把批次指派给你后，就能在这里查看学员进度并复核" />
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {cohorts.map(c => (
                  <button key={c.id} onClick={() => router.push(`/admin/training/cohorts/${c.id}`)}
                    className="rounded-xl border border-border bg-surface-2 px-5 py-4 text-left hover:border-primary">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-foreground">{c.name}</span>
                      <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(c.status)}`}>
                        {COHORT_STATUS_LABELS[c.status] || c.status}
                      </span>
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">
                      v{c.planVersion} · 截止 {c.deadline ? new Date(c.deadline).toLocaleString() : '未设置'}
                    </div>
                  </button>
                ))}
              </div>
            )}
            {cohorts.length > 0 && (
              <Pagination page={cohortPage} totalPages={cohortTotalPages} totalElements={cohortTotal}
                onPageChange={setCohortPage} loading={cohortLoading} />
            )}
          </>
        )}
      </div>
    </div>
  );
}

function TaskList({ tasks, onOpen }: { tasks: TaskItem[]; onOpen: (task: TaskItem) => void }) {
  const phases = useMemo(() => {
    const map = new Map<number, { phaseNo: number; phaseName: string | null; items: TaskItem[] }>();
    for (const t of tasks) {
      const phase = map.get(t.phaseNo) || { phaseNo: t.phaseNo, phaseName: t.phaseName, items: [] };
      phase.items.push(t);
      map.set(t.phaseNo, phase);
    }
    return [...map.values()].sort((a, b) => a.phaseNo - b.phaseNo);
  }, [tasks]);

  if (tasks.length === 0) {
    return <div className="text-sm text-muted-foreground">暂无任务</div>;
  }

  return (
    <div className="space-y-4">
      <LearningPath phases={phases} />
      {phases.map(phase => (
        <div key={phase.phaseNo}>
          <div className="mb-2 text-xs font-medium text-muted-foreground">
            阶段 {phase.phaseNo}{phase.phaseName ? ` · ${phase.phaseName}` : ''}
          </div>
          <div className="space-y-1.5">
            {phase.items.map(task => {
              const locked = task.status === 'locked';
              const status = task.status;
              const action = taskAction(task);
              return (
                <div key={task.recordId}
                  className={`flex items-center gap-3 rounded-lg border border-border px-3 py-2.5 ${
                    locked ? 'bg-surface/60 opacity-70' : 'bg-surface'}`}>
                  <span className="rounded bg-primary-light px-2 py-0.5 text-xs text-primary">
                    {TASK_TYPE_LABELS[task.type] || task.type}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-medium text-foreground">{task.title}</span>
                      {task.roundNo != null && task.roundNo > 1 && (
                        <span className="rounded bg-amber-50 px-2 py-0.5 text-xs text-amber-700">
                          第 {task.roundNo} 轮补训
                        </span>
                      )}
                      {task.remediationReason && (
                        <span className="text-xs text-amber-700">补训原因：{task.remediationReason}</span>
                      )}
                      {!task.isRequired && <span className="text-xs text-muted-foreground">选修</span>}
                      <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(status)}`}>
                        {RECORD_STATUS_LABELS[status] || status}
                      </span>
                      {task.latestAttempt && (
                        <span className="text-xs text-muted-foreground">
                          第 {task.latestAttempt.attemptNo} 次 · {ATTEMPT_STATUS_LABELS[task.latestAttempt.status] || task.latestAttempt.status}
                        </span>
                      )}
                    </div>
                    {task.resourceTitle && (
                      <div className="mt-0.5 text-xs text-muted-foreground">资源：{task.resourceTitle}</div>
                    )}
                    {task.blockingReasons.length > 0 && (
                      <div className="mt-0.5 text-xs text-amber-700">
                        未解锁：{task.blockingReasons.join('；')}
                      </div>
                    )}
                    {(task.maxAttempts != null || task.passCriteria) && (
                      <div className="mt-0.5 text-xs text-muted-foreground">
                        {task.maxAttempts != null
                          && `最多 ${task.maxAttempts} 次 · 剩余 ${task.remainingAttempts ?? task.maxAttempts} 次`}
                        {task.passCriteria && ` · 通过条件：${task.passCriteria}`}
                        {task.examDurationMinutes ? ` · 限时 ${task.examDurationMinutes} 分钟` : ''}
                      </div>
                    )}
                  </div>
                  <Button
                    variant={locked ? 'neutral' : 'primary'}
                    size="sm"
                    disabled={locked}
                    onClick={() => onOpen(task)}
                    title={locked ? `未解锁：${task.blockingReasons.join('；') || '先完成前置任务'}` : `${action}：${task.title}`}
                  >
                    {action}
                  </Button>
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

/** 学习路径（TR-08）：阶段进度条，已完成/进行中/未解锁三态 */
function LearningPath({
  phases,
}: {
  phases: Array<{ phaseNo: number; phaseName: string | null; items: TaskItem[] }>;
}) {
  if (phases.length <= 1) return null;
  const overallTotal = phases.reduce((n, p) => n + p.items.length, 0);
  const overallPassed = phases.reduce(
    (n, p) => n + p.items.filter(i => i.status === 'passed').length, 0);

  return (
    <div className="rounded-xl border border-border bg-surface-2 px-4 py-3">
      <div className="mb-2 flex items-center justify-between text-xs text-muted-foreground">
        <span className="font-medium text-foreground">学习路径</span>
        <span>{overallPassed}/{overallTotal} 通过</span>
      </div>
      <div className="flex items-center gap-1 overflow-x-auto pb-1">
        {phases.map((phase, idx) => {
          const passed = phase.items.filter(i => i.status === 'passed').length;
          const done = passed === phase.items.length;
          const locked = phase.items.every(i => i.status === 'locked');
          const active = !done && !locked;
          return (
            <React.Fragment key={phase.phaseNo}>
              {idx > 0 && <span className="flex-none text-muted-foreground">→</span>}
              <div className={`min-w-[104px] flex-none rounded-lg border px-3 py-1.5 text-xs ${
                done
                  ? 'border-green-200 bg-green-50 text-green-700'
                  : active
                    ? 'border-primary bg-primary-light text-primary'
                    : 'border-border bg-surface text-muted-foreground'
              }`}>
                <div className="font-medium">
                  {done ? '✓ ' : locked ? '🔒 ' : ''}阶段 {phase.phaseNo}
                </div>
                <div className="truncate">{phase.phaseName || '—'}</div>
                <div className="text-[10px] opacity-80">{passed}/{phase.items.length} 完成</div>
              </div>
            </React.Fragment>
          );
        })}
      </div>
    </div>
  );
}

function taskAction(task: TaskItem): string {
  switch (task.status) {
    case 'locked': return '未解锁';
    case 'passed':
      // 阅读任务通过后正文仍可回看；考试/陪练通过后按钮进成绩页
      return task.type === 'reading' ? '查看内容' : '查看成绩';
    case 'waived': return '已免修';
    case 'pending_review': return '待复核';
    case 'in_progress': return '继续';
    case 'failed': return '再试一次';
    default: return task.latestAttempt ? '查看' : '开始';
  }
}
