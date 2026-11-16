'use client';

import { useCallback, useEffect, useState } from 'react';
import { apiClient } from '@/lib/api/client';
import { Pagination } from '@/components/ui/Pagination';

interface DemoRequestItem {
  id: string;
  companyName: string;
  contactName: string;
  role?: string | null;
  teamSize?: string | null;
  contact: string;
  message?: string | null;
  source: string;
  status: 'new' | 'contacted' | 'closed';
  createdAt: string;
}

const STATUS_META: Record<DemoRequestItem['status'], { label: string; cls: string }> = {
  new: { label: '待跟进', cls: 'bg-amber-100 text-amber-700' },
  contacted: { label: '已联系', cls: 'bg-blue-100 text-blue-700' },
  closed: { label: '已关闭', cls: 'bg-slate-100 text-slate-600' },
};

const FILTERS: Array<{ key: string; label: string }> = [
  { key: '', label: '全部' },
  { key: 'new', label: '待跟进' },
  { key: 'contacted', label: '已联系' },
  { key: 'closed', label: '已关闭' },
];

function formatTime(value: string): string {
  if (!value) return '-';
  // 后端下发 LocalDateTime（无时区），直接按字符串切片，避免本地时区二次换算
  return `${value.slice(5, 10)} ${value.slice(11, 16)}`;
}

export default function DemoRequestsPage() {
  const [items, setItems] = useState<DemoRequestItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    const query = new URLSearchParams({ page: String(page), size: '20' });
    if (status) query.set('status', status);
    apiClient<{ content: DemoRequestItem[]; total: number; totalPages: number }>(
      `/admin/demo-requests?${query.toString()}`,
    )
      .then(d => {
        setItems(d.content || []);
        setTotalPages(d.totalPages || 0);
        setTotalElements(d.total || 0);
        setError('');
      })
      .catch(e => setError(e?.message || '加载失败'))
      .finally(() => setLoading(false));
  }, [page, status]);

  useEffect(() => { load(); }, [load]);

  const changeStatus = async (item: DemoRequestItem, next: DemoRequestItem['status']) => {
    if (item.status === next) return;
    setSavingId(item.id);
    setError('');
    // 乐观更新：失败回滚
    const previous = items;
    setItems(prev => prev.map(x => (x.id === item.id ? { ...x, status: next } : x)));
    try {
      await apiClient(`/admin/demo-requests/${item.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ status: next }),
      });
      if (status) load();
    } catch (e: unknown) {
      setItems(previous);
      setError(e instanceof Error ? e.message : '更新失败');
    } finally {
      setSavingId(null);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1100px]">
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-foreground">预约演示</h1>
          <p className="mt-1 text-xs text-muted-foreground">
            销冠门户公开提交的留资。不归属任何租户，只有平台管理员可见。
          </p>
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          {FILTERS.map(f => (
            <button
              key={f.key}
              onClick={() => { setStatus(f.key); setPage(1); }}
              className={`rounded-full border px-3 py-1.5 text-xs font-semibold transition-colors ${
                status === f.key
                  ? 'border-primary bg-primary text-white'
                  : 'border-border bg-white text-muted-foreground hover:text-foreground'
              }`}
            >
              {f.label}
            </button>
          ))}
          <span className="ml-auto text-xs text-muted-foreground tabular-nums">共 {totalElements} 条</span>
        </div>

        {error && <div className="mb-3 rounded bg-red-50 px-3 py-2 text-xs text-red-600">{error}</div>}

        {loading && items.length === 0 ? (
          <div className="py-16 text-center text-sm text-muted-foreground">加载中...</div>
        ) : items.length === 0 ? (
          <div className="py-16 text-center text-sm text-muted-foreground">
            暂无预约记录。门户提交后会自动出现在这里，同时给平台管理员发站内信。
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-border bg-white">
            <table className="w-full text-sm">
              <thead className="bg-surface-2 text-xs uppercase text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 text-left">公司 / 联系人</th>
                  <th className="px-4 py-3 text-left">角色 · 团队规模</th>
                  <th className="px-4 py-3 text-left">联系方式</th>
                  <th className="px-4 py-3 text-left">最想解决的问题</th>
                  <th className="px-4 py-3 text-left">状态</th>
                  <th className="px-4 py-3 text-left">提交时间</th>
                  <th className="px-4 py-3 text-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map(item => (
                  <tr key={item.id} className="border-t border-border align-top hover:bg-surface-2/50">
                    <td className="px-4 py-3">
                      <div className="font-medium text-foreground">{item.companyName}</div>
                      <div className="text-xs text-muted-foreground">{item.contactName}</div>
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {item.role || '-'}
                      {item.teamSize ? ` · ${item.teamSize}` : ''}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs">{item.contact}</td>
                    <td className="max-w-[260px] px-4 py-3 text-xs text-muted-foreground">
                      <span className="line-clamp-3 whitespace-pre-wrap">{item.message || '-'}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_META[item.status].cls}`}>
                        {STATUS_META[item.status].label}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs tabular-nums text-muted-foreground">
                      {formatTime(item.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="inline-flex flex-wrap justify-end gap-1">
                        {(['contacted', 'closed', 'new'] as const)
                          .filter(s => s !== item.status)
                          .map(s => (
                            <button
                              key={s}
                              disabled={savingId === item.id}
                              onClick={() => changeStatus(item, s)}
                              className="rounded px-2 py-1 text-xs hover:bg-surface-2 disabled:opacity-40"
                            >
                              {s === 'contacted' ? '标记已联系' : s === 'closed' ? '关闭' : '重新打开'}
                            </button>
                          ))}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <Pagination
          page={page}
          totalPages={totalPages}
          totalElements={totalElements}
          onPageChange={setPage}
          loading={loading}
        />
      </div>
    </div>
  );
}
