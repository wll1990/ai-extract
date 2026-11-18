'use client';
import { Pagination } from '@/components/ui/Pagination';

import React, { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import {
  listCases, deprecateCase, approveCase, copyCase, suggestCaseRevision, reindexCase,
  getCaseImprovements, effectiveStatus, listCaseSceneTags, STATUS_LABELS, type IndustryCase,
} from '@/lib/api/knowledge';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { RevisionModal } from '@/components/ui/RevisionModal';
import { IndexStatusBadge } from '@/components/ui/IndexStatusBadge';
import { Button } from '@/components/ui/Button';
import { FlowSteps } from '@/components/ui/FlowSteps';
import { ReviewFlowNote } from '@/components/admin/ReviewFlowNote';
import { RetrievalTestModal } from '@/components/admin/RetrievalTestModal';
import { LibraryStats } from '@/components/admin/LibraryStats';
import { ExpiryDialog } from '@/components/admin/ExpiryDialog';
import { getCurrentUser } from '@/lib/api/auth';
import { ApiError } from '@/lib/api/client';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

const STATUS_CLS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-600',
  pending: 'bg-warning-bg text-warning-text',
  active: 'bg-success-bg text-success',
  deprecated: 'bg-gray-100 text-gray-500',
  expired: 'bg-danger-bg text-danger',
};

const RESULT_LABELS: Record<string, string> = { win: '赢单', loss: '丢单', delay: '搁置' };
const RESULT_CLS: Record<string, string> = {
  win: 'bg-success-bg text-success',
  loss: 'bg-danger-bg text-danger',
  delay: 'bg-warning-bg text-warning-text',
};

