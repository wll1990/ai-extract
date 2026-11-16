'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  listPlans, createPlan, listCohorts, updateCohortStatus,
  generatePlan, getTrainingNeeds,
  PLAN_STATUS_LABELS, COHORT_STATUS_LABELS, statusCls,
  type TrainingPlan, type TrainingCohort, type TrainingNeeds,
} from '@/lib/api/training';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import EmptyState from '@/components/ui/EmptyState';
import { Pagination } from '@/components/ui/Pagination';
import { Toast } from '@/components/ui/Toast';
import { PageHelp } from '@/components/ui/PageHelp';

type Tab = 'plans' | 'cohorts';

/** 批次状态流转（与后端 updateCohortStatus 一致） */
const COHORT_NEXT_STATUS: Record<string, Array<{ status: 'completed' | 'cancelled' | 'archived'; label: string }>> = {
  active: [
    { status: 'completed', label: '结班' },
    { status: 'cancelled', label: '取消' },
    { status: 'archived', label: '归档' },
  ],
  completed: [{ status: 'archived', label: '归档' }],
  cancelled: [{ status: 'archived', label: '归档' }],
};

export default function AdminTrainingPlansPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>('plans');
  const [toast, setToast] = useState<string | null>(null);

  // ── 计划 ──
  const [plans, setPlans] = useState<TrainingPlan[]>([]);
  const [planTotal, setPlanTotal] = useState(0);
  const [planTotalPages, setPlanTotalPages] = useState(1);
  const [planPage, setPlanPage] = useState(1);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  // 输入框与生效关键字分离：Enter/搜索才发请求，避免每敲一个字都打后端
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [loadingPlans, setLoadingPlans] = useState(true);
  const [planError, setPlanError] = useState<string | null>(null);
  const requestSeq = useRef(0);
  const [reloadKey, setReloadKey] = useState(0);
  const [planNames, setPlanNames] = useState<Record<string, string>>({});

  // 新建计划内联表单
  const [showCreate, setShowCreate] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createDescription, setCreateDescription] = useState('');
  const [creating, setCreating] = useState(false);

  // AI 生成计划
  const [showGenerate, setShowGenerate] = useState(false);
  const [goal, setGoal] = useState('');
  const [phaseCount, setPhaseCount] = useState(4);
  const [generating, setGenerating] = useState(false);
  const [needs, setNeeds] = useState<TrainingNeeds | null>(null);

  // ── 批次 ──
  const [cohorts, setCohorts] = useState<TrainingCohort[]>([]);
  const [cohortTotal, setCohortTotal] = useState(0);
  const [cohortTotalPages, setCohortTotalPages] = useState(1);
  const [cohortPage, setCohortPage] = useState(1);
  const [cohortStatus, setCohortStatus] = useState('');
  const [loadingCohorts, setLoadingCohorts] = useState(false);
  const [cohortError, setCohortError] = useState<string | null>(null);

  const loadPlans = useCallback(() => {
    const seq = ++requestSeq.current;
    setLoadingPlans(true);
    setPlanError(null);
    listPlans({
      status: status || undefined,
      keyword: appliedKeyword || undefined,
      page: planPage,
      size: 20,
    })
      .then(data => {
        if (seq !== requestSeq.current) return; // 慢请求不能覆盖新结果
        setPlans(data.content || []);
        setPlanTotal(data.total || 0);
        setPlanTotalPages(Math.max(1, data.totalPages || 1));
      })
      .catch(e => {
        if (seq === requestSeq.current) setPlanError(e instanceof Error ? e.message : '加载失败');
      })
      .finally(() => {
        if (seq === requestSeq.current) setLoadingPlans(false);
      });
  }, [status, appliedKeyword, planPage]);

  /** 搜索/回车：重置到第 1 页并强制刷新一次 */
  const applySearch = () => {
    setPlanPage(1);
    setAppliedKeyword(keyword.trim());
    setReloadKey(k => k + 1);
  };

  const loadCohorts = useCallback(() => {
    setLoadingCohorts(true);
    setCohortError(null);
    listCohorts({ status: cohortStatus || undefined, page: cohortPage, size: 20 })
      .then(data => {
        setCohorts(data.content || []);
        setCohortTotal(data.total || 0);
        setCohortTotalPages(Math.max(1, data.totalPages || 1));
      })
      .catch(e => setCohortError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoadingCohorts(false));
  }, [cohortStatus, cohortPage]);

  useEffect(() => { loadPlans(); }, [loadPlans, reloadKey]);
  useEffect(() => { if (tab === 'cohorts') loadCohorts(); }, [tab, loadCohorts]);

  // 批次表头要显示计划名：一次拉取 id→name，失败时回退短 ID
  useEffect(() => {
    listPlans({ page: 1, size: 100 })
      .then(all => setPlanNames(Object.fromEntries((all.content || []).map(p => [p.id, p.name]))))
      .catch(() => setPlanNames({}));
  }, []);

  const onCreate = async () => {
    if (!createName.trim()) { setToast('计划名不能为空'); return; }
    setCreating(true);
    try {
      const plan = await createPlan({
        name: createName.trim(),
        description: createDescription.trim() || undefined,
      });
      router.push(`/admin/training/plans/${plan.id}`);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '创建失败');
      setCreating(false);
    }
  };

  const openGenerate = () => {
    setShowGenerate(true);
    setShowCreate(false);
    getTrainingNeeds(12).then(setNeeds).catch(() => setNeeds(null));
  };

  const onGenerate = async () => {
    if (goal.trim().length < 5) { setToast('训练目标至少 5 个字'); return; }
    setGenerating(true);
    try {
      const result = await generatePlan({ goal: goal.trim(), phaseCount });
      if (result.warnings?.length) {
        setToast(`已生成草稿，有 ${result.warnings.length} 条自动调整，请在计划里确认`);
      }
      router.push(`/admin/training/plans/${result.id}`);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '生成失败');
      setGenerating(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">培训计划</h1>
            <p className="text-sm text-muted-foreground">
              计划 → 发布版本 → 批次 → 分配；发布即冻结，学员按冻结快照学习
            </p>
          </div>
          <div className="flex items-center gap-3">
            <PageHelp
              title="培训计划使用说明"
              intro="培训由「计划 → 发布 → 批次 → 分配」四步组成。发布后内容冻结，学员按发布时的快照学习。"
              steps={[
                { title: '建计划加任务', desc: '新建计划后添加阅读、陪练或考试任务，可以设置前置任务和截止时间。' },
                { title: '发布', desc: '发布前会校验资源是否已生效、题集是否已发布；不通过会列出具体原因。' },
                { title: '建批次分配', desc: '计划发布后建批次，选择学员批量分配；分配后学员在「我的培训」看到任务。' },
                { title: '看进度与复核', desc: '在批次进度里跟踪完成情况；考试和陪练提交后由导师复核，全部通过后认证。' },
              ]}
              notes={[
                '发布后修改内容需要新建版本重新发布，已分配学员继续按旧快照学习。',
                '知识或案例被下线/过期会导致学员打不开资料，发布前系统会拦截。',
              ]}
            />
            {tab === 'plans' && (
              <>
                <button
                  onClick={openGenerate}
                  className="rounded-full border border-primary px-5 py-2.5 text-sm font-medium text-primary hover:bg-primary-light"
                >
                  ✨ AI 生成计划
                </button>
                <button
                  onClick={() => { setShowCreate(s => !s); setShowGenerate(false); }}
                  className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90 transition-opacity"
                >
                  {showCreate ? '收起' : '＋ 新建计划'}
                </button>
              </>
            )}
          </div>
        </div>

        {/* 标签页 */}
        <div className="mb-5 flex gap-2" data-tour="training-tabs">
          {([
            ['plans', `培训计划${planTotal ? `（${planTotal}）` : ''}`],
            ['cohorts', `培训批次${cohortTotal ? `（${cohortTotal}）` : ''}`],
          ] as [Tab, string][]).map(([key, label]) => (
            <button
              key={key}
              onClick={() => setTab(key)}
              className={`rounded-full px-4 py-2 text-sm transition-colors ${
                tab === key ? 'bg-primary text-white' : 'border border-border text-foreground hover:bg-primary-light'
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        {tab === 'plans' && (
          <>
            {showGenerate && (
              <div className="mb-5 rounded-xl border border-primary/40 bg-surface-2 p-5 space-y-3">
                <div>
                  <div className="mb-1 text-sm font-semibold text-foreground">用一句话说清训练目标</div>
                  <p className="mb-2 text-xs text-muted-foreground">
                    AI 会从本企业已生效的知识/案例和已发布分身里选内容，生成草稿计划；仍需你预览、调整、发布。
                  </p>
                  <textarea value={goal} onChange={e => setGoal(e.target.value)} rows={3}
                    placeholder="如：新人 30 天内能独立处理价格异议和竞品对比，陪练达到 80 分"
                    className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
                </div>
                <div className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground">阶段数</span>
                  <select value={phaseCount} onChange={e => setPhaseCount(Number(e.target.value))}
                    className="rounded-md border border-border bg-surface px-3 py-1.5 text-foreground">
                    {[3, 4, 5, 6].map(n => <option key={n} value={n}>{n}</option>)}
                  </select>
                  <span className="text-xs text-muted-foreground">
                    每阶段 1-4 个任务，总任务不超过 12 个
                  </span>
                </div>
                {needs && needs.dimensions.length > 0 && (
                  <div className="rounded-lg border border-border bg-surface p-3">
                    <div className="mb-2 text-xs font-medium text-muted-foreground">
                      当前能力缺口（点击可加到目标里）
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {needs.dimensions.slice(0, 8).map(d => (
                        <button key={d.sceneTag} type="button"
                          onClick={() => setGoal(g => g.includes(d.sceneTag) ? g
                            : `${g ? g + '；' : ''}重点补齐「${d.sceneTag}」`)}
                          className="rounded-full border border-border px-3 py-1 text-xs text-foreground hover:bg-primary-light">
                          {d.sceneTag}
                          <span className="ml-1 text-muted-foreground">
                            缺口 {d.gapCount} · 失败 {d.failedCount}
                          </span>
                        </button>
                      ))}
                    </div>
                  </div>
                )}
                <div className="flex justify-end gap-2">
                  <button onClick={() => setShowGenerate(false)}
                    className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">
                    取消
                  </button>
                  <button onClick={onGenerate} disabled={generating}
                    className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                    {generating ? '生成中…（约 10-30 秒）' : '生成草稿计划'}
                  </button>
                </div>
              </div>
            )}

            {showCreate && (
              <div className="mb-5 rounded-xl border border-border bg-surface-2 p-5 space-y-3">
                <div>
                  <label className="mb-1 block text-sm font-medium text-foreground">计划名 *</label>
                  <input value={createName} onChange={e => setCreateName(e.target.value)}
                    placeholder="如：新销售 30 天上岗计划"
                    className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-foreground">描述（可选）</label>
                  <textarea value={createDescription} onChange={e => setCreateDescription(e.target.value)}
                    rows={2} placeholder="说明适用人群与目标"
                    className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
                </div>
                <div className="flex justify-end">
                  <button onClick={onCreate} disabled={creating}
                    className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                    {creating ? '创建中…' : '创建并编辑'}
                  </button>
                </div>
              </div>
            )}

            <div className="mb-4 flex flex-wrap gap-2">
              <select value={status} onChange={e => { setStatus(e.target.value); setPlanPage(1); }}
                className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                <option value="">全部状态</option>
                {Object.entries(PLAN_STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              <input value={keyword} onChange={e => setKeyword(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') applySearch(); }}
                placeholder="搜索计划名…"
                className="w-56 rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
              <button onClick={applySearch}
                className="rounded-md bg-primary px-4 py-2 text-sm text-white hover:opacity-90">搜索</button>
            </div>

            {planError && (
              <div className="mb-4 rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
                {planError}
                <button onClick={loadPlans} className="ml-3 underline">重试</button>
              </div>
            )}

            {loadingPlans ? <LoadingSpinner /> : planError ? null : plans.length === 0 ? (
              <EmptyState icon="📋" title="还没有培训计划"
                description="建一个计划，安排阶段与任务，发布后即可开批次分配学员"
                action={{ label: '新建计划', onClick: () => setShowCreate(true) }} />
            ) : (
              <div className="overflow-x-auto rounded-xl border border-border bg-surface-2">
                <table className="w-full min-w-[720px] text-sm">
                  <thead className="bg-surface text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="px-4 py-3 font-medium">计划</th>
                      <th className="px-4 py-3 font-medium">状态</th>
                      <th className="px-4 py-3 font-medium">当前版本</th>
                      <th className="px-4 py-3 font-medium">更新时间</th>
                      <th className="px-4 py-3" />
                    </tr>
                  </thead>
                  <tbody>
                    {plans.map(p => (
                      <tr key={p.id} className="border-t border-border hover:bg-primary-light/40">
                        <td className="px-4 py-3">
                          <div className="flex flex-wrap items-center gap-1.5 font-medium text-foreground">
                            {p.name}
                            {p.generatedByAi && (
                              <span className="rounded-full bg-primary-light px-1.5 py-0.5 text-[10px] font-normal text-primary">AI 生成</span>
                            )}
                          </div>
                          {p.description && (
                            <div className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">{p.description}</div>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(p.status)}`}>
                            {PLAN_STATUS_LABELS[p.status] || p.status}
                          </span>
                        </td>
                        <td className="px-4 py-3 tabular-nums text-foreground">v{p.currentVersion}</td>
                        <td className="px-4 py-3 text-xs text-muted-foreground">
                          {p.updatedAt ? new Date(p.updatedAt).toLocaleString() : '—'}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <button onClick={() => router.push(`/admin/training/plans/${p.id}`)}
                            className="rounded-full border border-border px-3 py-1.5 text-xs text-foreground hover:bg-primary-light">
                            编辑 / 批次
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {!loadingPlans && plans.length > 0 && (
              <Pagination page={planPage} totalPages={planTotalPages} totalElements={planTotal}
                onPageChange={setPlanPage} loading={loadingPlans} />
            )}
          </>
        )}

        {tab === 'cohorts' && (
          <>
            <div className="mb-4 flex flex-wrap gap-2">
              <select value={cohortStatus} onChange={e => { setCohortStatus(e.target.value); setCohortPage(1); }}
                className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                <option value="">全部状态</option>
                {Object.entries(COHORT_STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </div>

            {cohortError && (
              <div className="mb-4 rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
                {cohortError}
                <button onClick={loadCohorts} className="ml-3 underline">重试</button>
              </div>
            )}

            {loadingCohorts ? <LoadingSpinner /> : cohortError ? null : cohorts.length === 0 ? (
              <EmptyState icon="👥" title="还没有培训批次"
                description="进入某个计划的编辑页，用已发布版本创建批次并分配学员"
                action={{ label: '去计划列表', onClick: () => setTab('plans') }} />
            ) : (
              <div className="overflow-x-auto rounded-xl border border-border bg-surface-2">
                <table className="w-full min-w-[720px] text-sm">
                  <thead className="bg-surface text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="px-4 py-3 font-medium">批次</th>
                      <th className="px-4 py-3 font-medium">状态</th>
                      <th className="px-4 py-3 font-medium">版本</th>
                      <th className="px-4 py-3 font-medium">截止时间</th>
                      <th className="px-4 py-3" />
                    </tr>
                  </thead>
                  <tbody>
                    {cohorts.map(c => (
                      <tr key={c.id} className="border-t border-border hover:bg-primary-light/40">
                        <td className="px-4 py-3">
                          <div className="font-medium text-foreground">{c.name}</div>
                          <div className="mt-0.5 text-xs text-muted-foreground">
                            {c.planName || planNames[c.planId] || `计划 ${c.planId.slice(0, 8)}`}
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${statusCls(c.status)}`}>
                            {COHORT_STATUS_LABELS[c.status] || c.status}
                          </span>
                        </td>
                        <td className="px-4 py-3 tabular-nums text-foreground">v{c.planVersion}</td>
                        <td className="px-4 py-3 text-xs text-muted-foreground">
                          {c.deadline ? new Date(c.deadline).toLocaleString() : '未设置'}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <div className="flex items-center justify-end gap-2">
                            <button onClick={() => router.push(`/admin/training/cohorts/${c.id}`)}
                              className="rounded-full border border-border px-3 py-1.5 text-xs text-foreground hover:bg-primary-light">
                              查看进度
                            </button>
                            {COHORT_NEXT_STATUS[c.status]?.map(next => (
                              <button key={next.status}
                                onClick={async () => {
                                  if (!confirm(`确认将批次「${c.name}」标记为${next.label}？`)) return;
                                  try {
                                    await updateCohortStatus(c.id, next.status);
                                    loadCohorts();
                                  } catch (e) {
                                    setToast(e instanceof Error ? e.message : '状态更新失败');
                                  }
                                }}
                                className="rounded-full px-3 py-1.5 text-xs text-primary hover:bg-primary-light">
                                {next.label}
                              </button>
                            ))}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {!loadingCohorts && cohorts.length > 0 && (
              <Pagination page={cohortPage} totalPages={cohortTotalPages} totalElements={cohortTotal}
                onPageChange={setCohortPage} loading={loadingCohorts} />
            )}
          </>
        )}
      </div>
    </div>
  );
}
