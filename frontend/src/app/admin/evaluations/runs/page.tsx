'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import {
  listRuns, createRun, cancelRun, retryFailedRun, listDatasets, getRunTrend, listDatasetVersions,
  RUN_STATUS_LABELS, RUN_STATUS_CLS, REVIEW_STATUS_LABELS, PURPOSE_LABELS, isRunTerminal,
  type EvaluationRun, type EvaluationDataset, type RunTrendPoint,
} from '@/lib/api/evaluation';
import { apiClient } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { Button } from '@/components/ui/Button';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

const PAGE_SIZE = 20;

interface SkillOption {
  id: string;
  name: string;
  status: string;
}

export default function EvaluationRunsPage() {
  const router = useRouter();
  const [runs, setRuns] = useState<EvaluationRun[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  useScrollOnPageChange(page);
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // 批次 → 题集名展示
  const [datasets, setDatasets] = useState<EvaluationDataset[]>([]);
  const [skills, setSkills] = useState<SkillOption[]>([]);

  // 新建运行表单
  const [showCreate, setShowCreate] = useState(false);
  const [datasetId, setDatasetId] = useState('');
  const [trendDatasetId, setTrendDatasetId] = useState('');
  const [trend, setTrend] = useState<RunTrendPoint[]>([]);
  const [trendLoading, setTrendLoading] = useState(false);
  const [trendError, setTrendError] = useState('');
  const [datasetVersion, setDatasetVersion] = useState('');
  const [publishedVersions, setPublishedVersions] = useState<{ version: number; itemCount: number }[]>([]);
  const [skillId, setSkillId] = useState('');
  const [creating, setCreating] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listRuns({ status: status || undefined, page, size: PAGE_SIZE })
      .then(data => {
        setRuns(data.content || []);
        setTotal(data.total || 0);
      })
      .catch(() => setError('加载失败，请确认有评测管理权限'))
      .finally(() => setLoading(false));
  }, [status, page]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    // 只允许对已发布（冻结）版本运行；分身只能选已发布的个体分身
    listDatasets({ status: 'published', size: 100 })
      .then(d => setDatasets(d.content || []))
      .catch(() => setDatasets([]));
    apiClient<SkillOption[]>('/admin/skills/picker')
      .then(list => setSkills((list || []).filter(s => s.status === 'published')))
      .catch(() => setSkills([]));
  }, []);

  // 从评测中心的流程条跳过来时直接展开表单；空列表时也默认展开，避免「没有数据也不知道点哪」
  useEffect(() => {
    if (typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('create') === '1') {
      setShowCreate(true);
    }
  }, []);

  useEffect(() => {
    if (!loading && runs.length === 0 && datasets.length > 0 && skills.length > 0) setShowCreate(true);
  }, [loading, runs.length, datasets.length, skills.length]);

  // 版本下拉只列已发布（冻结）版本：自由文本输错会被后端静默回落到当前版本
  useEffect(() => {
    setDatasetVersion('');
    if (!datasetId) { setPublishedVersions([]); return; }
    let cancelled = false;
    listDatasetVersions(datasetId)
      .then(list => {
        if (!cancelled) {
          setPublishedVersions((list || [])
            .filter(v => v.status === 'published')
            .map(v => ({ version: v.version, itemCount: v.itemCount })));
        }
      })
      .catch(() => { if (!cancelled) setPublishedVersions([]); });
    return () => { cancelled = true; };
  }, [datasetId]);

  useEffect(() => {
    if (!trendDatasetId) { setTrend([]); return; }
    let cancelled = false;
    setTrendLoading(true);
    setTrendError('');
    getRunTrend(trendDatasetId, 10)
      .then(points => { if (!cancelled) setTrend(points); })
      .catch(e => { if (!cancelled) { setTrend([]); setTrendError(e instanceof Error ? e.message : '趋势加载失败'); } })
      .finally(() => { if (!cancelled) setTrendLoading(false); });
    return () => { cancelled = true; };
  }, [trendDatasetId]);

  const datasetName = (id: string) => datasets.find(d => d.id === id)?.name || id.slice(0, 8);
  const skillName = (id: string) => skills.find(s => s.id === id)?.name || `分身 ${id.slice(0, 8)}`;
  const selectedDataset = datasets.find(d => d.id === datasetId);

  const runAction = async (fn: () => Promise<unknown>) => {
    setActing(true);
    setActionError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const onCreate = async () => {
    setFormError(null);
    if (!datasetId) { setFormError('请选择题集'); return; }
    if (!skillId) { setFormError('请选择分身'); return; }
    setCreating(true);
    try {
      const run = await createRun({
        datasetId,
        datasetVersion: datasetVersion.trim() ? Number(datasetVersion) : undefined,
        skillId,
        // 每次点击生成一个幂等键：连点两次也只会创建一个批次
        idempotencyKey: `ui-${crypto.randomUUID()}`,
      });
      router.push(`/admin/evaluations/runs/${run.id}`);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '创建失败');
      setCreating(false);
    }
  };

  const onCancel = (run: EvaluationRun) => {
    if (!window.confirm('确认取消该批次？已完成的题目证据会保留，未执行的题目不再执行。')) return;
    runAction(() => cancelRun(run.id));
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]" data-tour="eval-runs">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">评测运行</h1>
            <p className="text-sm text-muted-foreground">
              选题集冻结版本与分身，后台自动答题、评分、复核、定稿出报告
            </p>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <PageHelp
              title="评测运行怎么用"
              intro="一次运行 = 用指定分身回答某个已发布题集的全部题目，并给出分数和证据。运行是异步的，可以离开页面。"
              steps={[
                { title: '新建运行', desc: '选择已发布题集、已发布版本和已发布的个体分身；组织分身暂不支持评测。' },
                { title: '等待执行', desc: '后台逐题答题与评分，页面显示完成进度；中途可以取消。' },
                { title: '逐题复核', desc: '对回答逐条确认、改分或标记无法判断，理由必填；改分会进入最终均分。' },
                { title: '定稿与趋势', desc: '定稿生成报告；同一题集多次运行可在右侧看均分与通过率趋势。' },
              ]}
              actions={[
                { label: '新建运行', desc: '选择已发布题集（可指定已发布版本）和已发布个体分身，创建后立即在后台答题；连点只会创建一个批次。', tone: 'primary' },
                { label: '看结果', desc: '进入本次运行详情：计数卡、逐题回答与检索证据、人工复核和定稿报告。' },
                { label: '取消', desc: '仅运行中的批次可用。已完成的题目证据保留，未执行的题目标记为已取消；取消后不能重试。', tone: 'danger' },
                { label: '重试失败题', desc: '仅已完成/已取消且存在执行错误时出现，只重跑执行失败（error）的题，不处理业务低分。' },
                { label: '版本对比', desc: '同一题集不同版本/不同分身的运行结果对比；基线不按分身过滤，默认取最近完成的一次。' },
                { label: '题集管理', desc: '回到题集列表，新建、提交审核、发布或创建新版本。' },
                { label: '质量基线（选题集看趋势）', desc: '选择题集后展示最近 10 次已完成运行的均分、通过率与通过/失败/错误数，用于看模型或提示词变更前后的退化。' },
              ]}
              notes={[
                '只有执行失败的题能重试；业务低分不能通过重试刷分。',
                '已取消的批次不能重试，需要重新新建运行；取消时未执行的题目会标记为已取消。',
                '逐题最终分=人工改分优先，否则取 AI 原分；定稿后才能生成报告，刷新页面会自动加载。',
                '模型或提示词变更前后各跑一次同一回归题集，用于确认有没有退化（当前不会自动触发）。',
              ]}
            />
            <Button variant="neutral" onClick={() => router.push('/admin/evaluations/compare')}>版本对比</Button>
            <Button variant="neutral" onClick={() => router.push('/admin/evaluations')}>题集管理</Button>
            <Button variant="primary" onClick={() => { setShowCreate(s => !s); setFormError(null); }}>
              {showCreate ? '收起新建' : '＋ 新建运行'}
            </Button>
          </div>
        </div>

        {showCreate && (
          <div className="mb-5 rounded-xl border border-border bg-surface-2 p-5 space-y-3">
            {(datasets.length === 0 || skills.length === 0) && (
              <div className="rounded-lg border border-warning-text/30 bg-warning-bg px-3 py-2 text-xs leading-5 text-warning-text">
                还不能开始评测：
                {datasets.length === 0 && (
                  <span> 没有已发布题集，请先到「题集管理」完成 提交审核 → 通过 → 发布。</span>
                )}
                {skills.length === 0 && (
                  <span> 没有已发布分身，请先到「分身管理」发布一个个体分身（组织分身暂不支持评测）。</span>
                )}
                <span className="ml-1 inline-flex gap-2">
                  {datasets.length === 0 && (
                    <button className="underline" onClick={() => router.push('/admin/evaluations')}>去题集管理</button>
                  )}
                  {skills.length === 0 && (
                    <button className="underline" onClick={() => router.push('/admin/skills')}>去分身管理</button>
                  )}
                </span>
              </div>
            )}
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">题集（已发布）*</label>
                <select value={datasetId} onChange={e => { setDatasetId(e.target.value); setDatasetVersion(''); }}
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground">
                  <option value="">请选择题集</option>
                  {datasets.map(d => <option key={d.id} value={d.id}>{d.name}（{PURPOSE_LABELS[d.purpose] || d.purpose}）</option>)}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">版本（已发布）</label>
                <select value={datasetVersion} onChange={e => setDatasetVersion(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground">
                  <option value="">{selectedDataset ? `默认 v${selectedDataset.currentVersion}` : '默认当前版本'}</option>
                  {publishedVersions.map(v => (
                    <option key={v.version} value={String(v.version)}>v{v.version}（{v.itemCount} 题）</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">分身（已发布）*</label>
                <select value={skillId} onChange={e => setSkillId(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground">
                  <option value="">请选择分身</option>
                  {skills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </div>
            </div>
            {formError && <p className="text-sm text-danger">{formError}</p>}
            <p className="text-xs text-muted-foreground">
              仅已发布（冻结）的题集版本可运行；组织分身暂不支持评测。
            </p>
            <div className="flex justify-end">
              <button onClick={onCreate} disabled={creating}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {creating ? '创建中…' : '创建并开始'}
              </button>
            </div>
          </div>
        )}

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <span className="text-xs font-medium text-muted-foreground">运行状态</span>
          <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部状态</option>
            {Object.entries(RUN_STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <span className="ml-2 text-xs font-medium text-muted-foreground">质量基线</span>
          <select value={trendDatasetId} onChange={e => setTrendDatasetId(e.target.value)}
            aria-label="选择题集查看质量趋势"
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">选题集看最近 10 次趋势</option>
            {datasets.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>

        {trendDatasetId && (
          <div className="mb-4 rounded-xl border border-border bg-surface-2 p-4">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-foreground">质量基线趋势（最近 10 次已完成）</h2>
              <span className="text-xs text-muted-foreground">模型/Prompt 变更后看均分与通过率变化，不作硬门禁</span>
            </div>
            {trendError ? (
              <p className="text-sm text-danger">{trendError}</p>
            ) : trendLoading ? (
              <p className="text-sm text-muted-foreground">加载中…</p>
            ) : trend.length === 0 ? (
              <p className="text-sm text-muted-foreground">这个题集还没有已完成的运行</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-xs text-muted-foreground">
                    <tr>
                      <th className="py-1 pr-3">完成时间</th>
                      <th className="py-1 pr-3">版本</th>
                      <th className="py-1 pr-3">均分</th>
                      <th className="py-1 pr-3">通过率</th>
                      <th className="py-1 pr-3">通过/失败/错误</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trend.map(p => (
                      <tr key={p.runId} className="border-t border-border/60">
                        <td className="py-1.5 pr-3 text-muted-foreground">
                          {p.finishedAt ? new Date(p.finishedAt).toLocaleString() : '—'}
                        </td>
                        <td className="py-1.5 pr-3">v{p.datasetVersion ?? '—'}</td>
                        <td className="py-1.5 pr-3">{p.scoreAvg ?? '—'}</td>
                        <td className="py-1.5 pr-3">{p.passRate != null ? `${p.passRate}%` : '—'}</td>
                        <td className="py-1.5 pr-3 text-xs text-muted-foreground">
                          {p.passedItems}/{p.failedItems}/{p.errorItems}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {error && (
          <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">
            {error}
            <button onClick={() => load()} className="ml-3 underline">重试</button>
          </div>
        )}
        {actionError && (
          <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">
            {actionError}
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <>
            {runs.length === 0 ? (
              <div className="rounded-xl border border-border bg-surface px-6 py-10">
                <h2 className="text-lg font-semibold text-foreground">还没有评测运行</h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  一次运行 = 用指定分身答完一个已发布题集，并给出分数与证据。按下面的顺序做完即可。
                </p>
                <ol className="mt-5 space-y-3">
                  <li className="flex gap-3">
                    <span className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-[11px] font-semibold ${
                      datasets.length > 0 ? 'bg-success-bg text-success' : 'bg-primary text-white'
                    }`}>{datasets.length > 0 ? '✓' : '1'}</span>
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-foreground">准备一个已发布题集</p>
                      <p className="mt-0.5 text-xs leading-5 text-muted-foreground">
                        {datasets.length > 0
                          ? `已有 ${datasets.length} 个可用题集。`
                          : '题集需要「提交审核 → 审核通过 → 发布」，发布后版本冻结。'}
                      </p>
                      {datasets.length === 0 && (
                        <Button variant="secondary" size="sm" className="mt-2" onClick={() => router.push('/admin/evaluations')}>
                          去题集管理
                        </Button>
                      )}
                    </div>
                  </li>
                  <li className="flex gap-3">
                    <span className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-[11px] font-semibold ${
                      skills.length > 0 ? 'bg-success-bg text-success' : datasets.length > 0 ? 'bg-primary text-white' : 'bg-surface-2 text-muted-foreground'
                    }`}>{skills.length > 0 ? '✓' : '2'}</span>
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-foreground">准备一个已发布分身</p>
                      <p className="mt-0.5 text-xs leading-5 text-muted-foreground">
                        {skills.length > 0
                          ? `已有 ${skills.length} 个可选分身。组织分身暂不支持评测。`
                          : '只能评测已发布的个体分身；没有的话先去发布一个。'}
                      </p>
                      {skills.length === 0 && (
                        <Button variant="secondary" size="sm" className="mt-2" onClick={() => router.push('/admin/skills')}>
                          去分身管理
                        </Button>
                      )}
                    </div>
                  </li>
                  <li className="flex gap-3">
                    <span className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-[11px] font-semibold ${
                      datasets.length > 0 && skills.length > 0 ? 'bg-primary text-white' : 'bg-surface-2 text-muted-foreground'
                    }`}>3</span>
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-foreground">选择题集版本与分身，创建运行</p>
                      <p className="mt-0.5 text-xs leading-5 text-muted-foreground">
                        创建后后台逐题答题评分，可以离开页面；完成后逐题复核并定稿出报告。
                      </p>
                      <Button
                        variant="primary"
                        size="sm"
                        className="mt-2"
                        disabled={datasets.length === 0 || skills.length === 0}
                        onClick={() => { setShowCreate(true); window.scrollTo({ top: 0, behavior: 'smooth' }); }}
                      >
                        新建运行
                      </Button>
                    </div>
                  </li>
                </ol>
              </div>
            ) : (
              <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
                <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border">
                  <span className="col-span-4">题集 / 分身</span>
                  <span className="col-span-2">状态</span>
                  <span className="col-span-2">进度</span>
                  <span className="col-span-1 text-right">均分</span>
                  <span className="col-span-1">审核</span>
                  <span className="col-span-2 text-right">操作</span>
                </div>
                {runs.map(r => (
                  <div key={r.id} className="grid grid-cols-12 gap-2 px-5 py-3.5 border-b border-border last:border-0 items-center hover:bg-primary-light transition-colors">
                    <div className="col-span-4 min-w-0">
                      <p className="truncate text-sm font-medium text-foreground">{datasetName(r.datasetId)}</p>
                      <p className="truncate text-xs text-muted-foreground">
                        v{r.datasetVersion} · {skillName(r.skillId)} · 累计 {r.totalItems} 题
                      </p>
                    </div>
                    <span className="col-span-2">
                      <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${RUN_STATUS_CLS[r.status] || 'bg-gray-100 text-gray-600'}`}>
                        {RUN_STATUS_LABELS[r.status] || r.status}
                      </span>
                    </span>
                    <span className="col-span-2 text-sm text-foreground">
                      {r.completedItems}/{r.totalItems}
                      {r.errorItems > 0 && <span className="ml-1 text-xs text-danger">错 {r.errorItems}</span>}
                    </span>
                    <span className="col-span-1 text-right text-sm text-muted-foreground">
                      {r.scoreAvg == null ? '—' : r.scoreAvg}
                    </span>
                    <span className="col-span-1 text-xs text-muted-foreground">
                      {REVIEW_STATUS_LABELS[r.reviewStatus] || r.reviewStatus}
                    </span>
                    <div className="col-span-2 flex justify-end gap-2">
                      <button onClick={() => router.push(`/admin/evaluations/runs/${r.id}`)}
                        className="text-xs text-primary font-medium hover:underline">看结果</button>
                      {!isRunTerminal(r.status) && (
                        <button onClick={() => onCancel(r)} disabled={acting}
                          className="text-xs text-danger hover:underline">取消</button>
                      )}
                      {isRunTerminal(r.status) && r.errorItems > 0 && (
                        <button onClick={() => runAction(() => retryFailedRun(r.id))} disabled={acting}
                          className="text-xs text-primary font-medium hover:underline">重试失败题</button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {totalPages > 1 && (
              <div className="mt-4 flex items-center justify-center gap-3 text-sm">
                <button disabled={page <= 1} onClick={() => setPage(p => p - 1)}
                  className="rounded-md border border-border px-3 py-1.5 disabled:opacity-40 hover:bg-primary-light">上一页</button>
                <span className="text-muted-foreground">{page} / {totalPages}（共 {total} 条）</span>
                <button disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}
                  className="rounded-md border border-border px-3 py-1.5 disabled:opacity-40 hover:bg-primary-light">下一页</button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
