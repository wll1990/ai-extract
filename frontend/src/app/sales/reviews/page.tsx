'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { PageHelp } from '@/components/ui/PageHelp';
import { ReviewSession, listReviews, requestReport } from '@/lib/api/sales';
import { StatusBadge } from '@/components/sales/StatusBadge';
import { Permission } from '@/lib/permissions';
import { getUser } from '@/lib/storage';

export default function ReviewsPage() {
  const [rows, setRows] = useState<ReviewSession[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [scope, setScope] = useState<'mine' | 'company'>('mine');
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const canViewAll = Boolean(
    (getUser() as { permissions?: string[] } | null)?.permissions?.includes(
      Permission.SALES_REVIEW_VIEW_ALL,
    ),
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await listReviews({
        page,
        size: 20,
        status: status || undefined,
        scope,
      });
      setRows(res.content);
      setTotalPages(res.totalPages);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, status, scope]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const refresh = () => { void load(); };
    window.addEventListener('sales:refresh', refresh);
    return () => window.removeEventListener('sales:refresh', refresh);
  }, [load]);

  const retryReport = async (id: string) => {
    try {
      await requestReport(id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '重新生成失败');
    }
  };

  return (
    <div className="mx-auto max-w-6xl p-6">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">销售复盘</h1>
          <p className="text-sm text-gray-500">
            粘贴对话，找销冠分身复盘，报告可转线索和跟进任务。
          </p>
        </div>
        <div className="flex items-center gap-3">
          <PageHelp
            title="销售复盘使用说明"
            intro="把一次客户对话交给销冠分身复盘，得到可执行的改进建议，并可以转成线索和跟进任务。"
            steps={[
              { title: '新建复盘', desc: '选择一位已发布的销冠分身，粘贴或上传这次对话的文本。' },
              { title: '等待报告', desc: '报告在后台生成，通常几十秒；刷新列表可看到状态变为「已完成」。' },
              { title: '读报告', desc: '点进详情看摘要、亮点、问题、下一步动作，以及引用到的销冠经验。' },
              { title: '转线索 / 建任务', desc: '有价值的客户可以直接转成线索，并把下一步动作建成跟进任务。' },
            ]}
            notes={[
              '对话越完整（包含客户异议和你的回应），复盘越具体。',
              '报告生成失败会在详情里显示原因；模型 Key 余额不足时请联系管理员。',
            ]}
          />
          <Link
            href="/sales/reviews/new"
            data-tour="review-new-cta"
            className="rounded bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700"
          >
            新建复盘
          </Link>
        </div>
      </div>

      <div className="mb-4 flex flex-wrap gap-2">
        <select
          value={status}
          onChange={e => {
            setStatus(e.target.value);
            setPage(1);
          }}
          className="rounded border border-gray-300 px-3 py-2 text-sm"
        >
          <option value="">全部状态</option>
          <option value="active">待生成</option>
          <option value="report_ready">报告已生成</option>
          <option value="converted">已转线索</option>
        </select>
        {canViewAll && (
          <div className="flex rounded border border-gray-300 text-sm">
            {(['mine', 'company'] as const).map(s => (
              <button
                key={s}
                type="button"
                onClick={() => {
                  setScope(s);
                  setPage(1);
                }}
                className={`px-3 py-2 ${scope === s ? 'bg-gray-900 text-white' : 'text-gray-700'}`}
              >
                {s === 'mine' ? '我的' : '全公司'}
              </button>
            ))}
          </div>
        )}
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
              <th className="px-4 py-2">客户</th>
              <th className="px-4 py-2">主题</th>
              <th className="px-4 py-2">教练</th>
              <th className="px-4 py-2">状态</th>
              <th className="px-4 py-2">报告</th>
              <th className="px-4 py-2">创建时间</th>
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
                  {status ? (
                    '没有符合筛选条件的复盘，试试切换状态'
                  ) : (
                    <span className="inline-flex flex-col items-center gap-3">
                      <span>还没有复盘。选一位销冠分身，把今天的一段客户对话粘进来，几十秒后就能看到具体反馈。</span>
                      <Link href="/sales/reviews/new"
                        className="rounded bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700">
                        新建第一条复盘
                      </Link>
                    </span>
                  )}
                </td>
              </tr>
            )}
            {!loading &&
              rows.map(r => (
                <tr key={r.id} className="border-t border-gray-100 hover:bg-gray-50">
                  <td className="px-4 py-2">
                    <Link
                      href={`/sales/reviews/${r.id}`}
                      className="text-blue-600 hover:underline"
                    >
                      {r.customerName || '未命名客户'}
                    </Link>
                  </td>
                  <td className="px-4 py-2 text-gray-700">{r.topic || '—'}</td>
                  <td className="px-4 py-2 text-gray-700">{r.coachName || '—'}</td>
                  <td className="px-4 py-2">
                    <StatusBadge status={displayStatus(r)} />
                  </td>
                  <td className="px-4 py-2 text-gray-700">
                    {r.latestReport ? (
                      <span className="flex items-center gap-1" title={r.latestReport.errorMessage || undefined}>
                        v{r.latestReport.version} · <StatusBadge status={r.latestReport.status} />
                        {r.latestReport.status === 'failed' && (
                          <button onClick={() => retryReport(r.id)}
                            className="text-xs text-blue-600 hover:underline">重新生成</button>
                        )}
                      </span>
                    ) : (
                      <span className="text-gray-400">未生成</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-gray-500">
                    {new Date(r.createdAt).toLocaleString()}
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

/**
 * 会话本身只有 active/report_ready/converted，但用户更关心报告进度。
 * 展示态从 latestReport 派生，避免「待生成」+「生成中」同时出现。
 */
function displayStatus(r: {
  status: string;
  latestReport?: { status: string } | null;
}): string {
  const report = r.latestReport?.status;
  if (r.status === 'active' && (report === 'queued' || report === 'generating')) {
    return 'generating';
  }
  if (r.status === 'active' && report === 'failed') return 'failed';
  return r.status;
}
