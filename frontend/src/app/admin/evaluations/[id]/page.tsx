'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import {
  getDataset, updateDataset, submitDataset, reviewDataset, publishDataset, createVersion,
  listItems, addItem, updateItem, deleteItem, createGenerationJob, getGenerationJob,
  listGenerationJobs, listDatasetVersions, listRuns, linesToJsonArray, jsonArrayToLines,
  PURPOSE_LABELS, DATASET_STATUS_LABELS, DATASET_STATUS_CLS, RUN_STATUS_LABELS, REVIEW_STATUS_LABELS,
  type EvaluationDataset, type EvaluationItem, type GenerationJob, type DatasetVersion, type EvaluationRun,
} from '@/lib/api/evaluation';
import { listKnowledge, listCases } from '@/lib/api/knowledge';
import {
  getDatasetAnalysis, generateEvalItemsFromSignals, pollContentJob, type EvaluationDatasetAnalysis,
} from '@/lib/api/content';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';

interface ItemFormState {
  question: string;
  context: string;
  sceneTag: string;
  goldBasis: string;
  expectedLines: string;
  forbiddenLines: string;
  retrievalLines: string;
}

const EMPTY_FORM: ItemFormState = {
  question: '', context: '', sceneTag: '', goldBasis: '',
  expectedLines: '', forbiddenLines: '', retrievalLines: '',
};

const input = 'w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground';

/** 预期要点数组 → 紧凑预览（最多 2 项 + 计数） */
function previewPoints(json: string | null): string {
  if (!json) return '—';
  try {
    const arr = JSON.parse(json);
    if (!Array.isArray(arr) || arr.length === 0) return '—';
    const head = arr.slice(0, 2).join('、');
    return arr.length > 2 ? `${head} 等 ${arr.length} 项` : head;
  } catch {
    return json;
  }
}

