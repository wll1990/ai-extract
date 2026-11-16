'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import {
  listKnowledge, deprecateKnowledge, approveKnowledge, copyKnowledge, suggestKnowledgeRevision,
  reindexKnowledge, batchKnowledge, retrievalTest,
  getKnowledgeImprovements,
  effectiveStatus, CATEGORY_LABELS, SOURCE_TYPE_LABELS, STATUS_LABELS,
  type KnowledgeEntry, type RetrievalTestResult,
} from '@/lib/api/knowledge';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { RevisionModal } from '@/components/ui/RevisionModal';
import { IndexStatusBadge } from '@/components/ui/IndexStatusBadge';
import { getCurrentUser } from '@/lib/api/auth';
import { ApiError } from '@/lib/api/client';
import { ExpiryDialog } from '@/components/admin/ExpiryDialog';

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
  const [approveTarget, setApproveTarget] = useState<KnowledgeEntry | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [pendingCount, setPendingCount] = useState(0);

  // 检索测试：验证「某个问题会命中哪些知识/案例」，用于排查为什么分身没引用
  const [testOpen, setTestOpen] = useState(false);
  const [testQuery, setTestQuery] = useState('');
  const [testResult, setTestResult] = useState<RetrievalTestResult | null>(null);
  const [testing, setTesting] = useState(false);
  const runRetrievalTest = async () => {
    if (!testQuery.trim()) return;
    setTesting(true);
    try {
      setTestResult(await retrievalTest(testQuery.trim(), 5));
    } catch (e) {
      alert(e instanceof Error ? e.message : '检索测试失败');
    } finally {
      setTesting(false);
    }
  };

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listKnowledge({ category: category || undefined, sourceType: sourceType || undefined, status: status || undefined, keyword: keyword || undefined, scope: tab, aiGenerated: aiOnly || undefined, page, size: 20 })
      .then(data => {
        setEntries(data.content || []);
        setTotal(data.total || 0);
        setSelected(new Set());
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
    // 待审核数量：列表页给运营一个可直接点的入口
    listKnowledge({ status: 'pending', scope: tab, page: 1, size: 1 })
      .then(d => setPendingCount(d.total || 0))
      .catch(() => setPendingCount(0));
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
    // 已有失效时间的直接审批；没有的弹日期选择器（不再手输 ISO）
    if (e.expireAt) {
      runAction(() => approveKnowledge(e.id, e.expireAt || undefined), '已审批生效');
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
    if (action === 'deprecate' && !window.confirm(`确认下架选中的 ${selected.size} 条知识？30 天内可重新上架。`)) return;
    setActing(true);
    try {
      const r = await batchKnowledge(action, Array.from(selected), category);
      await load();
      if (r.failed > 0) {
        alert(`成功 ${r.success} 条，失败 ${r.failed} 条：\n` + r.failures.slice(0, 5).map(f => f.message).join('\n'));
      } else {
        setToast(action === 'approve'
          ? `已审批 ${r.success} 条（未设置失效时间的默认 1 年有效）`
          : `已处理 ${r.success} 条`);
      }
    } catch (e) {
      alert(e instanceof Error ? e.message : '批量操作失败');
    } finally {
      setActing(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / 20));

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">知识库</h1>
            <p className="text-sm text-muted-foreground">事实型知识（产品/价格/合规/术语/方法论），支持 AI 改写与人工确认</p>
          </div>
          <div className="flex gap-3">
            <PageHelp
              title="知识库使用说明"
              intro="知识库存事实（产品/价格/合规/政策/竞品/方法论），是分身回答「是什么」的依据。只有「已生效」且未过期的知识会被检索，相似度阈值 0.35，每次最多取 3 条。"
              steps={[
                { title: '新增或上传', desc: '少量知识用「新增知识」手填；整份资料用「上传文档萃取」。书籍/课程要选对应类型，系统只提炼方法论卡片并标书名章节。' },
                { title: '检查草稿', desc: '萃取先产草稿：质量分绿色可直接采纳，黄色建议补边界，红色有阻断项（正文过短、缺出处等）必须点「补齐」才能采纳。' },
                { title: '审批生效', desc: '草稿和待审核的知识点「审批」并设置失效时间；批量审批默认 1 年有效。过期后自动停止引用。' },
                { title: '查看与改写', desc: '点标题看全文与被引用情况；「AI改写」按你的指令改内容：草稿/待审直接覆盖，已生效会生成新版本草稿，审批后旧版下线。' },
                { title: '下架', desc: '过期或错误的知识点「下架」，分身立即停止引用；30 天内可重新上架。彻底删除不可恢复，仅草稿/待审/已下架可用。' },
              ]}
              notes={[
                '准入标准：标题 4–60 字且不能只是章节名；正文普通 ≥10 字（建议 40+）、书籍 60–600 字，超过 1500 字请拆条；分类必须合法。',
                '公共知识由平台维护，企业要改先「复制」到本企业；企业内容「提交到公共库」前会自动强脱敏。',
                '上传自动脱敏：手机号/身份证/银行卡/地址/合同编号/姓名+职务会打码；书籍与公共库还会隐去公司名、项目名和具体金额。',
                '标题写「场景 + 结论」，正文补具体数字与适用条件，检索命中率最高。',
              ]}
            />
            <button
              onClick={() => router.push('/admin/knowledge/new')}
              className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90 transition-opacity"
            >
              ＋ 新增知识
            </button>
            <button
              onClick={() => router.push('/admin/knowledge/new?tab=upload')}
              className="rounded-full border border-primary px-5 py-2.5 text-sm font-medium text-primary hover:bg-primary-light transition-colors"
            >
              上传文档萃取
            </button>
            <button onClick={() => setTestOpen(true)}
              className="rounded-full border border-border px-5 py-2.5 text-sm text-foreground hover:bg-surface-2 transition-colors">
              检索测试
            </button>
          </div>
        </div>

        {/* Tab */}
        <div className="mb-4 flex gap-1 rounded-lg bg-surface-2 p-1 w-fit" data-tour="knowledge-tabs">
          {(['private', 'public'] as const).map(t => (
            <button
              key={t}
              onClick={() => { setTab(t); setPage(1); }}
              className={`rounded-md px-4 py-1.5 text-sm font-medium transition-colors ${
                tab === t ? 'bg-surface text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              {t === 'private' ? '企业私有' : '公共知识'}
            </button>
          ))}
        </div>

        {/* KN-08 批量操作条 */}
        {selected.size > 0 && (
          <div className="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-primary/30 bg-primary-light px-4 py-3">
            <span className="text-sm font-medium text-foreground">已选 {selected.size} 条</span>
            <button disabled={acting} onClick={() => runBatch('approve')}
              className="rounded-full bg-primary px-4 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50">审批通过</button>
            <button disabled={acting} onClick={() => runBatch('reindex')}
              className="rounded-full border border-border bg-surface px-4 py-1.5 text-xs text-foreground hover:bg-surface-2 disabled:opacity-50">重建索引</button>
            <select
              disabled={acting}
              defaultValue=""
              onChange={e => { const v = e.target.value; if (v) { runBatch('category', v); e.currentTarget.value = ''; } }}
              className="rounded-full border border-border bg-surface px-3 py-1.5 text-xs text-foreground disabled:opacity-50"
            >
              <option value="" disabled>批量改分类</option>
              {Object.entries(CATEGORY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
            <button disabled={acting} onClick={() => runBatch('deprecate')}
              className="rounded-full border border-danger/40 bg-surface px-4 py-1.5 text-xs text-danger hover:bg-danger-bg disabled:opacity-50">下架</button>
            <button onClick={() => setSelected(new Set())}
              className="ml-auto text-xs text-muted-foreground hover:text-foreground">取消选择</button>
          </div>
        )}

        {/* 筛选 */}
        <div className="mb-4 flex flex-wrap gap-2">
          <select value={category} onChange={e => { setCategory(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部分类</option>
            {Object.entries(CATEGORY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <select value={sourceType} onChange={e => { setSourceType(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部来源</option>
            {Object.entries(SOURCE_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部状态</option>
            <option value="draft">草稿</option>
            <option value="pending">待审核</option>
            <option value="active">已生效（未过期）</option>
            <option value="expired">已过期</option>
            <option value="deprecated">已下架</option>
          </select>
          <input value={keyword} onChange={e => setKeyword(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') setPage(1); }}
            placeholder="搜索标题或正文…"
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground w-56" />
          <button onClick={() => setPage(1)} className="rounded-md bg-primary px-4 py-2 text-sm text-white hover:opacity-90">搜索</button>
          <button onClick={() => { setAiOnly(v => !v); setPage(1); }}
            className={`rounded-full px-3 py-2 text-xs font-medium ${
              aiOnly ? 'bg-primary text-white' : 'border border-border text-muted-foreground hover:bg-primary-light'}`}>
            AI 生成
          </button>
          {pendingCount > 0 && (
            <button onClick={() => { setStatus('pending'); setPage(1); }}
              className={`rounded-full px-3 py-2 text-xs font-medium ${status === 'pending' ? 'bg-warning-bg text-warning-text' : 'border border-warning-text/40 text-warning-text hover:bg-warning-bg'}`}>
              待我审 {pendingCount}
            </button>
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
            ? '已生效内容：生成新版本草稿，审批通过后才替换旧版。'
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
          title="审批生效"
          description={`「${approveTarget?.title || ''}」审批后会被分身检索，请选择失效时间。`}
          onCancel={() => setApproveTarget(null)}
          onConfirm={iso => {
            const target = approveTarget;
            setApproveTarget(null);
            if (target) runAction(() => approveKnowledge(target.id, iso), '已审批生效');
          }}
        />

        {toast && (
          <div className="fixed bottom-6 left-1/2 z-[70] -translate-x-1/2 rounded-full bg-foreground px-4 py-2 text-sm text-surface shadow-lg">
            {toast}
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
              <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
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
                  <span className="col-span-3">标题</span>
                  <span className="col-span-2">分类</span>
                  <span className="col-span-2">来源</span>
                  <span className="col-span-2">状态</span>
                  <span className="col-span-2 text-right">操作</span>
                </div>
                {entries.map(e => {
                  const st = effectiveStatus(e.status, e.expireAt);
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
                      <div className="col-span-3 min-w-0">
                        <button
                          onClick={() => router.push(`/admin/knowledge/${e.id}`)}
                          className="block w-full truncate text-left text-sm font-medium text-foreground hover:text-primary hover:underline"
                          title="查看全文"
                        >
                          {e.title}
                        </button>
                        <p className="truncate text-xs text-muted-foreground" title={e.content || ''}>
                          {e.content || e.source || '—'}
                        </p>
                      </div>
                      <span className="col-span-2 text-sm text-foreground">{CATEGORY_LABELS[e.category] || e.category}</span>
                      <span className="col-span-2 text-sm text-muted-foreground">
                        {SOURCE_TYPE_LABELS[e.sourceType] || e.sourceType}
                        {isSuperAdmin && (
                          <span className="block truncate text-[10px]" title={e.companyName || '公共知识'}>
                            {e.companyName || '公共'}
                          </span>
                        )}
                      </span>
                      <span className="col-span-2 flex items-center gap-1.5">
                        <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_CLS[st] || 'bg-gray-100 text-gray-600'}`}>
                          {STATUS_LABELS[st] || st}
                        </span>
                        {st === 'active' && <IndexStatusBadge status={e.embeddingStatus} title={e.indexError || undefined} />}
                        {e.sourceCompanyId && <span className="text-[10px] text-muted-foreground">企业提交</span>}
                        {e.generated && <span className="text-[10px] text-primary">AI 生成</span>}
                      </span>
                      <div className="col-span-2 flex justify-end gap-2">
                        {(e.status === 'pending' || e.status === 'draft') && (
                          <button onClick={() => onApprove(e)} disabled={acting} className="text-xs text-primary font-medium hover:underline">审批</button>
                        )}
                        {e.companyId === null && tab === 'public' && (
                          <button onClick={() => onCopy(e)} disabled={acting} className="text-xs text-primary font-medium hover:underline">复制</button>
                        )}
                        {st === 'active' && e.embeddingStatus !== 'indexed' && (
                          <button onClick={() => onReindex(e)} disabled={acting} className="text-xs text-primary font-medium hover:underline">重建索引</button>
                        )}
                        {e.status !== 'deprecated' && (
                          <button onClick={() => onSuggest(e)} disabled={acting} className="text-xs text-muted-foreground hover:underline">AI改写</button>
                        )}
                        {e.status !== 'deprecated' && (
                          <button onClick={() => onDeprecate(e)} disabled={acting} className="text-xs text-danger hover:underline">下架</button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {/* 分页 */}
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

        {testOpen && (
          <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-20" onClick={() => setTestOpen(false)}>
            <div className="max-h-[80vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-surface p-6 shadow-xl" onClick={e => e.stopPropagation()}>
              <div className="mb-3 flex items-start justify-between">
                <div>
                  <h2 className="text-lg font-bold text-foreground">检索测试</h2>
                  <p className="mt-1 text-xs text-muted-foreground">
                    输入一个客户会问的问题，看实际命中的知识与案例。低于 0.35（知识）/ 0.50（案例）的内容不会进入分身回答。
                  </p>
                </div>
                <button onClick={() => setTestOpen(false)} className="text-sm text-muted-foreground hover:text-foreground">关闭</button>
              </div>
              <div className="mb-4 flex gap-2">
                <input value={testQuery} onChange={e => setTestQuery(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') runRetrievalTest(); }}
                  placeholder="例如：客户说预算没批怎么办"
                  className="flex-1 rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                <button onClick={runRetrievalTest} disabled={testing || !testQuery.trim()}
                  className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                  {testing ? '检索中…' : '检索'}
                </button>
              </div>
              {testResult && (
                <div className="space-y-4 text-sm">
                  <div>
                    <p className="mb-1 text-xs font-semibold text-foreground">知识命中（{testResult.knowledge.length}）</p>
                    {testResult.knowledge.length === 0 ? (
                      <p className="text-xs text-muted-foreground">没有命中：检查是否已生效、是否过期，或标题/正文缺少场景与具体信息。</p>
                    ) : testResult.knowledge.map(h => (
                      <div key={h.id} className="mb-1 rounded-md bg-surface-2 px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="rounded-full bg-success-bg px-2 py-0.5 text-[11px] text-success">相似度 {h.similarity.toFixed(2)}</span>
                          <span className="text-foreground">{h.title}</span>
                        </div>
                        {h.source && <p className="mt-0.5 text-[11px] text-muted-foreground">出处：{h.source}</p>}
                      </div>
                    ))}
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold text-foreground">案例命中（{testResult.cases.length}）</p>
                    {testResult.cases.length === 0 ? (
                      <p className="text-xs text-muted-foreground">没有命中：案例阈值更高，补客户画像、场景标签和客户原话通常能提升命中。</p>
                    ) : testResult.cases.map(h => (
                      <div key={h.id} className="mb-1 rounded-md bg-surface-2 px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="rounded-full bg-success-bg px-2 py-0.5 text-[11px] text-success">相似度 {h.similarity.toFixed(2)}</span>
                          <span className="text-foreground">{h.title}</span>
                        </div>
                        {h.objection && <p className="mt-0.5 text-[11px] text-muted-foreground">异议：{h.objection}</p>}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
