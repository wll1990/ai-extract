'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getCase, getCaseRevisions, approveCase, deprecateCase, deleteCase, copyCase, suggestCaseRevision,
  getCaseImprovements, reindexCase, submitCaseToPublic, restoreCase,
  getCaseVersions, rollbackCase, getCaseReferences, getCaseSourceFile,
  effectiveStatus, STATUS_LABELS, type IndustryCase, type ReferenceItem,
} from '@/lib/api/knowledge';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { RevisionModal } from '@/components/ui/RevisionModal';
import { IndexStatusBadge } from '@/components/ui/IndexStatusBadge';
import { diffLines } from '@/lib/diff';
import { getContentHealth, deriveContent, pollContentJob, type ContentHealth, type DerivedContent } from '@/lib/api/content';
import { EvidencePanel } from '@/components/admin/EvidencePanel';
import { ExpiryDialog } from '@/components/admin/ExpiryDialog';
import { DerivedContentModal } from '@/components/admin/DerivedContentModal';

const PROFILE_LABELS: Record<string, string> = { industry: '行业', scale: '规模', role: '关键角色' };

/** 客户画像 JSON → 易读文本；非 JSON 原样返回 */
function formatProfile(raw: string | null): string {
  if (!raw) return '';
  try {
    const v = JSON.parse(raw);
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      return Object.entries(v)
        .map(([k, val]) => `${PROFILE_LABELS[k] || k}：${val}`)
        .join(' · ');
    }
  } catch { /* 非 JSON */ }
  return raw;
}

/** 客户原话：JSON 数组 → 列表；非 JSON 原样一条 */
function parseQuotes(raw: string | null): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    if (Array.isArray(v)) return v.map(String);
  } catch { /* 非 JSON */ }
  return [raw];
}

const RESULT_LABELS: Record<string, string> = { win: '赢单', loss: '丢单', delay: '搁置' };

const STATUS_CLS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-600',
  pending: 'bg-warning-bg text-warning-text',
  active: 'bg-success-bg text-success',
  deprecated: 'bg-gray-100 text-gray-500',
  expired: 'bg-danger-bg text-danger',
};

/** 可被 AI 整理/人工修订的文本字段 */
const DIFF_FIELDS: Array<{ key: keyof IndustryCase; label: string }> = [
  { key: 'title', label: '标题' },
  { key: 'customerProfile', label: '客户画像' },
  { key: 'objection', label: '核心异议' },
  { key: 'emotionChange', label: '情绪变化' },
  { key: 'keyActions', label: '关键动作' },
  { key: 'scripts', label: '关键话术' },
  { key: 'advanceNodes', label: '推进节点' },
  { key: 'reusablePoints', label: '可复用点' },
  { key: 'failurePoints', label: '失败点' },
];

