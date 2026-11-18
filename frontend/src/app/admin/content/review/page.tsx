'use client';

import React, { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  getReviewDrafts, approveReviewDraft, rejectReviewDraft,
  type ReviewDraft, type ReviewKind, type ReviewPathKind,
} from '@/lib/api/content';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';
import EmptyState from '@/components/ui/EmptyState';
import { EvidencePanel } from '@/components/admin/EvidencePanel';
import { ReviewFlowNote } from '@/components/admin/ReviewFlowNote';

const TABS: Array<{ kind: ReviewKind; label: string; path: ReviewPathKind; detail: (d: ReviewDraft) => string }> = [
  { kind: 'knowledge', label: '知识', path: 'knowledge', detail: d => `/admin/knowledge/${d.id}` },
  { kind: 'case', label: '案例', path: 'case', detail: d => `/admin/cases/${d.id}` },
  { kind: 'eval_item', label: '考题', path: 'eval-item', detail: d => `/admin/evaluations/${d.datasetId}` },
];

const PAGE_SIZE = 20;

/**
 * AI 待审视图：知识 / 案例 / 考题统一入口。
 * 行内展示生成证据，一键通过或丢弃（丢弃必须填理由）。
 */
export default function ContentReviewPage() {
  const [kind, setKind] = useState<ReviewKind>('knowledge');
  const [items, setItems] = useState<ReviewDraft[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [rejectTarget, setRejectTarget] = useState<ReviewDraft | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  const tab = TABS.find(t => t.kind === kind)!;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await getReviewDrafts(kind, page, PAGE_SIZE);
      setItems(result.items || []);
      setTotal(result.total || 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [kind, page]);

  useEffect(() => { load(); }, [load]);

  const onApprove = async (draft: ReviewDraft) => {
    if (!window.confirm(`确认通过这条${TAB_LABEL[draft.kind]}草稿？通过后会进入生效/可用状态。`)) return;
    setActing(draft.id);
    try {
      await approveReviewDraft(tabOf(draft.kind).path, draft.id);
      setToast('已通过');
      await load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(null);
    }
  };

  const submitReject = async () => {
    if (!rejectTarget) return;
    if (!rejectReason.trim()) {
      setToast('丢弃必须填写理由');
      return;
    }
    setActing(rejectTarget.id);
    try {
      await rejectReviewDraft(tabOf(rejectTarget.kind).path, rejectTarget.id, rejectReason.trim());
      setToast('已丢弃');
      setRejectTarget(null);
      setRejectReason('');
      await load();
    } catch (e) {
      setToast(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(null);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4">
          <Link href="/admin/content" className="text-sm text-muted-foreground hover:underline">← 返回内容工厂</Link>
          <h1 className="mt-2 text-[26px] font-bold text-foreground">AI 待审内容</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            审核对象是 AI 生成的「知识 / 案例 / 考题」内容，不是审核 AI 模型本身。
            它们和知识库/案例库的「待我审」是同一批记录，通过/丢弃走同一条审核链路；每条都带生成依据。
          </p>
        </div>

        <ReviewFlowNote className="mb-4" />

        <div className="mb-4 flex gap-2">
          {TABS.map(t => (
            <button key={t.kind}
              onClick={() => { setKind(t.kind); setPage(1); }}
              className={`rounded-full px-4 py-1.5 text-sm ${
                kind === t.kind ? 'bg-primary text-white' : 'border border-border text-foreground hover:bg-primary-light'}`}>
              {t.label}
            </button>
          ))}
        </div>

        {loading ? <LoadingSpinner /> : error ? (
          <div className="rounded-xl border border-border bg-surface-2 px-4 py-4 text-sm text-danger">
            {error}
            <button onClick={load} className="ml-3 underline">重试</button>
          </div>
        ) : items.length === 0 ? (
          <EmptyState icon="✅" title="没有待审草稿"
            description="AI 生成的知识/案例/考题会先进入这里，审核后才生效" />
        ) : (
          <div className="space-y-2">
            {items.map(draft => (
              <div key={draft.id} className="rounded-xl border border-border bg-surface-2 p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="mb-1 flex flex-wrap items-center gap-2">
                      <span className="rounded-full bg-primary-light px-2 py-0.5 text-[10px] text-primary">AI 生成</span>
                      {draft.sceneTag && <span className="text-xs text-muted-foreground">场景：{draft.sceneTag}</span>}
                      <span className="text-xs text-muted-foreground">
                        {draft.createdAt ? new Date(draft.createdAt).toLocaleString() : ''}
                      </span>
                      {draft.job && (
                        <span className={`rounded-full px-2 py-0.5 text-[10px] ${
                          draft.job.status === 'failed' ? 'bg-danger-bg text-danger' : 'bg-success-bg text-success'}`}>
                          任务 {draft.job.status}
                        </span>
                      )}
                    </div>
                    <Link href={tab.detail(draft)} className="text-sm font-medium text-foreground hover:underline">
                      {draft.title || '（无标题）'}
                    </Link>
                  </div>
                  <div className="flex gap-2">
                    <button onClick={() => onApprove(draft)} disabled={acting === draft.id}
                      className="rounded-full bg-primary px-3 py-1 text-xs text-white hover:opacity-90 disabled:opacity-50">
                      {acting === draft.id ? '处理中…' : '通过'}
                    </button>
                    <button onClick={() => { setRejectTarget(draft); setRejectReason(''); }}
                      disabled={acting === draft.id}
                      className="rounded-full border border-border px-3 py-1 text-xs text-danger hover:bg-danger-bg disabled:opacity-50">
                      丢弃
                    </button>
                  </div>
                </div>
                <div className="mt-2">
                  <EvidencePanel evidence={draft.evidence} jobId={draft.generationJobId} compact />
                </div>
              </div>
            ))}
          </div>
        )}

        {total > PAGE_SIZE && (
          <div className="mt-4 flex items-center justify-between text-sm text-muted-foreground">
            <span>共 {total} 条</span>
            <div className="flex gap-2">
              <button disabled={page <= 1} onClick={() => setPage(p => p - 1)}
                className="rounded border border-border px-3 py-1 disabled:opacity-40">上一页</button>
              <span className="px-2 py-1">{page} / {totalPages}</span>
              <button disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}
                className="rounded border border-border px-3 py-1 disabled:opacity-40">下一页</button>
            </div>
          </div>
        )}
      </div>

      {rejectTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6"
          onClick={() => setRejectTarget(null)}>
          <div className="w-full max-w-[520px] rounded-xl bg-surface p-5 shadow-xl" onClick={e => e.stopPropagation()}>
            <h3 className="mb-1 text-lg font-semibold text-foreground">丢弃草稿</h3>
            <p className="mb-3 text-xs text-muted-foreground">{rejectTarget.title}</p>
            <textarea value={rejectReason} onChange={e => setRejectReason(e.target.value)}
              rows={3} placeholder="丢弃理由（必填，会记录在条目的审核说明里）"
              className="mb-3 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
            <div className="flex justify-end gap-2">
              <button onClick={() => setRejectTarget(null)}
                className="rounded-full border border-border px-4 py-1.5 text-sm text-foreground">取消</button>
              <button onClick={submitReject} disabled={acting === rejectTarget.id}
                className="rounded-full border border-red-200 bg-danger-bg px-4 py-1.5 text-sm text-danger disabled:opacity-50">
                {acting === rejectTarget.id ? '处理中…' : '确认丢弃'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function tabOf(kind: ReviewKind) {
  return TABS.find(t => t.kind === kind)!;
}

const TAB_LABEL: Record<ReviewKind, string> = {
  knowledge: '知识',
  case: '案例',
  eval_item: '考题',
};
