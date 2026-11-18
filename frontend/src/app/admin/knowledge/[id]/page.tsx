'use client';

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useRouter } from 'next/navigation';
import {
  getKnowledge, getKnowledgeRevisions, approveKnowledge, deprecateKnowledge, deleteKnowledge, copyKnowledge,
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
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { getCurrentUser } from '@/lib/api/auth';
import {
  Archive, ArrowCounterClockwise, ArrowsSplit, Check, Copy, FirstAidKit,
  PaperPlaneTilt, PencilSimple, Trash,
} from '@phosphor-icons/react';

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
  const [deriveConfirmOpen, setDeriveConfirmOpen] = useState(false);
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);

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

  useEffect(() => {
    getCurrentUser().then(u => setIsSuperAdmin(u.role === 'super_admin')).catch(() => {});
  }, []);

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
    if (!window.confirm(`以 v${v.version} 的内容生成新草稿？当前版本不会被修改，新草稿通过后才会生效。`)) return;
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

  const onCopyToCompany = () => {
    if (!entry) return;
    runAction(async () => {
      const copy = await copyKnowledge(entry.id);
      router.push(`/admin/knowledge/${copy.id}`);
    });
  };

  const onHealth = async () => {
    try {
      setHealth(await getContentHealth('knowledge', id));
      setHealthOpen(true);
    } catch (e) {
      alert(e instanceof Error ? e.message : '体检失败');
    }
  };

  const requestDerive = () => setDeriveConfirmOpen(true);

  const onDerive = async () => {
    setDeriveConfirmOpen(false);
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
  // 公共知识只有平台超管能写；企业账号只读 + 复制
  const canWrite = isSuperAdmin || entry.companyId !== null;
  const nextStep = !canWrite
    ? '公共知识由平台维护，企业账号只读。需要修改请先「复制到本企业」，再改副本。'
    : st === 'active'
    ? entry.embeddingStatus === 'indexed'
      ? '已生效并完成索引，分身可以引用。要改内容用「AI 改写」；不再使用请点「下架」。'
      : '已生效，但索引还没完成，分身暂时引用不到；请等待自动重试或点「重建索引」。'
    : st === 'expired'
      ? '已过期，分身不再引用；需要继续使用请审核或创建新版本。'
      : entry.status === 'deprecated'
        ? '已下架，分身不再引用；30 天内可点「重新上架」。'
        : entry.status === 'pending'
          ? '待审核：确认内容无误后点「通过并生效」并设置失效时间。'
          : '草稿：先「编辑」补齐内容，再点「通过并生效」。';

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-4 flex items-center justify-between">
          <button onClick={() => router.push('/admin/knowledge')} className="text-sm text-muted-foreground hover:text-foreground">
            ← 返回知识库
          </button>
          <PageHelp
            title="知识详情使用说明"
            intro="这里查看知识全文、版本变化和引用情况，并完成「通过并生效 / 改写 / 下架」等维护动作。按钮按「主操作 → 内容工具 → 生命周期」从左到右排列。"
            steps={[
              { title: '先看当前状态', desc: '标题上方的状态标签说明它现在能不能被分身引用：草稿/待审不会引用，已生效且索引完成才会被检索。' },
              { title: '处理待办', desc: '草稿或待审时，主操作是「通过并生效」；内容不满意先「编辑」改字段，或用「AI 改写」按指令重写。' },
              { title: '看版本差异', desc: 'AI 改写已生效内容会生成新版本草稿，页面自动展开逐行差异（绿色新增、红色删除）；审核新版本后旧版才下线。' },
              { title: '日常维护', desc: '已生效内容用「AI 体检」查重复/过期/不可检索；用「AI 派生」生成本条知识的评测题和场景微练（只预览、不保存）；索引异常用「重建索引」。' },
              { title: '下线或恢复', desc: '不再使用点「下架」（立即停止引用，30 天内可重新上架）；确认永不需要时才「彻底删除」。' },
            ]}
            actions={[
              { label: '通过并生效', desc: '仅草稿/待审可用。通过后变「已生效」，并异步生成向量；索引完成后分身才会检索到。可设置失效时间。', tone: 'primary' },
              { label: '编辑', desc: '仅草稿/待审可用，直接改标题、正文、分类、来源等字段。已生效内容不能直接改，需走「AI 改写」生成新版本。' },
              { label: 'AI 改写', desc: '输入修改要求生成新内容。草稿/待审会直接覆盖本条；已生效会生成新版本草稿，旧版在通过前继续生效。' },
              { label: 'AI 体检', desc: '只做确定性检查（重复、过期、不可检索、字段缺失），不是打分，也不改内容。给出问题、严重度和建议。' },
              { label: 'AI 派生', desc: '点击后先弹确认：基于本条知识生成评测题和场景微练（知识不再派生 FAQ 回知识库，避免重复）。结果只预览、不自动落库，需按提示复制到评测中心/培训计划。' },
              { label: '重建索引', desc: '仅「已生效但索引未完成/失败」时出现。重新调用向量模型；异步执行，稍后刷新看索引徽标。' },
              { label: '提交到公共库', desc: '仅本企业已生效知识可用。生成公共待审候选，平台审核通过后所有企业可见；提交前自动强脱敏。' },
              { label: '放弃草稿', desc: '仅草稿可用。把当前草稿标记为下架并回到原版本，不删除记录；原版本不受影响。' },
              { label: '下架', desc: '立即停止被分身引用，30 天内可恢复。已下架内容仍可查看版本和引用历史。', tone: 'danger' },
              { label: '重新上架', desc: '仅已下架可用。回到下架前状态：原本生效的重新索引并参与检索；草稿/待审回到待处理。' },
              { label: '彻底删除', desc: '不可恢复，且仅草稿/待审/已下架可用。删除前校验派生版本和培训任务引用，避免版本链断裂。', tone: 'danger' },
              { label: '查看原始文档', desc: '有上传来源时出现，打开解析前的原文件（只读）。' },
              { label: '查看 / 以此版本修改', desc: '版本历史里的「查看」跳到该版本全文；「以此版本修改」用旧版本内容生成新草稿，不改历史版本。' },
            ]}
            notes={[
              '审核 ≠ 立刻可检索：通过后还要等索引徽标变成「已索引」，否则分身仍然引用不到。',
              '「下架」=立即停止引用，30 天内可重新上架；「彻底删除」=不可恢复，仅草稿/待审/已下架可用。',
              '彻底删除前会校验派生版本和培训任务引用，避免版本链断裂或学习任务失效。',
              '企业私有知识只有本企业可见；公共知识需要先复制到企业再修改。',
            ]}
          />
        </div>

        <div className="mb-6 rounded-2xl border border-border bg-surface p-6">
          <div>
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
          </div>

          {!canWrite && (
            <div className="mt-4 rounded-lg border border-warning-text/30 bg-warning-bg px-3 py-2 text-xs leading-5 text-warning-text">
              公共知识由平台维护，企业账号只读；需要修改请先「复制到本企业」，改自己企业的副本。
            </div>
          )}

          <div className="mt-3 rounded-lg bg-surface-2 px-3 py-2 text-xs leading-5 text-muted-foreground">
            <b className="text-foreground">下一步：</b>{nextStep}
          </div>

          {/* 操作栏：主操作 → 内容工具 → 生命周期，三组用竖线隔开，避免一排按钮看不出主次 */}
          <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border pt-4">
            {canWrite && (entry.status === 'pending' || entry.status === 'draft') && (
              <>
                <Button variant="primary" onClick={onApprove} disabled={acting}>
                  <Check size={16} weight="bold" />通过并生效
                </Button>
                <Button variant="neutral" onClick={() => router.push(`/admin/knowledge/${entry.id}/edit`)} disabled={acting}>
                  <PencilSimple size={16} />编辑
                </Button>
              </>
            )}
            {canWrite && (
              <Button variant="secondary" onClick={onSuggest} disabled={acting} title="按指令改正文；已生效内容会生成新版本草稿">
                <PencilSimple size={16} />AI 改写
              </Button>
            )}
            {entry.companyId === null && !isSuperAdmin && (
              <Button variant="secondary" onClick={onCopyToCompany} disabled={acting}>
                <Copy size={16} />复制到本企业
              </Button>
            )}
            <Button variant="neutral" onClick={onHealth} disabled={acting} title="只做确定性检查，不改内容">
              <FirstAidKit size={16} />AI 体检
            </Button>
            <Button variant="secondary" onClick={requestDerive} disabled={deriving}
              title="生成评测题与场景微练，仅预览不保存">
              <ArrowsSplit size={16} />{deriving ? '派生中…' : 'AI 派生'}
            </Button>

            <span className="mx-1 hidden h-5 w-px bg-border sm:block" aria-hidden="true" />

            {canWrite && st === 'active' && entry.embeddingStatus !== 'indexed' && (
              <Button variant="secondary" onClick={onReindex} disabled={acting}>
                <ArrowCounterClockwise size={16} />重建索引
              </Button>
            )}
            {canWrite && entry.companyId !== null && entry.status === 'active' && (
              <Button variant="neutral" onClick={onSubmitPublic} disabled={acting}>
                <PaperPlaneTilt size={16} />提交到公共库
              </Button>
            )}
            {canWrite && isDraft && (
              <Button variant="neutral" onClick={onDiscardDraft} disabled={acting}>
                <Archive size={16} />放弃草稿
              </Button>
            )}
            {canWrite && entry.status !== 'deprecated' && (
              <Button variant="danger" onClick={onDeprecate} disabled={acting}>
                <Archive size={16} />下架
              </Button>
            )}
            {canWrite && entry.status === 'deprecated' && (
              <Button variant="secondary" onClick={onRestore} disabled={acting}>
                <ArrowCounterClockwise size={16} />重新上架
              </Button>
            )}
            {canWrite && entry.status !== 'active' && (
              <Button variant="danger" onClick={onDelete} disabled={acting}>
                <Trash size={16} />彻底删除
              </Button>
            )}
          </div>
          <p className="mt-2 text-[11px] leading-5 text-muted-foreground">
            AI 改写：改正文，已生效会生成新版本草稿 · AI 体检：只查问题不改内容 ·
            AI 派生：生成本条知识的评测题与场景微练，仅预览不保存
          </p>

        </div>

        <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="min-w-0 space-y-4">

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

        {entry.generated && (
          <div className="rounded-xl border border-primary/30 bg-primary-light px-4 py-3">
            <p className="text-xs font-medium text-primary">AI 生成草稿 · 生成依据</p>
            <div className="mt-1">
              <EvidencePanel evidence={entry.evidence ?? null} jobId={entry.generationJobId} />
            </div>
          </div>
        )}

          </div>
          <aside className="space-y-4 lg:sticky lg:top-6">
          <div className="rounded-2xl border border-border bg-surface p-5">
            <h2 className="text-sm font-semibold text-foreground">状态与来源</h2>
            <dl className="mt-3 space-y-2 text-xs">
              {[
                ['来源', entry.source || '—'],
                ['生效时间', entry.effectiveAt ? new Date(entry.effectiveAt).toLocaleString() : '—'],
                ['失效时间', entry.expireAt ? new Date(entry.expireAt).toLocaleString() : '不限期'],
                ['更新时间', new Date(entry.updatedAt).toLocaleString()],
                ['来源文档', entry.sourceDocument || '—'],
                ['卡片版本', `v${entry.version}`],
                ['创建时间', new Date(entry.createdAt).toLocaleString()],
              ].map(([label, value]) => (
                <div key={label} className="flex items-start justify-between gap-3">
                  <dt className="shrink-0 text-muted-foreground">{label}</dt>
                  <dd className="min-w-0 break-words text-right text-foreground">{value}</dd>
                </div>
              ))}
            </dl>
            {entry.sourceTaskId && (
              <button onClick={openSourceFile} className="mt-3 text-xs text-primary hover:underline">
                查看原始文档
              </button>
            )}
          </div>

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
          </aside>
        </div>
      </div>

      <RevisionModal
        open={showRevision}
        title="AI 改写知识"
        behaviorNote={entry.status === 'active'
          ? '已生效内容：生成新版本草稿，审核通过后才替换旧版。'
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

      <DerivedContentModal open={deriveOpen} source={derived} sourceType="knowledge"
        onClose={() => setDeriveOpen(false)} />

      <ConfirmDialog
        open={deriveConfirmOpen}
        title="用这条知识做 AI 派生？"
        confirmLabel="开始派生"
        onConfirm={onDerive}
        onCancel={() => setDeriveConfirmOpen(false)}
      >
        <p>AI 会基于本条知识生成评测题和场景微练，用来验证分身是否真的掌握，以及给新人陪练。</p>
        <ul className="list-disc space-y-1 pl-5">
          <li>结果只用于预览和复制，不会自动入库、不会生成草稿，也不会修改原文。</li>
          <li>生成通常需要 10–60 秒；重复点击会重复消耗模型额度。</li>
          <li>生成后请人工检查，再复制到评测中心或培训计划。</li>
        </ul>
      </ConfirmDialog>

      <ExpiryDialog
        open={showApprove}
        title="通过并生效"
        description={`「${entry.title}」通过后会被分身检索，请选择失效时间。`}
        onCancel={() => setShowApprove(false)}
        onConfirm={iso => {
          setShowApprove(false);
          runAction(() => approveKnowledge(entry.id, iso));
        }}
      />
    </div>
  );
}
