'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { getMySalesDashboard, type MySalesDashboard } from '@/lib/api/sales';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { getUser } from '@/lib/storage';

const REVIEW_STATUS_LABELS: Record<string, string> = {
  active: '进行中',
  report_ready: '报告就绪',
  converted: '已转线索',
  closed: '已关闭',
};

export default function MySalesDashboardPage() {
  const router = useRouter();
  const [data, setData] = useState<MySalesDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    getMySalesDashboard()
      .then(setData)
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  if (loading && !data) return <div className="p-8"><LoadingSpinner /></div>;

  if (error) {
    return (
      <div className="p-8">
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
          {error}
          <button onClick={load} className="ml-3 underline">重试</button>
        </div>
      </div>
    );
  }
  if (!data) return null;

  const maxWeek = Math.max(...data.weekly.map(w => w.count), 1);
  const role = (getUser() as { role?: string } | null)?.role;
  const canViewTeam = role === 'company_admin' || role === 'super_admin';

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-2 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold text-foreground">我的销售看板</h1>
            <p className="mt-1 text-xs text-muted-foreground">
              本周从 {data.range.weekStart} 起 · 本月从 {data.range.monthStart} 起 ·
              待跟进=跟进中且 7 天无活动或已到下次跟进时间 · 逾期=未完成且已过截止时间
            </p>
          </div>
          {canViewTeam && (
            <button
              type="button"
              onClick={() => router.push('/sales/insights')}
              className="mr-2 rounded-lg border border-border bg-white px-3 py-2 text-xs text-foreground hover:bg-primary-light/40"
            >
              团队共性问题
            </button>
          )}
          <PageHelp
            title="我的销售看板"
            intro="只看你自己发起的复盘、负责的线索和待办任务。"
            steps={[
              { title: '看复盘', desc: '本周/本月复盘数量与最近记录，点击进入复盘详情' },
              { title: '看线索', desc: '线索按阶段分布；amber 提示是 7 天无活动或已到下次跟进时间的线索' },
              { title: '处理待办', desc: '跟进任务逾期和今日到期优先处理，完成后数量会自动回落' },
            ]}
            notes={['统计口径：本周从周一起，待跟进=跟进中且 7 天无活动或已到下次跟进时间。']}
          />
        </div>

        {/* KPI */}
        <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-4">
          <Kpi label="本周复盘" value={data.reviews.thisWeek} sub={`本月 ${data.reviews.thisMonth}`}
            onClick={() => router.push('/sales/reviews')} />
          <Kpi label="待跟进线索" value={data.leads.needFollowUp}
            sub={`其中逾期 ${data.leads.overdueFollowUp}`} tone={data.leads.overdueFollowUp > 0 ? 'amber' : 'normal'}
            onClick={() => router.push('/sales/leads')} />
          <Kpi label="待办跟进任务" value={data.tasks.pending}
            sub={`今日到期 ${data.tasks.dueToday} · 逾期 ${data.tasks.overdue}`}
            tone={data.tasks.overdue > 0 ? 'red' : 'normal'}
            onClick={() => router.push('/sales/tasks')} />
          <Kpi label="本月转化" value={data.leads.convertedThisMonth}
            sub={`累计 ${data.leads.converted}`} onClick={() => router.push('/sales/leads')} />
        </div>

        <div className="grid gap-6 lg:grid-cols-2">
          {/* 复盘趋势 */}
          <section className="rounded-xl border border-border bg-white p-5">
            <h2 className="mb-4 font-semibold text-foreground">近 8 周复盘</h2>
            {data.weekly.every(w => w.count === 0) ? (
              <p className="py-6 text-center text-sm text-muted-foreground">近 8 周还没有复盘记录</p>
            ) : (
              <div className="flex h-[140px] items-end gap-2">
                {data.weekly.map((w, i) => (
                  <div key={w.week} className="flex flex-1 flex-col items-center gap-1">
                    <span className="text-[10px] text-muted-foreground">{w.count || ''}</span>
                    <div
                      className={`w-full rounded-t ${i === data.weekly.length - 1 ? 'bg-primary' : 'bg-primary/40'}`}
                      style={{ height: `${Math.max((w.count / maxWeek) * 100, w.count > 0 ? 6 : 2)}%` }}
                      title={`${w.week} 周：${w.count} 次复盘`}
                    />
                    <span className="text-[10px] text-muted-foreground">{w.week.slice(5)}</span>
                  </div>
                ))}
              </div>
            )}
          </section>

          {/* 线索分布 */}
          <section className="rounded-xl border border-border bg-white p-5">
            <h2 className="mb-4 font-semibold text-foreground">我的线索</h2>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <Stat label="全部" value={data.leads.total} />
              <Stat label="本周新增" value={data.leads.newThisWeek} />
              <Stat label="待接触" value={data.leads.new} />
              <Stat label="跟进中" value={data.leads.following} />
              <Stat label="已转化" value={data.leads.converted} />
              <Stat label="无效" value={data.leads.invalid} />
            </div>
            {data.leads.needFollowUp > 0 && (
              <button onClick={() => router.push('/sales/leads')}
                className="mt-4 w-full rounded-lg bg-amber-50 px-3 py-2 text-left text-xs text-amber-800 hover:bg-amber-100">
                {data.leads.needFollowUp} 条线索需要跟进
                {data.leads.overdueFollowUp > 0 ? `，其中 ${data.leads.overdueFollowUp} 条已逾期` : ''}
              </button>
            )}
          </section>

          {/* 跟进任务 */}
          <section className="rounded-xl border border-border bg-white p-5">
            <h2 className="mb-4 font-semibold text-foreground">跟进任务</h2>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <Stat label="待完成" value={data.tasks.pending} />
              <Stat label="今日到期" value={data.tasks.dueToday} />
              <Stat label="已逾期" value={data.tasks.overdue} tone={data.tasks.overdue > 0 ? 'red' : undefined} />
              <Stat label="本周完成" value={data.tasks.doneThisWeek} />
            </div>
            {data.tasks.pending === 0 && (
              <p className="mt-4 text-xs text-muted-foreground">没有待办，保持住。</p>
            )}
          </section>

          {/* 最近复盘 */}
          <section className="rounded-xl border border-border bg-white p-5">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="font-semibold text-foreground">最近复盘</h2>
              <button onClick={() => router.push('/sales/reviews')}
                className="text-xs text-primary hover:underline">全部</button>
            </div>
            {data.recentReviews.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">
                还没有复盘，去发起一次
              </p>
            ) : (
              <ul className="space-y-2">
                {data.recentReviews.map(r => (
                  <li key={r.id}>
                    <button onClick={() => router.push(`/sales/reviews/${r.id}`)}
                      className="flex w-full items-center justify-between rounded-lg border border-border px-3 py-2 text-left text-sm hover:bg-primary-light/40">
                      <span className="min-w-0">
                        <span className="block truncate text-foreground">
                          {r.customerName || r.topic || '未命名复盘'}
                        </span>
                        <span className="text-xs text-muted-foreground">
                          {r.coachName ? `教练：${r.coachName} · ` : ''}
                          {r.createdAt ? r.createdAt.slice(0, 10) : ''}
                        </span>
                      </span>
                      <span className="ml-2 flex-none text-xs text-muted-foreground">
                        {REVIEW_STATUS_LABELS[r.status] || r.status}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}

function Kpi({
  label, value, sub, tone, onClick,
}: {
  label: string;
  value: number;
  sub: string;
  tone?: 'normal' | 'amber' | 'red';
  onClick?: () => void;
}) {
  const toneCls = tone === 'red'
    ? 'border-red-200 bg-red-50'
    : tone === 'amber'
      ? 'border-amber-200 bg-amber-50'
      : 'border-border bg-white';
  return (
    <button type="button" onClick={onClick}
      className={`rounded-xl border p-4 text-left transition-colors hover:opacity-90 ${toneCls}`}>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-2xl font-bold text-foreground tabular-nums">{value}</p>
      <p className="mt-0.5 text-[11px] text-muted-foreground">{sub}</p>
    </button>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'red' }) {
  return (
    <div className="flex items-center justify-between rounded-lg bg-surface-2 px-3 py-2">
      <span className="text-muted-foreground">{label}</span>
      <span className={`font-semibold tabular-nums ${tone === 'red' ? 'text-red-600' : 'text-foreground'}`}>
        {value}
      </span>
    </div>
  );
}
