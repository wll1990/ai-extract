'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { LeadSummary, listLeads, updateLeadStatus } from '@/lib/api/sales';
import { StatusBadge } from '@/components/sales/StatusBadge';
import { ActionLegend } from '@/components/sales/ActionLegend';
import { Button } from '@/components/ui/Button';
import { PageHelp } from '@/components/ui/PageHelp';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

const NEXT: Record<string, Array<{ label: string; status: string; hint: string }>> = {
  new: [
    { label: '开始跟进', status: 'following', hint: '把线索状态改为跟进中，之后可以记录活动和建任务' },
    { label: '标记无效', status: 'invalid', hint: '停用这条线索，不再出现在待跟进里；可随时恢复' },
  ],
  following: [
    { label: '标记成交', status: 'converted', hint: '记录为已成交；之后仍可标记无效' },
    { label: '标记无效', status: 'invalid', hint: '停用这条线索，不再出现在待跟进里；可随时恢复' },
  ],
  converted: [{ label: '标记无效', status: 'invalid', hint: '成交后如果发现是误操作，可标记无效' }],
  invalid: [{ label: '恢复为新线索', status: 'new', hint: '把无效线索恢复成新线索，重新进入待跟进' }],
};

export default function LeadsPage() {
  const router = useRouter();
  const [rows, setRows] = useState<LeadSummary[]>([]);
  const [page, setPage] = useState(1);
  useScrollOnPageChange(page);
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
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[28px] font-bold leading-tight text-foreground">线索</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              从销售复盘转来的客户线索：需求摘要、负责人、最近跟进和状态都在这里。
            </p>
          </div>
          <PageHelp
            title="线索使用说明"
            intro="线索来自销售复盘的「转线索」，也可以由有权限的成员直接分配。这里管理客户状态和负责人。"
            steps={[
              { title: '看线索', desc: '默认按状态筛选；点客户名进入详情，查看需求、联系人、活动时间线和关联任务。' },
              { title: '推进状态', desc: '新线索 → 跟进中 → 已成交；判断错了可以标记无效，再恢复。' },
              { title: '分负责人', desc: '在详情页把线索分配/转交给同事，活动时间线会记录操作人。' },
              { title: '记录跟进', desc: '详情页可加备注、建联系人；下一步动作建议建成跟进任务。' },
            ]}
            actions={[
              { label: '开始跟进', desc: '把「新线索」改为「跟进中」，表示已经开始接触客户。', tone: 'primary' },
              { label: '标记成交', desc: '把「跟进中」记录为「已成交」；不会自动创建订单。' },
              { label: '标记无效', desc: '停用这条线索，不再出现在待跟进里；可以恢复到新线索。' },
              { label: '恢复为新线索', desc: '把无效线索恢复成新线索，重新进入待跟进。' },
              { label: '状态筛选', desc: '只过滤列表，不改变线索数据。' },
            ]}
            notes={[
              '已成交或无效的线索仍会保留活动记录，不会被删除。',
              '线索详情里的「分配负责人」只影响负责人，不改状态。',
            ]}
          />
        </div>

        <div className="mb-3 flex flex-wrap items-center gap-2 rounded-xl border border-border bg-surface-2 px-3 py-2">
          <span className="text-xs text-muted-foreground">状态</span>
          {[
            ['', '全部'],
            ['new', '新线索'],
            ['following', '跟进中'],
            ['converted', '已成交'],
            ['invalid', '无效'],
          ].map(([value, label]) => (
            <button
              key={value}
              type="button"
              onClick={() => { setStatus(value); setPage(1); }}
              className={`rounded-md px-2.5 py-1 text-xs transition-colors ${
                status === value
                  ? 'bg-primary-light font-medium text-primary'
                  : 'text-muted-foreground hover:bg-surface'
              }`}
            >
              {label}
            </button>
          ))}
          <Button variant="ghost" size="sm" className="ml-auto" onClick={() => { void load(); }} disabled={loading}>
            {loading ? '刷新中…' : '刷新'}
          </Button>
        </div>

        <ActionLegend
          className="mb-4"
          items={[
            { label: '开始跟进', desc: '新线索 → 跟进中；之后可以记录活动和建任务。', tone: 'primary' },
            { label: '标记成交', desc: '跟进中 → 已成交；不会自动创建订单。' },
            { label: '标记无效 / 恢复', desc: '无效线索会退出待跟进，但记录保留，可随时恢复。' },
          ]}
        />

        {error && (
          <div className="mb-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

        <div className="overflow-hidden rounded-xl border border-border bg-surface-2">
          <table className="w-full min-w-[900px] text-left text-sm">
            <thead className="bg-surface text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">名称 / 需求</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium">负责人</th>
                <th className="px-4 py-3 font-medium">最近跟进</th>
                <th className="px-4 py-3 font-medium">来源</th>
                <th className="px-4 py-3 font-medium">下一步</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-muted-foreground">加载中…</td>
                </tr>
              )}
              {!loading && rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-12 text-center text-muted-foreground">
                    {status ? (
                      '没有符合筛选条件的线索，试试切换状态'
                    ) : (
                      <span className="inline-flex flex-col items-center gap-3">
                        <span>还没有线索。销售复盘生成报告后，可在详情页点「转线索」。</span>
                        <Button variant="primary" onClick={() => router.push('/sales/reviews')}>
                          去销售复盘
                        </Button>
                      </span>
                    )}
                  </td>
                </tr>
              )}
              {!loading &&
                rows.map(l => (
                  <tr key={l.id} className="border-t border-border hover:bg-surface">
                    <td className="px-4 py-3">
                      <Link href={`/sales/leads/${l.id}`} className="font-medium text-foreground hover:text-primary">
                        {l.name}
                      </Link>
                      {l.needSummary && (
                        <p className="mt-0.5 max-w-xs truncate text-xs text-muted-foreground" title={l.needSummary}>
                          {l.needSummary}
                        </p>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={l.status} domain="lead" />
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{l.ownerName || '—'}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {l.lastActivityAt ? new Date(l.lastActivityAt).toLocaleDateString() : '—'}
                      {l.nextFollowUpAt && (
                        <p className="text-muted-foreground-2">
                          下次 {new Date(l.nextFollowUpAt).toLocaleDateString()}
                        </p>
                      )}
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {l.sourceReviewId ? (
                        <Link href={`/sales/reviews/${l.sourceReviewId}`} className="text-primary hover:underline">
                          来自复盘
                        </Link>
                      ) : (
                        l.source
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1.5">
                        {(NEXT[l.status] || []).map(a => (
                          <Button
                            key={a.status}
                            variant={a.status === 'following' ? 'secondary' : 'neutral'}
                            size="sm"
                            onClick={() => move(l.id, a.status)}
                            title={a.hint}
                          >
                            {a.label}
                          </Button>
                        ))}
                      </div>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>

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
    </div>
  );
}
