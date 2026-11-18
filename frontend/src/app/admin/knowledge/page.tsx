'use client';
import { Pagination } from '@/components/ui/Pagination';

import React, { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import {
  listKnowledge, deprecateKnowledge, approveKnowledge, copyKnowledge, suggestKnowledgeRevision,
  reindexKnowledge, batchKnowledge,
  getKnowledgeImprovements,
  effectiveStatus, CATEGORY_LABELS, SOURCE_TYPE_LABELS, STATUS_LABELS,
  type KnowledgeEntry,
} from '@/lib/api/knowledge';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { RevisionModal } from '@/components/ui/RevisionModal';
import { IndexStatusBadge } from '@/components/ui/IndexStatusBadge';
import { Button } from '@/components/ui/Button';
import { FlowSteps } from '@/components/ui/FlowSteps';
import { ReviewFlowNote } from '@/components/admin/ReviewFlowNote';
import { LibraryStats } from '@/components/admin/LibraryStats';
import { RetrievalTestModal } from '@/components/admin/RetrievalTestModal';
import { getCurrentUser } from '@/lib/api/auth';
import { ApiError } from '@/lib/api/client';
import { ExpiryDialog } from '@/components/admin/ExpiryDialog';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

const STATUS_CLS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-600',
  pending: 'bg-warning-bg text-warning-text',
  active: 'bg-success-bg text-success',
  deprecated: 'bg-gray-100 text-gray-500',
  expired: 'bg-danger-bg text-danger',
};

