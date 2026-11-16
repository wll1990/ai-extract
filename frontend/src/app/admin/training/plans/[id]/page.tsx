'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getPlan, updatePlan, createVersion, addTask, updateTask, deleteTask, reorderTasks,
  previewPlan, publishPlan, retirePlan, listCohorts, createCohort,
  updatePhases, searchPicker, checkPublishReadiness, previewResource, previewTask, diffPlanVersions,
  previewDrill, repairPlanSnapshots,
  PLAN_STATUS_LABELS, COHORT_STATUS_LABELS, TASK_TYPE_LABELS, statusCls, parsePrerequisites,
  type TrainingPlanDetail, type TrainingTask, type TrainingPhase, type TrainingCohort,
  type TrainingTaskRequest, type PlanPreview, type PublishBlocker, type TaskResourcePreview,
  type PlanVersionDiff,
} from '@/lib/api/training';
import { listDatasets, type EvaluationDataset } from '@/lib/api/evaluation';
import type { PickerItem } from '@/lib/api/training';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';

interface TaskFormState {
  id?: string;
  type: string;
  phaseNo: string;
  phaseName: string;
  title: string;
  description: string;
  isRequired: boolean;
  orderNo: string;
  prerequisites: string[];
  resourceType: string;
  resourceId: string;
  sceneTag: string;
  allowHints: boolean;
  examDatasetId: string;
  examDatasetVersion: string;
  examItemCount: string;
  examDurationMinutes: string;
  minScore: string;
  minPointCoverage: string;
  maxAttempts: string;
  deadlinePolicy: string;
  /** 微练场景卡 JSON（AI 生成后原样提交） */
  drillScenario: string;
}

const EMPTY_TASK: TaskFormState = {
  type: 'reading', phaseNo: '1', phaseName: '', title: '', description: '',
  isRequired: true, orderNo: '', prerequisites: [], resourceType: 'knowledge', resourceId: '',
  sceneTag: '', allowHints: false, examDatasetId: '', examDatasetVersion: '', examItemCount: '',
  examDurationMinutes: '', minScore: '80', minPointCoverage: '', maxAttempts: '1',
  deadlinePolicy: 'auto_submit', drillScenario: '',
};

interface UserOption { id: string; name: string; account: string; role: string }

