'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';

interface Notice {
  id: string;
  type: string;
  title: string;
  content: string | null;
  link: string | null;
  read: boolean;
  createdAt: string;
}

/** NT-01/NT-04：C 端站内信铃铛 —— 未读数、按未读筛选、标记已读、删除 */
export function NotificationBell() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<Notice[]>([]);
  const [unread, setUnread] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const boxRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [page, count] = await Promise.all([
        apiClient<{ content: Notice[] }>(`/me/notifications?page=1&size=20&unreadOnly=${unreadOnly}`),
        apiClient<{ count: number }>('/me/notifications/unread-count'),
      ]);
      setItems(page.content || []);
      setUnread(count.count || 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [unreadOnly]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  const openItem = async (n: Notice) => {
    if (!n.read) {
      try {
        await apiClient(`/me/notifications/${n.id}/read`, { method: 'POST' });
      } catch { /* 标记失败不阻塞跳转 */ }
    }
    setOpen(false);
    if (n.link) router.push(n.link);
    else load();
  };

  const markAll = async () => {
    try {
      await apiClient('/me/notifications/read-all', { method: 'POST' });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    }
  };

  const remove = async (id: string) => {
    try {
      await apiClient(`/me/notifications/${id}`, { method: 'DELETE' });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    }
  };

  return (
    <div ref={boxRef} style={{ position: 'relative' }}>
      <button
        type="button"
        aria-label="站内信"
        onClick={() => { setOpen(v => !v); if (!open) load(); }}
        style={{
          position: 'relative', width: 34, height: 34, borderRadius: 10,
          border: '1px solid var(--s5)', background: 'var(--s2)', cursor: 'pointer',
          fontSize: 15,
        }}
      >
        🔔
        {unread > 0 && (
          <span style={{
            position: 'absolute', top: -4, right: -4, minWidth: 16, height: 16,
            borderRadius: 8, background: 'var(--tangerine)', color: '#fff',
            fontSize: 10, lineHeight: '16px', padding: '0 4px', fontWeight: 700,
          }}>{unread > 99 ? '99+' : unread}</span>
        )}
      </button>

      {open && (
        <div style={{
          position: 'absolute', right: 0, top: 42, width: 340, maxHeight: 420,
          overflow: 'auto', borderRadius: 14, border: '1px solid var(--s5)',
          background: 'var(--bg)', boxShadow: '0 12px 32px rgba(0,0,0,.12)', zIndex: 200,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', borderBottom: '1px solid var(--s5)' }}>
            {(['全部', '未读'] as const).map((label, i) => (
              <button key={label} type="button"
                onClick={() => setUnreadOnly(i === 1)}
                style={{
                  padding: '3px 10px', borderRadius: 100, fontSize: 12, cursor: 'pointer',
                  border: 'none', background: unreadOnly === (i === 1) ? 'var(--s12)' : 'transparent',
                  color: unreadOnly === (i === 1) ? '#fff' : 'var(--fg-mid)',
                }}>{label}</button>
            ))}
            <button type="button" onClick={markAll}
              style={{ marginLeft: 'auto', border: 'none', background: 'none', fontSize: 12, color: 'var(--fg-mid)', cursor: 'pointer' }}>
              全部已读
            </button>
          </div>

          {loading && <p style={{ padding: 16, fontSize: 13, color: 'var(--fg-mid)' }}>加载中…</p>}
          {error && (
            <div style={{ padding: 16, fontSize: 13 }}>
              <p style={{ color: '#c0392b' }}>{error}</p>
              <button type="button" onClick={load} style={{ marginTop: 6, fontSize: 12, cursor: 'pointer' }}>重试</button>
            </div>
          )}
          {!loading && !error && items.length === 0 && (
            <p style={{ padding: 20, fontSize: 13, color: 'var(--fg-mid)', textAlign: 'center' }}>暂无通知</p>
          )}
          {!loading && !error && items.map(n => (
            <div key={n.id} style={{
              display: 'flex', gap: 8, padding: '10px 12px',
              borderBottom: '1px solid var(--s3)', background: n.read ? 'transparent' : 'var(--s2)',
            }}>
              <button type="button" onClick={() => openItem(n)}
                style={{ flex: 1, textAlign: 'left', border: 'none', background: 'none', cursor: 'pointer', padding: 0 }}>
                <p style={{ fontSize: 13, fontWeight: n.read ? 400 : 600, color: 'var(--fg-high)', margin: 0 }}>{n.title}</p>
                {n.content && (
                  <p style={{ fontSize: 12, color: 'var(--fg-mid)', margin: '3px 0 0', overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
                    {n.content}
                  </p>
                )}
                <p style={{ fontSize: 11, color: 'var(--fg-low, #999)', margin: '4px 0 0' }}>
                  {new Date(n.createdAt).toLocaleString()}
                </p>
              </button>
              <button type="button" aria-label="删除通知" onClick={() => remove(n.id)}
                style={{ border: 'none', background: 'none', color: 'var(--fg-mid)', cursor: 'pointer', fontSize: 13 }}>
                ✕
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