export default function AdminDatasetEditorPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 题集 + 元信息表单
  const [dataset, setDataset] = useState<EvaluationDataset | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [purpose, setPurpose] = useState('');
  const [savingMeta, setSavingMeta] = useState(false);

  // 题目
  const [items, setItems] = useState<EvaluationItem[]>([]);
  const [itemTotal, setItemTotal] = useState(0);
  // 版本查看：默认当前版本；发布新版本后可切回旧发布版只读查看
  const [viewVersion, setViewVersion] = useState<number | null>(null);
  const [versions, setVersions] = useState<DatasetVersion[]>([]);
  const [recentJobs, setRecentJobs] = useState<GenerationJob[]>([]);
  const [recentRuns, setRecentRuns] = useState<EvaluationRun[]>([]);

  // AI 题目分析（难度/区分度/争议）
  const [analysis, setAnalysis] = useState<EvaluationDatasetAnalysis | null>(null);
  const [signalScene, setSignalScene] = useState('');
  const [signalBusy, setSignalBusy] = useState(false);

  // 题目表单
  const [itemFormOpen, setItemFormOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<EvaluationItem | null>(null);
  const [itemForm, setItemForm] = useState<ItemFormState>(EMPTY_FORM);
  const [savingItem, setSavingItem] = useState(false);

  // AI 生成候选
  const [genOpen, setGenOpen] = useState(false);
  const [genSourceType, setGenSourceType] = useState<'knowledge' | 'case'>('knowledge');
  const [genSources, setGenSources] = useState<{ id: string; title: string }[]>([]);
  const [genLoading, setGenLoading] = useState(false);
  const [genKeyword, setGenKeyword] = useState('');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [genJob, setGenJob] = useState<GenerationJob | null>(null);
  const [genStarting, setGenStarting] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  const loadAll = useCallback(async () => {
    setError(null);
    try {
      const ds = await getDataset(id);
      setDataset(ds);
      setName(ds.name);
      setDescription(ds.description || '');
      setPurpose(ds.purpose);
      const version = viewVersion ?? ds.currentVersion;
      const p = await listItems(id, version, 1, 200);
      setItems(p.content || []);
      setItemTotal(p.total || 0);
      // 最近生成任务：离开页面后回来仍能看到进度与 0 产出原因
      try {
        const jobs = await listGenerationJobs(id);
        setRecentJobs(jobs.slice(0, 3));
      } catch {
        setRecentJobs([]);
      }
      try {
        setVersions(await listDatasetVersions(id));
      } catch {
        setVersions([]);
      }
      // 最近运行：直接在这页看到跑分结果入口，不用再翻运行列表
      try {
        const runs = await listRuns({ datasetId: id, page: 1, size: 3 });
        setRecentRuns(runs.content || []);
      } catch {
        setRecentRuns([]);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请确认有权限');
    } finally {
      setLoading(false);
    }
  }, [id, viewVersion]);

  useEffect(() => { loadAll(); }, [loadAll]);

  // 题目分析失败不影响编辑主流程
  useEffect(() => {
    getDatasetAnalysis(id).then(setAnalysis).catch(() => setAnalysis(null));
  }, [id, items.length]);

  const isDraft = dataset?.status === 'draft';
  const draftCount = items.filter(i => i.status === 'draft').length;
  const readOnlyVersion = !!dataset && viewVersion !== null && viewVersion !== dataset.currentVersion;

  const runAction = async (fn: () => Promise<unknown>) => {
    try {
      await fn();
      await loadAll();
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    }
  };

  // ==================== 元信息 ====================

  const saveMeta = () => {
    if (!dataset) return;
    if (!name.trim()) { alert('题集名为必填'); return; }
    setSavingMeta(true);
    updateDataset(dataset.id, {
      name: name.trim(),
      purpose,
      description: description.trim() || undefined,
      version: dataset.currentVersion,
    })
      .then(() => loadAll())
      .catch(e => alert(e instanceof Error ? e.message : '保存失败'))
      .finally(() => setSavingMeta(false));
  };

  // ==================== 题目 CRUD ====================

  const openAddItem = () => {
    setEditingItem(null);
    setItemForm(EMPTY_FORM);
    setItemFormOpen(true);
  };

  const openEditItem = (item: EvaluationItem) => {
    setEditingItem(item);
    setItemForm({
      question: item.question,
      context: item.context || '',
      sceneTag: item.sceneTag || '',
      goldBasis: item.goldBasis || '',
      expectedLines: jsonArrayToLines(item.expectedPoints),
      forbiddenLines: jsonArrayToLines(item.forbiddenPoints),
      retrievalLines: jsonArrayToLines(item.retrievalExpected),
    });
    setItemFormOpen(true);
  };

  const saveItem = () => {
    if (!dataset) return;
    if (!itemForm.question.trim()) { alert('题干为必填'); return; }
    const req = {
      question: itemForm.question.trim(),
      context: itemForm.context.trim() || undefined,
      sceneTag: itemForm.sceneTag.trim() || undefined,
      goldBasis: itemForm.goldBasis.trim() || undefined,
      expectedPoints: linesToJsonArray(itemForm.expectedLines) || undefined,
      forbiddenPoints: linesToJsonArray(itemForm.forbiddenLines) || undefined,
      retrievalExpected: linesToJsonArray(itemForm.retrievalLines) || undefined,
    };
    setSavingItem(true);
    const p = editingItem ? updateItem(editingItem.id, req) : addItem(dataset.id, req);
    p.then(() => { setItemFormOpen(false); return loadAll(); })
      .catch(e => alert(e instanceof Error ? e.message : '保存失败'))
      .finally(() => setSavingItem(false));
  };

  const onDeleteItem = (item: EvaluationItem) => {
    if (!window.confirm(`确认删除该题目？\n「${item.question.slice(0, 30)}…」`)) return;
    runAction(() => deleteItem(item.id));
  };

  // ==================== AI 生成候选 ====================

  const loadGenSources = useCallback(async (sourceType: 'knowledge' | 'case', keyword = '') => {
    setGenLoading(true);
    setSelectedIds([]);
    setGenJob(null);
    try {
      if (sourceType === 'knowledge') {
        const p = await listKnowledge({ status: 'active', keyword: keyword || undefined, page: 1, size: 100 });
        setGenSources((p.content || []).map(k => ({ id: k.id, title: k.title })));
      } else {
        const p = await listCases({ status: 'active', keyword: keyword || undefined, page: 1, size: 100 });
        setGenSources((p.content || []).map(c => ({ id: c.id, title: c.title })));
      }
    } catch (e) {
      setGenSources([]);
      alert(e instanceof Error ? e.message : '加载来源失败');
    } finally {
      setGenLoading(false);
    }
  }, []);

  const openGen = () => {
    setGenOpen(true);
    loadGenSources(genSourceType);
  };

  const switchGenSourceType = (t: 'knowledge' | 'case') => {
    setGenSourceType(t);
    loadGenSources(t);
  };

  const toggleSelect = (sid: string) => {
    setSelectedIds(prev => prev.includes(sid) ? prev.filter(x => x !== sid) : [...prev, sid]);
  };

  const startGenerate = async () => {
    if (!dataset) return;
    if (selectedIds.length === 0) { alert('请至少选择一个来源'); return; }
    setGenStarting(true);
    try {
      const { jobId } = await createGenerationJob(dataset.id, genSourceType, selectedIds);
      setGenJob({ id: jobId, datasetId: dataset.id, sourceType: genSourceType, status: 'running', producedCount: 0, errorMessage: null });
      startPolling(jobId);
    } catch (e) {
      alert(e instanceof Error ? e.message : '生成失败');
    } finally {
      setGenStarting(false);
    }
  };

  const generateFromSignals = async () => {
    if (!dataset) return;
    if (!signalScene.trim()) { alert('请填写要出题的场景（如：价格异议）'); return; }
    setSignalBusy(true);
    try {
      const accepted = await generateEvalItemsFromSignals(dataset.id, signalScene.trim(), 5);
      const job = await pollContentJob(accepted.jobId);
      if (job.status === 'failed') {
        alert(job.errorMessage || '生成失败');
        return;
      }
      alert(`已从真实失败信号生成 ${job.producedCount} 道题，进入 AI 草稿待审；通过后才会进入题集`);
      setSignalScene('');
      await loadAll();
      getDatasetAnalysis(dataset.id).then(setAnalysis).catch(() => {});
    } catch (e) {
      alert(e instanceof Error ? e.message : '生成失败');
    } finally {
      setSignalBusy(false);
    }
  };

  const startPolling = (jobId: string) => {
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      try {
        const t = await getGenerationJob(jobId);
        setGenJob(t);
        if (t.status === 'done' || t.status === 'failed') {
          if (pollRef.current) clearInterval(pollRef.current);
          if (t.status === 'done') {
            if (t.producedCount > 0) {
              alert(`生成完成，共产出 ${t.producedCount} 道候选题目，请逐条检查后再提交审核`);
            } else {
              alert(`生成完成但没有产出题目：重复 ${t.skippedCount ?? 0} 题、缺少可定位事实 ${t.rejectedCount ?? 0} 题。换一批素材或补充来源正文后再试。`);
            }
            setGenOpen(false);
            await loadAll();
          }
        }
      } catch { /* 轮询失败忽略，下轮重试 */ }
    }, 2000);
  };

  // ==================== 渲染 ====================

  if (loading) return <LoadingSpinner />;

  if (error || !dataset) {
    return (
      <div className="min-h-screen bg-surface px-6 py-8">
        <div className="mx-auto max-w-[1080px]">
          <button onClick={() => router.push('/admin/evaluations')} className="mb-4 text-sm text-muted-foreground hover:underline">← 返回评测中心</button>
          <div className="rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">{error || '题集不存在'}</div>
        </div>
      </div>
    );
  }

  const actions = (
    <div className="flex flex-wrap gap-2">
      {isDraft && (
        <button onClick={() => runAction(() => submitDataset(dataset.id))}
          className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90">提交审核</button>
      )}
      {dataset.status === 'in_review' && (
        <>
          <button onClick={() => runAction(() => reviewDataset(dataset.id, 'approve'))}
            className="rounded-full bg-success px-4 py-2 text-sm font-medium text-white hover:opacity-90">通过</button>
          <button onClick={() => {
            const reason = window.prompt('请输入退回理由（必填）');
            if (reason === null) return;
            if (!reason.trim()) { alert('退回必须填写理由'); return; }
            runAction(() => reviewDataset(dataset.id, 'reject', reason.trim()));
          }} className="rounded-full bg-danger px-4 py-2 text-sm font-medium text-white hover:opacity-90">退回</button>
        </>
      )}
      {dataset.status === 'approved' && (
        <button onClick={() => {
          if (!window.confirm(`发布后将冻结 v${dataset.currentVersion}，评测运行只能选已发布版本。确认发布？`)) return;
          runAction(() => publishDataset(dataset.id));
        }}
          className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90">发布</button>
      )}
      {dataset.status === 'published' && (
        <button onClick={() => {
          if (!window.confirm(`将基于 v${dataset.currentVersion} 复制出新草稿 v${dataset.currentVersion + 1}；已发布版本保持冻结，可继续被运行引用。确认创建？`)) return;
          runAction(async () => {
            await createVersion(dataset.id);
            setViewVersion(null);
          });
        }}
          className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90">创建新版本</button>
      )}
    </div>
  );

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <button onClick={() => router.push('/admin/evaluations')} className="mb-4 text-sm text-muted-foreground hover:underline">← 返回评测中心</button>

        <PageHelp
          title="评测题集怎么用"
          intro="题集是一组「问题 + 期望要点」，用来衡量分身回答得对不对。流程是：录题 → 提交审核 → 发布冻结版本 → 到「评测运行」选分身跑分。"
          steps={[
            { title: '录题或 AI 生成', desc: '草稿状态下可以手工加题，也可以从知识库/案例库让 AI 生成候选，生成后请人工检查一遍。' },
            { title: '提交并审核', desc: '提交后由管理员审核；退回必须写理由，理由会显示在本页。' },
            { title: '发布冻结', desc: '发布后该版本只读，评测运行只能选已发布版本；要改题请「创建新版本」。' },
            { title: '跑运行看结果', desc: '到「评测运行」创建批次，完成后逐题复核、定稿出报告。' },
          ]}
          notes={[
            '研发回归用途的题目必须填写「检索期望」，验收/培训用途必须填写「预期要点」，否则提交审核会被拦。',
            '用途决定评分口径，运行时的用途必须与题集一致；发布后版本冻结，改题必须创建新版本。',
            'AI 生成的候选必须人工检查后再发布；题集会标「AI 生成」。',
            '跑完在「评测运行」复核：最终分以人工改分为准（标「人工」），定稿后生成报告。',
            '版本对比只在同题集同版本逐题可比；版本不同只比较公共题目，不相减总分。',
          ]}
        />

        {/* 头部 */}
        <div className="mb-6 flex items-start justify-between">
          <div>
            <h1 className="mb-1 text-[26px] font-bold text-foreground">{dataset.name}</h1>
            <p className="text-sm text-muted-foreground">
              {PURPOSE_LABELS[dataset.purpose] || dataset.purpose} · v{dataset.currentVersion} · {itemTotal} 题
              {dataset.companyId === null ? ' · 平台公共' : ''}
            </p>
          </div>
          <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${DATASET_STATUS_CLS[dataset.status] || 'bg-gray-100 text-gray-600'}`}>
            {DATASET_STATUS_LABELS[dataset.status] || dataset.status}
          </span>
        </div>

        {dataset.status === 'draft' && dataset.lastReviewReason && (
          <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
            <b>上次被退回：</b>{dataset.lastReviewReason}
            {dataset.reviewedAt && (
              <span className="ml-2 text-xs opacity-70">（{new Date(dataset.reviewedAt).toLocaleString()}）</span>
            )}
          </div>
        )}

        {readOnlyVersion && (
          <div className="mb-4 rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-muted-foreground">
            正在查看历史版本 v{viewVersion}（只读）。
            <button onClick={() => setViewVersion(null)} className="ml-2 text-primary underline">回到当前版本</button>
          </div>
        )}

        {draftCount > 0 && (
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-amber-200 bg-warning-bg px-4 py-3 text-sm text-warning-text">
            <span>还有 {draftCount} 道 AI 草稿题待审核；通过或丢弃后才能提交审核/发布。</span>
            <Link href="/admin/content/review" className="font-medium underline">去 AI 草稿审核</Link>
          </div>
        )}

        {/* AI 题目分析：评分身回答质量的核心读模型 */}
        <div className="mb-5 rounded-xl border border-border bg-surface-2 p-4">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <div>
              <p className="text-sm font-semibold text-foreground">AI 题目分析</p>
              <p className="text-xs text-muted-foreground">
                难度 = 1 - 通过率；区分度 = 高分组通过率 - 低分组通过率；争议率来自人工复核推翻。
              </p>
            </div>
            <div className="flex items-center gap-2">
              <input value={signalScene} onChange={e => setSignalScene(e.target.value)}
                placeholder="按场景出题，如：价格异议"
                className="w-52 rounded-md border border-border bg-surface px-3 py-1.5 text-xs text-foreground" />
              <button onClick={generateFromSignals} disabled={signalBusy}
                className="rounded-full bg-primary px-3 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50">
                {signalBusy ? '生成中…' : '从失败信号出题'}
              </button>
            </div>
          </div>
          {!analysis ? (
            <p className="text-xs text-muted-foreground">暂无分析数据，先跑一次评测。</p>
          ) : (
            <>
              <div className="mb-2 flex flex-wrap gap-2 text-xs">
                <span className="rounded-full border border-border bg-surface px-2 py-0.5 text-muted-foreground">
                  题目 {analysis.itemCount}
                </span>
                <span className="rounded-full border border-border bg-surface px-2 py-0.5 text-amber-700">
                  易 {analysis.tooEasy} · 难 {analysis.tooHard}
                </span>
                <span className="rounded-full border border-border bg-surface px-2 py-0.5 text-red-700">
                  区分度低 {analysis.lowDiscrimination}
                </span>
                <span className="rounded-full border border-border bg-surface px-2 py-0.5 text-red-700">
                  金标争议 {analysis.disputed}
                </span>
                <span className="rounded-full border border-border bg-surface px-2 py-0.5 text-muted-foreground">
                  未跑过 {analysis.neverRun}
                </span>
              </div>
              <div className="max-h-[220px] overflow-y-auto rounded-lg border border-border bg-surface">
                <table className="w-full min-w-[720px] text-xs">
                  <thead className="sticky top-0 bg-surface-2 text-left text-muted-foreground">
                    <tr>
                      <th className="px-3 py-2 font-medium">题目</th>
                      <th className="px-3 py-2 font-medium">场景</th>
                      <th className="px-3 py-2 font-medium">难度</th>
                      <th className="px-3 py-2 font-medium">区分度</th>
                      <th className="px-3 py-2 font-medium">争议率</th>
                      <th className="px-3 py-2 font-medium">标记</th>
                    </tr>
                  </thead>
                  <tbody>
                    {analysis.items.filter(i => i.attempts > 0).slice(0, 20).map(i => (
                      <tr key={i.itemId} className="border-t border-border">
                        <td className="max-w-[360px] px-3 py-2 text-foreground">
                          <div className="line-clamp-2">{i.question || '—'}</div>
                        </td>
                        <td className="px-3 py-2 text-muted-foreground">{i.sceneTag || '—'}</td>
                        <td className="px-3 py-2 tabular-nums">{i.difficulty.toFixed(2)}</td>
                        <td className="px-3 py-2 tabular-nums">{i.discrimination.toFixed(2)}</td>
                        <td className="px-3 py-2 tabular-nums">{(i.overturnRate * 100).toFixed(0)}%</td>
                        <td className="px-3 py-2">
                          {i.flags.map(f => (
                            <span key={f} className="mr-1 rounded-full bg-surface-2 px-1.5 py-0.5 text-[10px] text-muted-foreground">
                              {f}
                            </span>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>

        <div className="mb-5 rounded-xl border border-border bg-surface-2 p-4">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <div>
              <p className="text-sm font-semibold text-foreground">最近测试结果</p>
              <p className="text-xs text-muted-foreground">题集本身不含测试结果；结果来自「运行与结果」里的每一次运行。</p>
            </div>
            <div className="flex gap-2">
              <button onClick={() => router.push('/admin/evaluations/runs')}
                className="rounded-full border border-border px-3 py-1.5 text-xs text-foreground hover:bg-surface">
                全部运行
              </button>
              <button onClick={() => router.push('/admin/evaluations/runs')}
                className="rounded-full bg-primary px-3 py-1.5 text-xs font-medium text-white hover:opacity-90">
                去新建运行
              </button>
            </div>
          </div>
          {recentRuns.length === 0 ? (
            <p className="text-xs text-muted-foreground">
              还没有跑过这个题集。发布版本后到「运行与结果」新建一次运行，跑完在这里点「看结果」。
            </p>
          ) : (
            <div className="space-y-1">
              {recentRuns.map(r => (
                <div key={r.id} className="flex flex-wrap items-center gap-2 rounded-md bg-surface px-3 py-2 text-xs">
                  <span className="text-muted-foreground">{new Date(r.createdAt).toLocaleString()}</span>
                  <span className="rounded-full bg-surface-2 px-2 py-0.5 text-muted-foreground">
                    {RUN_STATUS_LABELS[r.status] || r.status}
                  </span>
                  <span className="text-muted-foreground">完成 {r.completedItems}/{r.totalItems}</span>
                  <span className="text-foreground">均分 {r.scoreAvg ?? '—'}</span>
                  <span className="text-muted-foreground">{REVIEW_STATUS_LABELS[r.reviewStatus] || r.reviewStatus}</span>
                  <button onClick={() => router.push(`/admin/evaluations/runs/${r.id}`)}
                    className="ml-auto text-primary hover:underline">看结果</button>
                </div>
              ))}
            </div>
          )}
        </div>

        {actions}

        {recentJobs.length > 0 && (
          <div className="mt-4 rounded-xl border border-border bg-surface-2 p-4">
            <p className="mb-2 text-xs font-semibold text-muted-foreground">最近 AI 生成任务</p>
            <ul className="space-y-1 text-xs text-muted-foreground">
              {recentJobs.map(j => (
                <li key={j.id}>
                  {(j.createdAt ? new Date(j.createdAt) : new Date()).toLocaleString()} ·{' '}
                  {j.status === 'running'
                    ? '进行中（可离开页面，稍后回来查看）'
                    : j.status === 'failed'
                      ? `失败：${j.errorMessage || '未知原因'}`
                      : `完成：产出 ${j.producedCount} 题`}
                  {j.status === 'done' && j.producedCount === 0 && (
                    <span className="ml-1 text-warning-text">
                      （重复 {j.skippedCount ?? 0} 题、缺少可定位事实 {j.rejectedCount ?? 0} 题）
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* 元信息编辑（仅草稿） */}
        <div className="mt-5 rounded-xl border border-border bg-surface-2 p-5">
          <h2 className="mb-3 text-sm font-semibold text-muted-foreground">题集信息{isDraft ? '' : '（发布后只读，编辑请创建新版本）'}</h2>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">题集名</label>
              <input value={name} onChange={e => setName(e.target.value)} disabled={!isDraft} className={input} />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">用途</label>
              <select value={purpose} onChange={e => setPurpose(e.target.value)} disabled={!isDraft} className={input}>
                {(['regression', 'acceptance', 'training'] as const).map(p => <option key={p} value={p}>{PURPOSE_LABELS[p]}</option>)}
              </select>
            </div>
          </div>
          <div className="mt-3">
            <label className="mb-1 block text-sm font-medium text-foreground">描述</label>
            <textarea value={description} onChange={e => setDescription(e.target.value)} disabled={!isDraft} rows={2} className={input} />
          </div>
          {isDraft && (
            <div className="mt-3 flex justify-end">
              <button onClick={saveMeta} disabled={savingMeta}
                className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {savingMeta ? '保存中…' : '保存信息'}
              </button>
            </div>
          )}
        </div>

        {/* 题目区 */}
        <div className="mt-6">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-lg font-semibold text-foreground">题目（{itemTotal}）</h2>
            <div className="flex items-center gap-2">
              {versions.length > 1 && (
                <select
                  value={viewVersion ?? dataset.currentVersion}
                  onChange={e => setViewVersion(Number(e.target.value))}
                  className="rounded-full border border-border bg-surface px-3 py-1.5 text-xs text-foreground"
                >
                  {versions.map(v => (
                    <option key={v.version} value={v.version}>
                      v{v.version} · {DATASET_STATUS_LABELS[v.status] || v.status}
                    </option>
                  ))}
                </select>
              )}
              {isDraft && !readOnlyVersion && (
                <>
                  <button onClick={openGen}
                    className="rounded-full border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary-light">🤖 AI 生成候选</button>
                  <button onClick={openAddItem}
                    className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90">＋ 添加题目</button>
                </>
              )}
            </div>
          </div>

          {items.length === 0 ? (
            <div className="rounded-xl border border-border bg-surface-2 py-16 text-center">
              <span className="text-4xl">📝</span>
              <p className="mt-3 text-sm text-muted-foreground">
                {isDraft ? '暂无题目，可手动添加或 AI 从知识/案例生成候选。' : '该版本暂无题目。'}
              </p>
            </div>
          ) : (
            <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
              <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border">
                <span className="col-span-1">序</span>
                <span className="col-span-5">题干</span>
                <span className="col-span-2">场景</span>
                <span className="col-span-3">预期要点</span>
                <span className="col-span-1 text-right">操作</span>
              </div>
              {items.map(item => (
                <div key={item.id} className="grid grid-cols-12 gap-2 px-5 py-3 border-b border-border last:border-0 items-start hover:bg-primary-light transition-colors">
                  <span className="col-span-1 text-sm text-muted-foreground">{item.orderNo}</span>
                  <div className="col-span-5 min-w-0">
                    <p className="text-sm text-foreground whitespace-pre-wrap break-words">
                      {item.generated && (
                        <span className="mr-2 rounded-full bg-primary-light px-1.5 py-0.5 text-[10px] text-primary align-middle">AI 生成</span>
                      )}
                      {item.question}
                    </p>
                    {item.goldBasis && <p className="mt-1 text-xs text-muted-foreground">依据：{item.goldBasis}</p>}
                  </div>
                  <span className="col-span-2 text-sm text-muted-foreground">{item.sceneTag || '—'}</span>
                  <span className="col-span-3 text-sm text-foreground">{previewPoints(item.expectedPoints)}</span>
                  <div className="col-span-1 flex justify-end gap-2">
                    {isDraft && !readOnlyVersion && (
                      <>
                        <button onClick={() => openEditItem(item)} className="text-xs text-primary hover:underline">编辑</button>
                        <button onClick={() => onDeleteItem(item)} className="text-xs text-danger hover:underline">删除</button>
                      </>
                    )}
                  </div>
                </div>
              ))}
              {itemTotal > items.length && (
                <p className="px-5 py-3 text-xs text-muted-foreground">仅显示前 {items.length} 题（共 {itemTotal} 题），请精简题集或联系管理员。</p>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 题目表单弹层 */}
      {itemFormOpen && (
        <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-6">
          <div className="w-full max-w-[640px] rounded-xl bg-surface p-6">
            <h3 className="mb-4 text-lg font-semibold text-foreground">{editingItem ? '编辑题目' : '添加题目'}</h3>
            <div className="space-y-3">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">题干 *</label>
                <textarea value={itemForm.question} onChange={e => setItemForm({ ...itemForm, question: e.target.value })} rows={3} className={input} placeholder="模拟客户提问或业务场景" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">固定上下文（可选）</label>
                <textarea value={itemForm.context} onChange={e => setItemForm({ ...itemForm, context: e.target.value })} rows={2} className={input} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-sm font-medium text-foreground">场景标签</label>
                  <input value={itemForm.sceneTag} onChange={e => setItemForm({ ...itemForm, sceneTag: e.target.value })} className={input} placeholder="2-4 字，如 异议处理" />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-foreground">金标依据</label>
                  <input value={itemForm.goldBasis} onChange={e => setItemForm({ ...itemForm, goldBasis: e.target.value })} className={input} placeholder="知识/案例标题或出处" />
                </div>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">预期要点（每行一个，验收/考试金标）</label>
                <textarea value={itemForm.expectedLines} onChange={e => setItemForm({ ...itemForm, expectedLines: e.target.value })} rows={3} className={input} placeholder={'复购率=重复购买客户数/总客户数\n…'} />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">禁止项（每行一个，红线，命中即失败）</label>
                <textarea value={itemForm.forbiddenLines} onChange={e => setItemForm({ ...itemForm, forbiddenLines: e.target.value })} rows={2} className={input} placeholder={'承诺无法兑现的价格\n…'} />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">检索期望（每行一个，研发回归用途）</label>
                <textarea value={itemForm.retrievalLines} onChange={e => setItemForm({ ...itemForm, retrievalLines: e.target.value })} rows={2} className={input} placeholder={'复购率定义\n…'} />
              </div>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button onClick={() => setItemFormOpen(false)} className="rounded-full border border-border px-4 py-2 text-sm text-muted-foreground hover:bg-surface-2">取消</button>
              <button onClick={saveItem} disabled={savingItem}
                className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {savingItem ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* AI 生成候选弹层 */}
      {genOpen && (
        <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-6">
          <div className="w-full max-w-[640px] rounded-xl bg-surface p-6">
            <h3 className="mb-4 text-lg font-semibold text-foreground">AI 生成候选题目</h3>
            <p className="mb-3 text-sm text-muted-foreground">选择来源（知识/案例），AI 逐条生成候选题目写入当前草稿版本，生成后请人工审核。</p>

            <div className="mb-3 flex gap-1 rounded-lg bg-surface-2 p-1 w-fit">
              {(['knowledge', 'case'] as const).map(t => (
                <button key={t} onClick={() => switchGenSourceType(t)}
                  className={`rounded-md px-4 py-1.5 text-sm font-medium ${genSourceType === t ? 'bg-surface text-foreground shadow-sm' : 'text-muted-foreground'}`}>
                  {t === 'knowledge' ? '知识库' : '案例库'}
                </button>
              ))}
            </div>

            <div className="mb-3 flex gap-2">
              <input
                value={genKeyword}
                onChange={e => setGenKeyword(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') loadGenSources(genSourceType, genKeyword.trim()); }}
                placeholder="搜索来源标题（回车检索）"
                className="flex-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
              />
              <button
                type="button"
                onClick={() => loadGenSources(genSourceType, genKeyword.trim())}
                className="rounded-md border border-border px-3 py-2 text-sm text-muted-foreground hover:bg-surface-2"
              >搜索</button>
            </div>

            {genLoading ? (
              <div className="py-8 text-center text-sm text-muted-foreground">加载来源中…</div>
            ) : genSources.length === 0 ? (
              <div className="py-8 text-center text-sm text-muted-foreground">暂无可用的{genSourceType === 'knowledge' ? '知识' : '案例'}来源。</div>
            ) : (
              <div className="max-h-64 overflow-y-auto rounded-md border border-border">
                {genSources.map(s => (
                  <label key={s.id} className="flex items-center gap-2 px-3 py-2 border-b border-border last:border-0 hover:bg-primary-light cursor-pointer">
                    <input type="checkbox" checked={selectedIds.includes(s.id)} onChange={() => toggleSelect(s.id)} className="h-4 w-4" />
                    <span className="text-sm text-foreground">{s.title}</span>
                  </label>
                ))}
              </div>
            )}

            {genJob && (
              <div className="mt-3 rounded-md bg-surface-2 p-3 text-sm text-foreground">
                {genJob.status === 'running' && <p>生成中…</p>}
                {genJob.status === 'done' && (
                  genJob.producedCount > 0
                    ? <p className="text-success">完成，产出 {genJob.producedCount} 题</p>
                    : <p className="text-warning-text">
                        完成但没有产出：重复 {genJob.skippedCount ?? 0} 题、缺少可定位事实 {genJob.rejectedCount ?? 0} 题。
                      </p>
                )}
                {genJob.status === 'failed' && <p className="text-danger">{genJob.errorMessage || '生成失败'}</p>}
              </div>
            )}

            <div className="mt-5 flex justify-end gap-2">
              <button onClick={() => setGenOpen(false)} disabled={genJob?.status === 'running'}
                className="rounded-full border border-border px-4 py-2 text-sm text-muted-foreground hover:bg-surface-2">关闭</button>
              <button onClick={startGenerate} disabled={genStarting || selectedIds.length === 0 || genJob?.status === 'running'}
                className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {genStarting ? '提交中…' : '开始生成'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
