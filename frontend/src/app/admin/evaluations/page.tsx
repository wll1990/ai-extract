'use client';
import { Pagination } from '@/components/ui/Pagination';

import React, { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import {
  listDatasets, listRuns, createDataset, submitDataset, reviewDataset, publishDataset, createVersion, copyDataset, addItem,
  PURPOSE_LABELS, DATASET_STATUS_LABELS, DATASET_STATUS_CLS,
  type EvaluationDataset,
} from '@/lib/api/evaluation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { Button } from '@/components/ui/Button';
import { FlowSteps } from '@/components/ui/FlowSteps';
import { LibraryStats } from '@/components/admin/LibraryStats';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

const PURPOSES = ['regression', 'acceptance', 'training'] as const;

export default function AdminEvaluationsPage() {
  const router = useRouter();
  const [datasets, setDatasets] = useState<EvaluationDataset[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  useScrollOnPageChange(page);
  const [purpose, setPurpose] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);

  // 新建题集内联表单
  const [showCreate, setShowCreate] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createPurpose, setCreatePurpose] = useState('acceptance');
  const [createDescription, setCreateDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [filtersOpen, setFiltersOpen] = useState(false);
  // 流程条用的全局计数：题库 / 已发布 / 待审 / 运行
  const [flowCounts, setFlowCounts] = useState({ datasets: 0, published: 0, inReview: 0, runs: 0 });

  const loadFlowCounts = useCallback(() => {
    Promise.all([
      listDatasets({ page: 1, size: 1 }).then(d => d.total || 0).catch(() => 0),
      listDatasets({ status: 'published', page: 1, size: 1 }).then(d => d.total || 0).catch(() => 0),
      listDatasets({ status: 'in_review', page: 1, size: 1 }).then(d => d.total || 0).catch(() => 0),
      listRuns({ page: 1, size: 1 }).then(d => d.total || 0).catch(() => 0),
    ]).then(([datasets, published, inReview, runs]) => setFlowCounts({ datasets, published, inReview, runs }));
  }, []);

  useEffect(() => { loadFlowCounts(); }, [loadFlowCounts]);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listDatasets({ purpose: purpose || undefined, status: status || undefined, keyword: keyword || undefined, page, size: 20 })
      .then(data => {
        setDatasets(data.content || []);
        setTotal(data.total || 0);
      })
      .catch(() => setError('加载失败，请确认有评测管理权限'))
      .finally(() => setLoading(false));
  }, [purpose, status, keyword, page]);

  useEffect(() => { load(); }, [load]);

  const runAction = async (fn: () => Promise<unknown>, then?: () => void) => {
    setActing(true);
    setActionError(null);
    try {
      await fn();
      if (then) then(); else await load();
      loadFlowCounts();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const onCreate = async () => {
    if (!createName.trim()) { alert('题集名为必填'); return; }
    setCreating(true);
    try {
      const ds = await createDataset({
        name: createName.trim(),
        purpose: createPurpose,
        description: createDescription.trim() || undefined,
      });
      router.push(`/admin/evaluations/${ds.id}`);
    } catch (e) {
      alert(e instanceof Error ? e.message : '创建失败');
      setCreating(false);
    }
  };

  const onApprove = (d: EvaluationDataset) => runAction(() => reviewDataset(d.id, 'approve'));
  const onReject = (d: EvaluationDataset) => {
    const reason = window.prompt('请输入退回理由（必填）');
    if (reason === null) return;
    if (!reason.trim()) { alert('退回必须填写理由'); return; }
    runAction(() => reviewDataset(d.id, 'reject', reason.trim()));
  };

  const totalPages = Math.max(1, Math.ceil(total / 20));
  const activeFilterCount = [purpose, status].filter(Boolean).length;

  /** 用 3 个常见销售异议问题快速建一个示例题集，直接进详情继续编辑/发布 */
  const createExampleDataset = async () => {
    setActing(true);
    try {
      const ds = await createDataset({
        name: `示例题集-销售异议（${new Date().toLocaleDateString()}）`,
        purpose: 'acceptance',
        description: '系统示例：3 个常见客户异议问题，可直接发布后跑分身看效果',
      });
      const samples = [
        {
          question: '客户说预算还没批下来，这个季度签不了，怎么办？',
          expectedPoints: '确认决策链与预算流程；识别真正阻力；提出分阶段验收或分期方案',
          forbiddenPoints: '直接降价；承诺无法兑现的时间点',
          sceneTag: '预算异议',
          orderNo: 1,
        },
        {
          question: '客户拿竞品报价压价，说我们贵 20%，怎么回应？',
          expectedPoints: '先确认比较口径；用总拥有成本对比；用同行业案例佐证',
          forbiddenPoints: '贬低竞品；直接承诺折扣',
          sceneTag: '价格异议',
          orderNo: 2,
        },
        {
          question: '客户一直说再考虑，跟了两周没进展，下一步怎么推进？',
          expectedPoints: '排查内部阻力；找到未表态的人；把材料送到反对者手上',
          forbiddenPoints: '反复催单；只联系对接人不触达决策者',
          sceneTag: '推进停滞',
          orderNo: 3,
        },
      ];
      for (const item of samples) {
        await addItem(ds.id, item);
      }
      router.push(`/admin/evaluations/${ds.id}`);
    } catch (e) {
      alert(e instanceof Error ? e.message : '创建示例题集失败');
    } finally {
      setActing(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]" data-tour="eval-datasets">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">评测中心</h1>
            <p className="text-sm text-muted-foreground">
              版本化题集：研发回归（测召回）/ 分身验收 / 培训考试，发布即冻结
            </p>
          </div>
          <div className="flex gap-2">
            <PageHelp
              title="评测中心怎么用"
              intro="评测集是尺子，用来衡量「当前分身 + 当前知识库/案例库」答得好不好。跑一次测试分四步：建题、发布、运行、看结果。"
              steps={[
                { title: '① 建题集', desc: '新建题集并逐题录入问题、期望要点、禁止要点；也可以让 AI 从知识库/案例库生成候选（必须人工检查）。' },
                { title: '② 提交并发布', desc: '提交审核 → 审核通过 → 发布。发布后版本冻结；要改题必须「创建新版本」。' },
                { title: '③ 新建运行', desc: '到「运行与结果」→「＋ 新建运行」，选已发布题集版本和已发布分身，创建即开始后台答题。' },
                { title: '④ 看过程与结果', desc: '在运行列表点「看结果」：上面是计数卡与均分，下面是逐题表；点单题「看回答与证据」查看 AI 回答、检索证据和分数。' },
                { title: '⑤ 复核并定稿', desc: '逐题确认/改分/无法判断（理由必填），然后「定稿出报告」，报告可导出 Markdown。' },
              ]}
              actions={[
                { label: '＋ 新建题集', desc: '展开内联表单，创建空题集后进入详情逐题录入；用途决定后续出现在哪个入口。', tone: 'primary' },
                { label: '运行与结果', desc: '创建运行、查看批次进度、逐题复核、定稿出报告。空列表会给出三步引导。' },
                { label: '版本对比', desc: '对同一题集的不同版本/分身运行做对比；同版本才逐题可比，基线不按分身过滤。' },
                { label: '编辑', desc: '进入题集详情，增删改题目、从知识/案例 AI 生成候选题（必须人工检查）。' },
                { label: '提交审核', desc: '仅草稿可用。提交后进入待审核，题集内容暂不可改。' },
                { label: '通过 / 退回', desc: '仅待审核可用。退回必须填写理由；通过后才可以发布。' },
                { label: '发布', desc: '仅已通过可用。发布后版本冻结，才能被「新建运行」选用。' },
                { label: '新版本', desc: '仅已发布可用。复制当前版本内容生成新草稿，改完重新走审核发布；旧版本仍可被运行选用。' },
                { label: '复制', desc: '仅公共题集出现。复制一份企业私有题集后再改。' },
                { label: '用示例题创建一个', desc: '快速生成含 3 道销售异议题的企业题集，用来先跑通流程；示例题仍需审核发布。' },
              ]}
              notes={[
                '结果在哪看：评测运行列表 → 某次运行「看结果」；报告在详情页顶部，刷新后自动加载。',
                '最终分=人工改分优先；对比页也按最终分展示，同版本才逐题可比。',
                '失败题可以直接「补知识/补案例」，补完生效后再跑一次同题集验证。',
                '新建运行只列「已发布」题集版本和「已发布」个体分身；找不到选项时先检查这两件事。',
              ]}
            />
            <Button variant="neutral" onClick={() => router.push('/admin/evaluations/runs')}>运行与结果</Button>
            <Button variant="neutral" onClick={() => router.push('/admin/evaluations/compare')}>版本对比</Button>
            <Button variant="primary" onClick={() => setShowCreate(s => !s)}>
              {showCreate ? '收起新建' : '＋ 新建题集'}
            </Button>
          </div>
        </div>

        <LibraryStats
          items={[
            { key: 'all', label: '题集总数', value: flowCounts.datasets, active: !status, onClick: () => { setStatus(''); setPage(1); } },
            { key: 'published', label: '已发布', value: flowCounts.published, tone: 'success', active: status === 'published', onClick: () => { setStatus('published'); setPage(1); } },
            { key: 'review', label: '待审核', value: flowCounts.inReview, tone: 'warning', active: status === 'in_review', onClick: () => { setStatus('in_review'); setPage(1); } },
            { key: 'runs', label: '运行记录', value: flowCounts.runs, onClick: () => router.push('/admin/evaluations/runs') },
          ]}
        />

        <FlowSteps
          compact
          className="mb-4"
          steps={[
            { id: 'dataset', title: '建题集', desc: '', status: flowCounts.datasets > 0 ? 'done' : 'current', onClick: () => setShowCreate(true) },
            { id: 'publish', title: '提交并发布', desc: '', status: flowCounts.published > 0 ? 'done' : flowCounts.inReview > 0 || flowCounts.datasets > 0 ? 'current' : 'todo', onClick: () => setStatus(flowCounts.inReview > 0 ? 'in_review' : 'draft') },
            { id: 'run', title: '新建运行', desc: '', status: flowCounts.runs > 0 ? 'done' : flowCounts.published > 0 ? 'current' : 'todo', onClick: () => router.push('/admin/evaluations/runs?create=1') },
            { id: 'review', title: '复核定稿', desc: '', status: flowCounts.runs > 0 ? 'current' : 'todo', onClick: () => router.push('/admin/evaluations/runs') },
          ]}
        />

        {datasets.length === 0 && (
        <div className="mb-5 rounded-xl border border-border bg-surface-2 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="text-sm font-semibold text-foreground">不知道问什么？照这三类出题</p>
              <ul className="mt-2 space-y-1 text-xs text-muted-foreground">
                <li>· <b className="text-foreground">事实类</b>：客户问价格、政策、产品参数，答案能在知识库里核对（如「50 万以上报价要谁审批？」）</li>
                <li>· <b className="text-foreground">异议类</b>：客户说贵、说预算没批、说要再考虑，看分身会不会按打法回应</li>
                <li>· <b className="text-foreground">边界类</b>：客户问我们做不到的事，看分身会不会编造或乱承诺</li>
              </ul>
            </div>
            <button onClick={createExampleDataset} disabled={acting}
              className="rounded-full border border-primary px-4 py-2 text-xs font-medium text-primary hover:bg-primary-light disabled:opacity-50">
              {acting ? '创建中…' : '用示例题创建一个'}
            </button>
          </div>
        </div>
        )}

        {/* 新建题集内联表单 */}
        {showCreate && (
          <div className="mb-5 rounded-xl border border-border bg-surface-2 p-5 space-y-3">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">题集名 *</label>
                <input value={createName} onChange={e => setCreateName(e.target.value)}
                  placeholder="如：销售FAQ回归题集"
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">用途 *</label>
                <select value={createPurpose} onChange={e => setCreatePurpose(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground">
                  {PURPOSES.map(p => <option key={p} value={p}>{PURPOSE_LABELS[p]}</option>)}
                </select>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">描述（可选）</label>
              <input value={createDescription} onChange={e => setCreateDescription(e.target.value)}
                placeholder="说明这个题集测什么"
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
            </div>
            <div className="flex justify-end">
              <button onClick={onCreate} disabled={creating}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {creating ? '创建中…' : '创建'}
              </button>
            </div>
          </div>
        )}

        {/* 工具栏：搜索 + 筛选，收进一个卡片 */}
        <div className="mb-4 rounded-xl border border-border bg-surface p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[13px] font-semibold text-foreground">题集列表</span>
            {flowCounts.inReview > 0 && (
              <button onClick={() => { setStatus('in_review'); setPage(1); }}
                className="rounded-full border border-warning-text/40 bg-warning-bg px-2.5 py-1 text-[11px] font-medium text-warning-text">
                待审核 {flowCounts.inReview}
              </button>
            )}
            <div className="ml-auto flex flex-wrap items-center gap-2">
              <input value={keyword} onChange={e => setKeyword(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') setPage(1); }}
                placeholder="搜索题集名…"
                className="h-9 w-64 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground" />
              <Button variant="primary" onClick={() => setPage(1)}>搜索</Button>
              <Button variant="neutral" onClick={() => setFiltersOpen(v => !v)}>
                筛选{activeFilterCount > 0 ? ` ${activeFilterCount}` : ''}
              </Button>
            </div>
          </div>
          {filtersOpen && (
            <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border pt-3">
              <select value={purpose} onChange={e => { setPurpose(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部用途</option>
                {PURPOSES.map(p => <option key={p} value={p}>{PURPOSE_LABELS[p]}</option>)}
              </select>
              <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部状态</option>
                {Object.entries(DATASET_STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              {activeFilterCount > 0 && (
                <button onClick={() => { setPurpose(''); setStatus(''); setPage(1); }}
                  className="h-9 rounded-[10px] px-3 text-xs text-muted-foreground hover:text-foreground">
                  清空筛选
                </button>
              )}
            </div>
          )}
        </div>

        {actionError && (
          <div className="mb-4 flex flex-wrap items-start justify-between gap-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-3 text-sm text-danger">
            <span className="min-w-0 leading-6">{actionError}</span>
            <button onClick={() => setActionError(null)} className="shrink-0 text-xs underline hover:no-underline">
              关闭
            </button>
          </div>
        )}

        {error && (
          <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">
            {error}
            <button onClick={() => window.location.reload()} className="ml-3 underline">重试</button>
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <>
            {datasets.length === 0 ? (
              <div className="py-20 text-center">
                <span className="text-5xl">🧪</span>
                <h2 className="mt-4 text-lg font-semibold text-foreground">暂无题集</h2>
                <p className="mt-2 text-sm text-muted-foreground">点击「新建题集」创建第一个评测题集。</p>
              </div>
            ) : (
              <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
                <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border">
                  <span className="col-span-4">题集名</span>
                  <span className="col-span-2">用途</span>
                  <span className="col-span-2">状态</span>
                  <span className="col-span-1 text-right">题数</span>
                  <span className="col-span-3 text-right">操作</span>
                </div>
                {datasets.map(d => (
                  <div key={d.id} className="grid grid-cols-12 gap-2 px-5 py-3.5 border-b border-border last:border-0 items-center hover:bg-primary-light transition-colors">
                    <div className="col-span-4 min-w-0">
                      <p className="truncate text-sm font-medium text-foreground">{d.name}</p>
                      <p className="truncate text-xs text-muted-foreground">
                        v{d.currentVersion} · {d.createdByName || '—'} · {d.companyId === null ? '平台公共' : '企业'}
                      </p>
                    </div>
                    <span className="col-span-2 text-sm text-foreground">{PURPOSE_LABELS[d.purpose] || d.purpose}</span>
                    <span className="col-span-2">
                      <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${DATASET_STATUS_CLS[d.status] || 'bg-gray-100 text-gray-600'}`}>
                        {DATASET_STATUS_LABELS[d.status] || d.status}
                      </span>
                    </span>
                    <span className="col-span-1 text-right text-sm text-muted-foreground">{d.itemCount}</span>
                    <div className="col-span-3 flex justify-end gap-2">
                      <button onClick={() => router.push(`/admin/evaluations/${d.id}`)}
                        className="text-xs text-primary font-medium hover:underline">编辑</button>
                      {d.status === 'draft' && (
                        <button onClick={() => runAction(() => submitDataset(d.id))} disabled={acting}
                          className="text-xs text-primary font-medium hover:underline">提交审核</button>
                      )}
                      {d.status === 'in_review' && (
                        <>
                          <button onClick={() => onApprove(d)} disabled={acting}
                            className="text-xs text-success font-medium hover:underline">通过</button>
                          <button onClick={() => onReject(d)} disabled={acting}
                            className="text-xs text-danger hover:underline">退回</button>
                        </>
                      )}
                      {d.status === 'approved' && (
                        <button onClick={() => runAction(() => publishDataset(d.id))} disabled={acting}
                          className="text-xs text-primary font-medium hover:underline">发布</button>
                      )}
                      {d.status === 'published' && (
                        <button onClick={() => runAction(() => createVersion(d.id))} disabled={acting}
                          className="text-xs text-primary font-medium hover:underline">新版本</button>
                      )}
                      {d.companyId === null && (
                        <button onClick={() => runAction(() => copyDataset(d.id), () => load())} disabled={acting}
                          className="text-xs text-muted-foreground hover:underline">复制</button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* 分页 */}
            <Pagination page={page} totalPages={totalPages} totalElements={total} onPageChange={setPage} loading={loading} />
          </>
        )}
      </div>
    </div>
  );
}