export default function AdminCasesPage() {
  const router = useRouter();
  const [cases, setCases] = useState<IndustryCase[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  useScrollOnPageChange(page);
  const [funnelStage, setFunnelStage] = useState('');
  const [sceneTag, setSceneTag] = useState('');
  const [sceneTags, setSceneTags] = useState<string[]>([]);
  const [result, setResult] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [aiOnly, setAiOnly] = useState(false);
  const [scope, setScope] = useState<'' | 'public' | 'private'>('');
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [revisionTarget, setRevisionTarget] = useState<IndustryCase | null>(null);
  const [approveTarget, setApproveTarget] = useState<IndustryCase | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [counts, setCounts] = useState({ draft: 0, pending: 0, active: 0, all: 0 });
  // 案例自己的检索测试：只测案例，不和知识混在一起
  const [testOpen, setTestOpen] = useState(false);
  const [filtersOpen, setFiltersOpen] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    const countOf = (st: string) => listCases({ status: st, scope: scope || undefined, page: 1, size: 1 })
      .then(d => d.total || 0)
      .catch(() => 0);
    Promise.all([
      listCases({ funnelStage: funnelStage || undefined, sceneTag: sceneTag || undefined, result: result || undefined, status: status || undefined, keyword: keyword || undefined, scope: scope || undefined, aiGenerated: aiOnly || undefined, page, size: 20 }),
      listCases({ scope: scope || undefined, page: 1, size: 1 }).then(d => d.total || 0).catch(() => 0),
      countOf('draft'),
      countOf('pending'),
      countOf('active'),
    ])
      .then(([data, all, draft, pending, active]) => {
        setCases(data.content || []);
        setTotal(data.total || 0);
        setSelected(new Set());
        setCounts({ draft, pending, active, all });
      })
      .catch(e => {
        if (e instanceof ApiError && e.status === 403) {
          setError('没有案例库管理权限，请联系企业管理员开通');
        } else if (e instanceof ApiError && e.status >= 500) {
          setError('服务暂时不可用，请稍后重试');
        } else if (e instanceof TypeError) {
          setError('网络连接失败，请检查网络后重试');
        } else {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      })
      .finally(() => setLoading(false));
  }, [funnelStage, sceneTag, result, status, keyword, scope, page, aiOnly]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    getCurrentUser().then(u => setIsSuperAdmin(u.role === 'super_admin')).catch(() => {});
  }, []);

  // 场景标签候选值随筛选范围更新
  useEffect(() => {
    listCaseSceneTags().then(setSceneTags).catch(() => setSceneTags([]));
  }, [scope]);

  useEffect(() => {
    if (!menuFor) return;
    const close = () => setMenuFor(null);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [menuFor]);

  const runAction = async (fn: () => Promise<unknown>) => {
    setActing(true);
    try { await fn(); await load(); }
    catch (e) { alert(e instanceof Error ? e.message : '操作失败'); }
    finally { setActing(false); }
  };

  const runActionApproved = async (fn: () => Promise<unknown>) => {
    setActing(true);
    try { await fn(); await load(); setToast('已通过并生效'); }
    catch (e) { alert(e instanceof Error ? e.message : '操作失败'); }
    finally { setActing(false); }
  };

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  const onApprove = (c: IndustryCase) => {
    if (c.expireAt) {
      runAction(() => approveCase(c.id, c.expireAt || undefined));
      return;
    }
    setApproveTarget(c);
  };
  const onDeprecate = (c: IndustryCase) => {
    if (!window.confirm(`确认下架「${c.title}」？下架后分身立即停止引用，30 天内可重新上架。`)) return;
    runAction(() => deprecateCase(c.id));
  };
  const onCopy = (c: IndustryCase) => runAction(() => copyCase(c.id));
  const onSuggest = (c: IndustryCase) => setRevisionTarget(c);

  const onReindex = (c: IndustryCase) => runAction(() => reindexCase(c.id));

  const toggleSelect = (id: string) => setSelected(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const toggleAll = () => setSelected(prev =>
    prev.size === cases.length ? new Set() : new Set(cases.map(c => c.id)));

  /**
   * 案例暂未提供后端批量端点，这里按当前页逐条调用现有接口：
   * 最多 20 条，串行执行，单条失败不影响其余项，最后汇总失败原因。
   */
  const runBatch = async (action: 'approve' | 'deprecate' | 'reindex') => {
    if (!selected.size) return;
    const selectedCases = cases.filter(c => selected.has(c.id));
    const writable = selectedCases.filter(c => isSuperAdmin || c.companyId !== null);
    const skipped = selectedCases.length - writable.length;
    if (writable.length === 0) {
      alert('选中的是公共案例，企业账号只读；请先「复制」到本企业后再批量操作。');
      return;
    }
    if (action === 'deprecate'
      && !window.confirm(`确认下架选中的 ${writable.length} 条案例？30 天内可重新上架。`)) return;
    setActing(true);
    let success = 0;
    const failures: string[] = [];
    for (const c of writable) {
      try {
        if (action === 'approve') await approveCase(c.id);
        else if (action === 'deprecate') await deprecateCase(c.id);
        else await reindexCase(c.id);
        success += 1;
      } catch (e) {
        failures.push(`${c.title}：${e instanceof Error ? e.message : '操作失败'}`);
      }
    }
    await load();
    setActing(false);
    if (failures.length > 0) {
      alert(`成功 ${success} 条，失败 ${failures.length} 条：\n${failures.slice(0, 5).join('\n')}`);
    } else {
      const label = action === 'approve' ? '通过' : action === 'deprecate' ? '下架' : '重建索引';
      setToast(`已${label} ${success} 条案例${skipped > 0 ? `；跳过 ${skipped} 条公共只读内容` : ''}`);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / 20));
  const activeFilterCount = [funnelStage, sceneTag, result, status].filter(Boolean).length + (aiOnly ? 1 : 0);

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">案例库</h1>
            <p className="text-sm text-muted-foreground">过程型案例（客户画像/推进过程/结果/可复用点）</p>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <PageHelp
              title="案例库使用说明"
              intro="案例库存一次真实客户推进（画像/异议/原话/动作/结果），是分身回答「这类客户怎么打」的实战依据。只有「已生效」且未过期、已完成索引的案例会被检索；相似度阈值 0.50（比知识更严），每次最多 3 条。"
              steps={[
                { title: '录入', desc: '少量案例用「新增案例」手填；整份材料用「上传文档萃取」。书籍/课程案例一律先落草稿并标「二手」，需补齐后审核。' },
                { title: '补齐字段', desc: '必须有客户画像、核心异议、关键动作/话术、结果（赢单/丢单/搁置）。不达标的案例会标红并禁止审核。' },
                { title: '审核通过', desc: '补齐后点「通过」并设置失效时间（案例可长期有效）；通过后等索引完成才会被检索。' },
                { title: '确认可检索', desc: '索引徽标变「已索引」才会命中；可用知识库页的「检索测试」验证某个客户问题实际命中哪些案例。' },
                { title: '维护与下架', desc: '点标题看全文与被引用情况；用「AI改写」改内容，用「下架」停止引用（30 天内可重新上架）。' },
              ]}
              actions={[
                { label: '新增案例', desc: '打开手填表单，录入客户画像、异议、动作、话术、推进节点和结果。新建后仍需审核。', tone: 'primary' },
                { label: '上传文档萃取', desc: '上传材料由系统提炼案例草稿；不会直接生效，需要补齐字段后再审核。' },
                { label: '检索测试', desc: '只测案例。输入一个客户推进场景或异议，查看实际命中的案例与相似度；标绿=会进入分身回答，标黄=近失命中、未达阈值。' },
                { label: '全部 / 公共案例 / 企业私有', desc: '切换检索范围。公共案例由平台维护，企业只能复制后修改。' },
                { label: '通过', desc: '把草稿/待审案例变为已生效。存在未达标字段时会被拦下，需先从详情页补齐。' },
                { label: '复制', desc: '公共案例出现。复制一份企业私有案例后再修改，不影响公共原文。' },
                { label: '更多', desc: '展开低频操作：重建索引、AI 改写、下架。' },
                { label: 'AI 生成', desc: '筛选 AI 生成/内容工厂产出的案例草稿，便于集中复核。' },
                { label: '待我审', desc: '快捷筛选待审核数量；数字随当前「全部/公共/私有」范围变化。' },
                { label: '搜索', desc: '按标题、异议、话术、可复用点做关键词筛选；与阶段/场景/结果/状态筛选叠加生效。' },
              ]}
              notes={[
                '审核 ≠ 立刻可检索：通过后还要等索引徽标变成「已索引」。',
                '准入标准：标题不能是「案例一」这类占位名；必须有核心异议或关键动作/话术；结果必须是赢单/丢单/搁置；书籍案例必须标出处。',
                '公共案例由平台维护，企业要改先「复制到本企业」；提交公共库前会自动强脱敏（公司名/项目名/金额）。',
                '标题写清「什么客户 + 什么场景」，并填客户原话、关键动作和推进节点，检索命中后才真正有用。',
              ]}
            />
            <Button variant="neutral" onClick={() => setTestOpen(true)}>检索测试</Button>
            <Button variant="secondary" onClick={() => router.push('/admin/cases/new?tab=upload')}>上传文档萃取</Button>
            <span data-tour="cases-add">
              <Button variant="primary" onClick={() => router.push('/admin/cases/new')}>＋ 新增案例</Button>
            </span>
          </div>
        </div>

        <LibraryStats
          items={[
            { key: 'all', label: '全部案例', value: counts.all, active: !status, onClick: () => { setStatus(''); setPage(1); } },
            { key: 'pending', label: '待审核', value: counts.pending, tone: 'warning', active: status === 'pending', onClick: () => { setStatus('pending'); setPage(1); } },
            { key: 'active', label: '已生效', value: counts.active, tone: 'success', active: status === 'active', onClick: () => { setStatus('active'); setPage(1); } },
            { key: 'draft', label: '草稿', value: counts.draft, active: status === 'draft', onClick: () => { setStatus('draft'); setPage(1); } },
          ]}
        />

        <FlowSteps
          compact
          className="mb-4"
          steps={[
            { id: 'input', title: '录入', desc: '', status: counts.all > 0 ? 'done' : 'current', onClick: () => router.push('/admin/cases/new') },
            { id: 'review', title: '审核', desc: '', status: counts.pending > 0 ? 'current' : counts.all > 0 ? 'done' : 'todo', onClick: () => { setStatus('pending'); setPage(1); } },
            { id: 'searchable', title: '可检索', desc: '', status: counts.active > 0 ? 'done' : counts.all > 0 ? 'current' : 'todo', onClick: () => { setStatus('active'); setPage(1); } },
            { id: 'maintain', title: '维护', desc: '', status: counts.active > 0 ? 'current' : 'todo' },
          ]}
        />

        <ReviewFlowNote collapsible className="mb-4" kind="case" />

        {/* 工具栏：范围 + 搜索 + 筛选 */}
        <div className="mb-4 rounded-xl border border-border bg-surface p-3">
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex rounded-lg bg-surface-2 p-0.5">
              {([
                ['', '全部'],
                ['public', '公共案例'],
                ['private', '企业私有'],
              ] as const).map(([value, label]) => (
                <button key={value || 'all'}
                  onClick={() => { setScope(value); setPage(1); }}
                  className={`rounded-md px-3.5 py-1.5 text-[13px] font-medium transition-colors ${
                    scope === value ? 'bg-surface text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
                  }`}>
                  {label}
                </button>
              ))}
            </div>

            <div className="ml-auto flex flex-wrap items-center gap-2">
              <input value={keyword} onChange={e => setKeyword(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') setPage(1); }}
                placeholder="搜索标题/异议/话术…"
                className="h-9 w-64 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground" />
              <Button variant="primary" onClick={() => setPage(1)}>搜索</Button>
              <Button variant="neutral" onClick={() => setFiltersOpen(v => !v)}>
                筛选{activeFilterCount > 0 ? ` ${activeFilterCount}` : ''}
              </Button>
            </div>
          </div>

          {filtersOpen && (
            <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border pt-3">
              <select value={funnelStage} onChange={e => { setFunnelStage(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部阶段</option>
                {['线索', '首触', '需求', '方案', '异议', '报价', '追单', '成交', '复购'].map(s => <option key={s} value={s}>{s}</option>)}
              </select>
              <select value={sceneTag} onChange={e => { setSceneTag(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部场景</option>
                {sceneTags.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
              <select value={result} onChange={e => { setResult(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部结果</option>
                <option value="win">赢单</option>
                <option value="loss">丢单</option>
                <option value="delay">搁置</option>
              </select>
              <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部状态</option>
                <option value="draft">草稿</option>
                <option value="pending">待审核</option>
                <option value="active">已生效（未过期）</option>
                <option value="expired">已过期</option>
                <option value="deprecated">已下架</option>
              </select>
              <button onClick={() => { setAiOnly(v => !v); setPage(1); }}
                className={`h-9 rounded-[10px] px-3 text-xs font-medium ${
                  aiOnly ? 'bg-primary text-white' : 'border border-border-strong text-muted-foreground hover:bg-surface-2'}`}>
                AI 生成
              </button>
              {activeFilterCount > 0 && (
                <button
                  onClick={() => { setFunnelStage(''); setSceneTag(''); setResult(''); setStatus(''); setAiOnly(false); setPage(1); }}
                  className="h-9 rounded-[10px] px-3 text-xs text-muted-foreground hover:text-foreground">
                  清空筛选
                </button>
              )}
            </div>
          )}
        </div>

        {error && (
          <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">
            {error}
            <button onClick={() => window.location.reload()} className="ml-3 underline">重试</button>
          </div>
        )}

        {selected.size > 0 && (
          <div className="sticky top-2 z-30 mb-3 flex flex-wrap items-center gap-2 rounded-xl border border-primary/40 bg-primary-light px-4 py-2.5 shadow-sm">
            <span className="text-sm font-semibold text-primary">已选 {selected.size} 条</span>
            <Button variant="primary" size="sm" disabled={acting}
              onClick={() => runBatch('approve')}
              title="批量通过选中的草稿/待审案例；存在未达标字段的会逐条失败并汇总原因">
              批量通过
            </Button>
            <Button variant="neutral" size="sm" disabled={acting}
              onClick={() => runBatch('reindex')}
              title="逐条异步重跑向量索引；只有已生效条目会成功">
              批量重建索引
            </Button>
            <Button variant="danger" size="sm" disabled={acting}
              onClick={() => runBatch('deprecate')}
              title="批量下架；30 天内可重新上架">
              批量下架
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>
              取消选择
            </Button>
            {scope === 'public' && !isSuperAdmin && (
              <span className="ml-auto text-[11px] text-warning-text">
                公共案例只读；批量操作会自动跳过，需先复制到本企业
              </span>
            )}
            {acting && <span className="ml-auto text-[11px] text-muted-foreground">处理中…</span>}
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <>
            {cases.length === 0 ? (
              <div className="py-20 text-center">
                <span className="text-5xl">{keyword || status || result || funnelStage || sceneTag ? '🔍' : '🎞️'}</span>
                <h2 className="mt-4 text-lg font-semibold text-foreground">
                  {keyword || status || result || funnelStage || sceneTag ? '没有匹配的案例' : '暂无案例'}
                </h2>
                <p className="mt-2 text-sm text-muted-foreground">
                  {keyword || status || result || funnelStage || sceneTag
                    ? '换个关键词或清空筛选条件再试。'
                    : '还没有案例。案例是分身回答的“实战依据”，建议先用最近 3 个成交/丢单的客户对话各建一条。'}
                </p>
                <div className="mt-5 flex justify-center gap-3">
                  <Button variant="primary" onClick={() => router.push('/admin/cases/new')}>新增案例</Button>
                  <Button variant="secondary" onClick={() => router.push('/admin/cases/new?tab=upload')}>上传文档萃取</Button>
                </div>
              </div>
            ) : (
              <div className="rounded-xl border border-border bg-surface-2">
                <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border items-center">
                  <span className="col-span-1">
                    <input
                      type="checkbox"
                      aria-label="全选本页案例"
                      checked={cases.length > 0 && selected.size === cases.length}
                      onChange={toggleAll}
                      className="h-4 w-4 accent-primary"
                    />
                  </span>
                  <span className="col-span-3">标题</span>
                  <span className="col-span-2">阶段</span>
                  <span className="col-span-2">结果</span>
                  <span className="col-span-2">状态</span>
                  <span className="col-span-2 text-right">操作</span>
                </div>
                {cases.map(c => {
                  const st = effectiveStatus(c.status, c.expireAt);
                  const canWrite = isSuperAdmin || c.companyId !== null;
                  return (
                    <div key={c.id} className={`grid grid-cols-12 gap-2 px-5 py-3.5 border-b border-border last:border-0 items-center transition-colors ${
                      selected.has(c.id) ? 'bg-primary-light' : 'hover:bg-primary-light'
                    }`}>
                      <span className="col-span-1">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${c.title}`}
                          checked={selected.has(c.id)}
                          onChange={() => toggleSelect(c.id)}
                          className="h-4 w-4 accent-primary"
                        />
                      </span>
                      <button onClick={() => router.push(`/admin/cases/${c.id}`)} className="col-span-3 min-w-0 text-left">
                        <p className="truncate text-sm font-medium text-foreground hover:underline">{c.title}</p>
                        <p className="truncate text-xs text-muted-foreground" title={c.objection || c.customerProfile || ''}>
                          {c.sceneTag ? `${c.sceneTag} · ` : ''}{c.objection || c.customerProfile || '—'}
                        </p>
                      </button>
                      <span className="col-span-2 text-sm text-foreground">{c.funnelStage || '—'}</span>
                      <span className="col-span-2">
                        {c.result ? <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${RESULT_CLS[c.result] || 'bg-gray-100 text-gray-600'}`}>{RESULT_LABELS[c.result] || c.result}</span> : <span className="text-muted-foreground">—</span>}
                      </span>
                      <span className="col-span-2 flex min-w-0 flex-col gap-0.5">
                        <span className="flex items-center gap-1.5">
                          <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_CLS[st] || 'bg-gray-100 text-gray-600'}`}>{STATUS_LABELS[st] || st}</span>
                          {c.origin === 'book' && (
                            <span className="rounded-full bg-warning-bg px-1.5 py-0.5 text-[10px] text-warning-text">书籍</span>
                          )}
                          {c.generated && <span className="text-[10px] text-primary">AI 生成</span>}
                          {st === 'active' && <IndexStatusBadge status={c.embeddingStatus} title={c.indexError || undefined} />}
                        </span>
                        {isSuperAdmin ? (
                          <span className="truncate text-[10px] text-muted-foreground" title={c.companyName || '公共案例'}>
                            {c.companyName || '公共'}
                          </span>
                        ) : (
                          <>
                            {c.companyId === null && <span className="text-[10px] text-primary">公共</span>}
                            {c.sourceCompanyId && <span className="text-[10px] text-muted-foreground">企业提交</span>}
                          </>
                        )}
                      </span>
                      <div className="col-span-2 flex justify-end items-center gap-2">
                        {canWrite && (c.status === 'pending' || c.status === 'draft') && (
                          <button onClick={() => onApprove(c)} disabled={acting} className="text-xs text-primary font-medium hover:underline">通过</button>
                        )}
                        {c.companyId === null && !isSuperAdmin && (
                          <button onClick={() => onCopy(c)} disabled={acting} className="text-xs text-primary font-medium hover:underline">复制</button>
                        )}
                        {canWrite ? (
                          <div className="relative">
                            <button
                              onClick={e => { e.stopPropagation(); setMenuFor(menuFor === c.id ? null : c.id); }}
                              className="text-xs text-muted-foreground hover:text-foreground hover:underline">更多</button>
                            {menuFor === c.id && (
                              <div className="absolute right-0 top-5 z-20 w-28 rounded-md border border-border bg-surface py-1 shadow-lg">
                                {st === 'active' && c.embeddingStatus !== 'indexed' && (
                                  <button onClick={() => { setMenuFor(null); onReindex(c); }} disabled={acting}
                                    className="block w-full px-3 py-1.5 text-left text-xs text-foreground hover:bg-primary-light disabled:opacity-50">重建索引</button>
                                )}
                                {c.status !== 'deprecated' && (
                                  <button onClick={() => { setMenuFor(null); onSuggest(c); }} disabled={acting}
                                    className="block w-full px-3 py-1.5 text-left text-xs text-foreground hover:bg-primary-light disabled:opacity-50">AI 改写</button>
                                )}
                                {c.status !== 'deprecated' && (
                                  <button onClick={() => { setMenuFor(null); onDeprecate(c); }} disabled={acting}
                                    className="block w-full px-3 py-1.5 text-left text-xs text-danger hover:bg-danger-bg disabled:opacity-50">下架</button>
                                )}
                                {c.status === 'deprecated' && !(st === 'active' && c.embeddingStatus !== 'indexed') && (
                                  <p className="px-3 py-1.5 text-xs text-muted-foreground">无可执行操作</p>
                                )}
                              </div>
                            )}
                          </div>
                        ) : (
                          <span className="text-xs text-muted-foreground">只读</span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            <Pagination page={page} totalPages={totalPages} totalElements={total} onPageChange={setPage} loading={loading} />
          </>
        )}
      </div>

      <RetrievalTestModal open={testOpen} scope="case" onClose={() => setTestOpen(false)} />

      <RevisionModal
        open={!!revisionTarget}
        title="AI 改写案例"
        behaviorNote={revisionTarget?.status === 'active'
          ? '已生效内容：生成新版本草稿，审核通过后才替换旧版。'
          : '草稿/待审内容：直接覆盖当前条目，不额外生成版本。'}
        submitLabel={revisionTarget?.status === 'active' ? '生成新版本草稿' : '直接改写'}
        entryTitle={revisionTarget?.title || ''}
        currentContent={revisionTarget
          ? [
              `标题：${revisionTarget.title}`,
              `客户画像：${revisionTarget.customerProfile || '—'}`,
              `核心异议：${revisionTarget.objection || '—'}`,
              `关键动作：${revisionTarget.keyActions || '—'}`,
              `关键话术：${revisionTarget.scripts || '—'}`,
              `推进节点：${revisionTarget.advanceNodes || '—'}`,
              `可复用点：${revisionTarget.reusablePoints || '—'}`,
              `失败点：${revisionTarget.failurePoints || '—'}`,
            ].join('\n\n')
          : ''}
        presets={[
          '补充客户异议的应对话术',
          '补充客户原话',
          '把推进节点按时间顺序重排',
          '压缩到 500 字以内',
        ]}
        onLoadSuggestions={() => getCaseImprovements(revisionTarget!.id)}
        onSubmit={async instruction => {
          const draft = await suggestCaseRevision(revisionTarget!.id, instruction);
          setRevisionTarget(null);
          router.push(`/admin/cases/${draft.id}`);
        }}
        onClose={() => setRevisionTarget(null)}
      />

      <ExpiryDialog
        open={!!approveTarget}
        title="通过并生效"
        description={`「${approveTarget?.title || ''}」通过后会被分身检索，可设置失效时间。`}
        onCancel={() => setApproveTarget(null)}
        onConfirm={iso => {
          const target = approveTarget;
          setApproveTarget(null);
          if (target) runActionApproved(() => approveCase(target.id, iso));
        }}
      />

      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[70] -translate-x-1/2 rounded-full bg-foreground px-4 py-2 text-sm text-surface shadow-lg">
          {toast}
        </div>
      )}
    </div>
  );
}
