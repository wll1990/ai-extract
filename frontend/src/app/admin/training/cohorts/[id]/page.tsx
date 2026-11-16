'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getCohort, getCohortProgress, batchAssign, cancelAssignment, certifyAssignment,
  listAssignmentAttempts, getAdminAttempt, reviewAttempt, regradeAttempt,
  createRemediation, reopenTask, waiveTask, revokeCertification,
  extendAssignmentDeadline, extendCohortDeadline, getCohortEffect,
  requestCohortExport, getExportJob, downloadExport, searchPicker,
  COHORT_STATUS_LABELS, ASSIGNMENT_STATUS_LABELS, RECORD_STATUS_LABELS,
  ATTEMPT_STATUS_LABELS, TASK_TYPE_LABELS, TERMINATION_LABELS, statusCls,
  type CohortProgress, type LearnerProgress, type AssignmentAttempts,
  type AdminAttemptDetail, type AttemptRow, type TaskRound, type ExportJob,
} from '@/lib/api/training';
import { getCurrentUser } from '@/lib/api/auth';
import { Permission } from '@/lib/permissions';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';

interface UserOption { id: string; name: string; account: string; role: string }

export default function AdminTrainingCohortPage() {
  const router = useRouter();
  const params = useParams();
  const cohortId = String(params.id);

  const [progress, setProgress] = useState<CohortProgress | null>(null);
  const [planName, setPlanName] = useState<string | null>(null);
  // 导师（TRAINING_REVIEW）能看进度、复核、认证；分配/取消属 TRAINING_MANAGE
  const [canManage, setCanManage] = useState(false);
  const [loading, setLoading] = useState(true);

  // 分页 / 筛选
  const [page, setPage] = useState(1);
  const pageSize = 20;
  const [statusFilter, setStatusFilter] = useState('');
  const [keywordDraft, setKeywordDraft] = useState('');
  const [keyword, setKeyword] = useState('');

  // 导出
  const [exportJob, setExportJob] = useState<ExportJob | null>(null);
  const [exporting, setExporting] = useState(false);

  // 延期 / 撤销 / 补训弹层
  const [extendTarget, setExtendTarget] = useState<LearnerProgress | null>(null);
  const [extendDeadline, setExtendDeadline] = useState('');
  const [extendReason, setExtendReason] = useState('');
  const [revokeTarget, setRevokeTarget] = useState<LearnerProgress | null>(null);
  const [revokeReason, setRevokeReason] = useState('');
  const [remediateTarget, setRemediateTarget] = useState<{
    row: LearnerProgress; round: TaskRound; title: string | null;
  } | null>(null);
  const [remediateReason, setRemediateReason] = useState('');
  const [remediateDeadline, setRemediateDeadline] = useState('');
  // 误判重做 / 免修 / 批次延期
  const [reopenTarget, setReopenTarget] = useState<{
    row: LearnerProgress; round: TaskRound; title: string | null;
  } | null>(null);
  const [reopenReason, setReopenReason] = useState('');
  const [reopenDeadline, setReopenDeadline] = useState('');
  const [waiveTarget, setWaiveTarget] = useState<{
    row: LearnerProgress; round: TaskRound; title: string | null;
  } | null>(null);
  const [waiveReason, setWaiveReason] = useState('');
  const [cohortExtendOpen, setCohortExtendOpen] = useState(false);
  const [cohortExtendDeadline, setCohortExtendDeadline] = useState('');
  const [cohortExtendReason, setCohortExtendReason] = useState('');
  // AI 培训效果
  const [effect, setEffect] = useState<Awaited<ReturnType<typeof getCohortEffect>> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [acting, setActing] = useState(false);

  // 分配
  const [users, setUsers] = useState<UserOption[]>([]);
  const [userKeyword, setUserKeyword] = useState('');
  const [selectedLearners, setSelectedLearners] = useState<string[]>([]);
  const [showAssign, setShowAssign] = useState(false);
  const [assignResult, setAssignResult] = useState<{
    assigned: number; skipped: number; failed: number;
    results: Array<{ learnerId: string | null; name: string | null; status: string; reason: string | null }>;
  } | null>(null);

  // 学员抽屉
  const [active, setActive] = useState<LearnerProgress | null>(null);
  const [attempts, setAttempts] = useState<AssignmentAttempts | null>(null);
  const [attemptLoading, setAttemptLoading] = useState(false);
  const [attemptsError, setAttemptsError] = useState<string | null>(null);
  const [activeAttempt, setActiveAttempt] = useState<AdminAttemptDetail | null>(null);
  const [attemptDetailLoading, setAttemptDetailLoading] = useState(false);
  const [attemptDetailError, setAttemptDetailError] = useState<string | null>(null);
  const attemptSeq = useRef(0);
  const [reviewAction, setReviewAction] = useState<'pass' | 'reject'>('pass');
  const [reviewReason, setReviewReason] = useState('');
  const [submittingReview, setSubmittingReview] = useState(false);

  // 认证备注弹层（window.prompt 在部分内嵌浏览器会被拦截）
  const [certifyTarget, setCertifyTarget] = useState<LearnerProgress | null>(null);
  const [certifyNote, setCertifyNote] = useState('');

  // 快速切筛选/翻页时丢弃过期响应，避免表格内容与筛选器不一致
  const loadSeq = useRef(0);
  const planLoadedRef = useRef(false);

  const load = useCallback(async () => {
    const seq = ++loadSeq.current;
    setLoading(true);
    setError(null);
    try {
      const p = await getCohortProgress(cohortId, {
        page, size: pageSize, status: statusFilter || undefined, keyword: keyword || undefined,
      });
      if (seq === loadSeq.current) setProgress(p);
      if (p.summary?.planName) {
        setPlanName(p.summary.planName);
        planLoadedRef.current = true;
      }
    } catch (e) {
      if (seq === loadSeq.current) setError(e instanceof Error ? e.message : '加载失败');
      return;
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
    // 旧后端没有 summary.planName 时才回退拉一次批次详情
    if (!planLoadedRef.current) {
      try {
        const detail = await getCohort(cohortId);
        if (seq === loadSeq.current) {
          setPlanName(detail.planName);
          planLoadedRef.current = true;
        }
      } catch {
        // 名称缺失不影响进度页主流程
      }
    }
  }, [cohortId, page, statusFilter, keyword]);

  useEffect(() => { load(); }, [load]);

  // 效果视图单独拉取：失败不影响主流程（进度页仍可用）
  useEffect(() => {
    getCohortEffect(cohortId).then(setEffect).catch(() => setEffect(null));
  }, [cohortId]);

  // 复核/认证后刷新进度时，同步抽屉头部的计数与认证状态
  useEffect(() => {
    if (!progress || !active) return;
    const row = progress.learners.find(l => l.assignmentId === active.assignmentId);
    if (row && row !== active) setActive(row);
    // active 变化由这里收敛，不能再进依赖，否则自激
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress]);

  useEffect(() => {
    getCurrentUser()
      .then(u => setCanManage((u.permissions || []).includes(Permission.TRAINING_MANAGE)))
      .catch(() => setCanManage(false));
  }, []);

  const loadUsers = async (kw = '') => {
    try {
      const data = await searchPicker('users', { keyword: kw || undefined, size: 30 });
      setUsers((data.content || []).map(u => ({
        id: u.id, name: u.name, account: u.subtitle || '', role: u.status,
      })));
    } catch {
      setToast('用户列表加载失败');
    }
  };

  const submitAssign = async () => {
    if (selectedLearners.length === 0) { setToast('请先选择学员'); return; }
    setActing(true);
    try {
      const result = await batchAssign(cohortId, selectedLearners);
      setAssignResult(result);
      setSelectedLearners([]);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '分配失败');
    } finally {
      setActing(false);
    }
  };

  const openCertify = (row: LearnerProgress) => {
    setCertifyTarget(row);
    setCertifyNote('');
  };

  const submitCertify = async () => {
    if (!certifyTarget) return;
    setActing(true);
    try {
      const result = await certifyAssignment(certifyTarget.assignmentId, certifyNote.trim() || undefined);
      setToast(result.alreadyCertified ? '该学员已认证过（备注未被覆盖）' : '认证已确认');
      setCertifyTarget(null);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '认证失败');
    } finally {
      setActing(false);
    }
  };

  const onCancel = async (row: LearnerProgress) => {
    if (!window.confirm(`取消「${row.learnerName || '该学员'}」的分配？历史记录会保留。`)) return;
    setActing(true);
    try {
      await cancelAssignment(row.assignmentId);
      setToast('分配已取消');
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '取消失败');
    } finally {
      setActing(false);
    }
  };

  // 关 A 立刻开 B 时，A 的响应后到不能覆盖 B（会串人、且含他人作答证据）
  const attemptsSeq = useRef(0);
  const loadAttempts = async (assignmentId: string) => {
    const seq = ++attemptsSeq.current;
    setAttemptLoading(true);
    setAttemptsError(null);
    try {
      const data = await listAssignmentAttempts(assignmentId);
      if (seq === attemptsSeq.current) setAttempts(data);
    } catch (e) {
      if (seq === attemptsSeq.current) {
        setAttemptsError(e instanceof Error ? e.message : '加载尝试失败');
        setAttempts(null);
      }
    } finally {
      if (seq === attemptsSeq.current) setAttemptLoading(false);
    }
  };

  const submitExtend = async () => {
    if (!extendTarget) return;
    if (!extendDeadline) { setToast('请选择新截止时间'); return; }
    const nextTime = new Date(extendDeadline).getTime();
    if (Number.isNaN(nextTime) || nextTime <= Date.now()) {
      setToast('新截止必须晚于当前时间');
      return;
    }
    if (extendTarget.deadline && nextTime <= new Date(extendTarget.deadline).getTime()) {
      setToast('新截止必须晚于原截止（只能延长）');
      return;
    }
    if (extendReason.trim().length < 2) { setToast('请填写延期理由'); return; }
    setActing(true);
    try {
      const result = await extendAssignmentDeadline(extendTarget.assignmentId, {
        deadline: extendDeadline,
        reason: extendReason.trim(),
      });
      setToast(`截止已延长${result.updatedAttempts ? `，同步调整 ${result.updatedAttempts} 个进行中考试` : ''}`);
      setExtendTarget(null);
      setExtendDeadline('');
      setExtendReason('');
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '延期失败');
    } finally {
      setActing(false);
    }
  };

  const submitRevoke = async () => {
    if (!revokeTarget) return;
    if (revokeReason.trim().length < 5) { setToast('撤销理由至少 5 个字'); return; }
    setActing(true);
    try {
      await revokeCertification(revokeTarget.assignmentId, revokeReason.trim());
      setToast('认证已撤销，学员回到待认证');
      setRevokeTarget(null);
      setRevokeReason('');
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '撤销失败');
    } finally {
      setActing(false);
    }
  };

  const submitRemediate = async () => {
    if (!remediateTarget) return;
    if (remediateReason.trim().length < 2) { setToast('补训原因至少 2 个字'); return; }
    if (remediateTarget.row.overdue && !remediateDeadline) {
      setToast('该学员分配已过截止，补训必须同时设置新截止');
      return;
    }
    if (remediateDeadline) {
      const nextTime = new Date(remediateDeadline).getTime();
      if (Number.isNaN(nextTime) || nextTime <= Date.now()) {
        setToast('新截止必须晚于当前时间');
        return;
      }
      if (remediateTarget.row.deadline
          && nextTime <= new Date(remediateTarget.row.deadline).getTime()) {
        setToast('新截止必须晚于原截止（只能延长）');
        return;
      }
    }
    setActing(true);
    try {
      const result = await createRemediation(remediateTarget.row.assignmentId, {
        recordId: remediateTarget.round.recordId,
        reason: remediateReason.trim(),
        deadline: remediateDeadline || undefined,
      });
      setToast(`已安排第 ${result.roundNo} 轮补训`);
      setRemediateTarget(null);
      setRemediateReason('');
      setRemediateDeadline('');
      await openDrawer(remediateTarget.row);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '补训失败');
    } finally {
      setActing(false);
    }
  };

  const submitReopen = async () => {
    if (!reopenTarget) return;
    if (reopenReason.trim().length < 5) { setToast('重做原因至少 5 个字（需说明误判依据）'); return; }
    if (reopenTarget.row.overdue && !reopenDeadline) {
      setToast('该学员分配已过截止，重做必须同时设置新截止');
      return;
    }
    if (reopenDeadline) {
      const nextTime = new Date(reopenDeadline).getTime();
      if (Number.isNaN(nextTime) || nextTime <= Date.now()) {
        setToast('新截止必须晚于当前时间');
        return;
      }
      if (reopenTarget.row.deadline
          && nextTime <= new Date(reopenTarget.row.deadline).getTime()) {
        setToast('新截止必须晚于原截止（只能延长）');
        return;
      }
    }
    setActing(true);
    try {
      const result = await reopenTask(reopenTarget.row.assignmentId, {
        recordId: reopenTarget.round.recordId,
        reason: reopenReason.trim(),
        deadline: reopenDeadline || undefined,
      });
      setToast(`已要求第 ${result.roundNo} 轮重做`);
      setReopenTarget(null);
      setReopenReason('');
      setReopenDeadline('');
      await openDrawer(reopenTarget.row);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '重做安排失败');
    } finally {
      setActing(false);
    }
  };

  const submitWaive = async () => {
    if (!waiveTarget) return;
    if (waiveReason.trim().length < 2) { setToast('免修原因至少 2 个字'); return; }
    setActing(true);
    try {
      await waiveTask(waiveTarget.row.assignmentId, waiveTarget.round.recordId,
        waiveReason.trim());
      setToast('已标记免修');
      setWaiveTarget(null);
      setWaiveReason('');
      await openDrawer(waiveTarget.row);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '免修失败');
    } finally {
      setActing(false);
    }
  };

  const submitCohortExtend = async () => {
    if (!cohortExtendDeadline) { setToast('请选择新截止时间'); return; }
    if (new Date(cohortExtendDeadline).getTime() <= Date.now()) {
      setToast('新截止必须晚于当前时间');
      return;
    }
    if (cohortExtendReason.trim().length < 2) { setToast('延期理由必填'); return; }
    setActing(true);
    try {
      const result = await extendCohortDeadline(cohortId, {
        deadline: cohortExtendDeadline,
        reason: cohortExtendReason.trim(),
      });
      setToast(`已延期 ${result.extended} 人${result.skipped > 0 ? `，跳过 ${result.skipped} 人（已结束或不晚于个人当前截止）` : ''}`);
      setCohortExtendOpen(false);
      setCohortExtendDeadline('');
      setCohortExtendReason('');
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '批次延期失败');
    } finally {
      setActing(false);
    }
  };

  const startExport = async () => {
    setExporting(true);
    try {
      const job = await requestCohortExport(cohortId);
      setExportJob(job);
      pollExport(job.id, 0);
    } catch (e) {
      setExporting(false);
      setToast(e instanceof Error ? e.message : '导出失败');
    }
  };

  const pollExport = async (jobId: string, attempt: number) => {
    if (attempt > 30) {
      setExporting(false);
      setToast('导出耗时较长，请稍后刷新导出列表');
      return;
    }
    try {
      const job = await getExportJob(jobId);
      setExportJob(job);
      if (job.status === 'done') {
        setToast(`导出完成，共 ${job.rowCount ?? 0} 行`);
        setExporting(false);
        return;
      }
      if (job.status === 'failed') {
        setToast(job.errorMessage || '导出失败');
        setExporting(false);
        return;
      }
    } catch {
      // 轮询失败继续重试
    }
    setTimeout(() => pollExport(jobId, attempt + 1), 2000);
  };

  const openDrawer = async (row: LearnerProgress) => {
    setActive(row);
    setAttempts(null);
    setActiveAttempt(null);
    setAttemptsError(null);
    setAttemptDetailError(null);
    setReviewReason('');
    setReviewAction('pass');
    await loadAttempts(row.assignmentId);
  };

  const closeDrawer = () => {
    // 作废在途请求，避免关闭后旧响应把上一个学员的数据带回来
    attemptsSeq.current++;
    attemptSeq.current++;
    setActive(null);
    setActiveAttempt(null);
    setAttempts(null);
  };

  const openAttempt = async (attemptId: string) => {
    // 快速点两条尝试时，丢弃后返回的旧响应
    const seq = ++attemptSeq.current;
    setActiveAttempt(null);
    setAttemptDetailError(null);
    setAttemptDetailLoading(true);
    try {
      const detail = await getAdminAttempt(attemptId);
      if (seq === attemptSeq.current) setActiveAttempt(detail);
    } catch (e) {
      if (seq === attemptSeq.current) {
        setAttemptDetailError(e instanceof Error ? e.message : '加载尝试详情失败');
      }
    } finally {
      if (seq === attemptSeq.current) setAttemptDetailLoading(false);
    }
    // 详情渲染位置在抽屉底部，滚过去避免「点了没反应」
    setTimeout(() => {
      document.getElementById('attempt-detail')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 120);
  };

  const submitReview = async () => {
    if (!activeAttempt) return;
    if (reviewReason.trim().length < 5) { setToast('复核理由至少 5 个字'); return; }
    setSubmittingReview(true);
    try {
      await reviewAttempt(activeAttempt.attempt.id, reviewAction, reviewReason.trim());
      setToast(reviewAction === 'pass' ? '已复核通过' : '已退回');
      setReviewReason('');
      setActiveAttempt(null);
      if (active) await loadAttempts(active.assignmentId);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '复核失败');
    } finally {
      setSubmittingReview(false);
    }
  };

  const onRegrade = async (attemptId: string) => {
    if (!window.confirm('重新触发 AI 评分？只对评分失败的尝试有效。')) return;
    setActing(true);
    try {
      await regradeAttempt(attemptId);
      setToast('已重新入队，几秒后刷新');
      await openAttempt(attemptId);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '重跑失败');
    } finally {
      setActing(false);
    }
  };

  if (loading && !progress) return <div className="p-8"><LoadingSpinner /></div>;
  if (error) {
    return (
      <div className="p-8">
        <div className="rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
          {error}
          <button onClick={load} className="ml-3 underline">重试</button>
        </div>
      </div>
    );
  }
  if (!progress) return null;

  const pendingRows = progress.learners.filter(l => l.pendingReview);

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[1180px]">
        <button onClick={() => router.push(canManage ? '/admin/training/plans' : '/training')}
          className="mb-2 text-xs text-muted-foreground hover:underline">
          ← {canManage ? '返回培训管理' : '返回我的培训'}
        </button>

        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">{progress.name}</h1>
            <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
              <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(progress.status)}`}>
                {COHORT_STATUS_LABELS[progress.status] || progress.status}
              </span>
              {planName && <span>{planName}</span>}
              <span>· v{progress.planVersion}</span>
              <span>· 截止 {progress.deadline ? new Date(progress.deadline).toLocaleString() : '未设置'}</span>
            </div>
          </div>
          <div className="flex gap-2">
            {canManage && progress.status === 'active' && (
              <button onClick={() => { setShowAssign(s => !s); loadUsers(); }}
                className="rounded-full bg-primary px-4 py-2.5 text-sm font-medium text-white hover:opacity-90">
                {showAssign ? '收起' : '＋ 分配学员'}
              </button>
            )}
            {canManage && progress.status !== 'active' && (
              <span className="self-center text-xs text-muted-foreground">
                批次已结束，不能再分配学员
              </span>
            )}
            {canManage && progress.status === 'active' && (
              <button onClick={() => setCohortExtendOpen(true)}
                className="rounded-full border border-border px-4 py-2.5 text-sm text-foreground hover:bg-primary-light">
                批次延期
              </button>
            )}
            {canManage && (
              <button onClick={startExport} disabled={exporting}
                className="rounded-full border border-border px-4 py-2.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">
                {exporting ? '导出中…' : '导出名单'}
              </button>
            )}
            <button onClick={load}
              className="rounded-full border border-border px-4 py-2.5 text-sm text-foreground hover:bg-primary-light">
              刷新
            </button>
          </div>
        </div>

        {exportJob && (
          <div className="mb-4 rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm">
            <span className="text-foreground">导出任务：{exportJob.status === 'done' ? '已完成' : exportJob.status === 'failed' ? '失败' : '生成中…'}</span>
            {exportJob.status === 'done' && (
              <button onClick={async () => {
                try {
                  await downloadExport(exportJob.id);
                } catch (e) {
                  setToast(e instanceof Error ? e.message : '下载失败');
                }
              }} className="ml-3 text-primary underline">下载 CSV</button>
            )}
            {exportJob.status === 'failed' && (
              <span className="ml-3 text-red-600">{exportJob.errorMessage}</span>
            )}
          </div>
        )}

        {/* AI 培训效果：不只看完成率 */}
        {effect && (
          <div className="mb-5 rounded-xl border border-border bg-surface-2 px-5 py-4">
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <div className="text-sm font-semibold text-foreground">培训效果</div>
              <div className="text-xs text-muted-foreground">{effect.summary}</div>
            </div>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <div className="rounded-lg bg-surface px-3 py-2">
                <div className="text-xs text-muted-foreground">认证率</div>
                <div className="text-xl font-semibold tabular-nums text-foreground">
                  {effect.certificationRate}%
                </div>
                <div className="text-[10px] text-muted-foreground">
                  {effect.certified}/{Math.max(0, effect.totalLearners - effect.cancelled)}
                </div>
              </div>
              <div className="rounded-lg bg-surface px-3 py-2">
                <div className="text-xs text-muted-foreground">补训轮次</div>
                <div className="text-xl font-semibold tabular-nums text-foreground">
                  {effect.remediationRounds}
                </div>
              </div>
              <div className="rounded-lg bg-surface px-3 py-2">
                <div className="text-xs text-muted-foreground">AI 转人工率</div>
                <div className="text-xl font-semibold tabular-nums text-foreground">
                  {effect.reviewRate}%
                </div>
                <div className="text-[10px] text-muted-foreground">
                  {effect.needReviewAttempts}/{effect.gradedAttempts} 次尝试
                </div>
              </div>
              <div className="rounded-lg bg-surface px-3 py-2">
                <div className="text-xs text-muted-foreground">待补知识缺口</div>
                <div className="text-xl font-semibold tabular-nums text-foreground">{effect.openGaps}</div>
              </div>
            </div>
            {effect.scenes.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-2">
                {effect.scenes.map(s => (
                  <span key={s.sceneTag}
                    className="rounded-full border border-border bg-surface px-3 py-1 text-xs text-foreground">
                    {s.sceneTag} · 均分 {s.avgScore}
                    <span className="ml-1 text-muted-foreground">（{s.attempts} 次）</span>
                  </span>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 汇总 */}
        <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-5">
          {([
            ['学员', progress.summary.total, ''],
            ['进行中', progress.summary.inProgress, ''],
            ['待复核', progress.summary.pendingReview, 'text-amber-600'],
            ['已认证', progress.summary.certified, 'text-green-600'],
            ['逾期', progress.summary.overdue, 'text-red-600'],
          ] as [string, number, string][]).map(([label, value, cls]) => (
            <div key={label} className="rounded-xl border border-border bg-surface-2 px-4 py-3">
              <div className="text-xs text-muted-foreground">{label}</div>
              <div className={`mt-1 text-2xl font-semibold tabular-nums ${cls || 'text-foreground'}`}>{value}</div>
            </div>
          ))}
        </div>

        {progress.summary.pendingReview > 0 && (
          <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            有 {progress.summary.pendingReview} 名学员的尝试等待人工复核
            {pendingRows.length > 0 && `，当前页：${pendingRows.map(r => r.learnerName || r.learnerId.slice(0, 8)).join('、')}`}
          </div>
        )}

        {/* 批量分配 */}
        {showAssign && (
          <div className="mb-5 rounded-xl border border-border bg-surface-2 p-5">
            <h2 className="mb-3 text-base font-semibold text-foreground">分配学员</h2>
            <div className="mb-2 flex gap-2">
              <input value={userKeyword} onChange={e => setUserKeyword(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') loadUsers(userKeyword.trim()); }}
                placeholder="搜索姓名/账号…"
                className="w-64 rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
              <button onClick={() => loadUsers(userKeyword.trim())}
                className="rounded-md border border-border px-3 py-2 text-sm hover:bg-primary-light">搜索</button>
            </div>
            <div className="max-h-56 overflow-y-auto rounded-lg border border-border bg-surface p-2">
              {users.length === 0 && <div className="p-2 text-sm text-muted-foreground">没有可分配的用户</div>}
              {users.map(u => (
                <label key={u.id} className="flex items-center gap-2 rounded px-2 py-1.5 text-sm text-foreground hover:bg-primary-light/50">
                  <input type="checkbox"
                    checked={selectedLearners.includes(u.id)}
                    onChange={e => setSelectedLearners(prev => e.target.checked
                      ? [...prev, u.id] : prev.filter(id => id !== u.id))} />
                  {u.name} <span className="text-xs text-muted-foreground">{u.account}</span>
                </label>
              ))}
            </div>
            <div className="mt-1 text-[10px] text-muted-foreground">
              列表最多显示 30 人；人多时输入姓名/账号精确搜索。已选学员跨搜索保留。
            </div>
            <div className="mt-3 flex items-center justify-between">
              <span className="text-xs text-muted-foreground">已选 {selectedLearners.length} 人；重复分配会自动跳过</span>
              <button onClick={submitAssign} disabled={acting || selectedLearners.length === 0}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '分配中…' : '开始分配'}
              </button>
            </div>
            {assignResult && (
              <div className="mt-3 rounded-lg border border-border bg-surface p-3">
                <div className="mb-2 text-sm text-foreground">
                  成功 {assignResult.assigned} · 跳过 {assignResult.skipped} · 失败 {assignResult.failed}
                </div>
                <div className="max-h-40 space-y-1 overflow-y-auto">
                  {assignResult.results.map((r, i) => (
                    <div key={`${r.learnerId}-${i}`} className="flex items-center gap-2 text-xs">
                      <span className={`inline-flex rounded-full border px-2 py-0.5 ${statusCls(r.status)}`}>
                        {r.status === 'assigned' ? '已分配'
                          : r.status === 'skipped' ? '已跳过'
                          : r.status === 'failed' ? '失败' : r.status}
                      </span>
                      <span className="text-foreground">{r.name || r.learnerId || '未知用户'}</span>
                      {r.reason && <span className="text-muted-foreground">{r.reason}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* 筛选 */}
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <select value={statusFilter}
            onChange={e => { setStatusFilter(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部状态</option>
            {Object.entries(ASSIGNMENT_STATUS_LABELS).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </select>
          <input value={keywordDraft} onChange={e => setKeywordDraft(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { setKeyword(keywordDraft.trim()); setPage(1); } }}
            placeholder="搜索学员姓名/账号…"
            className="w-56 rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
          <button onClick={() => { setKeyword(keywordDraft.trim()); setPage(1); }}
            className="rounded-md bg-primary px-4 py-2 text-sm text-white hover:opacity-90">搜索</button>
          <span className="text-xs text-muted-foreground">
            共 {progress.total} 人，第 {progress.page}/{Math.max(progress.totalPages, 1)} 页
          </span>
        </div>

        {/* 学员进度 */}
        <div className="overflow-x-auto rounded-xl border border-border bg-surface-2">
          <table className="w-full min-w-[880px] text-sm">
            <thead className="bg-surface text-left text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">学员</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium">任务</th>
                <th className="px-4 py-3 font-medium">必修通过</th>
                <th className="px-4 py-3 font-medium">截止</th>
                <th className="px-4 py-3 font-medium">认证</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {progress.learners.length === 0 && (
                <tr><td colSpan={7} className="px-4 py-10 text-center text-sm text-muted-foreground">
                  {statusFilter || keyword ? '没有符合筛选条件的学员' : '还没有学员，先点右上角「分配学员」'}
                </td></tr>
              )}
              {progress.learners.map(row => (
                <tr key={row.assignmentId} className="border-t border-border">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-foreground">{row.learnerName || row.learnerId.slice(0, 8)}</span>
                      {row.pendingReview && (
                        <span className={`rounded-full border px-2 py-0.5 text-xs ${
                          row.pendingReviewOverdue
                            ? 'border-red-200 bg-red-50 text-red-700'
                            : 'border-amber-200 bg-amber-50 text-amber-700'}`}>
                          待复核{row.pendingReviewHours != null ? ` ${row.pendingReviewHours}h` : ''}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(row.status)}`}>
                      {ASSIGNMENT_STATUS_LABELS[row.status] || row.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 tabular-nums text-foreground">
                    {row.completedTasks}/{row.totalTasks}
                  </td>
                  <td className="px-4 py-3 tabular-nums text-foreground">
                    {row.requiredPassed}/{row.requiredTotal}
                  </td>
                  <td className="px-4 py-3 text-xs">
                    {row.deadline ? (
                      <span className={row.overdue ? 'text-red-600' : 'text-muted-foreground'}>
                        {new Date(row.deadline).toLocaleString()}{row.overdue ? '（已逾期）' : ''}
                      </span>
                    ) : <span className="text-muted-foreground">未设置</span>}
                  </td>
                  <td className="px-4 py-3 text-xs">
                    {row.certification?.status === 'confirmed' ? (
                      <span className="text-green-700">
                        {row.certification.confirmedAt
                          ? new Date(row.certification.confirmedAt).toLocaleDateString()
                          : '—'} · {row.certification.confirmedByName || '已确认'}
                      </span>
                    ) : row.certification?.status === 'revoked' ? (
                      <span className="text-red-600">
                        已撤销
                        {row.certification.revokedAt
                          ? ` · ${new Date(row.certification.revokedAt).toLocaleDateString()}`
                          : ''}
                        {row.certification.revokedByName ? ` · ${row.certification.revokedByName}` : ''}
                      </span>
                    ) : row.canCertify ? (
                      <span className="text-amber-700">待确认</span>
                    ) : (
                      <span className="text-muted-foreground">
                        {row.certificationBlockers.length > 0
                          ? `缺：${row.certificationBlockers.map(b => b.title).join('、')}`
                          : row.certifyBlockedReason === 'already_certified' ? '已认证'
                          : row.certifyBlockedReason === 'cancelled' ? '已取消'
                          : row.certifyBlockedReason === 'not_pending' ? '必修未全部完成'
                          : row.certifyBlockedReason === 'self' ? '不能认证本人'
                          : '—'}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap justify-end gap-1">
                      <button onClick={() => openDrawer(row)}
                        className="rounded-full border border-border px-3 py-1 text-xs text-foreground hover:bg-primary-light">
                        详情 / 复核
                      </button>
                      {row.canCertify && (
                        <button onClick={() => openCertify(row)} disabled={acting}
                          className="rounded-full bg-primary px-3 py-1 text-xs text-white hover:opacity-90 disabled:opacity-50">
                          认证
                        </button>
                      )}
                      {(row.status === 'not_started' || row.status === 'in_progress') && (
                        <button onClick={() => {
                          setExtendTarget(row);
                          // 不预填原截止：原值提交必然「没有向后延长」而被 409
                          setExtendDeadline('');
                          setExtendReason('');
                        }} className="rounded-full border border-border px-3 py-1 text-xs text-foreground hover:bg-primary-light">
                          延期
                        </button>
                      )}
                      {row.certification?.status === 'confirmed' && (
                        <button onClick={() => { setRevokeTarget(row); setRevokeReason(''); }}
                          className="rounded-full border border-amber-300 px-3 py-1 text-xs text-amber-700 hover:bg-amber-50">
                          撤销认证
                        </button>
                      )}
                      {canManage && row.status !== 'cancelled' && row.status !== 'certified' && (
                        <button onClick={() => onCancel(row)} disabled={acting}
                          className="rounded-full border border-red-200 px-3 py-1 text-xs text-red-600 hover:bg-red-50 disabled:opacity-50">
                          取消
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {progress.totalPages > 1 && (
          <div className="flex items-center justify-center gap-3 py-4">
            <button disabled={loading || page <= 1} onClick={() => setPage(p => Math.max(1, p - 1))}
              className="rounded-full border border-border px-4 py-2 text-sm disabled:opacity-30">上一页</button>
            <span className="text-xs text-muted-foreground">第 {progress.page}/{progress.totalPages} 页</span>
            <button disabled={loading || page >= progress.totalPages} onClick={() => setPage(p => p + 1)}
              className="rounded-full border border-border px-4 py-2 text-sm disabled:opacity-30">下一页</button>
          </div>
        )}
      </div>

      {/* 学员抽屉 */}
      {active && (
        <div className="fixed inset-0 z-40 bg-black/30" onClick={closeDrawer}>
          <div className="absolute right-0 top-0 h-full w-full max-w-[760px] overflow-y-auto bg-surface p-6 shadow-2xl"
            onClick={e => e.stopPropagation()}>
            <div className="mb-4 flex items-start justify-between">
              <div>
                <h2 className="text-lg font-semibold text-foreground">{active.learnerName || active.learnerId}</h2>
                <div className="mt-1 text-xs text-muted-foreground">
                  {ASSIGNMENT_STATUS_LABELS[active.status] || active.status}
                  {' · '}任务 {active.completedTasks}/{active.totalTasks}
                  {' · '}必修通过 {active.requiredPassed}/{active.requiredTotal}
                </div>
              </div>
              <button onClick={closeDrawer}
                className="text-sm text-muted-foreground hover:underline">关闭</button>
            </div>

            {attemptsError && (
              <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                尝试记录加载失败：{attemptsError}
                <button onClick={() => active && loadAttempts(active.assignmentId)} className="ml-3 underline">重试</button>
              </div>
            )}

            {attemptLoading ? <LoadingSpinner /> : !attempts ? null : (
              <div className="space-y-3">
                {attempts.tasks.length === 0 && (
                  <div className="py-10 text-center text-sm text-muted-foreground">该学员还没有任务记录</div>
                )}
                {attempts.tasks.map(task => (
                  <div key={task.recordId} className="rounded-xl border border-border bg-surface-2 p-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="rounded bg-primary-light px-2 py-0.5 text-xs text-primary">
                        {TASK_TYPE_LABELS[task.type || ''] || task.type || '任务'}
                      </span>
                      <span className="font-medium text-foreground">{task.title || task.taskId.slice(0, 8)}</span>
                      <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(task.recordStatus)}`}>
                        {RECORD_STATUS_LABELS[task.recordStatus] || task.recordStatus}
                      </span>
                      {task.phaseName && <span className="text-xs text-muted-foreground">{task.phaseName}</span>}
                    </div>
                    {(task.rounds && task.rounds.length > 0 ? task.rounds : [{
                      recordId: task.recordId,
                      roundNo: task.roundNo ?? 1,
                      status: task.recordStatus,
                      remediationReason: task.remediationReason,
                      effective: true,
                      attempts: task.attempts,
                    }]).map(round => (
                      <div key={round.recordId} className={`mt-2 rounded-lg border px-2 py-1.5 ${
                        round.effective ? 'border-primary/40 bg-surface' : 'border-border bg-surface/50'}`}>
                        <div className="flex flex-wrap items-center gap-2 text-xs">
                          <span className="font-medium text-foreground">第 {round.roundNo} 轮</span>
                          <span className={`inline-flex rounded-full border px-2 py-0.5 ${statusCls(round.status)}`}>
                            {RECORD_STATUS_LABELS[round.status] || round.status}
                          </span>
                          {round.effective && (
                            <span className="rounded bg-primary-light px-2 py-0.5 text-primary">当前有效</span>
                          )}
                          {round.remediationReason && (
                            <span className="text-muted-foreground">补训原因：{round.remediationReason}</span>
                          )}
                          {active && round.effective && round.status === 'failed'
                            && active.status !== 'certified' && active.status !== 'cancelled' && (
                            <button onClick={() => {
                              setRemediateTarget({ row: active, round, title: task.title });
                              setRemediateReason('');
                              setRemediateDeadline('');
                            }}
                              className="rounded-full bg-primary px-3 py-0.5 text-[11px] text-white hover:opacity-90">
                              安排补训
                            </button>
                          )}
                          {active && round.effective && round.status === 'passed'
                            && task.type !== 'reading'
                            && active.status !== 'certified' && active.status !== 'cancelled' && (
                            <button onClick={() => {
                              setReopenTarget({ row: active, round, title: task.title });
                              setReopenReason('');
                              setReopenDeadline('');
                            }}
                              className="rounded-full border border-amber-300 bg-amber-50 px-3 py-0.5 text-[11px] text-amber-700 hover:bg-amber-100">
                              判定误判，要求重做
                            </button>
                          )}
                          {active && round.effective
                            && round.status !== 'passed' && round.status !== 'waived'
                            && active.status !== 'certified' && active.status !== 'cancelled' && (
                            <button onClick={() => {
                              setWaiveTarget({ row: active, round, title: task.title });
                              setWaiveReason('');
                            }}
                              className="rounded-full border border-border px-3 py-0.5 text-[11px] text-foreground hover:bg-primary-light">
                              免修
                            </button>
                          )}
                        </div>
                        {round.attempts.length === 0 ? (
                          <div className="mt-1 text-xs text-muted-foreground">暂无尝试</div>
                        ) : (
                          <div className="mt-1 space-y-1">
                            {round.attempts.map(attempt => (
                              <AttemptRowView key={attempt.id} attempt={attempt}
                                onClick={() => openAttempt(attempt.id)} />
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            )}

            {/* 认证历史 */}
            {attempts && attempts.certificationHistory.length > 0 && (
              <div className="mt-4 rounded-xl border border-border bg-surface-2 p-4">
                <div className="mb-2 text-sm font-semibold text-foreground">认证历史</div>
                <div className="space-y-1">
                  {attempts.certificationHistory.map(c => (
                    <div key={c.id} className="rounded bg-surface px-2 py-1.5 text-xs">
                      <span className={`mr-2 inline-flex rounded-full border px-2 py-0.5 ${
                        c.status === 'confirmed' ? 'border-green-200 bg-green-50 text-green-700'
                          : 'border-red-200 bg-red-50 text-red-700'}`}>
                        {c.status === 'confirmed' ? '确认' : c.status === 'revoked' ? '已撤销' : c.status}
                      </span>
                      {c.status === 'confirmed'
                        ? `确认于 ${new Date(c.confirmedAt || c.createdAt).toLocaleString()}${c.confirmedByName ? ` · ${c.confirmedByName}` : ''}`
                        : `撤销于 ${new Date(c.revokedAt || c.createdAt).toLocaleString()}${c.revokedByName ? ` · ${c.revokedByName}` : ''}：${c.revokeReason || ''}`}
                      {c.note && <span className="ml-2 text-muted-foreground">备注：{c.note}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 尝试详情 */}
            {attemptDetailLoading && <div className="mt-4"><LoadingSpinner /></div>}
            {attemptDetailError && (
              <div className="mt-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                尝试详情加载失败：{attemptDetailError}
              </div>
            )}
            {activeAttempt && (
              <div id="attempt-detail" className="mt-5 rounded-xl border border-border bg-surface-2 p-4">
                <div className="mb-3 flex items-start justify-between">
                  <div>
                    <div className="text-sm font-semibold text-foreground">
                      第 {activeAttempt.attempt.attemptNo} 次尝试 · {activeAttempt.task.title}
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                      <span className={`inline-flex rounded-full border px-2 py-0.5 ${statusCls(activeAttempt.attempt.status)}`}>
                        {ATTEMPT_STATUS_LABELS[activeAttempt.attempt.status] || activeAttempt.attempt.status}
                      </span>
                      {activeAttempt.attempt.terminationReason && (
                        <span className="text-amber-700">
                          {TERMINATION_LABELS[activeAttempt.attempt.terminationReason] || activeAttempt.attempt.terminationReason}
                        </span>
                      )}
                      {activeAttempt.attempt.result && (
                        <span>结论：{activeAttempt.attempt.result === 'pass' ? '通过' : '未通过'}</span>
                      )}
                    </div>
                  </div>
                  <button onClick={() => setActiveAttempt(null)}
                    className="text-xs text-muted-foreground hover:underline">收起</button>
                </div>

                {activeAttempt.attempt.errorMessage && (
                  <div className="mb-3 rounded bg-red-50 px-3 py-2 text-xs text-red-700">
                    {activeAttempt.attempt.errorMessage}
                    {activeAttempt.attempt.status === 'error' && (
                      <button onClick={() => onRegrade(activeAttempt.attempt.id)} disabled={acting}
                        className="ml-3 underline">重新评分</button>
                    )}
                  </div>
                )}

                {/* 评分汇总 */}
                {activeAttempt.attempt.score && (
                  <div className="mb-3 rounded-lg border border-border bg-surface p-3">
                    <div className="flex flex-wrap gap-4 text-sm">
                      <span>总分 <b className="tabular-nums">{activeAttempt.attempt.score.total_score}</b></span>
                      <span>及格线 {activeAttempt.attempt.score.pass_threshold ?? '—'}</span>
                      <span>置信度 {activeAttempt.attempt.score.confidence?.toFixed?.(2) ?? '—'}</span>
                      {activeAttempt.attempt.score.point_coverage != null && (
                        <span>要点覆盖 {(Number(activeAttempt.attempt.score.point_coverage) * 100).toFixed(0)}%</span>
                      )}
                      {activeAttempt.attempt.score.need_review && <span className="text-amber-700">AI 建议复核</span>}
                    </div>
                    {typeof activeAttempt.attempt.score.reason === 'string' && (
                      <div className="mt-2 text-xs text-muted-foreground">{activeAttempt.attempt.score.reason}</div>
                    )}
                    {typeof activeAttempt.attempt.score.improvement === 'string' && activeAttempt.attempt.score.improvement && (
                      <div className="mt-2 rounded border border-primary/30 bg-primary-light/40 px-2 py-1 text-xs leading-5 text-foreground">
                        AI 改进建议：{activeAttempt.attempt.score.improvement}
                      </div>
                    )}
                    {activeAttempt.attempt.score.items?.length ? (
                      <div className="mt-2 space-y-1">
                        {activeAttempt.attempt.score.items.map(item => (
                          <div key={item.itemId} className="rounded bg-surface-2 px-2 py-1 text-xs">
                            <span className="mr-2 font-medium">第 {item.orderNo} 题</span>
                            <span className="tabular-nums">{item.total_score} 分</span>
                            <span className="ml-2 text-muted-foreground">
                              {item.answered ? (item.verdict === 'pass' ? '通过' : '未通过') : '未作答'}
                            </span>
                            {item.hit_points?.length ? (
                              <div className="mt-1 text-green-700">命中：{item.hit_points.join('；')}</div>
                            ) : null}
                            {item.missed_points?.length ? (
                              <div className="text-amber-700">遗漏：{item.missed_points.join('；')}</div>
                            ) : null}
                            {item.forbidden_hits?.length ? (
                              <div className="text-red-600">红线：{item.forbidden_hits.join('；')}</div>
                            ) : null}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </div>
                )}

                {/* 题面与答案（含金标，仅管理侧） */}
                {activeAttempt.attempt.questionSnapshot && (
                  <div className="mb-3 space-y-2">
                    {activeAttempt.attempt.questionSnapshot.items.map(q => {
                      const key = activeAttempt.attempt.questionSnapshot!.answerKey[q.itemId];
                      const answer = activeAttempt.attempt.answers?.[q.itemId];
                      return (
                        <div key={q.itemId} className="rounded-lg border border-border bg-surface p-3 text-xs">
                          <div className="font-medium text-foreground">第 {q.orderNo} 题：{q.question}</div>
                          {q.context && <div className="mt-1 text-muted-foreground">背景：{q.context}</div>}
                          {key && (
                            <div className="mt-2 space-y-0.5 text-muted-foreground">
                              {key.expectedPoints != null && <div>要点：{renderJson(key.expectedPoints)}</div>}
                              {key.forbiddenPoints != null && <div>红线：{renderJson(key.forbiddenPoints)}</div>}
                              {key.scoringRule && <div>口径：{key.scoringRule}</div>}
                              {key.goldBasis && <div>依据：{key.goldBasis}</div>}
                            </div>
                          )}
                          <div className="mt-2 rounded bg-surface-2 px-2 py-1 text-foreground">
                            学员作答：{answer?.trim() ? answer : <span className="text-muted-foreground">未作答</span>}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}

                {/* 陪练对话 */}
                {activeAttempt.messages.length > 0 && (
                  <div className="mb-3 max-h-64 space-y-1 overflow-y-auto rounded-lg border border-border bg-surface p-3">
                    {activeAttempt.messages.map(m => (
                      <div key={m.id} className={`text-xs ${m.role === 'user' ? 'text-foreground' : 'text-muted-foreground'}`}>
                        <b>{m.role === 'user' ? '学员' : (m.roleLabel || '客户')}：</b>{m.content}
                      </div>
                    ))}
                  </div>
                )}

                {/* 复核历史 */}
                {activeAttempt.reviews.length > 0 && (
                  <div className="mb-3 space-y-1">
                    <div className="text-xs font-medium text-foreground">复核历史</div>
                    {activeAttempt.reviews.map(r => (
                      <div key={r.id} className="rounded bg-surface px-2 py-1 text-xs text-muted-foreground">
                        {r.reviewerName || '—'} · {r.action === 'pass' ? '通过' : '退回'} · {r.reason}
                        <span className="ml-2">{new Date(r.createdAt).toLocaleString()}</span>
                      </div>
                    ))}
                  </div>
                )}

                {/* 复核操作 */}
                {(activeAttempt.attempt.status === 'pending_review' || activeAttempt.attempt.status === 'error') && (
                  <div className="rounded-lg border border-border bg-surface p-3">
                    <div className="mb-2 text-xs font-medium text-foreground">人工裁决</div>
                    <div className="mb-2 flex gap-2">
                      <button onClick={() => setReviewAction('pass')}
                        className={`rounded-full px-3 py-1 text-xs ${reviewAction === 'pass'
                          ? 'bg-green-600 text-white' : 'border border-border text-foreground'}`}>通过</button>
                      <button onClick={() => setReviewAction('reject')}
                        className={`rounded-full px-3 py-1 text-xs ${reviewAction === 'reject'
                          ? 'bg-red-600 text-white' : 'border border-border text-foreground'}`}>退回</button>
                    </div>
                    <textarea rows={2} value={reviewReason} maxLength={2000}
                      onChange={e => setReviewReason(e.target.value)}
                      placeholder="复核理由（至少 5 个字，学员可见）"
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-xs text-foreground" />
                    <div className="mt-1 text-right text-[10px] text-muted-foreground">
                      {reviewReason.length}/2000
                    </div>
                    <div className="mt-2 flex justify-end">
                      <button onClick={submitReview} disabled={submittingReview}
                        className="rounded-full bg-primary px-4 py-1.5 text-xs text-white hover:opacity-90 disabled:opacity-50">
                        {submittingReview ? '提交中…' : '提交复核'}
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* 认证确认弹层 */}
      {certifyTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setCertifyTarget(null)}>
          <div className="w-full max-w-[460px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">确认认证</h3>
            <p className="mb-4 text-sm text-muted-foreground">
              确认「{certifyTarget.learnerName || certifyTarget.learnerId}」已完成全部必修并通过认证。
              认证后分配状态变为已认证，学员可见确认人与时间。
            </p>
            <textarea rows={3} value={certifyNote} maxLength={1000}
              onChange={e => setCertifyNote(e.target.value)}
              placeholder="备注（可留空，学员可见）"
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <div className="mt-1 text-right text-[10px] text-muted-foreground">{certifyNote.length}/1000</div>
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setCertifyTarget(null)}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">
                取消
              </button>
              <button onClick={submitCertify} disabled={acting}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '提交中…' : '确认认证'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 延期弹层 */}
      {extendTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setExtendTarget(null)}>
          <div className="w-full max-w-[460px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">延长截止时间</h3>
            <p className="mb-4 text-sm text-muted-foreground">
              学员「{extendTarget.learnerName || extendTarget.learnerId}」当前截止：
              {extendTarget.deadline ? new Date(extendTarget.deadline).toLocaleString() : '未设置'}。
              只能向后延长；进行中的考试会同步恢复被分配截止截断的时间。
            </p>
            <input type="datetime-local" value={extendDeadline}
              onChange={e => setExtendDeadline(e.target.value)}
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <textarea rows={2} value={extendReason} maxLength={1000}
              onChange={e => setExtendReason(e.target.value)}
              placeholder="延期理由（必填）"
              className="mt-2 w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setExtendTarget(null)}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">取消</button>
              <button onClick={submitExtend} disabled={acting}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '提交中…' : '确认延期'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 撤销认证弹层 */}
      {revokeTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setRevokeTarget(null)}>
          <div className="w-full max-w-[460px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">撤销认证</h3>
            <p className="mb-4 text-sm text-muted-foreground">
              撤销后「{revokeTarget.learnerName || revokeTarget.learnerId}」回到待认证，原确认记录保留在认证历史中。
            </p>
            <textarea rows={3} value={revokeReason} maxLength={1000}
              onChange={e => setRevokeReason(e.target.value)}
              placeholder="撤销理由（至少 5 个字，学员可见）"
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <div className="mt-1 text-right text-[10px] text-muted-foreground">{revokeReason.length}/1000</div>
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setRevokeTarget(null)}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">取消</button>
              <button onClick={submitRevoke} disabled={acting}
                className="rounded-full bg-amber-600 px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '提交中…' : '确认撤销'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 补训弹层 */}
      {remediateTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setRemediateTarget(null)}>
          <div className="w-full max-w-[460px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">安排补训</h3>
            <p className="mb-4 text-sm text-muted-foreground">
              为「{remediateTarget.row.learnerName || remediateTarget.row.learnerId}」的任务
              {remediateTarget.title ? `「${remediateTarget.title}」` : ''}追加第
              {(remediateTarget.round.roundNo || 1) + 1} 轮补训；原失败记录保留。
              {remediateTarget.row.overdue && (
                <span className="ml-1 font-medium text-red-600">该分配已逾期，必须填写新截止。</span>
              )}
            </p>
            <textarea rows={3} value={remediateReason} maxLength={1000}
              onChange={e => setRemediateReason(e.target.value)}
              placeholder="补训原因（至少 2 个字，学员可见）"
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <label className="mt-3 mb-1 block text-xs text-muted-foreground">
              新截止（分配已过期时必填；未过期可留空）
            </label>
            <input type="datetime-local" value={remediateDeadline}
              onChange={e => setRemediateDeadline(e.target.value)}
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setRemediateTarget(null)}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">取消</button>
              <button onClick={submitRemediate} disabled={acting}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '提交中…' : '确认补训'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 误判重做弹层 */}
      {reopenTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setReopenTarget(null)}>
          <div className="w-full max-w-[460px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">判定误判，要求重做</h3>
            <p className="mb-4 text-sm text-muted-foreground">
              为「{reopenTarget.row.learnerName || reopenTarget.row.learnerId}」的任务
              {reopenTarget.title ? `「${reopenTarget.title}」` : ''}追加第
              {(reopenTarget.round.roundNo || 1) + 1} 轮重做。原通过结论保留为历史证据，
              但认证门槛按最新一轮重算；已认证的学员需先撤销认证。
              {reopenTarget.row.overdue && (
                <span className="ml-1 font-medium text-red-600">该分配已逾期，必须填写新截止。</span>
              )}
            </p>
            <textarea rows={3} value={reopenReason} maxLength={1000}
              onChange={e => setReopenReason(e.target.value)}
              placeholder="重做原因（至少 5 个字，需说明误判依据，学员可见）"
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <label className="mt-3 mb-1 block text-xs text-muted-foreground">
              新截止（分配已过期时必填；未过期可留空）
            </label>
            <input type="datetime-local" value={reopenDeadline}
              onChange={e => setReopenDeadline(e.target.value)}
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setReopenTarget(null)}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">取消</button>
              <button onClick={submitReopen} disabled={acting}
                className="rounded-full bg-amber-600 px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '提交中…' : '确认重做'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 免修弹层 */}
      {waiveTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setWaiveTarget(null)}>
          <div className="w-full max-w-[460px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">免修 / 豁免</h3>
            <p className="mb-4 text-sm text-muted-foreground">
              将「{waiveTarget.row.learnerName || waiveTarget.row.learnerId}」的任务
              {waiveTarget.title ? `「${waiveTarget.title}」` : ''}标记为免修：
              计入认证满足，但不计入通过数；已认证的学员需先撤销认证。
            </p>
            <textarea rows={3} value={waiveReason} maxLength={1000}
              onChange={e => setWaiveReason(e.target.value)}
              placeholder="免修原因（至少 2 个字，学员可见）"
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setWaiveTarget(null)}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">取消</button>
              <button onClick={submitWaive} disabled={acting}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '提交中…' : '确认免修'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 批次延期弹层 */}
      {cohortExtendOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setCohortExtendOpen(false)}>
          <div className="w-full max-w-[460px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">批次统一延期</h3>
            <p className="mb-4 text-sm text-muted-foreground">
              对批次内未结束的学员逐个延长截止；已认证/已取消的学员自动跳过，
              新截止不晚于个人当前截止的学员也会跳过并返回原因。
            </p>
            <input type="datetime-local" value={cohortExtendDeadline}
              onChange={e => setCohortExtendDeadline(e.target.value)}
              className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <textarea rows={2} value={cohortExtendReason} maxLength={1000}
              onChange={e => setCohortExtendReason(e.target.value)}
              placeholder="延期理由（必填）"
              className="mt-2 w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setCohortExtendOpen(false)}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">取消</button>
              <button onClick={submitCohortExtend} disabled={acting}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {acting ? '提交中…' : '确认延期'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function AttemptRowView({ attempt, onClick }: { attempt: AttemptRow; onClick: () => void }) {
  return (
    <button onClick={onClick}
      className="flex w-full items-center gap-2 rounded border border-border bg-surface px-2 py-1.5 text-left text-xs hover:bg-primary-light/50">
      <span className="tabular-nums text-muted-foreground">#{attempt.attemptNo}</span>
      <span className={`inline-flex rounded-full border px-2 py-0.5 ${statusCls(attempt.status)}`}>
        {ATTEMPT_STATUS_LABELS[attempt.status] || attempt.status}
      </span>
      {attempt.needsReview && (
        <span className="rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-amber-700">待裁决</span>
      )}
      {attempt.terminationReason && (
        <span className="text-amber-700">
          {TERMINATION_LABELS[attempt.terminationReason] || attempt.terminationReason}
        </span>
      )}
      {attempt.totalScore != null && <span className="tabular-nums">{attempt.totalScore} 分</span>}
      {attempt.result && <span>{attempt.result === 'pass' ? '通过' : '未通过'}</span>}
      <span className="ml-auto text-muted-foreground">
        {attempt.submittedAt ? new Date(attempt.submittedAt).toLocaleString() : ''}
      </span>
    </button>
  );
}

function renderJson(value: unknown): string {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
