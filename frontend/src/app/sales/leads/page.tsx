'use client';

import { useCallback, useEffect, useState } from 'react';
import { LeadSummary, listLeads, updateLeadStatus } from '@/lib/api/sales';
import { StatusBadge } from '@/components/sales/StatusBadge';

const NEXT: Record<string, Array<{ label: string; status: string }>> = {
  new: [
    { label: '开始跟进', status: 'following' },
    { label: '标记无效', status: 'invalid' },
  ],
  following: [
    { label: '标记成交', status: 'converted' },
    { label: '标记无效', status: 'invalid' },
  ],
  converted: [{ label: '标记无效', status: 'invalid' }],
  invalid: [{ label: '恢复为新线索', status: 'new' }],
};

export default function LeadsPage() {
  const [rows, setRows] = useState<LeadSummary[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await listLeads({ page, size: 20, status: status || undefined });
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

  useEffect(() => {
    const refresh = () => { void load(); };
    window.addEventListener('sales:refresh', refresh);
    return () => window.removeEventListener('sales:refresh', refresh);
  }, [load]);

  const move = async (id: string, next: string) => {
    try {
      await updateLeadStatus(id, next);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '状态更新失败');
    }
  };

  return (
    <div className="mx-auto max-w-5xl p-6">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">线索</h1>
          <p className="text-sm text-gray-500">
            从销售复盘转来的客户线索：需求摘要、负责人、最近跟进和状态都在这里。
          </p>
        </div>
        <select
          value={status}
          onChange={e => {
            setStatus(e.target.value);
            setPage(1);
          }}
          className="rounded border border-gray-300 px-3 py-2 text-sm"
        >
          <option value="">全部状态</option>
          <option value="new">新线索</option>
          <option value="following">跟进中</option>
          <option value="converted">已成交</option>
          <option value="invalid">无效</option>
        </select>
      </div>
      {error && (
        <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}
      <div className="overflow-hidden rounded border border-gray-200">
        <table className="w-full text-left text-sm">
          <thead className="bg-gray-50 text-gray-600">
            <tr>
              <th className="px-4 py-2">名称 / 需求</th>
              <th className="px-4 py-2">状态</th>
              <th className="px-4 py-2">负责人</th>
              <th className="px-4 py-2">最近跟进</th>
              <th className="px-4 py-2">来源</th>
              <th className="px-4 py-2">操作</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-500">
                  加载中…
                </td>
              </tr>
            )}
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-500">
                  <p>还没有线索</p>
                  <a href="/sales/reviews" className="mt-2 inline-block text-blue-600 hover:underline">
                    从销售复盘转线索 →
                  </a>
                </td>
              </tr>
            )}
            {!loading &&
              rows.map(l => (
                <tr key={l.id} className="border-t border-gray-100">
                  <td className="px-4 py-2 font-medium text-gray-900">
                    <a href={`/sales/leads/${l.id}`} className="text-blue-600 hover:underline">
                      {l.name}
                    </a>
                    {l.needSummary && (
                      <p className="mt-0.5 max-w-xs truncate text-xs font-normal text-gray-500" title={l.needSummary}>
                        {l.needSummary}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge status={l.status} domain="lead" />
                  </td>
                  <td className="px-4 py-2 text-gray-600">{l.ownerName || '—'}</td>
                  <td className="px-4 py-2 text-gray-500">
                    {l.lastActivityAt ? new Date(l.lastActivityAt).toLocaleDateString() : '—'}
                    {l.nextFollowUpAt && (
                      <p className="text-xs text-gray-400">
                        下次 {new Date(l.nextFollowUpAt).toLocaleDateString()}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-2 text-gray-500">
                    {l.sourceReviewId ? (
                      <a
                        href={`/sales/reviews/${l.sourceReviewId}`}
                        className="text-blue-600 hover:underline"
                      >
                        来自复盘
                      </a>
                    ) : (
                      l.source
                    )}
                  </td>
                  <td className="px-4 py-2">
                    <div className="flex gap-1">
                      {(NEXT[l.status] || []).map(a => (
                        <button
                          key={a.status}
                          type="button"
                          onClick={() => move(l.id, a.status)}
                          className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
                        >
                          {a.label}
                        </button>
                      ))}
                    </div>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
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
    </div>
  );
}
