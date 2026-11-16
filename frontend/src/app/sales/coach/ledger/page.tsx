'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import {
  CoachLedgerRow,
  CoachRequestRow,
  getCoachLedger,
  getCoachTranscript,
  listCoachRequests,
  markCoachRequestsRead,
  replyCoachRequestsBatch,
  transferCoachRequest,
  listCoachCandidates,
} from '@/lib/api/sales';
import { listSkills } from '@/lib/api/skill';
import { getUser } from '@/lib/storage';

interface TranscriptSegment {
  seq: number;
  speaker: string;
  text: string;
}

export default function CoachLedgerPage() {
  const [tab, setTab] = useState<'history' | 'requests'>('history');
  const [rows, setRows] = useState<CoachLedgerRow[]>([]);
  const [requests, setRequests] = useState<CoachRequestRow[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // null=未知，false=当前账号还没有已发布分身（台账必然为空）
  const [hasPublished, setHasPublished] = useState<boolean | null>(null);
  const [transcript, setTranscript] = useState<{
    title: string;
    segments: TranscriptSegment[];
  } | null>(null);

  // SA-08 台账任务化
  const [statusFilter, setStatusFilter] = useState<'pending' | 'done' | ''>('pending');
  const [mineOnly, setMineOnly] = useState(true);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [modal, setModal] = useState<{ type: 'reply' | 'transfer'; ids: string[]; title: string } | null>(null);
  const [replyText, setReplyText] = useState('');
  const [coaches, setCoaches] = useState<Array<{ id: string; name: string; role: string }>>([]);
  const [transferTo, setTransferTo] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (tab === 'history') {
        const res = await getCoachLedger(page, 20);
        setRows(res.content);
        setTotalPages(res.totalPages);
      } else {
        const res = await listCoachRequests({
          page, size: 20,
          status: statusFilter || undefined,
          mine: mineOnly,
        });
        setRequests(res.content);
        setTotalPages(res.totalPages);
        setSelected(new Set());
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [tab, page, statusFilter, mineOnly]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const userId = (getUser() as { id?: string } | null)?.id;
    if (!userId) return;
    listSkills(1, 1, 'published', userId, 'individual')
      .then(d => setHasPublished((d.content?.length || 0) > 0))
      .catch(() => setHasPublished(null));
  }, []);

  const openTranscript = async (row: CoachLedgerRow) => {
    try {
      const res = await getCoachTranscript(row.sessionId);
      setTranscript({
        title: `${row.reviewerName || '同事'} · ${row.customerName || '未命名客户'}`,
        segments: res.segments,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '原文不可查看');
    }
  };

  const toggleSel = (id: string) => setSelected(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const runMarkRead = async () => {
    if (selected.size === 0) return;
    setBusy(true);
    try {
      await markCoachRequestsRead(Array.from(selected));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '标记已读失败');
    } finally {
      setBusy(false);
    }
  };

  const openReply = (ids: string[], title: string) => {
    if (ids.length === 0) return;
    setReplyText('');
    setModal({ type: 'reply', ids, title });
  };

  const openTransfer = async (ids: string[], title: string) => {
    if (ids.length === 0) return;
    setTransferTo('');
    try {
      if (coaches.length === 0) setCoaches(await listCoachCandidates());
    } catch { /* 列表失败在弹层里提示 */ }
    setModal({ type: 'transfer', ids, title });
  };

  const submitModal = async () => {
    if (!modal) return;
    setBusy(true);
    try {
      if (modal.type === 'reply') {
        const text = replyText.trim();
        if (!text) { alert('请输入回复内容'); setBusy(false); return; }
        await replyCoachRequestsBatch(modal.ids, text);
      } else {
        if (!transferTo) { alert('请选择转交对象'); setBusy(false); return; }
        await transferCoachRequest(modal.ids[0], transferTo);
      }
      setModal(null);
      await load();
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-5xl p-6" data-tour="coach-ledger">
      <h1 className="text-xl font-semibold text-gray-900">教练台账</h1>
      <p className="mb-4 text-sm text-gray-500">
        谁找你复盘过。默认只看摘要；对方授权后才能看原文。
      </p>

      <div className="mb-3 flex gap-4 text-sm">
        <button
          type="button"
          onClick={() => {
            setTab('history');
            setPage(1);
          }}
          className={tab === 'history' ? 'font-medium text-gray-900' : 'text-gray-500'}
        >
          复盘记录
        </button>
        <button
          type="button"
          onClick={() => {
            setTab('requests');
            setPage(1);
          }}
          className={tab === 'requests' ? 'font-medium text-gray-900' : 'text-gray-500'}
        >
          待我补充
        </button>
      </div>

      {error && (
        <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      {tab === 'history' ? (
        <div className="overflow-hidden rounded border border-gray-200">
          <table className="w-full text-left text-sm">
            <thead className="bg-gray-50 text-gray-600">
              <tr>
                <th className="px-4 py-2">销售</th>
                <th className="px-4 py-2">客户</th>
                <th className="px-4 py-2">主题</th>
                <th className="px-4 py-2">摘要</th>
                <th className="px-4 py-2">原文</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-gray-500">
                    加载中…
                  </td>
                </tr>
              )}
              {!loading && rows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-gray-500">
                    {hasPublished === false ? (
                      <>
                        你还没有已发布的分身，同事暂时无法找你复盘。
                        <Link href="/admin/skills/upload" className="ml-2 text-blue-600 hover:underline">
                          先上传素材创建分身 →
                        </Link>
                      </>
                    ) : (
                      '还没有人找你的分身复盘'
                    )}
                  </td>
                </tr>
              )}
              {!loading &&
                rows.map(r => (
                  <tr key={r.sessionId} className="border-t border-gray-100">
                    <td className="px-4 py-2">{r.reviewerName || '—'}</td>
                    <td className="px-4 py-2">{r.customerName || '—'}</td>
                    <td className="px-4 py-2">{r.topic || '—'}</td>
                    <td className="max-w-xs truncate px-4 py-2 text-gray-600">
                      {r.summary
                        || (r.reportStatus === 'failed'
                          ? `生成失败：${r.reportError || '未知原因'}`
                          : r.reportStatus
                            ? '报告生成中'
                            : '未生成报告')}
                    </td>
                    <td className="px-4 py-2">
                      {r.transcriptShared ? (
                        <button
                          type="button"
                          onClick={() => openTranscript(r)}
                          className="text-blue-600 hover:underline"
                        >
                          查看原文
                        </button>
                      ) : (
                        <span className="text-xs text-gray-400">未授权</span>
                      )}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <select
              value={statusFilter}
              onChange={e => { setStatusFilter(e.target.value as 'pending' | 'done' | ''); setPage(1); }}
              className="rounded border border-gray-300 px-2 py-1"
            >
              <option value="pending">待处理</option>
              <option value="done">已回复</option>
              <option value="">全部状态</option>
            </select>
            <label className="flex items-center gap-1 text-gray-600">
              <input type="checkbox" checked={mineOnly} onChange={e => { setMineOnly(e.target.checked); setPage(1); }} />
              只看指派给我的
            </label>
            {selected.size > 0 && (
              <div className="ml-auto flex items-center gap-2">
                <span className="text-xs text-gray-500">已选 {selected.size} 条</span>
                <button type="button" disabled={busy} onClick={runMarkRead}
                  className="rounded border border-gray-300 px-3 py-1 text-xs disabled:opacity-50">标记已读</button>
                <button type="button" disabled={busy} onClick={() => openReply(Array.from(selected), `回复 ${selected.size} 条请求`)}
                  className="rounded bg-blue-600 px-3 py-1 text-xs text-white disabled:opacity-50">批量回复</button>
                <button type="button" onClick={() => setSelected(new Set())}
                  className="text-xs text-gray-500 hover:underline">取消</button>
              </div>
            )}
          </div>

          {loading && <p className="py-8 text-center text-sm text-gray-500">加载中…</p>}
          {!loading && requests.length === 0 && (
            <p className="py-8 text-center text-sm text-gray-500">
              {statusFilter === 'pending' ? '暂时没有待处理请求' : '没有符合条件的请求'}
            </p>
          )}
          {!loading && requests.map(r => {
            const unread = !r.readAt && r.status === 'pending';
            return (
              <div key={r.id} className={`rounded border p-3 ${unread ? 'border-blue-200 bg-blue-50/40' : 'border-gray-200'}`}>
                <div className="flex items-start gap-3">
                  <input type="checkbox" className="mt-1"
                    checked={selected.has(r.id)} onChange={() => toggleSel(r.id)} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-sm font-medium text-gray-900">
                        {r.reviewerName || '同事'} · {r.customerName || '未命名客户'}
                        <span className={`ml-2 rounded-full px-2 py-0.5 text-[11px] ${
                          r.status === 'done' ? 'bg-green-100 text-green-700'
                            : r.status === 'cancelled' ? 'bg-gray-100 text-gray-500'
                              : unread ? 'bg-blue-100 text-blue-700' : 'bg-amber-100 text-amber-700'
                        }`}>
                          {r.status === 'done' ? '已回复' : r.status === 'cancelled' ? '已撤回' : unread ? '未读' : '待处理'}
                        </span>
                      </span>
                      <span className="text-xs text-gray-500">{new Date(r.requestedAt).toLocaleString()}</span>
                    </div>
                    <p className="mt-1 text-sm text-gray-600">{r.message || '没有留言'}</p>
                    {r.replyContent && (
                      <p className="mt-1 rounded bg-gray-50 px-2 py-1 text-xs text-gray-600">
                        你的回复：{r.replyContent}
                      </p>
                    )}
                    <div className="mt-2 flex flex-wrap gap-3 text-xs">
                      <Link href={`/sales/coach/requests/${r.sessionId}`} className="text-blue-600 hover:underline">查看复盘</Link>
                      {r.status === 'pending' && (
                        <>
                          {unread && (
                            <button type="button" disabled={busy}
                              onClick={async () => { await markCoachRequestsRead([r.id]); await load(); }}
                              className="text-gray-600 hover:underline disabled:opacity-50">标记已读</button>
                          )}
                          <button type="button" onClick={() => openReply([r.id], `${r.reviewerName || '同事'} · ${r.customerName || '未命名客户'}`)}
                            className="text-blue-600 hover:underline">回复</button>
                          <button type="button" onClick={() => openTransfer([r.id], `${r.reviewerName || '同事'} · ${r.customerName || '未命名客户'}`)}
                            className="text-gray-600 hover:underline">转交</button>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <div className="mt-3 flex items-center justify-end gap-2 text-sm">
        <button
          disabled={page <= 1 || loading}
          onClick={() => setPage(p => p - 1)}
          className="rounded border px-3 py-1.5 disabled:opacity-50"
        >
          上一页
        </button>
        <span className="text-gray-500">
          {page} / {totalPages}
        </span>
        <button
          disabled={page >= totalPages || loading}
          onClick={() => setPage(p => p + 1)}
          className="rounded border px-3 py-1.5 disabled:opacity-50"
        >
          下一页
        </button>
      </div>

      {modal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={() => !busy && setModal(null)}>
          <div role="dialog" aria-modal="true" aria-label={modal.title}
            className="w-full max-w-md rounded bg-white p-5"
            onClick={e => e.stopPropagation()}>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-base font-medium">
                {modal.type === 'reply' ? '回复教练请求' : '转交给其他同事'}
              </h3>
              <button type="button" aria-label="关闭" onClick={() => !busy && setModal(null)} className="text-gray-400">✕</button>
            </div>
            <p className="mb-3 text-xs text-gray-500">{modal.title}</p>
            {modal.type === 'reply' ? (
              <textarea
                value={replyText}
                onChange={e => setReplyText(e.target.value)}
                rows={5}
                placeholder="给发起人的回复建议…（同一内容会回复给选中全部）"
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
              />
            ) : (
              <select value={transferTo} onChange={e => setTransferTo(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm">
                <option value="">选择转交对象</option>
                {coaches.map(c => <option key={c.id} value={c.id}>{c.name}（{c.role}）</option>)}
              </select>
            )}
            <div className="mt-4 flex justify-end gap-2">
              <button type="button" disabled={busy} onClick={() => setModal(null)}
                className="rounded border px-4 py-1.5 text-sm disabled:opacity-50">取消</button>
              <button type="button" disabled={busy} onClick={submitModal}
                className="rounded bg-blue-600 px-4 py-1.5 text-sm text-white disabled:opacity-50">
                {busy ? '提交中…' : '提交'}
              </button>
            </div>
          </div>
        </div>
      )}

      {transcript && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={() => setTranscript(null)}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label={transcript.title}
            className="max-h-[75vh] w-full max-w-2xl overflow-auto rounded bg-white p-5"
            onClick={e => e.stopPropagation()}
          >
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-base font-medium">{transcript.title}</h3>
              <button type="button" aria-label="关闭" onClick={() => setTranscript(null)} className="text-gray-400">
                ✕
              </button>
            </div>
            <div className="space-y-2">
              {transcript.segments.map(s => (
                <div key={s.seq} className="rounded bg-gray-50 p-2 text-sm">
                  <span className="mr-2 text-xs text-gray-400">第 {s.seq} 段</span>
                  <span className="whitespace-pre-wrap">{s.text}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