export default function AdminKnowledgePage() {
  const router = useRouter();
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  useScrollOnPageChange(page);
  const [tab, setTab] = useState<'private' | 'public'>('private');
  const [category, setCategory] = useState('');
  const [sourceType, setSourceType] = useState('');
  const [status, setStatus] = useState('');
  const [aiOnly, setAiOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [revisionTarget, setRevisionTarget] = useState<KnowledgeEntry | null>(null);
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [batchCategory, setBatchCategory] = useState('');
  const [approveTarget, setApproveTarget] = useState<KnowledgeEntry | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [counts, setCounts] = useState({ draft: 0, pending: 0, active: 0, all: 0 });
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [menuFor, setMenuFor] = useState<string | null>(null);

  // 检索测试：知识库只测知识，案例库有独立入口
  const [testOpen, setTestOpen] = useState(false);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  useEffect(() => {
    if (!menuFor) return;
    const close = () => setMenuFor(null);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [menuFor]);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    // 流程条要的是「全库管道计数」，不能被当前筛选条件影响，所以单独按状态取 total
    const countOf = (st: string) => listKnowledge({ status: st, scope: tab, page: 1, size: 1 })
      .then(d => d.total || 0)
      .catch(() => 0);
    Promise.all([
      listKnowledge({ category: category || undefined, sourceType: sourceType || undefined, status: status || undefined, keyword: keyword || undefined, scope: tab, aiGenerated: aiOnly || undefined, page, size: 20 }),
      listKnowledge({ scope: tab, page: 1, size: 1 }).then(d => d.total || 0).catch(() => 0),
      countOf('draft'),
      countOf('pending'),
      countOf('active'),
    ])
      .then(([data, all, draft, pending, active]) => {
        setEntries(data.content || []);
        setTotal(data.total || 0);
        setSelected(new Set());
        setCounts({ draft, pending, active, all });
      })
      .catch(e => {
        // 区分权限不足 / 网络异常 / 服务端错误，避免所有失败都提示「请确认有管理权限」
        if (e instanceof ApiError && e.status === 403) {
          setError('没有知识库管理权限，请联系企业管理员开通');
        } else if (e instanceof ApiError && e.status >= 500) {
          setError('服务暂时不可用，请稍后重试');
        } else if (e instanceof TypeError) {
          setError('网络连接失败，请检查网络后重试');
        } else {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      })
      .finally(() => setLoading(false));
  }, [category, sourceType, status, keyword, tab, page, aiOnly]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    getCurrentUser().then(u => setIsSuperAdmin(u.role === 'super_admin')).catch(() => {});
  }, []);

  const runAction = async (fn: () => Promise<unknown>, successMessage?: string) => {
    setActing(true);
    try {
      await fn();
      await load();
      if (successMessage) setToast(successMessage);
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const onApprove = (e: KnowledgeEntry) => {
    // 已有失效时间的直接审核；没有的弹日期选择器（不再手输 ISO）
    if (e.expireAt) {
      runAction(() => approveKnowledge(e.id, e.expireAt || undefined), '已通过并生效');
      return;
    }
    setApproveTarget(e);
  };

  const onDeprecate = (e: KnowledgeEntry) => {
    if (!window.confirm(`确认下架「${e.title}」？下架后分身立即停止引用，30 天内可重新上架。`)) return;
    runAction(() => deprecateKnowledge(e.id));
  };

  const onCopy = (e: KnowledgeEntry) => {
    runAction(() => copyKnowledge(e.id));
  };

  const onSuggest = (e: KnowledgeEntry) => setRevisionTarget(e);

  const onReindex = (e: KnowledgeEntry) => runAction(() => reindexKnowledge(e.id));

  // KN-08 批量操作
  const toggleSelect = (id: string) => setSelected(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const toggleAll = () => setSelected(prev =>
    prev.size === entries.length ? new Set() : new Set(entries.map(e => e.id)));
  const runBatch = async (action: 'approve' | 'deprecate' | 'reindex' | 'category', category?: string) => {
    if (!selected.size) return;
    const selectedEntries = entries.filter(e => selected.has(e.id));
    // 公共内容只有平台超管能写；企业账号选中后自动跳过
    const writable = selectedEntries.filter(e => isSuperAdmin || e.companyId !== null);
    const skipped = selectedEntries.length - writable.length;
    if (writable.length === 0) {
      alert('选中的是公共知识，企业账号只读；请先「复制」到本企业后再批量操作。');
      return;
    }
    if (action === 'deprecate' && !window.confirm(`确认下架选中的 ${writable.length} 条知识？30 天内可重新上架。`)) return;
    setActing(true);
    try {
      const r = await batchKnowledge(action, writable.map(e => e.id), category);
      if (action === 'category') setBatchCategory('');
      await load();
      if (r.failed > 0) {
        alert(`成功 ${r.success} 条，失败 ${r.failed} 条：\n` + r.failures.slice(0, 5).map(f => f.message).join('\n'));
      } else {
        const suffix = skipped > 0 ? `；已跳过 ${skipped} 条公共只读内容` : '';
        setToast((action === 'approve'
          ? `已通过 ${r.success} 条（未设置失效时间的默认 1 年有效）`
          : `已处理 ${r.success} 条`) + suffix);
      }
    } catch (e) {
      alert(e instanceof Error ? e.message : '批量操作失败');
    } finally {
      setActing(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / 20));
  const activeFilterCount = [category, sourceType, status].filter(Boolean).length + (aiOnly ? 1 : 0);

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">知识库</h1>
            <p className="text-sm text-muted-foreground">事实型知识（产品/价格/合规/术语/方法论），支持 AI 改写与人工确认</p>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <PageHelp
              title="知识库使用说明"
              intro="知识库存事实（产品/价格/合规/政策/竞品/方法论），是分身回答「是什么」的依据。只有「已生效」且未过期、已完成索引的知识会被检索；相似度阈值 0.45，每次最多取 3 条。"
              steps={[
                { title: '录入', desc: '少量知识用「新增知识」手填；整份资料用「上传文档萃取」。书籍/课程要选对应类型，系统只提炼方法论卡片并标书名章节。' },
                { title: '审核通过', desc: '草稿和待审核的知识点点「通过」并设置失效时间；批量通过默认 1 年有效。通过后还要等索引完成。' },
                { title: '确认可检索', desc: '状态旁的索引徽标变「已索引」才会被分身引用；长期异常点「重建索引」。可用「检索测试」验证某个问题实际会命中什么。' },
                { title: '日常维护', desc: '点标题看全文与被引用情况；「AI改写」按指令改内容：草稿/待审直接覆盖，已生效会生成新版本草稿，通过后旧版下线。' },
                { title: '下架', desc: '过期或错误的知识点「下架」，分身立即停止引用；30 天内可重新上架。彻底删除不可恢复，仅草稿/待审/已下架可用。' },
              ]}
              actions={[
                { label: '新增知识', desc: '打开手填表单。新建默认是待审核状态（部分入口是草稿），需要通过后才生效。', tone: 'primary' },
                { label: '上传文档萃取', desc: '上传 Word/PDF/PPT/表格等，系统解析并提炼成多条知识草稿；先出草稿，不会直接生效。' },
                { label: '检索测试', desc: '只测知识。输入客户原话，查看实际命中的知识与相似度；标绿=会进入分身回答，标黄=近失命中、未达阈值，不会引用。案例请在案例库页测。' },
                { label: '企业私有 / 公共知识', desc: '切换检索范围。公共知识由平台维护，企业只能复制；企业私有只在本企业检索。' },
                { label: '通过 / 批量通过', desc: '把草稿/待审变为已生效，可设置失效时间；批量通过默认 1 年有效，原有失效时间会保留。' },
                { label: '复制', desc: '仅公共知识出现。复制一份企业私有内容后再修改，不影响公共原文。' },
                { label: '重建索引', desc: '已生效但索引失败/未完成时出现。异步重跑向量化，稍后刷新看徽标。' },
                { label: 'AI改写', desc: '按指令重写内容。草稿/待审直接覆盖；已生效生成新版本草稿，通过后旧版才下线。' },
                { label: '下架 / 批量下架', desc: '立即停止被引用，30 天内可重新上架；不会删除记录。', tone: 'danger' },
                { label: '批量改分类', desc: '选中多条后统一改分类；逐条执行，失败项会列出原因。' },
                { label: 'AI 生成', desc: '筛选 AI 生成的条目，用来复核内容工厂产出的草稿。' },
                { label: '待我审', desc: '快捷筛选待审核数量；数字来自当前企业私有/公共范围。' },
              ]}
              notes={[
                '审核 ≠ 立刻可检索：通过后还要等索引徽标变成「已索引」，否则分身仍然引用不到。',
                '准入标准：标题 4–60 字且不能只是章节名；正文普通 ≥10 字（建议 40+）、书籍 60–600 字，超过 1500 字请拆条；分类必须合法。',
                '公共知识由平台维护，企业要改先「复制」到本企业；企业内容「提交到公共库」前会自动强脱敏。',
                '上传自动脱敏：手机号/身份证/银行卡/地址/合同编号/姓名+职务会打码；书籍与公共库还会隐去公司名、项目名和具体金额。',
                '标题写「场景 + 结论」，正文补具体数字与适用条件，检索命中率最高。',
              ]}
            />
            <Button variant="neutral" onClick={() => setTestOpen(true)}>
              检索测试
            </Button>
            <Button variant="secondary" onClick={() => router.push('/admin/knowledge/new?tab=upload')}>
              上传文档萃取
            </Button>
            <Button variant="primary" onClick={() => router.push('/admin/knowledge/new')}>
              ＋ 新增知识
            </Button>
          </div>
        </div>

        <LibraryStats
          items={[
            { key: 'all', label: '全部内容', value: counts.all, active: !status, onClick: () => { setStatus(''); setPage(1); } },
            { key: 'pending', label: '待审核', value: counts.pending, tone: 'warning', active: status === 'pending', onClick: () => { setStatus('pending'); setPage(1); } },
            { key: 'active', label: '已生效', value: counts.active, tone: 'success', active: status === 'active', onClick: () => { setStatus('active'); setPage(1); } },
            { key: 'draft', label: '草稿', value: counts.draft, active: status === 'draft', onClick: () => { setStatus('draft'); setPage(1); } },
          ]}
        />

        <FlowSteps
          compact
          className="mb-4"
          steps={[
            { id: 'input', title: '录入', desc: '', status: counts.all > 0 ? 'done' : 'current', onClick: () => router.push('/admin/knowledge/new') },
            { id: 'review', title: '审核', desc: '', status: counts.pending > 0 ? 'current' : counts.all > 0 ? 'done' : 'todo', onClick: () => { setStatus('pending'); setPage(1); } },
            { id: 'searchable', title: '可检索', desc: '', status: counts.active > 0 ? 'done' : counts.all > 0 ? 'current' : 'todo', onClick: () => { setStatus('active'); setPage(1); } },
            { id: 'maintain', title: '维护', desc: '', status: counts.active > 0 ? 'current' : 'todo' },
          ]}
        />

        <ReviewFlowNote collapsible className="mb-4" kind="knowledge" />

        {/* 工具栏：范围 + 搜索 + 筛选，收敛到一个卡片里 */}
        <div className="mb-4 rounded-xl border border-border bg-surface p-3" data-tour="knowledge-tabs">
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex rounded-lg bg-surface-2 p-0.5">
              {(['private', 'public'] as const).map(t => (
                <button
                  key={t}
                  onClick={() => { setTab(t); setPage(1); }}
                  className={`rounded-md px-3.5 py-1.5 text-[13px] font-medium transition-colors ${
                    tab === t ? 'bg-surface text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {t === 'private' ? '企业私有' : '公共知识'}
                </button>
              ))}
            </div>

            <div className="ml-auto flex flex-wrap items-center gap-2">
              <input
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') setPage(1); }}
                placeholder="搜索标题或正文…"
                className="h-9 w-64 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground"
              />
              <Button variant="primary" onClick={() => setPage(1)}>搜索</Button>
              <Button variant="neutral" onClick={() => setFiltersOpen(v => !v)}>
                筛选{activeFilterCount > 0 ? ` ${activeFilterCount}` : ''}
              </Button>
            </div>
          </div>

          {filtersOpen && (
            <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border pt-3">
              <select value={category} onChange={e => { setCategory(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部分类</option>
                {Object.entries(CATEGORY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              <select value={sourceType} onChange={e => { setSourceType(e.target.value); setPage(1); }}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-3 text-sm text-foreground">
                <option value="">全部来源</option>
                {Object.entries(SOURCE_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
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
                  onClick={() => { setCategory(''); setSourceType(''); setStatus(''); setAiOnly(false); setPage(1); }}
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

        <RevisionModal
          open={!!revisionTarget}
          title="AI 改写知识"
          behaviorNote={revisionTarget?.status === 'active'
            ? '已生效内容：生成新版本草稿，审核通过后才替换旧版。'
            : '草稿/待审内容：直接覆盖当前条目，不额外生成版本。'}
          submitLabel={revisionTarget?.status === 'active' ? '生成新版本草稿' : '直接改写'}
          entryTitle={revisionTarget?.title || ''}
          currentContent={revisionTarget ? `${revisionTarget.title}\n\n${revisionTarget.content || ''}` : ''}
          presets={[
            '压缩到 300 字以内',
            '补充适用边界和不适用场景',
            '改成分点表述，便于检索',
            '补充具体数字或条款',
          ]}
          onLoadSuggestions={() => getKnowledgeImprovements(revisionTarget!.id)}
          onSubmit={async instruction => {
            const draft = await suggestKnowledgeRevision(revisionTarget!.id, instruction);
            setRevisionTarget(null);
            router.push(`/admin/knowledge/${draft.id}`);
          }}
          onClose={() => setRevisionTarget(null)}
        />

        <ExpiryDialog
          open={!!approveTarget}
          title="通过并生效"
          description={`「${approveTarget?.title || ''}」通过后会被分身检索，请选择失效时间。`}
          onCancel={() => setApproveTarget(null)}
          onConfirm={iso => {
            const target = approveTarget;
            setApproveTarget(null);
            if (target) runAction(() => approveKnowledge(target.id, iso), '已通过并生效');
          }}
        />

        {toast && (
          <div className="fixed bottom-6 left-1/2 z-[70] -translate-x-1/2 rounded-full bg-foreground px-4 py-2 text-sm text-surface shadow-lg">
            {toast}
          </div>
        )}

        {selected.size > 0 && (
          <div className="sticky top-2 z-30 mb-3 flex flex-wrap items-center gap-2 rounded-xl border border-primary/40 bg-primary-light px-4 py-2.5 shadow-sm">
            <span className="text-sm font-semibold text-primary">已选 {selected.size} 条</span>
            <Button variant="primary" size="sm" disabled={acting}
              onClick={() => runBatch('approve')}
              title="批量通过选中的草稿/待审知识；未设置失效时间的默认 1 年有效">
              批量通过
            </Button>
            <Button variant="neutral" size="sm" disabled={acting}
              onClick={() => runBatch('reindex')}
              title="逐条异步重跑向量索引；只有已生效条目会成功">
              批量重建索引
            </Button>
            <div className="flex items-center gap-1.5">
              <select
                value={batchCategory}
                onChange={e => setBatchCategory(e.target.value)}
                className="h-8 rounded-[10px] border border-border-strong bg-surface px-2 text-[13px] text-foreground"
                title="选择要批量应用的新分类"
              >
                <option value="">改分类为…</option>
                {Object.entries(CATEGORY_LABELS).map(([k, v]) => (
                  <option key={k} value={k}>{v}</option>
                ))}
              </select>
              <Button
                variant="neutral"
                size="sm"
                disabled={!batchCategory || acting}
                onClick={() => { void runBatch('category', batchCategory); }}
                title="只改分类，不动标题和正文"
              >
                应用
              </Button>
            </div>
            <Button variant="danger" size="sm" disabled={acting}
              onClick={() => runBatch('deprecate')}
              title="批量下架；30 天内可重新上架">
              批量下架
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>
              取消选择
            </Button>
            {tab === 'public' && !isSuperAdmin && (
              <span className="ml-auto text-[11px] text-warning-text">
                公共知识只读；批量操作会自动跳过，需先复制到本企业
              </span>
            )}
            {acting && <span className="ml-auto text-[11px] text-muted-foreground">处理中…</span>}
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <>
            {tab === 'public' && (
              <p className="mb-3 text-xs text-muted-foreground">
                公共知识由平台维护：企业可「复制」到本企业修改；企业私有知识可在详情页「提交到公共库」，由平台审核后生效。
              </p>
            )}
            {entries.length === 0 ? (
              <div className="py-20 text-center">
                <span className="text-5xl">{keyword || status || category || sourceType ? '🔍' : '📖'}</span>
                <h2 className="mt-4 text-lg font-semibold text-foreground">
                  {keyword || status || category || sourceType ? '没有匹配的知识' : '暂无知识'}
                </h2>
                <p className="mt-2 text-sm text-muted-foreground">
                  {keyword || status || category || sourceType
                    ? '换个关键词或清空筛选条件再试。'
                    : tab === 'public'
                    ? (isSuperAdmin
                        ? '公共知识对所有企业可见，但现在一条都没有。建议先补一批通用销售知识（价格异议、竞品对比、合规话术等）。'
                        : '公共知识由平台运营维护。企业可以先用「新增知识」维护自己的知识，或在公共库有内容后复制到本企业。')
                    : '还没有企业私有知识，点击「新增知识」或「上传文档萃取」开始。'}
                </p>
                <div className="mt-5 flex justify-center gap-3">
                  {tab === 'public' && isSuperAdmin && (
                    <button onClick={() => router.push('/admin/knowledge/new')}
                      className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90">
                      新增公共知识
                    </button>
                  )}
                  {tab === 'public' && !isSuperAdmin && (
                    <button onClick={() => setTab('private')}
                      className="rounded-full border border-border px-5 py-2.5 text-sm text-foreground hover:bg-surface-2">
                      查看企业私有知识
                    </button>
                  )}
                  {tab === 'private' && (
                    <>
                      <button onClick={() => router.push('/admin/knowledge/new')}
                        className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90">
                        新增知识
                      </button>
                      <button onClick={() => router.push('/admin/knowledge/new?tab=upload')}
                        className="rounded-full border border-primary px-5 py-2.5 text-sm font-medium text-primary hover:bg-primary-light">
                        上传文档萃取
                      </button>
                    </>
                  )}
                </div>
              </div>
            ) : (
              <div className="rounded-xl border border-border bg-surface-2">
                <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border items-center">
                  <span className="col-span-1">
                    <input
                      type="checkbox"
                      aria-label="全选本页"
                      checked={entries.length > 0 && selected.size === entries.length}
                      onChange={toggleAll}
                      className="h-4 w-4 accent-primary"
                    />
                  </span>
                  <span className="col-span-5">标题</span>
                  <span className="col-span-2">分类</span>
                  <span className="col-span-2">状态</span>
                  <span className="col-span-2 text-right">操作</span>
                </div>
                {entries.map(e => {
                  const st = effectiveStatus(e.status, e.expireAt);
                  const canWrite = isSuperAdmin || e.companyId !== null;
                  return (
                    <div key={e.id} className={`grid grid-cols-12 gap-2 px-5 py-3.5 border-b border-border last:border-0 items-center transition-colors ${selected.has(e.id) ? 'bg-primary-light' : 'hover:bg-primary-light'}`}>
                      <span className="col-span-1">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${e.title}`}
                          checked={selected.has(e.id)}
                          onChange={() => toggleSelect(e.id)}
                          className="h-4 w-4 accent-primary"
                        />
                      </span>
                      <div className="col-span-5 min-w-0">
                        <button
                          onClick={() => router.push(`/admin/knowledge/${e.id}`)}
                          className="block w-full truncate text-left text-sm font-medium text-foreground hover:text-primary hover:underline"
                          title="查看全文"
                        >
                          {e.title}
                        </button>
                        <p className="mt-0.5 truncate text-xs text-muted-foreground" title={e.content || ''}>
                          {e.content || e.source || '—'}
                          <span className="ml-2 text-muted-foreground-2">
                            {SOURCE_TYPE_LABELS[e.sourceType] || e.sourceType}
                            {isSuperAdmin && ` · ${e.companyName || '公共'}`}
                          </span>
                        </p>
                      </div>
                      <span className="col-span-2 text-sm text-foreground">{CATEGORY_LABELS[e.category] || e.category}</span>
                      <span className="col-span-2 flex flex-wrap items-center gap-1.5">
                        <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_CLS[st] || 'bg-gray-100 text-gray-600'}`}>
                          {STATUS_LABELS[st] || st}
                        </span>
                        {st === 'active' && <IndexStatusBadge status={e.embeddingStatus} title={e.indexError || undefined} />}
                        {e.companyId === null && <span className="text-[10px] text-muted-foreground">公共</span>}
                        {e.sourceCompanyId && <span className="text-[10px] text-muted-foreground">企业提交</span>}
                        {e.generated && <span className="text-[10px] text-primary">AI 生成</span>}
                      </span>
                      <div className="col-span-2 flex items-center justify-end gap-2">
                        {canWrite && (e.status === 'pending' || e.status === 'draft') && (
                          <button onClick={() => onApprove(e)} disabled={acting} className="text-xs text-primary font-medium hover:underline">通过</button>
                        )}
                        {e.companyId === null && !isSuperAdmin && (
                          <button onClick={() => onCopy(e)} disabled={acting} className="text-xs text-primary font-medium hover:underline">复制</button>
                        )}
                        {canWrite ? (
                          <div className="relative">
                            <button
                              onClick={ev => { ev.stopPropagation(); setMenuFor(menuFor === e.id ? null : e.id); }}
                              className="text-xs text-muted-foreground hover:text-foreground hover:underline">更多</button>
                            {menuFor === e.id && (
                              <div className="absolute right-0 top-5 z-20 w-28 rounded-md border border-border bg-surface py-1 shadow-lg">
                                {st === 'active' && e.embeddingStatus !== 'indexed' && (
                                  <button onClick={() => { setMenuFor(null); onReindex(e); }} disabled={acting}
                                    className="block w-full px-3 py-1.5 text-left text-xs text-foreground hover:bg-primary-light disabled:opacity-50">重建索引</button>
                                )}
                                {e.status !== 'deprecated' && (
                                  <button onClick={() => { setMenuFor(null); onSuggest(e); }} disabled={acting}
                                    className="block w-full px-3 py-1.5 text-left text-xs text-foreground hover:bg-primary-light disabled:opacity-50">AI 改写</button>
                                )}
                                {e.status !== 'deprecated' && (
                                  <button onClick={() => { setMenuFor(null); onDeprecate(e); }} disabled={acting}
                                    className="block w-full px-3 py-1.5 text-left text-xs text-danger hover:bg-danger-bg disabled:opacity-50">下架</button>
                                )}
                                {st !== 'active' && e.status === 'deprecated' && (
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

            {/* 分页 */}
            <Pagination page={page} totalPages={totalPages} totalElements={total} onPageChange={setPage} loading={loading} />
          </>
        )}

        <RetrievalTestModal open={testOpen} scope="knowledge" onClose={() => setTestOpen(false)} />
      </div>
    </div>
  );
}
