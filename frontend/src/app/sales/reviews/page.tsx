'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { PageHelp } from '@/components/ui/PageHelp';
import { Button } from '@/components/ui/Button';
import { FlowSteps } from '@/components/ui/FlowSteps';
import { ActionLegend } from '@/components/sales/ActionLegend';
import { ReviewSession, listReviews, requestReport } from '@/lib/api/sales';
import { StatusBadge } from '@/components/sales/StatusBadge';
import { Permission } from '@/lib/permissions';
import { getUser } from '@/lib/storage';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

export default function ReviewsPage() {
  const router = useRouter();
  const [rows, setRows] = useState<ReviewSession[]>([]);
  const [page, setPage] = useState(1);
  useScrollOnPageChange(page);
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
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[28px] font-bold leading-tight text-foreground">销售复盘</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              粘贴一次客户对话，让销冠分身复盘；报告能转成线索和跟进任务。
            </p>
          </div>
          <div className="flex items-center gap-2">
            <PageHelp
              title="销售复盘使用说明"
              intro="把一次客户对话交给销冠分身复盘，得到可执行的改进建议，并可以转成线索和跟进任务。"
              steps={[
                { title: '新建复盘', desc: '选择一位已发布的销冠分身，粘贴或上传这次对话的文本。' },
                { title: '等待报告', desc: '报告在后台生成，通常几十秒；状态会从「生成中」变为「报告已生成」。' },
                { title: '读报告', desc: '点客户名进详情，看摘要、亮点、问题、下一步动作和引用的销冠经验。' },
                { title: '转线索 / 建任务', desc: '有价值的客户转成线索；把报告的下一步建成跟进任务。' },
              ]}
              actions={[
                { label: '新建复盘', desc: '选择教练分身并提交对话文本；提交后自动进入详情并开始生成报告。', tone: 'primary' },
                { label: '客户名 / 查看', desc: '打开复盘详情，才能转线索、生成跟进任务或沉积为知识/案例。' },
                { label: '重新生成', desc: '仅在该版本报告生成失败时出现；会新增一个报告版本，旧版本保留。' },
                { label: '状态筛选', desc: '按「待生成 / 报告已生成 / 已转线索」过滤列表，不影响数据。' },
                { label: '我的 / 全公司', desc: '只有具备全公司查看权限的管理者能切换；销售默认只看自己发起的复盘。' },
              ]}
              notes={[
                '对话越完整（包含客户异议和你的回应），复盘越具体。',
                '报告生成失败会在详情里显示原因；模型 Key 余额不足时请联系管理员。',
              ]}
            />
            <Button variant="primary" onClick={() => router.push('/sales/reviews/new')} data-tour="review-new-cta">
              ＋ 新建复盘
            </Button>
          </div>
        </div>

        <FlowSteps
          compact
          className="mb-4"
          steps={[
            { id: 'create', title: '新建复盘', desc: '选教练、贴对话', status: rows.length === 0 ? 'current' : 'done', onClick: () => router.push('/sales/reviews/new'), actionLabel: '新建' },
            { id: 'report', title: '生成报告', desc: '等待 30–90 秒', status: rows.some(r => r.status === 'report_ready' || r.status === 'converted') ? 'done' : 'todo' },
            { id: 'convert', title: '转线索 / 建任务', desc: '在详情页完成', status: rows.some(r => r.status === 'converted') ? 'done' : 'todo' },
            { id: 'follow', title: '在线索里跟进', desc: '记录活动与下一步', status: 'todo', onClick: () => router.push('/sales/leads'), actionLabel: '去线索' },
          ]}
        />

        <div className="mb-3 flex flex-wrap items-center gap-2 rounded-xl border border-border bg-surface-2 px-3 py-2">
          <span className="text-xs text-muted-foreground">状态</span>
          {[
            ['', '全部'],
            ['active', '待生成'],
            ['report_ready', '报告已生成'],
            ['converted', '已转线索'],
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
          {canViewAll && (
            <div className="ml-auto flex items-center gap-1 rounded-lg border border-border bg-surface p-0.5">
              {(['mine', 'company'] as const).map(s => (
                <button
                  key={s}
                  type="button"
                  onClick={() => { setScope(s); setPage(1); }}
                  className={`rounded-md px-2.5 py-1 text-xs ${
                    scope === s ? 'bg-primary text-white' : 'text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {s === 'mine' ? '我的' : '全公司'}
                </button>
              ))}
            </div>
          )}
          <Button variant="ghost" size="sm" onClick={() => { void load(); }} disabled={loading}>
            {loading ? '刷新中…' : '刷新'}
          </Button>
        </div>

        <ActionLegend
          className="mb-4"
          items={[
            { label: '新建复盘', desc: '提交一次客户对话，系统会调教练分身生成报告。', tone: 'primary' },
            { label: '重新生成', desc: '只在该版本生成失败时出现；新增版本，不覆盖旧报告。' },
            { label: '状态筛选 / 我的·全公司', desc: '只改变列表范围，不改动数据；全公司视图需要管理者权限。' },
          ]}
        />

        {error && (
          <div className="mb-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

      <div className="overflow-hidden rounded-xl border border-border bg-surface-2">
        <table className="w-full text-left text-sm">
          <thead className="bg-surface text-xs text-muted-foreground">
            <tr>
              <th className="px-4 py-3 font-medium">客户</th>
              <th className="px-4 py-3 font-medium">主题</th>
              <th className="px-4 py-3 font-medium">教练</th>
              <th className="px-4 py-3 font-medium">状态</th>
              <th className="px-4 py-3 font-medium">报告</th>
              <th className="px-4 py-3 font-medium">创建时间</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={7} className="px-4 py-10 text-center text-muted-foreground">
                  加载中…
                </td>
              </tr>
            )}
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-muted-foreground">
                  {status ? (
                    '没有符合筛选条件的复盘，试试切换状态'
                  ) : (
                    <span className="inline-flex flex-col items-center gap-3">
                      <span>还没有复盘。选一位销冠分身，把今天的一段客户对话粘进来，几十秒后就能看到具体反馈。</span>
                      <Button variant="primary" onClick={() => router.push('/sales/reviews/new')}>
                        新建第一条复盘
                      </Button>
                    </span>
                  )}
                </td>
              </tr>
            )}
            {!loading &&
              rows.map(r => (
                <tr key={r.id} className="border-t border-border hover:bg-surface">
                  <td className="px-4 py-3">
                    <Link
                      href={`/sales/reviews/${r.id}`}
                      className="font-medium text-foreground hover:text-primary"
                    >
                      {r.customerName || '未命名客户'}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{r.topic || '—'}</td>
                  <td className="px-4 py-3 text-muted-foreground">{r.coachName || '—'}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={displayStatus(r)} />
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {r.latestReport ? (
                      <span className="flex flex-wrap items-center gap-1.5" title={r.latestReport.errorMessage || undefined}>
                        <span className="tabular-nums">v{r.latestReport.version}</span>
                        <StatusBadge status={r.latestReport.status} />
                        {r.latestReport.status === 'failed' && (
                          <button
                            onClick={() => retryReport(r.id)}
                            title="重新生成会新增一个报告版本，不覆盖旧版本"
                            className="text-xs text-primary hover:underline"
                          >
                            重新生成
                          </button>
                        )}
                      </span>
                    ) : (
                      <span className="text-muted-foreground-2">未生成</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {new Date(r.createdAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Button variant="ghost" size="sm" onClick={() => router.push(`/sales/reviews/${r.id}`)}>
                      查看
                    </Button>
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
