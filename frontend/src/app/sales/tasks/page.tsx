'use client';

import { useCallback, useEffect, useState } from 'react';
import { TaskView, listTasks, updateTaskStatus } from '@/lib/api/sales';
import { StatusBadge } from '@/components/sales/StatusBadge';
import { TaskEditModal } from '@/components/sales/TaskEditModal';
import { TaskTransferModal } from '@/components/sales/TaskTransferModal';
import { TaskEscalateModal } from '@/components/sales/TaskEscalateModal';

export default function TasksPage() {
  const [rows, setRows] = useState<TaskView[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [status, setStatus] = useState('pending');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<TaskView | null>(null);
  const [transferring, setTransferring] = useState<TaskView | null>(null);
  const [escalating, setEscalating] = useState<TaskView | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await listTasks({ page, size: 20, status: status || undefined });
      setRows(res.content);
      setTotalPages(res.totalPages);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, status]);

  useEffect(() => {
    void load();
  }, [load]);

  // 同路由点通知不会重挂页面，监听全局事件强制刷新
  useEffect(() => {
    const refresh = () => { void load(); };
    window.addEventListener('sales:refresh', refresh);
    return () => window.removeEventListener('sales:refresh', refresh);
  }, [load]);

  const move = async (id: string, next: string) => {
    if (busyId) return;
    setBusyId(id);
    try {
      await updateTaskStatus(id, next);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '状态更新失败');
    } finally {
      setBusyId(null);
    }
  };

  const changed = async () => {
    setEditing(null);
    setTransferring(null);
    setEscalating(null);
    await load();
  };

  return (
    <div className="mx-auto max-w-5xl p-6">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">跟进任务</h1>
          <p className="text-sm text-gray-500">复盘「下一步」直接落成待办。</p>
        </div>
        <select
          value={status}
          onChange={e => {
            setStatus(e.target.value);
            setPage(1);
          }}
          className="rounded border border-gray-300 px-3 py-2 text-sm"
        >
          <option value="">全部</option>
          <option value="pending">待办</option>
          <option value="done">已完成</option>
          <option value="cancelled">已取消</option>
        </select>
      </div>
      {error && (
        <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}
      <div className="space-y-2">
        {loading && <p className="py-8 text-center text-sm text-gray-500">加载中…</p>}
        {!loading && rows.length === 0 && (
          <p className="py-8 text-center text-sm text-gray-500">没有任务</p>
        )}
        {!loading &&
          rows.map(t => {
            const overdue =
              t.status === 'pending' && t.dueAt && new Date(t.dueAt).getTime() < Date.now();
            return (
              <div
                key={t.id}
                className="flex items-start justify-between rounded border border-gray-200 p-3"
              >
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium text-gray-900">{t.title}</span>
                    <StatusBadge status={t.status} />
                    {overdue && <span className="text-xs text-red-600">已逾期</span>}
                    {t.escalatedAt && (
                      <span className="rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-700">
                        已升级
                      </span>
                    )}
                  </div>
                  <p className="mt-1 text-xs text-gray-500">
                    {[t.channel, t.dueAt ? new Date(t.dueAt).toLocaleString() : null]
                      .filter(Boolean)
                      .join(' · ') || '无渠道/截止'}
                    {t.ownerName ? ` · 负责人：${t.ownerName}` : ''}
                  </p>
                  {t.content && <p className="mt-1 text-xs text-gray-600">{t.content}</p>}
                </div>
                {t.status === 'pending' && (
                  <div className="flex flex-wrap justify-end gap-1">
                    <button
                      type="button"
                      disabled={busyId === t.id}
                      onClick={() => move(t.id, 'done')}
                      className="rounded bg-green-600 px-2 py-1 text-xs text-white"
                    >
                      完成
                    </button>
                    <button
                      type="button"
                      disabled={busyId === t.id}
                      onClick={() => move(t.id, 'cancelled')}
                      className="rounded border px-2 py-1 text-xs"
                    >
                      取消
                    </button>
                    <button
                      type="button"
                      onClick={() => setEditing(t)}
                      className="rounded border px-2 py-1 text-xs"
                    >
                      编辑
                    </button>
                    <button
                      type="button"
                      onClick={() => setTransferring(t)}
                      className="rounded border px-2 py-1 text-xs"
                    >
                      转交
                    </button>
                    {overdue && !t.escalatedAt && (
                      <button
                        type="button"
                        onClick={() => setEscalating(t)}
                        className="rounded border border-amber-300 px-2 py-1 text-xs text-amber-700"
                      >
                        升级
                      </button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
      </div>
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

      {editing && (
        <TaskEditModal task={editing} onClose={() => setEditing(null)} onSaved={changed} />
      )}
      {transferring && (
        <TaskTransferModal
          task={transferring}
          onClose={() => setTransferring(null)}
          onTransferred={changed}
        />
      )}
      {escalating && (
        <TaskEscalateModal
          task={escalating}
          onClose={() => setEscalating(null)}
          onEscalated={changed}
        />
      )}
    </div>
  );
}
