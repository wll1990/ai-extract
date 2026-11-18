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
import { ActionLegend } from '@/components/sales/ActionLegend';
import { Button } from '@/components/ui/Button';
import { PageHelp } from '@/components/ui/PageHelp';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

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
  useScrollOnPageChange(page);
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
    <div className="min-h-screen bg-surface px-6 py-8" data-tour="coach-ledger">
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[28px] font-bold leading-tight text-foreground">教练台账</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              同事用你的分身做的复盘，以及他们请你补充的问题。默认只看摘要；对方授权后才能看原文。
            </p>
          </div>
          <PageHelp
            title="教练台账使用说明"
            intro="你作为教练，能在这里看到谁用你的分身复盘过，并回复同事请你补充的问题。"
            steps={[
              { title: '看复盘记录', desc: '每次同事用你的分身复盘都会留一条记录；点「查看原文」需要对方授权。' },
              { title: '处理请求', desc: '「待我补充」是同事请你补充判断的请求；可以逐条或批量回复。' },
              { title: '标记已读', desc: '只是把未读状态改为已读，不等于回复；回复后请求才进入已回复。' },
              { title: '转交', desc: '请求不属于你时，转交给其他有教练分身权限的同事。' },
            ]}
            actions={[
              { label: '复盘记录 / 待我补充', desc: '切换两个列表；切换会重置到第 1 页。' },
              { label: '查看原文', desc: '仅当发起人勾选「允许教练看原文」时可见；否则只能看摘要。' },
              { label: '标记已读', desc: '把请求从未读改为待处理，不回复内容；可批量。' },
              { label: '回复', desc: '给发起人补充你的判断；批量回复会把同一内容发给选中的全部请求。' },
              { label: '转交', desc: '把请求交给其他教练；一次只能转交一条。' },
              { label: '状态筛选 / 只看指派给我的', desc: '筛选待处理、已回复或全部请求，只影响列表。' },
            ]}
            notes={[
              '教练台账只展示用你的分身发起的复盘，不会展示其他教练的记录。',
              '转交和回复都会通知发起人；已回复的请求仍保留在列表里。',
            ]}
          />
        </div>

        <div className="mb-3 inline-flex items-center gap-1 rounded-xl border border-border bg-surface-2 p-1">
          {([
            ['history', '复盘记录'],
            ['requests', '待我补充'],
          ] as const).map(([key, label]) => (
            <button
              key={key}
              type="button"
              onClick={() => { setTab(key); setPage(1); }}
              className={`rounded-lg px-3 py-1.5 text-sm transition-colors ${
                tab === key ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        <ActionLegend
          className="mb-4"
          items={[
            { label: '查看原文', desc: '只在发起人授权时出现；未授权时只能看摘要。' },
            { label: '标记已读 / 回复', desc: '已读只是状态，回复才会把内容发给发起人；可批量回复。' },
            { label: '转交', desc: '把请求交给其他教练；只影响请求负责人，不改复盘归属。' },
          ]}
        />

        {error && (
          <div className="mb-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

      {tab === 'history' ? (
        <div className="overflow-hidden rounded-xl border border-border bg-surface-2">
          <table className="w-full min-w-[820px] text-left text-sm">
            <thead className="bg-surface text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">销售</th>
                <th className="px-4 py-3 font-medium">客户</th>
                <th className="px-4 py-3 font-medium">主题</th>
                <th className="px-4 py-3 font-medium">摘要</th>
                <th className="px-4 py-3 font-medium">原文</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={5} className="px-4 py-10 text-center text-muted-foreground">加载中…</td>
                </tr>
              )}
              {!loading && rows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-12 text-center text-muted-foreground">
                    {hasPublished === false ? (
                      <>
                        你还没有已发布的分身，同事暂时无法找你复盘。
                        <Link href="/admin/skills/upload" className="ml-2 text-primary hover:underline">
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
                  <tr key={r.sessionId} className="border-t border-border hover:bg-surface">
                    <td className="px-4 py-3 text-foreground">{r.reviewerName || '—'}</td>
                    <td className="px-4 py-3 text-foreground">{r.customerName || '—'}</td>
                    <td className="px-4 py-3 text-muted-foreground">{r.topic || '—'}</td>
                    <td className="max-w-xs truncate px-4 py-3 text-xs text-muted-foreground">
                      {r.summary
                        || (r.reportStatus === 'failed'
                          ? `生成失败：${r.reportError || '未知原因'}`
                          : r.reportStatus
                            ? '报告生成中'
                            : '未生成报告')}
                    </td>
                    <td className="px-4 py-3">
                      {r.transcriptShared ? (
                        <button
                          type="button"
                          onClick={() => openTranscript(r)}
                          title="发起人已授权，可查看完整对话"
                          className="text-xs text-primary hover:underline"
                        >
                          查看原文
                        </button>
                      ) : (
                        <span className="text-xs text-muted-foreground-2">未授权</span>
                      )}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-surface-2 px-3 py-2 text-sm">
            <select
              value={statusFilter}
              onChange={e => { setStatusFilter(e.target.value as 'pending' | 'done' | ''); setPage(1); }}
              className="rounded-md border border-border-strong bg-surface px-2 py-1 text-xs text-foreground"
              title="筛选请求状态；只影响列表"
            >
              <option value="pending">待处理</option>
              <option value="done">已回复</option>
              <option value="">全部状态</option>
            </select>
            <label className="flex items-center gap-1 text-xs text-muted-foreground">
              <input type="checkbox" checked={mineOnly} onChange={e => { setMineOnly(e.target.checked); setPage(1); }} />
              只看指派给我的
            </label>
            {selected.size > 0 && (
              <div className="ml-auto flex items-center gap-2">
                <span className="text-xs text-muted-foreground">已选 {selected.size} 条</span>
                <Button variant="neutral" size="sm" disabled={busy} onClick={runMarkRead}
                  title="只改未读状态，不发送回复">
                  标记已读
                </Button>
                <Button variant="primary" size="sm" disabled={busy} onClick={() => openReply(Array.from(selected), `回复 ${selected.size} 条请求`)}
                  title="同一内容会发给选中的全部请求">
                  批量回复
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>取消选择</Button>
              </div>
            )}
          </div>

          {loading && <p className="py-10 text-center text-sm text-muted-foreground">加载中…</p>}
          {!loading && requests.length === 0 && (
            <p className="rounded-xl border border-border bg-surface-2 py-12 text-center text-sm text-muted-foreground">
              {statusFilter === 'pending' ? '暂时没有待处理请求' : '没有符合条件的请求'}
            </p>
          )}
          {!loading && requests.map(r => {
            const unread = !r.readAt && r.status === 'pending';
            return (
              <div key={r.id} className={`rounded-xl border p-4 ${
                unread ? 'border-primary/30 bg-primary-light/30' : 'border-border bg-surface-2'
              }`}>
                <div className="flex items-start gap-3">
                  <input type="checkbox" className="mt-1"
                    checked={selected.has(r.id)} onChange={() => toggleSel(r.id)} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-sm font-semibold text-foreground">
                        {r.reviewerName || '同事'} · {r.customerName || '未命名客户'}
                        <span className={`ml-2 rounded-full px-2 py-0.5 text-[11px] ${
                          r.status === 'done' ? 'bg-success-bg text-success'
                            : r.status === 'cancelled' ? 'bg-surface text-muted-foreground'
                              : unread ? 'bg-primary-light text-primary' : 'bg-warning-bg text-warning-text'
                        }`}>
                          {r.status === 'done' ? '已回复' : r.status === 'cancelled' ? '已撤回' : unread ? '未读' : '待处理'}
                        </span>
                      </span>
                      <span className="text-xs text-muted-foreground">{new Date(r.requestedAt).toLocaleString()}</span>
                    </div>
                    <p className="mt-1.5 text-sm text-foreground">{r.message || '没有留言'}</p>
                    {r.replyContent && (
                      <p className="mt-1.5 rounded-lg bg-surface px-2.5 py-1.5 text-xs text-muted-foreground">
                        你的回复：{r.replyContent}
                      </p>
                    )}
                    <div className="mt-2.5 flex flex-wrap items-center gap-3 text-xs">
                      <Link href={`/sales/coach/requests/${r.sessionId}`} className="text-primary hover:underline">
                        查看复盘
                      </Link>
                      {r.status === 'pending' && (
                        <>
                          {unread && (
                            <button type="button" disabled={busy}
                              onClick={async () => { await markCoachRequestsRead([r.id]); await load(); }}
                              title="只改未读状态，不发送回复"
                              className="text-muted-foreground hover:underline disabled:opacity-50">标记已读</button>
                          )}
                          <button type="button" onClick={() => openReply([r.id], `${r.reviewerName || '同事'} · ${r.customerName || '未命名客户'}`)}
                            title="补充你的判断，发送给发起人"
                            className="text-primary hover:underline">回复</button>
                          <button type="button" onClick={() => openTransfer([r.id], `${r.reviewerName || '同事'} · ${r.customerName || '未命名客户'}`)}
                            title="把这条请求交给其他教练"
                            className="text-muted-foreground hover:underline">转交</button>
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
          <Button variant="neutral" size="sm" disabled={page <= 1 || loading} onClick={() => setPage(p => p - 1)}>
            上一页
          </Button>
          <span className="text-muted-foreground">{page} / {totalPages}</span>
          <Button variant="neutral" size="sm" disabled={page >= totalPages || loading} onClick={() => setPage(p => p + 1)}>
            下一页
          </Button>
        </div>
      </div>

      {modal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={() => !busy && setModal(null)}>
          <div role="dialog" aria-modal="true" aria-label={modal.title}
            className="w-full max-w-md rounded-2xl bg-surface p-5 shadow-xl"
            onClick={e => e.stopPropagation()}>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-base font-semibold text-foreground">
                {modal.type === 'reply' ? '回复教练请求' : '转交给其他同事'}
              </h3>
              <button type="button" aria-label="关闭" onClick={() => !busy && setModal(null)} className="text-muted-foreground">✕</button>
            </div>
            <p className="mb-3 text-xs text-muted-foreground">{modal.title}</p>
            {modal.type === 'reply' ? (
              <textarea
                value={replyText}
                onChange={e => setReplyText(e.target.value)}
                rows={5}
                placeholder="给发起人的回复建议…（同一内容会回复给选中全部）"
                className="w-full rounded-lg border border-border-strong bg-surface-2 px-3 py-2 text-sm text-foreground"
              />
            ) : (
              <select value={transferTo} onChange={e => setTransferTo(e.target.value)}
                className="w-full rounded-lg border border-border-strong bg-surface-2 px-3 py-2 text-sm text-foreground">
                <option value="">选择转交对象</option>
                {coaches.map(c => <option key={c.id} value={c.id}>{c.name}（{c.role}）</option>)}
              </select>
            )}
            <div className="mt-4 flex justify-end gap-2">
              <Button variant="neutral" disabled={busy} onClick={() => setModal(null)}>取消</Button>
              <Button variant="primary" disabled={busy} onClick={submitModal}>
                {busy ? '提交中…' : '提交'}
              </Button>
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
            className="max-h-[75vh] w-full max-w-2xl overflow-auto rounded-2xl bg-surface p-5 shadow-xl"
            onClick={e => e.stopPropagation()}
          >
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-base font-semibold text-foreground">{transcript.title}</h3>
              <button type="button" aria-label="关闭" onClick={() => setTranscript(null)} className="text-muted-foreground">
                ✕
              </button>
            </div>
            <div className="space-y-2">
              {transcript.segments.map(s => (
                <div key={s.seq} className="rounded-lg bg-surface-2 p-2 text-sm text-foreground">
                  <span className="mr-2 text-xs text-muted-foreground">第 {s.seq} 段</span>
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