export default function AdminTrainingPlanEditorPage() {
  const router = useRouter();
  const params = useParams();
  const planId = String(params.id);

  const [detail, setDetail] = useState<TrainingPlanDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [acting, setActing] = useState(false);

  // 基本信息编辑
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  // 任务表单
  const [taskForm, setTaskForm] = useState<TaskFormState | null>(null);
  const [savingTask, setSavingTask] = useState(false);
  const [datasets, setDatasets] = useState<EvaluationDataset[]>([]);
  const [resourceOptions, setResourceOptions] = useState<PickerItem[]>([]);
  const [resourceKeyword, setResourceKeyword] = useState('');
  const [resourcesLoaded, setResourcesLoaded] = useState(false);
  const [resourceLoading, setResourceLoading] = useState(false);
  const [resourceError, setResourceError] = useState('');
  const [resourceReloadKey, setResourceReloadKey] = useState(0);
  // 选中/已绑定资源的预览卡
  const [resourcePreview, setResourcePreview] = useState<TaskResourcePreview | null>(null);
  const [resourcePreviewLoading, setResourcePreviewLoading] = useState(false);
  const [resourceExpanded, setResourceExpanded] = useState(false);
  const [taskSnapshot, setTaskSnapshot] = useState('');
  const [drillGenerating, setDrillGenerating] = useState(false);

  // 预览
  const [preview, setPreview] = useState<PlanPreview | null>(null);
  const [contentPreview, setContentPreview] = useState<{
    title: string; resource?: TaskResourcePreview; loading: boolean;
  } | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  // 发布预检
  const [blockers, setBlockers] = useState<PublishBlocker[] | null>(null);
  const [checkingPublish, setCheckingPublish] = useState(false);

  // 版本对比
  const [diffOpen, setDiffOpen] = useState(false);
  const [diffFrom, setDiffFrom] = useState('');
  const [diffTo, setDiffTo] = useState('');
  const [diffData, setDiffData] = useState<PlanVersionDiff | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState('');

  // 批次
  const [cohorts, setCohorts] = useState<TrainingCohort[]>([]);
  const [showCohortForm, setShowCohortForm] = useState(false);
  const [cohortName, setCohortName] = useState('');
  const [cohortVersion, setCohortVersion] = useState('');
  const [cohortMentorId, setCohortMentorId] = useState('');
  const [cohortDeadline, setCohortDeadline] = useState('');
  const [users, setUsers] = useState<UserOption[]>([]);
  const [mentorKeyword, setMentorKeyword] = useState('');
  const [creatingCohort, setCreatingCohort] = useState(false);
  const [phaseEditing, setPhaseEditing] = useState<{ phaseNo: number; name: string } | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    getPlan(planId)
      .then(d => {
        setDetail(d);
        setName(d.name);
        setDescription(d.description || '');
      })
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false));
    listCohorts({ planId, size: 50 })
      .then(d => setCohorts(d.content || []))
      .catch(() => setCohorts([]));
  }, [planId]);

  useEffect(() => { load(); }, [load]);

  // 资源选择器：按类型走统一 picker，输入防抖 300ms
  const formType = taskForm?.type;
  const formResourceType = taskForm?.resourceType;
  useEffect(() => {
    if (!formType) {
      setResourceOptions([]);
      setResourceError('');
      return;
    }
    const kind = formType === 'practice' ? 'skills'
      : formType === 'exam' ? null
      : formResourceType === 'case' ? 'cases' : 'knowledge';
    if (!kind) {
      setResourceOptions([]);
      setResourceError('');
      return;
    }
    let cancelled = false;
    // 切换资源类型先清空旧结果，避免上一类资源短暂显示
    setResourceOptions([]);
    setResourceError('');
    setResourceLoading(true);
    const timer = setTimeout(() => {
      searchPicker(kind, { keyword: resourceKeyword || undefined, size: 30 })
        .then(data => { if (!cancelled) setResourceOptions(data.content || []); })
        .catch(e => {
          if (!cancelled) {
            setResourceOptions([]);
            setResourceError(e instanceof Error ? e.message : '资源搜索失败');
          }
        })
        .finally(() => { if (!cancelled) setResourceLoading(false); });
    }, 300);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [formType, formResourceType, resourceKeyword, resourceReloadKey]);

  // 选中资源后拉预览：知识正文 / 案例字段 / 分身开场白
  useEffect(() => {
    if (!taskForm) {
      setResourcePreview(null);
      return;
    }
    const resourceType = taskForm.type === 'practice' ? 'skill'
      : (taskForm.type === 'reading' || taskForm.type === 'drill') ? taskForm.resourceType : null;
    if (!resourceType || !taskForm.resourceId) {
      setResourcePreview(null);
      return;
    }
    let cancelled = false;
    setResourcePreviewLoading(true);
    previewResource(planId, resourceType, taskForm.resourceId)
      .then(r => { if (!cancelled) setResourcePreview(r); })
      .catch(() => { if (!cancelled) setResourcePreview(null); })
      .finally(() => { if (!cancelled) setResourcePreviewLoading(false); });
    return () => { cancelled = true; };
  }, [planId, taskForm?.resourceId, taskForm?.resourceType, taskForm?.type]);

  const currentVersion = useMemo(
    () => detail?.versions.find(v => v.version === detail.currentVersion) || null,
    [detail]);
  // 退役计划整体只读：退役后再 publish/改任务会把状态复活，后端也已拒绝
  const editable = currentVersion?.status === 'draft' && detail?.status !== 'retired';
  const publishedVersions = useMemo(
    () => (detail?.versions || []).filter(v => v.status === 'published'),
    [detail]);
  const allTasks = useMemo(() => detail?.tasks || [], [detail]);

  const run = async (fn: () => Promise<unknown>, successMsg?: string) => {
    setActing(true);
    try {
      await fn();
      if (successMsg) setToast(successMsg);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const repairSnapshots = () => {
    if (!detail) return;
    const version = detail.currentVersion;
    if (!window.confirm(`重建 v${version} 的阅读任务内容快照？微练/陪练/考试会跳过。`)) return;
    run(async () => {
      const result = await repairPlanSnapshots(detail.id, version);
      setToast(`快照重建完成：成功 ${result.repaired}，跳过 ${result.skipped}，失败 ${result.failed}`);
    });
  };

  const openTaskForm = async (task?: TrainingTask) => {
    if (!resourcesLoaded) {
      try {
        // 服务端单页上限 50；已绑定但不在首批的题集会在下拉里补一条「当前已选」
        const data = await listDatasets({ status: 'published', size: 50 });
        setDatasets(data.content || []);
        setResourcesLoaded(true);
      } catch {
        setToast('题集列表加载失败，可先填其他字段');
      }
    }
    if (task) {
      const next: TaskFormState = {
        id: task.id,
        type: task.type,
        phaseNo: String(task.phaseNo),
        phaseName: task.phaseName || '',
        title: task.title,
        description: task.description || '',
        isRequired: task.isRequired,
        orderNo: String(task.orderNo),
        prerequisites: parsePrerequisites(task.prerequisites),
        resourceType: task.resourceType || (task.type === 'practice' ? 'skill' : 'knowledge'),
        resourceId: task.resourceId || '',
        sceneTag: task.sceneTag || '',
        allowHints: task.allowHints,
        examDatasetId: task.examDatasetId || '',
        examDatasetVersion: task.examDatasetVersion ? String(task.examDatasetVersion) : '',
        examItemCount: task.examItemCount ? String(task.examItemCount) : '',
        examDurationMinutes: task.examDurationMinutes ? String(task.examDurationMinutes) : '',
        minScore: String(task.passCriteria?.minScore ?? 80),
        minPointCoverage: task.passCriteria?.minPointCoverage != null
          ? String(task.passCriteria.minPointCoverage) : '',
        maxAttempts: String(task.maxAttempts),
        deadlinePolicy: task.deadlinePolicy || 'auto_submit',
        drillScenario: (task as unknown as { drillScenario?: unknown }).drillScenario
          ? JSON.stringify((task as unknown as { drillScenario?: unknown }).drillScenario) : '',
      };
      setTaskForm(next);
      setTaskSnapshot(JSON.stringify(next));
      setResourceExpanded(false);
    } else {
      // 默认沿用最后一个阶段：同一阶段加多个任务不用每次手改阶段号
      const lastPhaseNo = allTasks.length === 0
        ? 1 : Math.max(...allTasks.map(t => t.phaseNo));
      const lastPhase = allTasks.find(t => t.phaseNo === lastPhaseNo);
      const next: TaskFormState = {
        ...EMPTY_TASK,
        phaseNo: String(lastPhaseNo),
        phaseName: lastPhase?.phaseName || '',
      };
      setTaskForm(next);
      setTaskSnapshot(JSON.stringify(next));
      setResourceExpanded(false);
    }
  };

  const onGenerateDrill = async () => {
    if (!taskForm) return;
    if (!taskForm.resourceId) { setToast('请先选择微练依据的知识/案例/分身'); return; }
    setDrillGenerating(true);
    try {
      const result = await previewDrill({
        resourceType: taskForm.resourceType,
        resourceId: taskForm.resourceId,
        sceneTag: taskForm.sceneTag.trim() || undefined,
        questionCount: 2,
      });
      setTaskForm(prev => prev ? { ...prev, drillScenario: result.scenario } : prev);
      setToast('练习内容已生成，可保存任务');
    } catch (e) {
      setToast(e instanceof Error ? e.message : '生成失败');
    } finally {
      setDrillGenerating(false);
    }
  };

  const saveTask = async () => {
    if (!taskForm) return;
    if (!taskForm.title.trim()) { setToast('任务标题不能为空'); return; }
    const phaseNo = Number(taskForm.phaseNo);
    if (!Number.isInteger(phaseNo) || phaseNo < 1) { setToast('阶段序号从 1 开始'); return; }
    if (taskForm.type === 'reading' && (!taskForm.resourceType || !taskForm.resourceId)) {
      setToast('阅读任务必须选择知识或案例资源'); return;
    }
    if (taskForm.type === 'practice' && !taskForm.resourceId) {
      setToast('陪练任务必须选择分身'); return;
    }
    if (taskForm.type === 'exam') {
      if (!taskForm.examDatasetId || !taskForm.examDatasetVersion) { setToast('考试任务必须选择已发布题集版本'); return; }
      if (!taskForm.examDurationMinutes) { setToast('考试任务必须设置时长'); return; }
    }
    if (taskForm.type === 'drill') {
      if (!taskForm.resourceId) { setToast('微练必须选择依据的知识/案例/分身'); return; }
      if (!taskForm.drillScenario.trim()) { setToast('微练必须先点击「生成练习内容」'); return; }
      try {
        const card = JSON.parse(taskForm.drillScenario);
        if (!card?.scenario || !Array.isArray(card?.questions) || card.questions.length === 0) {
          setToast('微练内容结构不完整，请重新生成'); return;
        }
      } catch {
        setToast('微练内容不是合法 JSON，请重新生成'); return;
      }
    }

    const payload: TrainingTaskRequest = {
      type: taskForm.type,
      phaseNo,
      phaseName: taskForm.phaseName.trim() || undefined,
      title: taskForm.title.trim(),
      description: taskForm.description.trim() || undefined,
      isRequired: taskForm.isRequired,
      orderNo: taskForm.orderNo ? Number(taskForm.orderNo) : undefined,
      prerequisites: taskForm.prerequisites,
      resourceType: taskForm.type === 'exam' ? undefined
        : (taskForm.type === 'practice' ? 'skill' : taskForm.resourceType),
      resourceId: taskForm.type === 'exam' ? undefined : (taskForm.resourceId || undefined),
      sceneTag: taskForm.type === 'exam' ? undefined : (taskForm.sceneTag.trim() || undefined),
      allowHints: taskForm.type === 'exam' || taskForm.type === 'drill' ? false : taskForm.allowHints,
      drillScenario: taskForm.type === 'drill' ? taskForm.drillScenario : undefined,
      examDatasetId: taskForm.type === 'exam' ? taskForm.examDatasetId : undefined,
      examDatasetVersion: taskForm.type === 'exam' ? Number(taskForm.examDatasetVersion) : undefined,
      examItemCount: taskForm.type === 'exam' && taskForm.examItemCount
        ? Number(taskForm.examItemCount) : undefined,
      examDurationMinutes: taskForm.type === 'exam' ? Number(taskForm.examDurationMinutes) : undefined,
      // 陪练没有金标要点，后端会直接拒绝 minPointCoverage；考试/微练才有要点覆盖口径
      passCriteria: taskForm.type === 'reading' ? undefined : JSON.stringify({
        minScore: Number(taskForm.minScore || (taskForm.type === 'drill' ? 60 : 80)),
        ...((taskForm.type === 'exam' || taskForm.type === 'drill') && taskForm.minPointCoverage
          ? { minPointCoverage: Number(taskForm.minPointCoverage) } : {}),
      }),
      maxAttempts: Number(taskForm.maxAttempts || 1),
      deadlinePolicy: taskForm.type === 'reading' ? undefined : taskForm.deadlinePolicy,
    };

    setSavingTask(true);
    try {
      if (taskForm.id) {
        await updateTask(planId, taskForm.id, payload);
        setToast('任务已更新');
      } else {
        await addTask(planId, payload);
        setToast('任务已添加');
      }
      setTaskForm(null);
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingTask(false);
    }
  };

  /** 关闭任务弹层：与打开时快照比较，所有字段都算改动 */
  const requestCloseTaskForm = () => {
    if (!taskForm) return;
    if (taskSnapshot && JSON.stringify(taskForm) === taskSnapshot) {
      setTaskForm(null);
      return;
    }
    if (window.confirm('关闭将丢弃未保存的修改，确定？')) setTaskForm(null);
  };

  const openPreview = async (version?: number) => {
    setPreviewLoading(true);
    try {
      setPreview(await previewPlan(planId, version));
    } catch (e) {
      setToast(e instanceof Error ? e.message : '预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  /** 任务内容查看：直接看学员会读到的知识正文/案例字段，不用进编辑 */
  const showTaskContent = async (task: TrainingTask) => {
    setContentPreview({ title: task.title, loading: true });
    try {
      const previewTaskView = await previewTask(planId, task.id);
      setContentPreview({ title: task.title, resource: previewTaskView.resource, loading: false });
    } catch (e) {
      setContentPreview(null);
      setToast(e instanceof Error ? e.message : '内容加载失败');
    }
  };

  /** 版本对比：默认最近两个版本，可切换 */
  const openVersionDiff = async (from?: string, to?: string) => {
    const versions = detail?.versions || [];
    if (versions.length < 2) return;
    const f = from || String(versions[1].version);
    const t = to || String(versions[0].version);
    setDiffOpen(true);
    setDiffFrom(f);
    setDiffTo(t);
    if (f === t) {
      setDiffError('请选择两个不同版本');
      setDiffData(null);
      return;
    }
    setDiffLoading(true);
    setDiffError('');
    try {
      setDiffData(await diffPlanVersions(planId, Number(f), Number(t)));
    } catch (e) {
      setDiffData(null);
      setDiffError(e instanceof Error ? e.message : '对比失败');
    } finally {
      setDiffLoading(false);
    }
  };

  /** 检查发布 → 列出全部 blocker；无阻塞再二次确认，避免「检查即发布」 */
  const runPublishCheck = async () => {
    setCheckingPublish(true);
    try {
      const result = await checkPublishReadiness(planId);
      setBlockers(result.blockers);
      if (result.ok) {
        if (window.confirm('检查通过，确认发布？发布后本版本冻结，不能再改任务。')) {
          await run(() => publishPlan(planId), '版本已发布');
          setBlockers(null);
        }
      }
    } catch (e) {
      setToast(e instanceof Error ? e.message : '发布检查失败');
    } finally {
      setCheckingPublish(false);
    }
  };

  /** 阶段内上移/下移：提交完整 ID 序列，服务端按阶段重新编号 */
  const moveTask = async (phase: TrainingPhase, index: number, delta: -1 | 1) => {
    const target = index + delta;
    if (target < 0 || target >= phase.tasks.length) return;
    const ordered = [...phase.tasks];
    [ordered[index], ordered[target]] = [ordered[target], ordered[index]];
    const byPhase = new Map<number, TrainingTask[]>();
    for (const t of allTasks) {
      byPhase.set(t.phaseNo, [...(byPhase.get(t.phaseNo) || []), t]);
    }
    byPhase.set(phase.phaseNo, ordered);
    const taskIds = [...byPhase.entries()]
      .sort((a, b) => a[0] - b[0])
      .flatMap(([, list]) => list.map(t => t.id));
    await run(() => reorderTasks(planId, taskIds), '顺序已保存');
  };

  const loadUsers = async (keyword = '') => {
    try {
      const data = await searchPicker('users', { keyword: keyword || undefined, size: 30 });
      setUsers((data.content || []).map(u => ({
        id: u.id, name: u.name, account: u.subtitle || '', role: u.status,
      })));
    } catch {
      setUsers([]);
    }
  };

  /** 阶段改名/重排：提交顺序即新顺序，后端重编 phase_no */
  const movePhase = async (index: number, delta: -1 | 1) => {
    if (!detail) return;
    const target = index + delta;
    if (target < 0 || target >= detail.phases.length) return;
    const ordered = [...detail.phases];
    [ordered[index], ordered[target]] = [ordered[target], ordered[index]];
    await run(() => updatePhases(planId, ordered.map(p => ({
      phaseNo: p.phaseNo,
      phaseName: p.phaseName || '',
    }))), '阶段顺序已保存');
  };

  const savePhaseName = async () => {
    if (!phaseEditing || !detail) return;
    // 后端会忽略空阶段名（保留原值），前端必须先拦住，否则提示「已保存」但界面不变
    if (!phaseEditing.name.trim()) { setToast('阶段名不能为空'); return; }
    await run(() => updatePhases(planId, detail.phases.map(p => ({
      phaseNo: p.phaseNo,
      phaseName: p.phaseNo === phaseEditing.phaseNo ? phaseEditing.name.trim() : (p.phaseName || ''),
    }))), '阶段名已保存');
    setPhaseEditing(null);
  };

  const submitCohort = async () => {
    if (!cohortName.trim()) { setToast('批次名不能为空'); return; }
    if (!cohortVersion) { setToast('请选择已发布版本'); return; }
    setCreatingCohort(true);
    try {
      await createCohort({
        planId,
        planVersion: Number(cohortVersion),
        name: cohortName.trim(),
        mentorId: cohortMentorId || undefined,
        deadline: cohortDeadline ? cohortDeadline : undefined,
      });
      setToast('批次已创建');
      setShowCohortForm(false);
      setCohortName(''); setCohortVersion(''); setCohortMentorId(''); setCohortDeadline('');
      load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '创建批次失败');
    } finally {
      setCreatingCohort(false);
    }
  };

  if (loading && !detail) return <div className="p-8"><LoadingSpinner /></div>;
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
  if (!detail) return null;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[1080px]">
        {/* 头部 */}
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div>
            <button onClick={() => router.push('/admin/training/plans')}
              className="mb-2 text-xs text-muted-foreground hover:underline">← 返回计划列表</button>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">{detail.name}</h1>
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(detail.status)}`}>
                {PLAN_STATUS_LABELS[detail.status] || detail.status}
              </span>
              <span>当前版本 v{detail.currentVersion}</span>
              <span>· {editable ? '草稿（可编辑）' : '已发布（只读）'}</span>
              {detail.generatedByAi && (
                <span className="rounded-full border border-primary/40 bg-primary-light px-2 py-0.5 text-xs text-primary">
                  AI 生成
                </span>
              )}
            </div>
            {detail.goal && (
              <p className="mt-2 max-w-[720px] text-xs text-muted-foreground">
                训练目标：{detail.goal}
              </p>
            )}
            {detail.generationNote && (
              <p className="mt-1 max-w-[720px] text-xs text-muted-foreground">
                生成依据：{detail.generationNote}
              </p>
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            <button onClick={() => openPreview()} disabled={previewLoading}
              className="rounded-full border border-border px-4 py-2.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">
              {previewLoading ? '预览加载中…' : '学员视角预览'}
            </button>
            {editable && (
              <button disabled={acting || checkingPublish}
                onClick={runPublishCheck}
                className="rounded-full bg-primary px-4 py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {checkingPublish ? '检查中…' : '检查发布'}
              </button>
            )}
            {detail.versions.length >= 2 && (
              <button onClick={() => openVersionDiff()}
                className="rounded-full border border-border px-4 py-2.5 text-sm text-foreground hover:bg-primary-light">
                版本对比
              </button>
            )}
            {currentVersion?.status === 'published' && detail.status !== 'retired' && (
              <button disabled={acting}
                onClick={() => { if (window.confirm('从最新已发布版本复制出新草稿版本？')) run(() => createVersion(planId), '已创建新草稿版本'); }}
                className="rounded-full border border-border px-4 py-2.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">
                新建版本
              </button>
            )}
            {detail.status === 'published' && (
              <button disabled={acting}
                onClick={repairSnapshots}
                title="重建阅读任务冻结正文，修复快照缺失/损坏"
                className="rounded-full border border-border px-4 py-2.5 text-sm text-foreground hover:bg-primary-light disabled:opacity-50">
                重建内容快照
              </button>
            )}
            {detail.status === 'published' && (
              <button disabled={acting}
                onClick={() => { if (window.confirm('退役后不能再建新批次，已有学习不受影响。继续？')) run(() => retirePlan(planId), '计划已退役'); }}
                className="rounded-full border border-red-200 px-4 py-2.5 text-sm text-red-600 hover:bg-red-50 disabled:opacity-50">
                退役
              </button>
            )}
          </div>
        </div>

        {/* 资源失效提醒 */}
        {(detail.resourceAlerts?.length ?? 0) > 0 && (
          <section className="mb-6 rounded-xl border border-danger bg-danger-bg px-5 py-4">
            <h2 className="mb-2 text-base font-semibold text-danger">
              资源失效提醒（{detail.resourceAlerts!.length}）
            </h2>
            <ul className="space-y-1 text-sm text-foreground">
              {detail.resourceAlerts!.map(a => {
                const task = detail.tasks.find(t => t.id === a.taskId);
                const typeLabel = a.resourceType === 'knowledge' ? '知识'
                  : a.resourceType === 'case' ? '案例'
                    : a.resourceType === 'exam_dataset' ? '题集' : '分身';
                return (
                  <li key={a.id} className="flex flex-wrap items-center justify-between gap-2">
                    <span>· 任务「{task?.title || '已删除任务'}」引用的{typeLabel}：{a.reason}</span>
                    {task && (
                      <button type="button" disabled={!editable}
                        onClick={() => openTaskForm(task)}
                        title={editable ? '打开任务编辑，替换失效资源' : '当前版本只读，请先新建版本再替换'}
                        className="rounded-full border border-danger px-3 py-0.5 text-xs text-danger hover:bg-white disabled:opacity-50">
                        去替换
                      </button>
                    )}
                  </li>
                );
              })}
            </ul>
            <p className="mt-2 text-xs text-muted-foreground">
              在下方任务里替换资源后，系统会在下一次扫描（约 10 分钟内）自动关闭提醒；学员端打开任务时会看到明确提示。
            </p>
          </section>
        )}

        {/* 发布预检结果：列出全部阻塞项，点击可直达任务 */}
        {blockers && (
          <section className={`mb-6 rounded-xl border px-5 py-4 ${
            blockers.length === 0 ? 'border-green-200 bg-green-50' : 'border-amber-300 bg-amber-50'
          }`}>
            <div className="mb-2 flex items-center justify-between">
              <h2 className={`text-base font-semibold ${blockers.length === 0 ? 'text-green-700' : 'text-amber-800'}`}>
                {blockers.length === 0 ? '✓ 发布检查通过' : `发布检查发现 ${blockers.length} 个阻塞项`}
              </h2>
              <button type="button" onClick={() => setBlockers(null)}
                className="text-xs text-muted-foreground hover:underline">关闭</button>
            </div>
            {blockers.length > 0 && (
              <ul className="space-y-1 text-sm text-foreground">
                {blockers.map((b, i) => {
                  const task = b.taskId ? detail.tasks.find(t => t.id === b.taskId) : undefined;
                  return (
                    <li key={`${b.taskId || 'plan'}-${i}`} className="flex flex-wrap items-center justify-between gap-2">
                      <span>· {b.title ? `任务「${b.title}」：` : ''}{b.reason}</span>
                      {task && (
                        <button type="button" onClick={() => openTaskForm(task)}
                          className="text-xs text-primary hover:underline">去修复</button>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        )}

        {/* 基本信息 */}
        <section className="mb-6 rounded-xl border border-border bg-surface-2 p-5">
          <h2 className="mb-3 text-base font-semibold text-foreground">基本信息</h2>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">计划名</label>
              <input value={name} onChange={e => setName(e.target.value)} disabled={!editable}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground disabled:opacity-60" />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">描述</label>
              <input value={description} onChange={e => setDescription(e.target.value)} disabled={!editable}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground disabled:opacity-60" />
            </div>
          </div>
          {editable && (
            <div className="mt-3 flex justify-end">
              <button disabled={acting || !name.trim()}
                onClick={() => run(() => updatePlan(planId, {
                  name: name.trim(), description: description.trim() || undefined,
                  expectedVersion: detail.currentVersion,
                }), '已保存')}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                保存
              </button>
            </div>
          )}
        </section>

        {/* 版本 */}
        <section className="mb-6 rounded-xl border border-border bg-surface-2 p-5">
          <h2 className="mb-3 text-base font-semibold text-foreground">版本</h2>
          <div className="flex flex-wrap gap-2">
            {detail.versions.map(v => (
              <div key={v.id} className={`rounded-lg border px-3 py-2 text-xs ${
                v.version === detail.currentVersion ? 'border-primary bg-primary-light' : 'border-border'}`}>
                <div className="font-medium text-foreground">v{v.version}</div>
                <div className="mt-0.5 text-muted-foreground">
                  {v.status === 'published' ? `已发布 · ${v.taskCount ?? 0} 个任务` : '草稿'}
                  {v.version === detail.currentVersion ? ' · 当前' : ''}
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* 任务树 */}
        <section className="mb-6 rounded-xl border border-border bg-surface-2 p-5">
          <div className="mb-4 flex items-center justify-between">
            <div>
              <h2 className="text-base font-semibold text-foreground">阶段与任务</h2>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {editable ? '拖不动就点 ↑↓ 调整阶段内顺序；前置任务必须先完成才能解锁' : '当前版本已发布，任务只读'}
              </p>
            </div>
            {editable && (
              <button onClick={() => openTaskForm()}
                className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90">
                ＋ 添加任务
              </button>
            )}
          </div>

          {detail.phases.length === 0 ? (
            <div className="py-10 text-center text-sm text-muted-foreground">还没有任务，先添加一个阅读或考试任务</div>
          ) : detail.phases.map(phase => (
            <div key={phase.phaseNo} className="mb-5 last:mb-0">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span className="rounded bg-surface px-2 py-0.5 font-mono text-xs text-muted-foreground">
                  阶段 {phase.phaseNo}
                </span>
                {phaseEditing?.phaseNo === phase.phaseNo ? (
                  <>
                    <input value={phaseEditing.name} onChange={e => setPhaseEditing({ ...phaseEditing, name: e.target.value })}
                      className="rounded border border-border bg-surface px-2 py-1 text-sm text-foreground" />
                    <button onClick={savePhaseName} disabled={acting}
                      className="rounded-full bg-primary px-3 py-0.5 text-xs text-white disabled:opacity-50">保存</button>
                    <button onClick={() => setPhaseEditing(null)}
                      className="text-xs text-muted-foreground hover:underline">取消</button>
                  </>
                ) : (
                  <>
                    <span className="text-sm font-medium text-foreground">{phase.phaseName || '未命名阶段'}</span>
                    {editable && (
                      <button onClick={() => setPhaseEditing({ phaseNo: phase.phaseNo, name: phase.phaseName || '' })}
                        className="text-xs text-muted-foreground hover:underline">改名</button>
                    )}
                  </>
                )}
                <span className="text-xs text-muted-foreground">· {phase.tasks.length} 个任务</span>
                {editable && (
                  <span className="ml-1 flex gap-1">
                    <button disabled={detail.phases.indexOf(phase) === 0 || acting}
                      onClick={() => movePhase(detail.phases.indexOf(phase), -1)}
                      className="rounded border border-border px-2 text-xs disabled:opacity-30">↑</button>
                    <button disabled={detail.phases.indexOf(phase) === detail.phases.length - 1 || acting}
                      onClick={() => movePhase(detail.phases.indexOf(phase), 1)}
                      className="rounded border border-border px-2 text-xs disabled:opacity-30">↓</button>
                  </span>
                )}
              </div>
              <div className="space-y-2">
                {phase.tasks.map((task, idx) => (
                  <div key={task.id} className="flex items-start gap-3 rounded-lg border border-border bg-surface px-4 py-3">
                    <div className="mt-0.5 flex flex-col gap-0.5">
                      <button disabled={!editable || idx === 0 || acting}
                        onClick={() => moveTask(phase, idx, -1)}
                        className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-20">▲</button>
                      <button disabled={!editable || idx === phase.tasks.length - 1 || acting}
                        onClick={() => moveTask(phase, idx, 1)}
                        className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-20">▼</button>
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="rounded bg-primary-light px-2 py-0.5 text-xs text-primary">
                          {TASK_TYPE_LABELS[task.type] || task.type}
                        </span>
                        <span className="font-medium text-foreground">{task.title}</span>
                        <span className="text-xs text-muted-foreground">
                          {task.isRequired ? '必修' : '选修'} · 最多 {task.maxAttempts} 次
                          {task.type !== 'reading' && ` · 及格 ${task.passCriteria?.minScore ?? 80} 分`}
                          {task.type === 'exam' && task.examDurationMinutes ? ` · ${task.examDurationMinutes} 分钟` : ''}
                          {task.type !== 'reading' ? ` · 逾期${task.deadlinePolicy === 'timeout_fail' ? '转复核' : '自动交卷'}` : ''}
                        </span>
                      </div>
                      {task.description && (
                        <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">{task.description}</div>
                      )}
                      {parsePrerequisites(task.prerequisites).length > 0 && (
                        <div className="mt-1 text-xs text-muted-foreground">
                          前置：
                          {parsePrerequisites(task.prerequisites)
                            .map(id => allTasks.find(t => t.id === id)?.title || id.slice(0, 8))
                            .join('、')}
                        </div>
                      )}
                    </div>
                    <div className="flex gap-1">
                      {task.resourceType && (
                        <button onClick={() => showTaskContent(task)}
                          className="rounded-full border border-primary/40 px-3 py-1 text-xs text-primary hover:bg-primary-light">
                          查看内容
                        </button>
                      )}
                      {editable && (
                        <>
                        <button onClick={() => openTaskForm(task)}
                          className="rounded-full border border-border px-3 py-1 text-xs text-foreground hover:bg-primary-light">
                          编辑
                        </button>
                        <button onClick={() => {
                          if (window.confirm(`删除任务「${task.title}」？被其它任务当作前置时会被拒绝。`)) {
                            run(() => deleteTask(planId, task.id), '任务已删除');
                          }
                        }} className="rounded-full border border-red-200 px-3 py-1 text-xs text-red-600 hover:bg-red-50">
                          删除
                        </button>
                        </>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </section>

        {/* 批次 */}
        <section className="rounded-xl border border-border bg-surface-2 p-5">
          <div className="mb-4 flex items-center justify-between">
            <div>
              <h2 className="text-base font-semibold text-foreground">批次</h2>
              <p className="mt-0.5 text-xs text-muted-foreground">
                批次只能绑定已发布版本；学员分配到的是冻结快照
              </p>
            </div>
            <button
              disabled={publishedVersions.length === 0 || detail.status === 'retired'}
              onClick={() => { setShowCohortForm(s => !s); loadUsers(); }}
              className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light disabled:opacity-40">
              {showCohortForm ? '收起' : '＋ 新建批次'}
            </button>
          </div>

          {detail.status === 'retired' ? (
            <div className="mb-4 rounded-lg bg-slate-100 px-3 py-2 text-xs text-slate-600">
              计划已退役，不再允许新建批次；已有批次与学习记录不受影响
            </div>
          ) : publishedVersions.length === 0 ? (
            <div className="mb-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
              还没有已发布版本，发布后才能创建批次
            </div>
          ) : null}

          {showCohortForm && (
            <div className="mb-4 grid gap-3 rounded-lg border border-border bg-surface p-4 md:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">批次名 *</label>
                <input value={cohortName} onChange={e => setCohortName(e.target.value)}
                  placeholder="如：2026 秋季一期"
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">计划版本 *</label>
                <select value={cohortVersion} onChange={e => setCohortVersion(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                  <option value="">请选择已发布版本</option>
                  {publishedVersions.map(v => <option key={v.id} value={v.version}>v{v.version}</option>)}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">导师 / 复核人（可选）</label>
                <div className="mb-1 flex gap-1">
                  <input value={mentorKeyword} onChange={e => setMentorKeyword(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') loadUsers(mentorKeyword.trim()); }}
                    placeholder="搜索姓名/账号…"
                    className="w-full rounded-md border border-border bg-surface-2 px-2 py-1 text-xs text-foreground" />
                  <button onClick={() => loadUsers(mentorKeyword.trim())}
                    className="rounded border border-border px-2 text-xs hover:bg-primary-light">搜索</button>
                </div>
                <select value={cohortMentorId} onChange={e => setCohortMentorId(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                  <option value="">不指定（管理员复核）</option>
                  {/* 搜索后旧选中项可能不在结果里：保留可见，避免「显示不指定但实际仍绑定」 */}
                  {cohortMentorId && !users.some(u => u.id === cohortMentorId) && (
                    <option value={cohortMentorId}>当前已选（{cohortMentorId.slice(0, 8)}）</option>
                  )}
                  {users.map(u => <option key={u.id} value={u.id}>{u.name}（{u.account}）</option>)}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">截止时间（可选）</label>
                <input type="datetime-local" value={cohortDeadline} onChange={e => setCohortDeadline(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
              </div>
              <div className="flex justify-end md:col-span-2">
                <button onClick={submitCohort} disabled={creatingCohort}
                  className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                  {creatingCohort ? '创建中…' : '创建批次'}
                </button>
              </div>
            </div>
          )}

          {cohorts.length === 0 ? (
            <div className="py-6 text-center text-sm text-muted-foreground">这个计划还没有批次</div>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full min-w-[640px] text-sm">
                <thead className="bg-surface text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-4 py-2.5 font-medium">批次</th>
                    <th className="px-4 py-2.5 font-medium">状态</th>
                    <th className="px-4 py-2.5 font-medium">版本</th>
                    <th className="px-4 py-2.5 font-medium">截止</th>
                    <th className="px-4 py-2.5" />
                  </tr>
                </thead>
                <tbody>
                  {cohorts.map(c => (
                    <tr key={c.id} className="border-t border-border">
                      <td className="px-4 py-2.5 font-medium text-foreground">{c.name}</td>
                      <td className="px-4 py-2.5">
                        <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(c.status)}`}>
                          {COHORT_STATUS_LABELS[c.status] || c.status}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 tabular-nums">v{c.planVersion}</td>
                      <td className="px-4 py-2.5 text-xs text-muted-foreground">
                        {c.deadline ? new Date(c.deadline).toLocaleString() : '未设置'}
                      </td>
                      <td className="px-4 py-2.5 text-right">
                        <button onClick={() => router.push(`/admin/training/cohorts/${c.id}`)}
                          className="rounded-full border border-border px-3 py-1 text-xs hover:bg-primary-light">
                          进度 / 分配
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>

      {/* 任务编辑弹层 */}
      {taskForm && (
        <div className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-black/30 p-6"
          onClick={requestCloseTaskForm}>
          <div className="w-full max-w-[720px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-4 text-lg font-semibold text-foreground">
              {taskForm.id ? '编辑任务' : '添加任务'}
            </h3>
            <div className="grid gap-3 md:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">类型 *</label>
                <select value={taskForm.type}
                  onChange={e => {
                    const type = e.target.value;
                    // 切类型必须清空资源绑定：知识 ID 当分身提交会 404，分身 ID 当阅读资源会 400
                    setTaskForm({
                      ...taskForm,
                      type,
                      resourceId: '',
                      resourceType: type === 'practice' ? 'skill' : 'knowledge',
                      sceneTag: '',
                      examDatasetId: type === 'exam' ? taskForm.examDatasetId : '',
                      examDatasetVersion: type === 'exam' ? taskForm.examDatasetVersion : '',
                      examItemCount: type === 'exam' ? taskForm.examItemCount : '',
                      examDurationMinutes: type === 'exam' ? taskForm.examDurationMinutes : '',
                      drillScenario: type === 'drill' ? taskForm.drillScenario : '',
                    });
                  }}
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                  {Object.entries(TASK_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">标题 *</label>
                <input value={taskForm.title}
                  onChange={e => setTaskForm({ ...taskForm, title: e.target.value })}
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">阶段序号 *</label>
                <input type="number" min={1} value={taskForm.phaseNo}
                  onChange={e => setTaskForm({ ...taskForm, phaseNo: e.target.value })}
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-foreground">阶段名</label>
                <input value={taskForm.phaseName}
                  onChange={e => setTaskForm({ ...taskForm, phaseName: e.target.value })}
                  placeholder="如：第一周：产品知识"
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
              </div>
              <div className="md:col-span-2">
                <label className="mb-1 block text-xs font-medium text-foreground">说明</label>
                <textarea rows={2} value={taskForm.description}
                  onChange={e => setTaskForm({ ...taskForm, description: e.target.value })}
                  className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
              </div>

              {taskForm.type === 'reading' && (
                <>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">资源类型 *</label>
                    <select value={taskForm.resourceType}
                      onChange={e => setTaskForm({ ...taskForm, resourceType: e.target.value, resourceId: '' })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                      <option value="knowledge">知识条目</option>
                      <option value="case">案例</option>
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">资源 *</label>
                    <input value={resourceKeyword} onChange={e => setResourceKeyword(e.target.value)}
                      placeholder="搜索标题…"
                      className="mb-1 w-full rounded-md border border-border bg-surface-2 px-3 py-1.5 text-xs text-foreground" />
                    <select value={taskForm.resourceId}
                      onChange={e => setTaskForm({ ...taskForm, resourceId: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                      <option value="">请选择（仅列出已生效资源）</option>
                      {/* 已绑定资源不在当前搜索结果里时也要显示真实标题，不能只剩 UUID */}
                      {taskForm.resourceId && !resourceOptions.some(o => o.id === taskForm.resourceId) && (
                        <option value={taskForm.resourceId}>
                          当前已选：{resourcePreview?.title || taskForm.resourceId.slice(0, 8)}
                          {resourcePreview?.available === false ? '（不可用，请替换）' : '（不在搜索结果中）'}
                        </option>
                      )}
                      {resourceOptions.map(item => (
                        <option key={item.id} value={item.id}>{optionLabel(item)}</option>
                      ))}
                    </select>
                    {resourceLoading && <div className="mt-1 text-[10px] text-muted-foreground">搜索中…</div>}
                    {!resourceLoading && resourceError && (
                      <div className="mt-1 text-[10px] text-red-600">
                        {resourceError}
                        <button type="button" className="ml-2 underline"
                          onClick={() => setResourceReloadKey(k => k + 1)}>重试</button>
                      </div>
                    )}
                    {!resourceLoading && !resourceError && resourceOptions.length === 0 && (
                      <div className="mt-1 text-[10px] text-muted-foreground">没有匹配的资源，试试更短的关键词</div>
                    )}
                    <ResourcePreviewCard preview={resourcePreview} loading={resourcePreviewLoading}
                      expanded={resourceExpanded} onToggle={() => setResourceExpanded(v => !v)} />
                    <div className="mt-1 text-[10px] text-muted-foreground">
                      最多显示 30 条；资源多时输入标题关键词精确搜索
                    </div>
                  </div>
                </>
              )}

              {taskForm.type === 'drill' && (
                <>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">依据资源类型 *</label>
                    <select value={taskForm.resourceType}
                      onChange={e => setTaskForm({ ...taskForm, resourceType: e.target.value, resourceId: '', drillScenario: '' })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                      <option value="knowledge">知识条目</option>
                      <option value="case">案例</option>
                      <option value="skill">分身（AI 客户）</option>
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">依据资源 *</label>
                    <input value={resourceKeyword} onChange={e => setResourceKeyword(e.target.value)}
                      placeholder="搜索标题…"
                      className="mb-1 w-full rounded-md border border-border bg-surface-2 px-3 py-1.5 text-xs text-foreground" />
                    <select value={taskForm.resourceId}
                      onChange={e => setTaskForm({ ...taskForm, resourceId: e.target.value, drillScenario: '' })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                      <option value="">请选择（仅列出可用资源）</option>
                      {taskForm.resourceId && !resourceOptions.some(o => o.id === taskForm.resourceId) && (
                        <option value={taskForm.resourceId}>
                          当前已选：{resourcePreview?.title || taskForm.resourceId.slice(0, 8)}
                        </option>
                      )}
                      {resourceOptions.map(item => (
                        <option key={item.id} value={item.id}>{optionLabel(item)}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">场景标签</label>
                    <input value={taskForm.sceneTag}
                      onChange={e => setTaskForm({ ...taskForm, sceneTag: e.target.value })}
                      placeholder="如：价格异议"
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                  </div>
                  <div className="md:col-span-2">
                    <div className="rounded-lg border border-border bg-surface p-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="text-xs text-muted-foreground">
                          微练题目由 AI 依据所选资源生成（1-3 问，含评分要点）；生成后随任务一起冻结。
                        </div>
                        <button type="button" onClick={onGenerateDrill} disabled={drillGenerating || !taskForm.resourceId}
                          className="rounded-full bg-primary px-4 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50">
                          {drillGenerating ? '生成中…' : taskForm.drillScenario ? '重新生成练习内容' : '生成练习内容'}
                        </button>
                      </div>
                      {taskForm.drillScenario ? (() => {
                        try {
                          const card = JSON.parse(taskForm.drillScenario);
                          return (
                            <div className="mt-2 rounded bg-surface-2 px-3 py-2 text-xs leading-5 text-foreground">
                              <div className="font-medium">场景：{card.scenario}</div>
                              {card.clientRole && <div className="mt-1 text-muted-foreground">客户角色：{card.clientRole}</div>}
                              <div className="mt-1 text-muted-foreground">
                                共 {Array.isArray(card.questions) ? card.questions.length : 0} 问；
                                每题 {Array.isArray(card.questions?.[0]?.expectedPoints) ? card.questions[0].expectedPoints.length : 0} 个评分要点
                              </div>
                            </div>
                          );
                        } catch {
                          return <div className="mt-2 text-xs text-red-600">已生成内容无法解析，请重新生成</div>;
                        }
                      })() : (
                        <div className="mt-2 text-xs text-muted-foreground">尚未生成练习内容</div>
                      )}
                    </div>
                  </div>
                </>
              )}

              {taskForm.type === 'practice' && (
                <>
                  <div className="md:col-span-2">
                    <label className="mb-1 block text-xs font-medium text-foreground">陪练分身 *</label>
                    <input value={resourceKeyword} onChange={e => setResourceKeyword(e.target.value)}
                      placeholder="搜索分身名称/销冠…"
                      className="mb-1 w-full rounded-md border border-border bg-surface-2 px-3 py-1.5 text-xs text-foreground" />
                    <select value={taskForm.resourceId}
                      onChange={e => setTaskForm({ ...taskForm, resourceId: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                      <option value="">请选择已发布分身</option>
                      {taskForm.resourceId && !resourceOptions.some(o => o.id === taskForm.resourceId) && (
                        <option value={taskForm.resourceId}>
                          当前已选：{resourcePreview?.title || taskForm.resourceId.slice(0, 8)}
                          {resourcePreview?.available === false ? '（不可用，请替换）' : '（不在搜索结果中）'}
                        </option>
                      )}
                      {resourceOptions.map(item => (
                        <option key={item.id} value={item.id}>{optionLabel(item)}</option>
                      ))}
                    </select>
                    {resourceLoading && <div className="mt-1 text-[10px] text-muted-foreground">搜索中…</div>}
                    {!resourceLoading && resourceError && (
                      <div className="mt-1 text-[10px] text-red-600">
                        {resourceError}
                        <button type="button" className="ml-2 underline"
                          onClick={() => setResourceReloadKey(k => k + 1)}>重试</button>
                      </div>
                    )}
                    {!resourceLoading && !resourceError && resourceOptions.length === 0 && (
                      <div className="mt-1 text-[10px] text-muted-foreground">没有匹配的分身，试试更短的关键词</div>
                    )}
                    <ResourcePreviewCard preview={resourcePreview} loading={resourcePreviewLoading}
                      expanded={resourceExpanded} onToggle={() => setResourceExpanded(v => !v)} />
                    <div className="mt-1 text-[10px] text-muted-foreground">
                      最多显示 30 条；分身多时输入名称/销冠精确搜索
                    </div>
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">场景标签</label>
                    <input value={taskForm.sceneTag}
                      onChange={e => setTaskForm({ ...taskForm, sceneTag: e.target.value })}
                      placeholder="如：异议处理"
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                  </div>
                  <label className="flex items-end gap-2 pb-2 text-sm text-foreground">
                    <input type="checkbox" checked={taskForm.allowHints}
                      onChange={e => setTaskForm({ ...taskForm, allowHints: e.target.checked })} />
                    允许提示
                  </label>
                </>
              )}

              {taskForm.type === 'exam' && (
                <>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">题集 *</label>
                    <select value={taskForm.examDatasetId}
                      onChange={e => {
                        const ds = datasets.find(d => d.id === e.target.value);
                        setTaskForm({
                          ...taskForm, examDatasetId: e.target.value,
                          examDatasetVersion: ds ? String(ds.currentVersion) : '',
                        });
                      }}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                      <option value="">请选择已发布题集</option>
                      {taskForm.examDatasetId && !datasets.some(d => d.id === taskForm.examDatasetId) && (
                        <option value={taskForm.examDatasetId}>当前已选（{taskForm.examDatasetId.slice(0, 8)}）</option>
                      )}
                      {datasets.map(d => (
                        <option key={d.id} value={d.id}>{d.name}（v{d.currentVersion}，{d.itemCount} 题）</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">题集版本 *</label>
                    <input type="number" min={1} value={taskForm.examDatasetVersion}
                      onChange={e => setTaskForm({ ...taskForm, examDatasetVersion: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">抽题数（留空=全部）</label>
                    <input type="number" min={1} value={taskForm.examItemCount}
                      onChange={e => setTaskForm({ ...taskForm, examItemCount: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">考试时长（分钟）*</label>
                    <input type="number" min={1} value={taskForm.examDurationMinutes}
                      onChange={e => setTaskForm({ ...taskForm, examDurationMinutes: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                  </div>
                </>
              )}

              {taskForm.type !== 'reading' && (
                <>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">及格分</label>
                    <input type="number" min={0} max={100} value={taskForm.minScore}
                      onChange={e => setTaskForm({ ...taskForm, minScore: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                  </div>
                  {taskForm.type === 'exam' && (
                    <div>
                      <label className="mb-1 block text-xs font-medium text-foreground">要点覆盖率门槛（0-1，可选）</label>
                      <input type="number" step="0.05" min={0} max={1} value={taskForm.minPointCoverage}
                        onChange={e => setTaskForm({ ...taskForm, minPointCoverage: e.target.value })}
                        className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                    </div>
                  )}
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">最多尝试次数</label>
                    <input type="number" min={1} value={taskForm.maxAttempts}
                      onChange={e => setTaskForm({ ...taskForm, maxAttempts: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-foreground">逾期策略</label>
                    <select value={taskForm.deadlinePolicy}
                      onChange={e => setTaskForm({ ...taskForm, deadlinePolicy: e.target.value })}
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                      <option value="auto_submit">自动交卷（auto_submit）</option>
                      <option value="timeout_fail">转人工复核（timeout_fail）</option>
                    </select>
                  </div>
                </>
              )}

              <div className="md:col-span-2">
                <label className="mb-1 block text-xs font-medium text-foreground">前置任务（可多选）</label>
                <div className="max-h-32 overflow-y-auto rounded-md border border-border bg-surface-2 p-2">
                  {allTasks.filter(t => t.id !== taskForm.id).length === 0 && (
                    <div className="px-1 py-1 text-xs text-muted-foreground">暂无其它任务</div>
                  )}
                  {allTasks.filter(t => t.id !== taskForm.id).map(t => (
                    <label key={t.id} className="flex items-center gap-2 px-1 py-1 text-sm text-foreground">
                      <input type="checkbox"
                        checked={taskForm.prerequisites.includes(t.id)}
                        onChange={e => setTaskForm({
                          ...taskForm,
                          prerequisites: e.target.checked
                            ? [...taskForm.prerequisites, t.id]
                            : taskForm.prerequisites.filter(id => id !== t.id),
                        })} />
                      [{TASK_TYPE_LABELS[t.type] || t.type}] {t.title}
                    </label>
                  ))}
                </div>
              </div>

              <label className="flex items-center gap-2 text-sm text-foreground">
                <input type="checkbox" checked={taskForm.isRequired}
                  onChange={e => setTaskForm({ ...taskForm, isRequired: e.target.checked })} />
                必修任务
              </label>
            </div>

            <div className="mt-5 flex justify-end gap-2">
              <button onClick={requestCloseTaskForm}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">
                取消
              </button>
              <button onClick={saveTask} disabled={savingTask}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {savingTask ? '保存中…' : '保存任务'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 预览弹层 */}
      {preview && (
        <div className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-black/30 p-6"
          onClick={() => setPreview(null)}>
          <div className="w-full max-w-[640px] rounded-xl bg-surface p-6 shadow-xl" onClick={e => e.stopPropagation()}>
            <div className="mb-4 flex flex-wrap items-start justify-between gap-2">
              <div>
                <h3 className="text-lg font-semibold text-foreground">学员视角预览（v{preview.version}）</h3>
                <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs">
                  {detail.versions.filter(v => v.status === 'published').map(v => (
                    <button key={v.id} type="button" onClick={() => openPreview(v.version)}
                      className={`rounded-full border px-2.5 py-0.5 ${
                        preview.version === v.version
                          ? 'border-green-300 bg-green-50 text-green-700'
                          : 'border-border text-muted-foreground hover:bg-primary-light'
                      }`}>
                      已发布 v{v.version}
                    </button>
                  ))}
                  {detail.versions.filter(v => v.status === 'draft').map(v => (
                    <button key={v.id} type="button" onClick={() => openPreview(v.version)}
                      className={`rounded-full border px-2.5 py-0.5 ${
                        preview.version === v.version
                          ? 'border-amber-300 bg-amber-50 text-amber-700'
                          : 'border-border text-muted-foreground hover:bg-primary-light'
                      }`}>
                      草稿 v{v.version}
                    </button>
                  ))}
                </div>
              </div>
              <button onClick={() => setPreview(null)} className="text-sm text-muted-foreground hover:underline">关闭</button>
            </div>
            {!preview.published && (
              <div className="mb-3 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                正在预览未发布版本，学员看不到；学员当前看到的是
                {preview.latestPublishedVersion ? ` v${preview.latestPublishedVersion}` : '（暂无已发布版本）'}。
              </div>
            )}
            {preview.phases.length === 0 && (
              <p className="text-sm text-muted-foreground">这个版本还没有任务，先添加一个。</p>
            )}
            <div className="space-y-4">
              {preview.phases.map(phase => (
                <div key={phase.phaseNo}>
                  <div className="mb-1 text-sm font-medium text-foreground">
                    阶段 {phase.phaseNo} · {phase.phaseName || '未命名'}
                  </div>
                  <ul className="space-y-1">
                    {phase.tasks.map(t => (
                      <li key={t.id} className="rounded border border-border bg-surface-2 px-3 py-2 text-sm">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="rounded bg-primary-light px-2 py-0.5 text-xs text-primary">
                            {TASK_TYPE_LABELS[t.type] || t.type}
                          </span>
                          <span className="text-foreground">{t.title}</span>
                          <span className="text-xs text-muted-foreground">{t.isRequired ? '必修' : '选修'}</span>
                        </div>
                        {t.resource?.title && (
                          <div className="mt-1 text-xs text-muted-foreground">
                            资源：{t.resource.title}
                            {t.resource.available === false
                              ? ` · ${t.resource.unavailableReason || '不可用'}`
                              : ''}
                          </div>
                        )}
                        <PreviewResourceDetails resource={t.resource} />
                        {t.type === 'exam' && (
                          <div className="mt-1 text-xs text-muted-foreground">
                            题集 v{t.examDatasetVersion} · {t.examItemCount ? `抽 ${t.examItemCount} 题` : '全部题目'}
                            {' · '}{t.examDurationMinutes} 分钟 · 及格 {t.passCriteria?.minScore ?? 80}
                          </div>
                        )}
                        {t.type === 'practice' && t.sceneTag && (
                          <div className="mt-1 text-xs text-muted-foreground">场景：{t.sceneTag}</div>
                        )}
                        {t.prerequisiteTitles && t.prerequisiteTitles.length > 0 && (
                          <div className="mt-1 text-xs text-amber-700">
                            🔒 需先完成：{t.prerequisiteTitles.join('、')}
                          </div>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
            <p className="mt-4 text-xs text-muted-foreground">仅预览，不会创建任何学习记录；学员实际看到的是发布时冻结的内容。</p>
          </div>
        </div>
      )}

      {contentPreview && (
        <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-6"
          onClick={() => setContentPreview(null)}>
          <div className="w-full max-w-2xl rounded-xl bg-white p-5" onClick={e => e.stopPropagation()}>
            <div className="mb-2 flex items-start justify-between gap-2">
              <h3 className="text-lg font-semibold text-foreground">任务内容 · {contentPreview.title}</h3>
              <button type="button" onClick={() => setContentPreview(null)}
                className="text-sm text-muted-foreground hover:text-foreground">关闭</button>
            </div>
            {contentPreview.loading ? (
              <p className="text-sm text-muted-foreground">内容加载中…</p>
            ) : contentPreview.resource ? (
              <PreviewResourceDetails resource={contentPreview.resource} />
            ) : (
              <p className="text-sm text-muted-foreground">资源内容不可用，请检查任务绑定的资源。</p>
            )}
          </div>
        </div>
      )}

      {/* 版本对比 */}
      {diffOpen && (
        <div className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-black/30 p-6"
          onClick={() => setDiffOpen(false)}>
          <div className="w-full max-w-[720px] rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-lg font-semibold text-foreground">版本对比</h3>
              <button onClick={() => setDiffOpen(false)} className="text-sm text-muted-foreground hover:underline">关闭</button>
            </div>
            <div className="mb-4 flex flex-wrap items-end gap-2 text-sm">
              <label>
                <span className="mb-1 block text-xs text-muted-foreground">对比基准</span>
                <select value={diffFrom} onChange={e => openVersionDiff(e.target.value, diffTo)}
                  className="rounded-md border border-border bg-surface-2 px-3 py-1.5">
                  {detail.versions.map(v => (
                    <option key={v.id} value={v.version}>v{v.version}（{v.status === 'published' ? '已发布' : '草稿'}）</option>
                  ))}
                </select>
              </label>
              <span className="pb-2 text-muted-foreground">→</span>
              <label>
                <span className="mb-1 block text-xs text-muted-foreground">目标版本</span>
                <select value={diffTo} onChange={e => openVersionDiff(diffFrom, e.target.value)}
                  className="rounded-md border border-border bg-surface-2 px-3 py-1.5">
                  {detail.versions.map(v => (
                    <option key={v.id} value={v.version}>v{v.version}（{v.status === 'published' ? '已发布' : '草稿'}）</option>
                  ))}
                </select>
              </label>
            </div>
            {diffLoading && <p className="text-sm text-muted-foreground">对比中…</p>}
            {!diffLoading && diffError && <p className="text-sm text-red-600">{diffError}</p>}
            {!diffLoading && !diffError && diffData && (
              <div className="space-y-4 text-sm">
                <DiffSection title="新增任务" tone="green"
                  items={diffData.added.map(d => ({ title: d.title, detail: `阶段 ${d.phaseNo}` }))} />
                <DiffSection title="删除任务" tone="red"
                  items={diffData.removed.map(d => ({ title: d.title, detail: `阶段 ${d.phaseNo}` }))} />
                <div>
                  <h4 className="mb-1 font-medium text-foreground">修改任务（{diffData.changed.length}）</h4>
                  {diffData.changed.length === 0 && <p className="text-xs text-muted-foreground">没有字段变化</p>}
                  <ul className="space-y-2">
                    {diffData.changed.map(item => (
                      <li key={item.title} className="rounded border border-border bg-surface-2 px-3 py-2">
                        <div className="text-sm text-foreground">{item.title}</div>
                        <ul className="mt-1 space-y-0.5 text-xs text-muted-foreground">
                          {item.changes.map(c => (
                            <li key={c.field}>
                              {FIELD_LABELS[c.field] || c.field}：
                              <span className="text-red-600">{formatDiffValue(c.from)}</span>
                              {' → '}
                              <span className="text-green-700">{formatDiffValue(c.to)}</span>
                            </li>
                          ))}
                        </ul>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

const FIELD_LABELS: Record<string, string> = {
  type: '任务类型',
  phaseNo: '阶段',
  isRequired: '必修',
  resourceType: '资源类型',
  resourceId: '绑定资源',
  sceneTag: '场景标签',
  allowHints: '允许提示',
  examDatasetId: '题集',
  examDatasetVersion: '题集版本',
  examItemCount: '抽题数',
  examDurationMinutes: '考试时长',
  passCriteria: '通过条件',
  maxAttempts: '最多尝试',
  deadlinePolicy: '逾期策略',
  prerequisites: '前置任务',
};

function formatDiffValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function DiffSection({
  title, tone, items,
}: {
  title: string;
  tone: 'green' | 'red';
  items: Array<{ title: string; detail: string }>;
}) {
  return (
    <div>
      <h4 className="mb-1 font-medium text-foreground">{title}（{items.length}）</h4>
      {items.length === 0 && <p className="text-xs text-muted-foreground">无</p>}
      <ul className="space-y-1">
        {items.map(item => (
          <li key={item.title} className={`rounded px-2 py-1 text-xs ${
            tone === 'green' ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'
          }`}>
            {item.title} <span className="opacity-70">· {item.detail}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 下拉选项：名称 · 分类/场景 · 非正常状态 */
function optionLabel(item: PickerItem): string {
  const label = item.subtitle ? `${item.name} · ${item.subtitle}` : item.name;
  if (item.status && item.status !== 'active' && item.status !== 'published') {
    return `${label}（${item.status}）`;
  }
  return label;
}

/** 学员视角预览里的资源内容：默认收起，点开看学员实际会读到的正文 */
function PreviewResourceDetails({ resource }: { resource?: TaskResourcePreview }) {
  if (!resource) return null;
  const hasCase = Boolean(resource.customerProfile || resource.funnelStage || resource.sceneTag
    || resource.dialogueQuote || resource.objection || resource.emotionChange || resource.keyActions
    || resource.scripts || resource.advanceNodes || resource.result
    || resource.reusablePoints || resource.failurePoints);
  const hasDrill = Boolean(resource.scenario);
  if (!resource.content && !hasCase && !hasDrill && !resource.openingMessage) return null;
  return (
    <div className="mt-1 space-y-1 text-xs">
      {resource.content && (
        <details className="rounded border border-border bg-white px-2 py-1">
          <summary className="cursor-pointer text-primary">查看知识正文</summary>
          <div className="mt-1 max-h-60 overflow-auto whitespace-pre-wrap text-muted-foreground">
            {resource.content}
          </div>
        </details>
      )}
      {hasCase && (
        <details className="rounded border border-border bg-white px-2 py-1">
          <summary className="cursor-pointer text-primary">查看案例内容</summary>
          <dl className="mt-1 space-y-1 text-muted-foreground">
            {resource.customerProfile && <div><dt className="inline font-medium">客户画像：</dt><dd className="inline whitespace-pre-wrap">{resource.customerProfile}</dd></div>}
            {resource.funnelStage && <div><dt className="inline font-medium">漏斗阶段：</dt><dd className="inline">{resource.funnelStage}</dd></div>}
            {resource.objection && <div><dt className="inline font-medium">异议：</dt><dd className="inline whitespace-pre-wrap">{resource.objection}</dd></div>}
            {resource.emotionChange && <div><dt className="inline font-medium">情绪变化：</dt><dd className="inline whitespace-pre-wrap">{resource.emotionChange}</dd></div>}
            {resource.keyActions && <div><dt className="inline font-medium">关键动作：</dt><dd className="inline whitespace-pre-wrap">{resource.keyActions}</dd></div>}
            {resource.scripts && <div><dt className="inline font-medium">话术：</dt><dd className="inline whitespace-pre-wrap">{resource.scripts}</dd></div>}
            {resource.advanceNodes && <div><dt className="inline font-medium">推进节点：</dt><dd className="inline whitespace-pre-wrap">{resource.advanceNodes}</dd></div>}
            {resource.result && <div><dt className="inline font-medium">结果：</dt><dd className="inline">{resource.result === 'win' ? '赢单' : resource.result === 'loss' ? '丢单' : resource.result === 'delay' ? '搁置' : resource.result}</dd></div>}
            {resource.reusablePoints && <div><dt className="inline font-medium">可复用要点：</dt><dd className="inline whitespace-pre-wrap">{resource.reusablePoints}</dd></div>}
            {resource.failurePoints && <div><dt className="inline font-medium">失败点：</dt><dd className="inline whitespace-pre-wrap">{resource.failurePoints}</dd></div>}
          </dl>
        </details>
      )}
      {hasDrill && (
        <details className="rounded border border-border bg-white px-2 py-1">
          <summary className="cursor-pointer text-primary">查看微练场景</summary>
          <div className="mt-1 space-y-1 text-muted-foreground">
            <p className="whitespace-pre-wrap">{resource.scenario}</p>
            {resource.clientRole && <p>客户角色：{resource.clientRole}</p>}
            {(resource.questions?.length ?? 0) > 0 && (
              <ol className="list-decimal pl-4">
                {resource.questions!.map((q, i) => <li key={i}>{q.question || '（无题干）'}</li>)}
              </ol>
            )}
          </div>
        </details>
      )}
      {resource.openingMessage && (
        <details className="rounded border border-border bg-white px-2 py-1">
          <summary className="cursor-pointer text-primary">查看分身开场白</summary>
          <div className="mt-1 whitespace-pre-wrap text-muted-foreground">{resource.openingMessage}</div>
        </details>
      )}
    </div>
  );
}

/** 选中资源后的预览卡：知识正文 / 案例字段 / 分身开场白 */
function ResourcePreviewCard({
  preview, loading, expanded, onToggle,
}: {
  preview: TaskResourcePreview | null;
  loading: boolean;
  expanded: boolean;
  onToggle: () => void;
}) {
  if (loading) {
    return <div className="mt-2 rounded-lg border border-border bg-surface px-3 py-2 text-xs text-muted-foreground">资源详情加载中…</div>;
  }
  if (!preview) return null;
  return (
    <div className="mt-2 rounded-lg border border-border bg-surface px-3 py-2 text-xs">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium text-foreground">{preview.title || '未命名资源'}</span>
        {preview.available === false && (
          <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] text-amber-700">不可用</span>
        )}
      </div>
      {preview.available === false && preview.unavailableReason && (
        <p className="mt-1 text-red-600">{preview.unavailableReason}</p>
      )}
      {preview.content && (
        <>
          <p className={`mt-1 whitespace-pre-wrap text-muted-foreground ${expanded ? '' : 'line-clamp-3'}`}>
            {preview.content}
          </p>
          <button type="button" onClick={onToggle} className="mt-1 text-primary hover:underline">
            {expanded ? '收起' : '展开全文'}
          </button>
        </>
      )}
      {(preview.customerProfile || preview.sceneTag || preview.objection || preview.keyActions) && (
        <dl className="mt-1 grid grid-cols-1 gap-1 text-muted-foreground md:grid-cols-2">
          {preview.customerProfile && <div><dt className="inline font-medium">客户画像：</dt><dd className="inline">{preview.customerProfile}</dd></div>}
          {preview.sceneTag && <div><dt className="inline font-medium">场景：</dt><dd className="inline">{preview.sceneTag}</dd></div>}
          {preview.objection && <div><dt className="inline font-medium">异议：</dt><dd className="inline">{preview.objection}</dd></div>}
          {preview.keyActions && <div><dt className="inline font-medium">关键动作：</dt><dd className="inline">{preview.keyActions}</dd></div>}
          {preview.reusablePoints && <div className="md:col-span-2"><dt className="inline font-medium">可复用要点：</dt><dd className="inline">{preview.reusablePoints}</dd></div>}
        </dl>
      )}
      {preview.openingMessage && (
        <p className="mt-1 line-clamp-3 text-muted-foreground">开场白：{preview.openingMessage}</p>
      )}
    </div>
  );
}
