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
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { getCurrentUser } from '@/lib/api/auth';
import {
  Archive, ArrowCounterClockwise, ArrowsSplit, Check, Copy, FirstAidKit,
  PaperPlaneTilt, PencilSimple, Trash,
} from '@phosphor-icons/react';

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
  const [deriveConfirmOpen, setDeriveConfirmOpen] = useState(false);
  const [showApprove, setShowApprove] = useState(false);
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);

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

  useEffect(() => {
    getCurrentUser().then(u => setIsSuperAdmin(u.role === 'super_admin')).catch(() => {});
  }, []);

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

  const requestDerive = () => setDeriveConfirmOpen(true);

  const onDerive = async () => {
    if (!c) return;
    setDeriveConfirmOpen(false);
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
    if (!window.confirm(`以 v${v.version} 的内容生成新草稿？当前版本不会被修改，新草稿通过后才会生效。`)) return;
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
    runAction(async () => {
      const copy = await copyCase(c.id);
      router.push(`/admin/cases/${copy.id}`);
    });
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
  // 公共案例只有平台超管能写；企业账号只读 + 复制
  const canWrite = isSuperAdmin || c.companyId !== null;
  const quoteList = parseQuotes(c.dialogueQuote);
  const pendingRevisions = revisions.filter(r => r.status === 'draft' || r.status === 'pending');
  const changedFields = previous
    ? DIFF_FIELDS.filter(f =>
        (previous[f.key] as unknown as string | null) !== (c[f.key] as unknown as string | null))
    : [];
  const nextStep = !canWrite
    ? '公共案例由平台维护，企业账号只读。需要修改请先「复制到本企业」，再改副本。'
    : c.qualityIssues && (c.status === 'draft' || c.status === 'pending')
    ? '未达生效标准：先点「编辑」补齐客户画像/核心异议/关键动作/话术/结果，再通过并生效。'
    : st === 'active'
      ? c.embeddingStatus === 'indexed'
        ? '已生效并完成索引，分身可以引用。要改内容用「AI 改写」；不再使用请点「下架」。'
        : '已生效，但索引还没完成，分身暂时引用不到；请等待自动重试或点「重建索引」。'
      : st === 'expired'
        ? '已过期，分身不再引用；需要继续使用请审核或创建新版本。'
        : c.status === 'deprecated'
          ? '已下架，分身不再引用；30 天内可点「重新上架」。'
          : c.status === 'pending'
            ? '待审核：确认客户画像、异议、动作和结果无误后点「通过并生效」。'
            : '草稿：先「编辑」补齐字段，再点「通过并生效」。';

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-4 flex items-center justify-between">
          <button onClick={() => router.push('/admin/cases')} className="text-sm text-muted-foreground hover:text-foreground">
            ← 返回案例库
          </button>
          <PageHelp
            title="案例详情使用说明"
            intro="案例记录一次真实客户推进过程，分身回答「这类客户怎么打」时会引用相似案例。按钮按「主操作 → 内容工具 → 生命周期」排列。"
            steps={[
              { title: '先看是否达标', desc: '草稿/待审若显示「未达生效标准」或「建议补充」，先点「编辑」补齐客户画像、核心异议、动作/话术、结果等字段。' },
              { title: '通过并生效', desc: '补齐后点「通过并生效」并设置失效时间；通过后异步生成向量，索引完成后分身才会检索到。' },
              { title: '看版本差异', desc: 'AI 改写已生效案例会生成新版本草稿，并按字段展示差异；审核新版本后旧版才下线。' },
              { title: '维护与复盘', desc: '用「AI 体检」查字段缺失与同场景对照；用「AI 派生」生成知识卡、评测题和场景微练（只预览、不保存）；索引异常用「重建索引」。' },
              { title: '下线或恢复', desc: '不再使用点「下架」（立即停止引用，30 天内可重新上架）；确认永不需要时才「彻底删除」。' },
            ]}
            actions={[
              { label: '通过并生效', desc: '仅草稿/待审可用，且存在未达标字段时会被禁用（需先补齐）。通过后变「已生效」并异步索引，可设置失效时间。', tone: 'primary' },
              { label: '编辑', desc: '仅草稿/待审可用，直接改标题、客户画像、异议、动作、话术、推进节点和结果。已生效案例不能直接改。' },
              { label: 'AI 改写', desc: '输入修改要求生成新内容：草稿/待审直接覆盖；已生效生成新版本草稿，旧版在通过前继续生效。' },
              { label: 'AI 体检', desc: '确定性检查：结果、场景、异议、动作、话术、可复用点、时效与重复；列出同场景成功/失败案例供对照。不改内容。' },
              { label: 'AI 派生', desc: '点击后先弹确认：基于本条案例生成「知识卡 + 评测题 + 场景微练」。结果只预览、不自动落库，需按提示复制到知识库/评测中心/培训计划。' },
              { label: '重建索引', desc: '仅「已生效但索引未完成/失败」时出现。异步重新向量化，稍后刷新看索引徽标。' },
              { label: '提交到公共库', desc: '仅本企业已生效案例可用。生成公共待审候选，平台审核通过后所有企业可见；提交前请确认不含客户敏感信息。' },
              { label: '放弃草稿', desc: '仅草稿可用。标记当前草稿为下架并回到原版本，不删除记录。' },
              { label: '复制到本企业', desc: '仅公共案例出现。复制一份企业私有草稿后再改，不改动公共原文。' },
              { label: '下架', desc: '立即停止被分身引用，30 天内可恢复；已下架案例仍可查看版本和引用历史。', tone: 'danger' },
              { label: '重新上架', desc: '仅已下架可用。原本生效的重新索引并参与检索；草稿/待审回到待处理状态。' },
              { label: '彻底删除', desc: '不可恢复，仅草稿/待审/已下架可用；删除前校验派生版本和培训任务引用。', tone: 'danger' },
              { label: '查看原始文档', desc: '有上传来源时出现，打开解析前的原文件（只读）。' },
              { label: '查看 / 以此版本修改', desc: '版本历史中「查看」打开该版本全文；「以此版本修改」用旧版本内容生成新草稿，不改历史版本。' },
            ]}
            notes={[
              '审核 ≠ 立刻可检索：通过后还要等索引徽标变成「已索引」，否则分身仍然引用不到。',
              '「下架」=立即停止引用，30 天内可重新上架；「彻底删除」=不可恢复，仅草稿/待审/已下架可用。',
              '公共案例需要先「复制到本企业」再修改。',
            ]}
          />
        </div>

        <div className="mb-6 rounded-2xl border border-border bg-surface p-6">
        <div className="flex items-start justify-between gap-4">
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

        {!canWrite && (
          <div className="mb-4 rounded-lg border border-warning-text/30 bg-warning-bg px-3 py-2 text-xs leading-5 text-warning-text">
            公共案例由平台维护，企业账号只读；需要修改请先「复制到本企业」，改自己企业的副本。
          </div>
        )}

        <div className="mb-5 rounded-lg bg-surface-2 px-3 py-2 text-xs leading-5 text-muted-foreground">
          <b className="text-foreground">下一步：</b>{nextStep}
        </div>

        <div className="mb-3 flex flex-wrap items-center gap-2 border-y border-border py-3">
          {canWrite && (c.status === 'pending' || c.status === 'draft') && (
            <>
              <Button variant="primary" onClick={onApprove} disabled={acting || !!c.qualityIssues}
                title={c.qualityIssues ? '请先补齐未达标的字段' : undefined}>
                <Check size={16} weight="bold" />通过并生效
              </Button>
              <Button variant="neutral" onClick={() => router.push(`/admin/cases/${c.id}/edit`)} disabled={acting}>
                <PencilSimple size={16} />编辑
              </Button>
            </>
          )}
          {canWrite && (
            <Button variant="secondary" onClick={onSuggest} disabled={acting} title="按指令改内容；已生效案例会生成新版本草稿">
              <PencilSimple size={16} />AI 改写
            </Button>
          )}
          {c.companyId === null && !isSuperAdmin && (
            <Button variant="secondary" onClick={onCopy} disabled={acting}>
              <Copy size={16} />复制到本企业
            </Button>
          )}
          <Button variant="neutral" onClick={onHealth} disabled={acting} title="只做确定性检查，不改内容">
            <FirstAidKit size={16} />AI 体检
          </Button>
          <Button variant="secondary" onClick={requestDerive} disabled={deriving}
            title="生成知识卡、评测题与场景微练，仅预览不保存">
            <ArrowsSplit size={16} />{deriving ? '派生中…' : 'AI 派生'}
          </Button>

          <span className="mx-1 hidden h-5 w-px bg-border sm:block" aria-hidden="true" />

          {canWrite && st === 'active' && c.embeddingStatus !== 'indexed' && (
            <Button variant="secondary" onClick={onReindex} disabled={acting}>
              <ArrowCounterClockwise size={16} />重建索引
            </Button>
          )}
          {canWrite && c.companyId !== null && c.status === 'active' && (
            <Button variant="neutral" onClick={onSubmitPublic} disabled={acting}>
              <PaperPlaneTilt size={16} />提交到公共库
            </Button>
          )}
          {canWrite && isDraft && (
            <Button variant="neutral" onClick={onDiscardDraft} disabled={acting}>
              <Archive size={16} />放弃草稿
            </Button>
          )}
          {canWrite && c.status !== 'deprecated' && (
            <Button variant="danger" onClick={onDeprecate} disabled={acting}>
              <Archive size={16} />下架
            </Button>
          )}
          {canWrite && c.status === 'deprecated' && (
            <Button variant="secondary" onClick={onRestore} disabled={acting}>
              <ArrowCounterClockwise size={16} />重新上架
            </Button>
          )}
          {canWrite && c.status !== 'active' && (
            <Button variant="danger" onClick={onDelete} disabled={acting}>
              <Trash size={16} />彻底删除
            </Button>
          )}
        </div>
        <p className="mb-5 text-[11px] leading-5 text-muted-foreground">
          AI 改写：改内容，已生效会生成新版本草稿 · AI 体检：只查问题不改内容 ·
          AI 派生：生成本条案例的知识卡、评测题与场景微练，仅预览不保存
        </p>
        </div>

        <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="min-w-0 space-y-4">

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

        {c.generated && (
          <div className="rounded-xl border border-primary/30 bg-primary-light px-4 py-3">
            <p className="text-xs font-medium text-primary">AI 生成草稿 · 生成依据</p>
            <div className="mt-1">
              <EvidencePanel evidence={c.evidence ?? null} jobId={c.generationJobId} />
            </div>
          </div>
        )}

          </div>
          <aside className="space-y-4 lg:sticky lg:top-6">
          <div className="rounded-2xl border border-border bg-surface p-5">
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
          </aside>
        </div>
      </div>

      <RevisionModal
        open={showRevision}
        title="AI 改写案例"
        behaviorNote={c.status === 'active'
          ? '已生效内容：生成新版本草稿，审核通过后才替换旧版。'
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

      <DerivedContentModal open={deriveOpen} source={derived} sourceType="case"
        onClose={() => setDeriveOpen(false)} />

      <ConfirmDialog
        open={deriveConfirmOpen}
        title="用这条案例做 AI 派生？"
        confirmLabel="开始派生"
        onConfirm={onDerive}
        onCancel={() => setDeriveConfirmOpen(false)}
      >
        <p>AI 会基于本条案例的客户画像、异议、动作和话术生成知识卡、评测题和场景微练。</p>
        <ul className="list-disc space-y-1 pl-5">
          <li>结果只用于预览和复制，不会自动入库、不会生成草稿，也不会修改原案例。</li>
          <li>生成通常需要 10–60 秒；重复点击会重复消耗模型额度。</li>
          <li>生成后请人工检查，再复制到知识库、评测中心或培训计划。</li>
        </ul>
      </ConfirmDialog>

      <ExpiryDialog
        open={showApprove}
        title="通过并生效"
        description={`「${c.title}」通过后会被分身检索，可设置失效时间。`}
        onCancel={() => setShowApprove(false)}
        onConfirm={iso => {
          setShowApprove(false);
          runAction(() => approveCase(c.id, iso));
        }}
      />
    </div>
  );
}
