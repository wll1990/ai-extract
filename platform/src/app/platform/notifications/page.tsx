'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { apiClient } from '@/lib/api/client';

interface NotificationItem {
  id: string;
  type: string;
  title: string;
  content: string | null;
  link: string | null;
  read: boolean;
  createdAt: string | null;
}

/** C 端站内信 — 与 B 端共用 /me/notifications，按当前用户隔离 */
export default function NotificationsPage() {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    setLoading(true);
    setError('');
    apiClient<{ content: NotificationItem[] }>('/me/notifications?page=1&size=50')
      .then(d => setItems(d.content || []))
      .catch((e: unknown) => setError(e instanceof Error && e.message ? e.message : '通知加载失败'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const markAllRead = async () => {
    try {
      await apiClient('/me/notifications/read-all', { method: 'POST' });
      load();
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    }
  };

  return (
    <div className="min-h-screen bg-[#f7f9ff] px-5 py-10">
      <div className="mx-auto w-full max-w-2xl">
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-xl font-bold text-[#0f172a]">站内信</h1>
          <button onClick={markAllRead} className="text-sm text-[#2563EB] hover:underline">全部标为已读</button>
        </div>

        {error && <p className="mb-4 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-600">{error}</p>}

        {loading ? (
          <p className="py-16 text-center text-sm text-[#64748b]">加载中…</p>
        ) : items.length === 0 ? (
          <p className="py-16 text-center text-sm text-[#64748b]">暂无通知</p>
        ) : (
          <div className="space-y-3">
            {items.map(n => (
              <div key={n.id} className={`rounded-xl border px-4 py-3 ${n.read ? 'border-[#e2e8f0] bg-white' : 'border-[#bfdbfe] bg-[#eff6ff]'}`}>
                <div className="flex items-center justify-between gap-3">
                  <p className="text-sm font-medium text-[#0f172a]">{n.title}</p>
                  {!n.read && <span className="shrink-0 rounded-full bg-[#2563EB] px-2 py-0.5 text-[10px] text-white">未读</span>}
                </div>
                {n.content && <p className="mt-1 text-sm text-[#475569]">{n.content}</p>}
                <p className="mt-2 text-xs text-[#94a3b8]">
                  {n.createdAt ? new Date(n.createdAt).toLocaleString('zh-CN') : ''}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
