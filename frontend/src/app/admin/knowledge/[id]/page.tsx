'use client';

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useRouter } from 'next/navigation';
import {
  getKnowledge, getKnowledgeRevisions, approveKnowledge, deprecateKnowledge, deleteKnowledge,
  suggestKnowledgeRevision, getKnowledgeImprovements, reindexKnowledge, submitKnowledgeToPublic,
  restoreKnowledge, getKnowledgeVersions, rollbackKnowledge, getKnowledgeReferences,
  getKnowledgeSourceFile,
  effectiveStatus, CATEGORY_LABELS, SOURCE_TYPE_LABELS,
  STATUS_LABELS, type KnowledgeEntry, type ReferenceItem,
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

const STATUS_CLS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-600',
  pending: 'bg-warning-bg text-warning-text',
  active: 'bg-success-bg text-success',
  deprecated: 'bg-gray-100 text-gray-500',
  expired: 'bg-danger-bg text-danger',
};

export default function KnowledgeDetailPage({ params }: { params: { id: string } }) {
  const router = useRouter();
  const { id } = params;

  const [entry, setEntry] = useState<KnowledgeEntry | null>(null);
  const [previous, setPrevious] = useState<KnowledgeEntry | null>(null);
  const [revisions, setRevisions] = useState<KnowledgeEntry[]>([]);
  const [versions, setVersions] = useState<KnowledgeEntry[]>([]);
  const [refs, setRefs] = useState<ReferenceItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [acting, setActing] = useState(false);
  const [showRevision, setShowRevision] = useState(false);
  const [showApprove, setShowApprove] = useState(false);
  const [health, setHealth] = useState<ContentHealth | null>(null);
  const [healthOpen, setHealthOpen] = useState(false);
  const [derived, setDerived] = useState<DerivedContent | null>(null);
  const [deriveOpen, setDeriveOpen] = useState(false);
  const [deriving, setDeriving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const current = await getKnowledge(id);
      setEntry(current);
      if (current.supersedesId) {
        getKnowledge(current.supersedesId).then(setPrevious).catch(() => setPrevious(null));
      } else {
        setPrevious(null);
      }
      getKnowledgeRevisions(current.id).then(setRevisions).catch(() => setRevisions([]));
      getKnowledgeVersions(current.id).then(setVersions).catch(() => setVersions([]));
      getKnowledgeReferences(current.id).then(setRefs).catch(() => setRefs([]));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请确认有权限查看');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const openSourceFile = useCallback(async () => {
    try {
      const file = await getKnowledgeSourceFile(id);
      window.open(file.url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      alert(e instanceof Error ? e.message : '原始文档不可用');
    }
  }, [id]);

  const diff = useMemo(
    () => (previous && entry ? diffLines(previous.content || '', entry.content || '') : null),
    [previous, entry],
  );

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
    if (!entry) return;
    if (entry.expireAt) {
      runAction(() => approveKnowledge(entry.id, entry.expireAt || undefined));
      return;
    }
    setShowApprove(true);
  };

  const onDeprecate = () => {
    if (!entry) return;
    const impacted = refs.length > 0
      ? `\n最近 30 天有 ${refs.length} 个分身引用过它，下架后将不再命中。`
      : '';
    if (!window.confirm(`确认下架「${entry.title}」？下架后分身立即停止引用，30 天内可重新上架。${impacted}`)) return;
    runAction(() => deprecateKnowledge(entry.id));
  };

  const onRollback = (v: KnowledgeEntry) => {
    if (!entry) return;
    if (!window.confirm(`以 v${v.version} 的内容生成新草稿？当前版本不会被修改，新草稿审批后才会生效。`)) return;
    runAction(async () => {
      const draft = await rollbackKnowledge(entry.id, v.id);
      router.push(`/admin/knowledge/${draft.id}`);
    });
  };

  const onReindex = () => {
    if (!entry) return;
    runAction(() => reindexKnowledge(entry.id));
  };

  const onRestore = () => {
    if (!entry) return;
    if (!window.confirm('重新上架这条知识？会回到下架前的状态：原本生效的重新索引并参与检索，草稿/待审的回到待处理状态。')) return;
    runAction(() => restoreKnowledge(entry.id));
  };

  const onDelete = async () => {
    if (!entry) return;
    if (!window.confirm(`彻底删除「${entry.title}」？\n\n删除后不可恢复，版本链和引用记录也不会再关联到这条知识。`)) return;
    setActing(true);
    try {
      await deleteKnowledge(entry.id);
      router.push('/admin/knowledge');
    } catch (e) {
      alert(e instanceof Error ? e.message : '删除失败');
      setActing(false);
    }
  };

  const onSubmitPublic = () => {
    if (!entry) return;
    if (!window.confirm('提交到公共库？平台审核通过后，所有企业都能检索到这条知识（不含企业私有信息）。')) return;
    runAction(() => submitKnowledgeToPublic(entry.id));
  };

  const onDiscardDraft = () => {
    if (!entry) return;
    if (!window.confirm('放弃这个修订版本？原版本不受影响。')) return;
    runAction(async () => {
      await deprecateKnowledge(entry.id);
      router.push(entry.supersedesId ? `/admin/knowledge/${entry.supersedesId}` : '/admin/knowledge');
    });
  };

  const onSuggest = () => setShowRevision(true);

  const onHealth = async () => {
    try {
      setHealth(await getContentHealth('knowledge', id));
      setHealthOpen(true);
    } catch (e) {
      alert(e instanceof Error ? e.message : '体检失败');
    }
  };

  const onDerive = async () => {
    setDeriving(true);
    try {
      const accepted = await deriveContent('knowledge', id);
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

  if (loading) {
    return <div className="min-h-screen bg-surface px-6 py-16"><LoadingSpinner /></div>;
  }

  if (error || !entry) {
    return (
      <div className="min-h-screen bg-surface px-6 py-16">
        <div className="mx-auto max-w-[880px] rounded-xl border border-red-200 bg-danger-bg px-4 py-3 text-sm text-danger">
          {error || '知识不存在'}
          <button onClick={() => router.push('/admin/knowledge')} className="ml-3 underline">返回列表</button>
        </div>
      </div>
    );
  }

  const st = effectiveStatus(entry.status, entry.expireAt);
  const isDraft = entry.status === 'draft';
  const pendingRevisions = revisions.filter(r => r.status === 'draft' || r.status === 'pending');

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4 flex items-center justify-between">
          <button onClick={() => router.push('/admin/knowledge')} className="text-sm text-muted-foreground hover:text-foreground">
            ← 返回知识库
          </button>
          <PageHelp
            title="知识详情使用说明"
            intro="这里可以查看知识全文、版本变化，并对 AI 改写出的新版本做采用或放弃。"
            steps={[
              { title: '看全文', desc: '标题下方是正文；旧版与新版有差异时会自动展开逐行对比（绿色新增、红色删除）。' },
              { title: 'AI 改写', desc: '输入修改要求：草稿/待审条目会被直接覆盖；已生效条目会生成新版本草稿，原版本在审批前保持生效。' },
              { title: '采用或放弃', desc: '草稿确认无误后点「采用」，知识才会生效；不满意点「放弃」，原版本继续生效。' },
              { title: '审批', desc: '待审核的知识点「审批」后变为已生效，可以被分身检索引用。' },
            ]}
            notes={[
              '「下架」=立即停止引用，30 天内可重新上架；「彻底删除」=不可恢复，仅草稿/待审/已下架可用。',
              '彻底删除前会校验派生版本和培训任务引用，避免版本链断裂或学习任务失效。',
              '企业私有知识只有本企业可见；公共知识需要先复制到企业再修改。',
            ]}
          />
        </div>

        <div className="mb-6 rounded-2xl border border-border bg-surface p-6">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_CLS[st] || 'bg-gray-100 text-gray-600'}`}>
                  {STATUS_LABELS[st] || st}
                </span>
                {st === 'active' && <IndexStatusBadge status={entry.embeddingStatus} title={entry.indexError || undefined} />}
                <span className="text-xs text-muted-foreground">v{entry.version}</span>
                <span className="text-xs text-muted-foreground">{CATEGORY_LABELS[entry.category] || entry.category}</span>
                <span className="text-xs text-muted-foreground">{SOURCE_TYPE_LABELS[entry.sourceType] || entry.sourceType}</span>
                {entry.generated && (
                  <span className="rounded-full bg-primary-light px-2 py-0.5 text-[10px] text-primary">AI 生成</span>
                )}
              </div>
              <h1 className="text-[26px] font-bold leading-snug text-foreground">{entry.title}</h1>
            </div>
            <div className="flex flex-wrap gap-2">
              {(entry.status === 'pending' || entry.status === 'draft') && (
                <>
                  <button onClick={() => router.push(`/admin/knowledge/${entry.id}/edit`)} disabled={acting}
                    className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-surface-2 disabled:opacity-50">
                    编辑
                  </button>
                  <button onClick={onApprove} disabled={acting}
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
              {st === 'active' && entry.embeddingStatus !== 'indexed' && (
                <button onClick={onReindex} disabled={acting}
                  className="rounded-full border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary-light disabled:opacity-50">
                  重建索引
                </button>
              )}
              {entry.companyId !== null && entry.status === 'active' && (
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
              {entry.status !== 'deprecated' && (
                <button onClick={onDeprecate} disabled={acting}
                  className="rounded-full border border-border px-4 py-2 text-sm text-danger hover:bg-danger-bg disabled:opacity-50">
                  下架
                </button>
              )}
              {entry.status === 'deprecated' && (
                <button onClick={onRestore} disabled={acting}
                  className="rounded-full border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary-light disabled:opacity-50">
                  重新上架
                </button>
              )}
              {entry.status !== 'active' && (
                <button onClick={onDelete} disabled={acting}
                  className="rounded-full border border-danger px-4 py-2 text-sm text-danger hover:bg-danger-bg disabled:opacity-50">
                  彻底删除
                </button>
              )}
            </div>
          </div>

          <div className="mt-5 grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
            <div><p className="text-xs text-muted-foreground">来源</p><p className="mt-0.5 text-foreground">{entry.source || '—'}</p></div>
            <div><p className="text-xs text-muted-foreground">生效时间</p><p className="mt-0.5 text-foreground">{entry.effectiveAt ? new Date(entry.effectiveAt).toLocaleString() : '—'}</p></div>
            <div><p className="text-xs text-muted-foreground">失效时间</p><p className="mt-0.5 text-foreground">{entry.expireAt ? new Date(entry.expireAt).toLocaleString() : '不限期'}</p></div>
            <div><p className="text-xs text-muted-foreground">更新时间</p><p className="mt-0.5 text-foreground">{new Date(entry.updatedAt).toLocaleString()}</p></div>
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg bg-surface-2 px-3 py-2 text-sm">
            <span className="text-muted-foreground">来源文档</span>
            <span className="text-foreground">{entry.sourceDocument || '—'}</span>
            <span className="text-muted-foreground">卡片版本</span>
            <span className="text-foreground">v{entry.version}</span>
            <span className="text-muted-foreground">创建时间</span>
            <span className="text-foreground">{new Date(entry.createdAt).toLocaleString()}</span>
            {entry.sourceTaskId && (
              <button onClick={openSourceFile} className="ml-auto text-primary underline hover:no-underline">
                查看原始文档
              </button>
            )}
          </div>

          {entry.generated && (
            <div className="mt-3 rounded-lg border border-primary/30 bg-primary-light px-3 py-2">
              <p className="text-xs font-medium text-primary">AI 生成草稿 · 生成依据</p>
              <div className="mt-1">
                <EvidencePanel evidence={entry.evidence ?? null} jobId={entry.generationJobId} />
              </div>
            </div>
          )}
        </div>

        {pendingRevisions.length > 0 && (
          <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            这条知识有 {pendingRevisions.length} 个修订版本待处理：
            {pendingRevisions.map(r => (
              <button key={r.id} onClick={() => router.push(`/admin/knowledge/${r.id}`)}
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
              <button onClick={() => router.push(`/admin/knowledge/${previous.id}`)}
                className="text-xs text-primary hover:underline">
                查看原版本
              </button>
            </div>
            {diff ? (
              <pre className="max-h-[420px] overflow-auto rounded-lg bg-surface-2 p-3 text-xs leading-5">
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
        )}

        <div className="rounded-2xl border border-border bg-surface p-6">
          <h2 className="mb-3 text-sm font-semibold text-foreground">正文</h2>
          <div className="whitespace-pre-wrap break-words text-[15px] leading-7 text-foreground">
            {entry.content || '（无正文）'}
          </div>
        </div>

        <div className="mt-4 grid gap-4 md:grid-cols-2">
          <div className="rounded-2xl border border-border bg-surface p-6">
            <h2 className="mb-3 text-sm font-semibold text-foreground">版本历史</h2>
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
                        {v.id === entry.id && <span className="text-[11px] text-primary">当前查看</span>}
                      </div>
                      <p className="mt-0.5 truncate text-xs text-muted-foreground">{v.title}</p>
                      <p className="text-[11px] text-muted-foreground-2">
                        {v.createdAt ? new Date(v.createdAt).toLocaleString() : '—'}
                      </p>
                    </div>
                    <div className="flex shrink-0 gap-2">
                      {v.id !== entry.id && (
                        <>
                          <button onClick={() => router.push(`/admin/knowledge/${v.id}`)}
                            className="text-xs text-primary hover:underline">查看</button>
                          <button onClick={() => onRollback(v)} disabled={acting}
                            className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-50">
                            以此版本修改
                          </button>
                        </>
                      )}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </div>

          <div className="rounded-2xl border border-border bg-surface p-6">
            <h2 className="mb-1 text-sm font-semibold text-foreground">被引用</h2>
            <p className="mb-3 text-xs text-muted-foreground">最近 30 天命中的分身（未索引或未生效不会命中）</p>
            {refs.length === 0 ? (
              <p className="text-sm text-muted-foreground">最近 30 天还没有分身引用过这条知识</p>
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
        title="AI 改写知识"
        behaviorNote={entry.status === 'active'
          ? '已生效内容：生成新版本草稿，审批通过后才替换旧版。'
          : '草稿/待审内容：直接覆盖当前条目，不额外生成版本。'}
        submitLabel={entry.status === 'active' ? '生成新版本草稿' : '直接改写'}
        entryTitle={entry.title}
        currentContent={`${entry.title}\n\n${entry.content || ''}`}
        presets={[
          '压缩到 300 字以内',
          '补充适用边界和不适用场景',
          '改成分点表述，便于检索',
          '补充具体数字或条款',
        ]}
        onLoadSuggestions={() => getKnowledgeImprovements(entry.id)}
        onSubmit={async instruction => {
          const draft = await suggestKnowledgeRevision(entry.id, instruction);
          setShowRevision(false);
          router.push(`/admin/knowledge/${draft.id}`);
        }}
        onClose={() => setShowRevision(false)}
      />

      {healthOpen && health && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setHealthOpen(false)}>
          <div className="max-h-[80vh] w-full max-w-[560px] overflow-y-auto rounded-xl bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">知识体检</h3>
            <p className="mb-3 text-xs text-muted-foreground">
              这些是确定性检查（重复/过期/不可检索/字段缺失），不是 AI 评分。
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
                <div className="mb-1 text-xs font-medium text-muted-foreground">相似条目（检查口径冲突）</div>
                {health.similar.map(s => (
                  <button key={s.id} onClick={() => router.push(`/admin/knowledge/${s.id}`)}
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
        description={`「${entry.title}」审批后会被分身检索，请选择失效时间。`}
        onCancel={() => setShowApprove(false)}
        onConfirm={iso => {
          setShowApprove(false);
          runAction(() => approveKnowledge(entry.id, iso));
        }}
      />
    </div>
  );
}