export default function AdminCaseDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [c, setC] = useState<IndustryCase | null>(null);
  const [previous, setPrevious] = useState<IndustryCase | null>(null);
  const [revisions, setRevisions] = useState<IndustryCase[]>([]);
  const [versions, setVersions] = useState<IndustryCase[]>([]);
  const [refs, setRefs] = useState<ReferenceItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [showRevision, setShowRevision] = useState(false);
  const [health, setHealth] = useState<ContentHealth | null>(null);
  const [healthOpen, setHealthOpen] = useState(false);
  const [derived, setDerived] = useState<DerivedContent | null>(null);
  const [deriveOpen, setDeriveOpen] = useState(false);
  const [deriving, setDeriving] = useState(false);
  const [showApprove, setShowApprove] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const current = await getCase(id);
      setC(current);
      if (current.supersedesId) {
        getCase(current.supersedesId).then(setPrevious).catch(() => setPrevious(null));
      } else {
        setPrevious(null);
      }
      getCaseRevisions(current.id).then(setRevisions).catch(() => setRevisions([]));
      getCaseVersions(current.id).then(setVersions).catch(() => setVersions([]));
      getCaseReferences(current.id).then(setRefs).catch(() => setRefs([]));
    } catch {
      setError('加载失败，请确认有权限');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const openSourceFile = useCallback(async () => {
    try {
      const file = await getCaseSourceFile(id);
      window.open(file.url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      alert(e instanceof Error ? e.message : '原始文档不可用');
    }
  }, [id]);

  const onHealth = async () => {
    if (!c) return;
    try {
      setHealth(await getContentHealth('case', c.id));
      setHealthOpen(true);
    } catch (e) {
      alert(e instanceof Error ? e.message : '体检失败');
    }
  };

  const onDerive = async () => {
    if (!c) return;
    setDeriving(true);
    try {
      const accepted = await deriveContent('case', c.id);
      const job = await pollContentJob(accepted.jobId);
      if (job.status === 'failed') {
        alert(job.errorMessage || '派生失败');
        return;
      }
      setDerived((job.result ?? null) as DerivedContent | null);
      setDeriveOpen(true);
    } catch (e) {
      alert(e instanceof Error ? e.message : '派生失败');
    } finally {
      setDeriving(false);
    }
  };

  const runAction = async (fn: () => Promise<unknown>) => {
    setActing(true);
    try {
      await fn();
      await load();
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const onApprove = () => {
    if (!c) return;
    if (c.expireAt) {
      runAction(() => approveCase(c.id, c.expireAt || undefined));
      return;
    }
    setShowApprove(true);
  };

  const onDeprecate = () => {
    if (!c) return;
    const impacted = refs.length > 0
      ? `\n最近 30 天有 ${refs.length} 个分身引用过它，下架后将不再命中。`
      : '';
    if (!window.confirm(`确认下架「${c.title}」？下架后分身立即停止引用，30 天内可重新上架。${impacted}`)) return;
    runAction(() => deprecateCase(c.id));
  };

  const onRollback = (v: IndustryCase) => {
    if (!c) return;
    if (!window.confirm(`以 v${v.version} 的内容生成新草稿？当前版本不会被修改，新草稿审批后才会生效。`)) return;
    runAction(async () => {
      const draft = await rollbackCase(c.id, v.id);
      router.push(`/admin/cases/${draft.id}`);
    });
  };

  const onReindex = () => {
    if (!c) return;
    runAction(() => reindexCase(c.id));
  };

  const onRestore = () => {
    if (!c) return;
    if (!window.confirm('重新上架这条案例？会回到下架前的状态：原本生效的重新索引并参与检索，草稿/待审的回到待处理状态。')) return;
    runAction(() => restoreCase(c.id));
  };

  const onDelete = async () => {
    if (!c) return;
    if (!window.confirm(`彻底删除「${c.title}」？\n\n删除后不可恢复，版本链和引用记录也不会再关联到这条案例。`)) return;
    setActing(true);
    try {
      await deleteCase(c.id);
      router.push('/admin/cases');
    } catch (e) {
      alert(e instanceof Error ? e.message : '删除失败');
      setActing(false);
    }
  };

  const onSubmitPublic = () => {
    if (!c) return;
    if (!window.confirm('提交到公共库？平台审核通过后，所有企业都能检索到这条案例（请先确认不含客户敏感信息）。')) return;
    runAction(() => submitCaseToPublic(c.id));
  };

  const onCopy = () => {
    if (!c) return;
    runAction(() => copyCase(c.id));
  };

  const onDiscardDraft = () => {
    if (!c) return;
    if (!window.confirm('放弃这个修订版本？原版本不受影响。')) return;
    runAction(async () => {
      await deprecateCase(c.id);
      router.push(c.supersedesId ? `/admin/cases/${c.supersedesId}` : '/admin/cases');
    });
  };

  const onSuggest = () => setShowRevision(true);

  if (loading) return <div className="min-h-screen bg-surface px-6 py-16"><LoadingSpinner /></div>;

  if (error || !c) {
    return (
      <div className="min-h-screen bg-surface px-6 py-16">
        <div className="mx-auto max-w-[880px] rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
          {error || '案例不存在'}
          <button onClick={() => router.push('/admin/cases')} className="ml-3 underline">返回列表</button>
        </div>
      </div>
    );
  }

  const st = effectiveStatus(c.status, c.expireAt);
  const isDraft = c.status === 'draft';
  const quoteList = parseQuotes(c.dialogueQuote);
  const pendingRevisions = revisions.filter(r => r.status === 'draft' || r.status === 'pending');
  const changedFields = previous
    ? DIFF_FIELDS.filter(f =>
        (previous[f.key] as unknown as string | null) !== (c[f.key] as unknown as string | null))
    : [];

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[880px]">
        <div className="mb-4 flex items-center justify-between">
          <button onClick={() => router.push('/admin/cases')} className="text-sm text-muted-foreground hover:text-foreground">
            ← 返回案例库
          </button>
          <PageHelp
            title="案例详情使用说明"
            intro="案例记录一次真实客户推进过程，分身回答时会引用相似案例。这里可以看全文、做 AI 改写并确认新版本。"
            steps={[
              { title: '看全文', desc: '客户画像、推进过程、复盘要点都在下方；有修订时会展示与旧版的差异。' },
              { title: 'AI 改写', desc: '输入修改要求：草稿/待审案例会被直接覆盖；已生效案例会生成新版本草稿，原版本在审批前保持生效。' },
              { title: '采用或放弃', desc: '草稿确认后点「采用」才会生效；不满意点「放弃草稿」，原版本继续生效。' },
              { title: '审批', desc: '待审核案例点「审批」后变为已生效，可被分身检索引用。' },
            ]}
            notes={[
              '「下架」=立即停止引用，30 天内可重新上架；「彻底删除」=不可恢复，仅草稿/待审/已下架可用。',
              '公共案例需要先「复制」到本企业再修改。',
            ]}
          />
        </div>

        <div className="mb-6 flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="mb-1 text-[26px] font-bold text-foreground">{c.title}</h1>
            <p className="text-sm text-muted-foreground">
              {c.funnelStage || '未指定阶段'} · {c.sceneTag || '未指定场景'} · v{c.version}
            </p>
            <p className="mt-1 flex flex-wrap items-center gap-x-2 text-sm text-muted-foreground">
              <span>来源文档：{c.sourceDocument || '—'}</span>
              <span>· 出处：{c.source || '—'}</span>
              {c.sourceTaskId && (
                <button onClick={openSourceFile} className="text-primary underline hover:no-underline">
                  查看原始文档
                </button>
              )}
            </p>
            {c.generated && (
              <div className="mt-2 rounded-lg border border-primary/30 bg-primary-light px-3 py-2">
                <p className="text-xs font-medium text-primary">AI 生成草稿 · 生成依据</p>
                <div className="mt-1">
                  <EvidencePanel evidence={c.evidence ?? null} jobId={c.generationJobId} />
                </div>
              </div>
            )}
          </div>
          <div className="flex shrink-0 flex-col items-end gap-2">
            <span className="flex items-center gap-1.5">
              <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_CLS[st] || 'bg-gray-100 text-gray-600'}`}>
                {STATUS_LABELS[st] || st}
              </span>
              {c.origin === 'book' && (
                <span className="rounded-full bg-warning-bg px-2 py-0.5 text-[11px] text-warning-text">书籍案例（二手）</span>
              )}
              {c.generated && (
                <span className="rounded-full bg-primary-light px-2 py-0.5 text-[11px] text-primary">AI 生成</span>
              )}
              {st === 'active' && <IndexStatusBadge status={c.embeddingStatus} title={c.indexError || undefined} />}
            </span>
            {c.result && <span className="text-sm font-semibold text-primary">{RESULT_LABELS[c.result] || c.result}</span>}
          </div>
        </div>

        <div className="mb-5 flex flex-wrap gap-2">
          {(c.status === 'pending' || c.status === 'draft') && (
            <>
              <button onClick={() => router.push(`/admin/cases/${c.id}/edit`)} disabled={acting}
                className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-surface-2 disabled:opacity-50">
                编辑
              </button>
              <button onClick={onApprove} disabled={acting || !!c.qualityIssues}
                title={c.qualityIssues ? '请先补齐未达标的字段' : undefined}
                className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                审批生效
              </button>
            </>
          )}
          <button onClick={onSuggest} disabled={acting}
            className="rounded-full border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary-light disabled:opacity-50">
            AI 改写
          </button>
          <button onClick={onHealth} disabled={acting}
            className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-surface-2 disabled:opacity-50">
            AI 体检
          </button>
          <button onClick={onDerive} disabled={deriving}
            className="rounded-full border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary-light disabled:opacity-50">
            {deriving ? '派生中…' : 'AI 一源多用'}
          </button>
          {st === 'active' && c.embeddingStatus !== 'indexed' && (
            <button onClick={onReindex} disabled={acting}
              className="rounded-full border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary-light disabled:opacity-50">
              重建索引
            </button>
          )}
          {c.companyId !== null && c.status === 'active' && (
            <button onClick={onSubmitPublic} disabled={acting}
              className="rounded-full border border-border px-4 py-2 text-sm text-muted-foreground hover:bg-surface-2 disabled:opacity-50">
              提交到公共库
            </button>
          )}
          {isDraft && (
            <button onClick={onDiscardDraft} disabled={acting}
              className="rounded-full border border-border px-4 py-2 text-sm text-muted-foreground hover:bg-surface-2 disabled:opacity-50">
              放弃草稿
            </button>
          )}
          {c.companyId === null && (
            <button onClick={onCopy} disabled={acting}
              className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-surface-2 disabled:opacity-50">
              复制到本企业
            </button>
          )}
          {c.status !== 'deprecated' && (
            <button onClick={onDeprecate} disabled={acting}
              className="rounded-full border border-border px-4 py-2 text-sm text-danger hover:bg-danger-bg disabled:opacity-50">
              下架
            </button>
          )}
          {c.status === 'deprecated' && (
            <button onClick={onRestore} disabled={acting}
              className="rounded-full border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary-light disabled:opacity-50">
              重新上架
            </button>
          )}
          {c.status !== 'active' && (
            <button onClick={onDelete} disabled={acting}
              className="rounded-full border border-danger px-4 py-2 text-sm text-danger hover:bg-danger-bg disabled:opacity-50">
              彻底删除
            </button>
          )}
        </div>

        {c.qualityIssues && (c.status === 'draft' || c.status === 'pending') && (
          <div className="mb-4 rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
            未达生效标准：{c.qualityIssues}
            <button onClick={() => router.push(`/admin/cases/${c.id}/edit`)}
              className="ml-3 underline hover:no-underline">去补齐</button>
          </div>
        )}
        {c.qualityWarnings && (
          <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
            建议补充：{c.qualityWarnings}
          </div>
        )}

        {pendingRevisions.length > 0 && (
          <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            这条案例有 {pendingRevisions.length} 个修订版本待处理：
            {pendingRevisions.map(r => (
              <button key={r.id} onClick={() => router.push(`/admin/cases/${r.id}`)}
                className="ml-2 underline hover:no-underline">
                v{r.version}（{STATUS_LABELS[r.status] || r.status}）
              </button>
            ))}
          </div>
        )}

        {isDraft && previous && (
          <div className="mb-4 rounded-xl border border-border bg-surface p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-sm font-semibold text-foreground">
                与 v{previous.version} 的差异
                <span className="ml-2 text-xs font-normal text-muted-foreground">绿色为新增，红色为删除</span>
              </h2>
              <button onClick={() => router.push(`/admin/cases/${previous.id}`)}
                className="text-xs text-primary hover:underline">
                查看原版本
              </button>
            </div>
            {changedFields.length === 0 ? (
              <p className="text-sm text-muted-foreground">文本字段没有差异（仅结构化字段或状态变化）。</p>
            ) : (
              <div className="space-y-4">
                {changedFields.map(f => {
                  const diff = diffLines(
                    (previous[f.key] as unknown as string) || '',
                    (c[f.key] as unknown as string) || '');
                  return (
                    <div key={String(f.key)}>
                      <p className="mb-1 text-xs font-semibold text-muted-foreground">{f.label}</p>
                      {diff ? (
                        <pre className="max-h-[240px] overflow-auto rounded-lg bg-surface-2 p-3 text-xs leading-5">
                          {diff.map((line, idx) => (
                            <div key={idx} className={
                              line.type === 'add' ? 'bg-green-50 text-green-800'
                                : line.type === 'del' ? 'bg-red-50 text-red-700 line-through decoration-red-300'
                                  : 'text-foreground'
                            }>
                              <span className="select-none pr-2 text-muted-foreground">
                                {line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' '}
                              </span>
                              {line.text || ' '}
                            </div>
                          ))}
                        </pre>
                      ) : (
                        <p className="text-sm text-muted-foreground">文本过长，已跳过逐行对比，请分别查看两个版本。</p>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {c.customerProfile && (
          <div className="mb-4 rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-2 text-sm font-semibold text-muted-foreground">客户画像</h2>
            <p className="text-sm text-foreground whitespace-pre-wrap">{formatProfile(c.customerProfile)}</p>
          </div>
        )}

        {quoteList.length > 0 && (
          <div className="mb-4 rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-2 text-sm font-semibold text-muted-foreground">客户原话</h2>
            <ul className="space-y-1 text-sm text-foreground">
              {quoteList.map((q, i) => <li key={i} className="border-l-2 border-primary/40 pl-2">“{q}”</li>)}
            </ul>
          </div>
        )}

        <div className="mb-4 rounded-xl border border-border bg-surface-2 p-5">
          <h2 className="mb-4 text-sm font-semibold text-muted-foreground">推进过程</h2>
          {[
            ['核心异议', c.objection],
            ['情绪变化', c.emotionChange],
            ['关键动作', c.keyActions],
            ['关键话术', c.scripts],
            ['推进节点', c.advanceNodes],
          ].filter(([, v]) => v).map(([label, value], i) => (
            <div key={label} className="relative flex gap-4 pb-5 last:pb-0">
              <div className="flex flex-col items-center">
                <span className="h-3 w-3 rounded-full bg-primary shrink-0 mt-1" />
                {i < 4 && <span className="w-px flex-1 bg-border" />}
              </div>
              <div className="min-w-0 flex-1">
                <p className="mb-1 text-sm font-medium text-foreground">{label}</p>
                <p className="text-sm text-muted-foreground whitespace-pre-wrap">{value}</p>
              </div>
            </div>
          ))}
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-2 text-sm font-semibold text-success">✅ 可复用点</h2>
            <p className="text-sm text-foreground whitespace-pre-wrap">{c.reusablePoints || '—'}</p>
          </div>
          <div className="rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-2 text-sm font-semibold text-danger">⚠️ 失败点</h2>
            <p className="text-sm text-foreground whitespace-pre-wrap">{c.failurePoints || '—'}</p>
          </div>
        </div>

        <div className="mt-4 grid gap-4 md:grid-cols-2">
          <div className="rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-2 text-sm font-semibold text-foreground">版本历史</h2>
            {versions.length === 0 ? (
              <p className="text-sm text-muted-foreground">暂无历史版本</p>
            ) : (
              <ol className="space-y-3">
                {versions.map(v => (
                  <li key={v.id} className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-foreground">v{v.version}</span>
                        <span className={`rounded-full px-2 py-0.5 text-[11px] ${STATUS_CLS[v.status] || 'bg-gray-100 text-gray-600'}`}>
                          {STATUS_LABELS[v.status] || v.status}
                        </span>
                        {v.id === c.id && <span className="text-[11px] text-primary">当前查看</span>}
                      </div>
                      <p className="mt-0.5 truncate text-xs text-muted-foreground">{v.title}</p>
                      <p className="text-[11px] text-muted-foreground-2">
                        {v.createdAt ? new Date(v.createdAt).toLocaleString() : '—'}
                      </p>
                    </div>
                    {v.id !== c.id && (
                      <div className="flex shrink-0 gap-2">
                        <button onClick={() => router.push(`/admin/cases/${v.id}`)}
                          className="text-xs text-primary hover:underline">查看</button>
                        <button onClick={() => onRollback(v)} disabled={acting}
                          className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-50">
                          以此版本修改
                        </button>
                      </div>
                    )}
                  </li>
                ))}
              </ol>
            )}
          </div>

          <div className="rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-1 text-sm font-semibold text-foreground">被引用</h2>
            <p className="mb-2 text-xs text-muted-foreground">最近 30 天命中的分身</p>
            {refs.length === 0 ? (
              <p className="text-sm text-muted-foreground">最近 30 天还没有分身引用过这条案例</p>
            ) : (
              <ul className="space-y-2">
                {refs.map(r => (
                  <li key={r.skillId || r.skillName} className="flex items-center justify-between text-sm">
                    <span className="truncate text-foreground">{r.skillName}</span>
                    <span className="ml-3 shrink-0 text-xs text-muted-foreground">
                      {r.refCount} 次 · {r.lastReferencedAt ? new Date(r.lastReferencedAt).toLocaleDateString() : '—'}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>

      <RevisionModal
        open={showRevision}
        title="AI 改写案例"
        behaviorNote={c.status === 'active'
          ? '已生效内容：生成新版本草稿，审批通过后才替换旧版。'
          : '草稿/待审内容：直接覆盖当前条目，不额外生成版本。'}
        submitLabel={c.status === 'active' ? '生成新版本草稿' : '直接改写'}
        entryTitle={c.title}
        currentContent={[
          `标题：${c.title}`,
          `客户画像：${c.customerProfile || '—'}`,
          `核心异议：${c.objection || '—'}`,
          `关键动作：${c.keyActions || '—'}`,
          `关键话术：${c.scripts || '—'}`,
          `推进节点：${c.advanceNodes || '—'}`,
          `可复用点：${c.reusablePoints || '—'}`,
          `失败点：${c.failurePoints || '—'}`,
        ].join('\n\n')}
        presets={[
          '补充客户异议的应对话术',
          '补充客户原话',
          '把推进节点按时间顺序重排',
          '压缩到 500 字以内',
        ]}
        onLoadSuggestions={() => getCaseImprovements(c.id)}
        onSubmit={async instruction => {
          const draft = await suggestCaseRevision(c.id, instruction);
          setShowRevision(false);
          router.push(`/admin/cases/${draft.id}`);
        }}
        onClose={() => setShowRevision(false)}
      />

      {healthOpen && health && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setHealthOpen(false)}>
          <div className="max-h-[80vh] w-full max-w-[560px] overflow-y-auto rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">案例体检</h3>
            <p className="mb-3 text-xs text-muted-foreground">
              确定性检查：结果、场景、异议、动作、话术、可复用点、时效与重复。
            </p>
            {health.issues.length === 0 ? (
              <div className="rounded-lg bg-success-bg px-3 py-2 text-sm text-success">没有发现问题</div>
            ) : (
              <div className="space-y-2">
                {health.issues.map((issue, i) => (
                  <div key={i} className="rounded-lg border border-border bg-surface-2 px-3 py-2">
                    <div className="flex items-center gap-2">
                      <span className={`rounded-full px-2 py-0.5 text-[10px] ${
                        issue.severity === 'high' ? 'bg-danger-bg text-danger' : 'bg-warning-bg text-warning-text'}`}>
                        {issue.severity === 'high' ? '高' : '中'}
                      </span>
                      <span className="text-sm text-foreground">{issue.message}</span>
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">建议：{issue.suggestion}</div>
                  </div>
                ))}
              </div>
            )}
            {health.similar.length > 0 && (
              <div className="mt-4">
                <div className="mb-1 text-xs font-medium text-muted-foreground">同场景案例（成功/失败对照）</div>
                {health.similar.map(s => (
                  <button key={s.id} onClick={() => router.push(`/admin/cases/${s.id}`)}
                    className="mb-1 block w-full rounded bg-surface-2 px-3 py-1.5 text-left text-xs text-foreground hover:bg-primary-light">
                    {s.title} <span className="text-muted-foreground">· {s.hint}</span>
                  </button>
                ))}
              </div>
            )}
            <div className="mt-4 flex justify-end">
              <button onClick={() => setHealthOpen(false)}
                className="rounded-full border border-border px-4 py-1.5 text-sm text-foreground hover:bg-primary-light">
                知道了
              </button>
            </div>
          </div>
        </div>
      )}

      <DerivedContentModal open={deriveOpen} source={derived} onClose={() => setDeriveOpen(false)} />

      <ExpiryDialog
        open={showApprove}
        title="审批生效"
        description={`「${c.title}」审批后会被分身检索，可设置失效时间。`}
        onCancel={() => setShowApprove(false)}
        onConfirm={iso => {
          setShowApprove(false);
          runAction(() => approveCase(c.id, iso));
        }}
      />
    </div>
  );
}
