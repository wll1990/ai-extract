'use client';

import { useCallback, useEffect, useState } from 'react';
import { TaskView, listTasks, updateTaskStatus } from '@/lib/api/sales';
import { StatusBadge } from '@/components/sales/StatusBadge';
import { TaskEditModal } from '@/components/sales/TaskEditModal';
import { TaskTransferModal } from '@/components/sales/TaskTransferModal';
import { TaskEscalateModal } from '@/components/sales/TaskEscalateModal';
import { ActionLegend } from '@/components/sales/ActionLegend';
import { Button } from '@/components/ui/Button';
import { PageHelp } from '@/components/ui/PageHelp';
import { useScrollOnPageChange } from '@/lib/useScrollOnPageChange';

export default function TasksPage() {
  const [rows, setRows] = useState<TaskView[]>([]);
  const [page, setPage] = useState(1);
  useScrollOnPageChange(page);
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
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[28px] font-bold leading-tight text-foreground">跟进任务</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              复盘报告里的「下一步」会落成这里的待办；完成、转交或升级都在这一页处理。
            </p>
          </div>
          <PageHelp
            title="跟进任务使用说明"
            intro="任务来自复盘报告的「下一步」，也可以手动编辑。任务是客户跟进的最小执行单元。"
            steps={[
              { title: '看待办', desc: '默认只看「待办」；可切换已完成/已取消查看历史。' },
              { title: '完成或取消', desc: '做完点「完成」；不做点「取消」，两者都会留档，不会删除任务。' },
              { title: '改期或转交', desc: '「编辑」可改标题、渠道、截止时间和话术；「转交」把任务交给同事。' },
              { title: '升级', desc: '逾期任务可以升级，提醒上级/教练介入；升级后不会自动完成。' },
            ]}
            actions={[
              { label: '完成', desc: '把待办标记为已完成，任务保留在「已完成」筛选里。', tone: 'primary' },
              { label: '取消', desc: '放弃这项跟进；任务保留在「已取消」里，可留档复盘。' },
              { label: '编辑', desc: '修改标题、渠道、截止时间和跟进话术；不会改负责人和状态。' },
              { label: '转交', desc: '把任务负责人改成同事；原负责人会收到通知，任务状态不变。' },
              { label: '升级', desc: '仅逾期且未升级的任务可点；用于提醒上级介入，任务仍需完成或取消。' },
              { label: '状态筛选', desc: '在待办 / 已完成 / 已取消之间切换，只影响列表。' },
            ]}
            notes={[
              '任务逾期按截止时间判断；没有截止时间的任务不会被标记逾期。',
              '完成或取消都是留档，不会释放关联线索。',
            ]}
          />
        </div>

        <div className="mb-3 flex flex-wrap items-center gap-2 rounded-xl border border-border bg-surface-2 px-3 py-2">
          <span className="text-xs text-muted-foreground">状态</span>
          {[
            ['pending', '待办'],
            ['done', '已完成'],
            ['cancelled', '已取消'],
            ['', '全部'],
          ].map(([value, label]) => (
            <button
              key={value || 'all'}
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
            { label: '完成 / 取消', desc: '结束任务；两种结果都留档，可在状态筛选里回看。', tone: 'primary' },
            { label: '编辑 / 转交', desc: '改内容或换负责人；转交不改变任务状态。' },
            { label: '升级', desc: '仅逾期且未升级的任务可点；提醒上级介入，任务仍需处理。' },
          ]}
        />

        {error && (
          <div className="mb-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

        <div className="space-y-2">
          {loading && <p className="rounded-xl border border-border bg-surface-2 py-10 text-center text-sm text-muted-foreground">加载中…</p>}
          {!loading && rows.length === 0 && (
            <p className="rounded-xl border border-border bg-surface-2 py-12 text-center text-sm text-muted-foreground">
              没有任务。到销售复盘详情点「生成跟进任务」，或切换状态筛选。
            </p>
          )}
          {!loading &&
            rows.map(t => {
              const overdue =
                t.status === 'pending' && t.dueAt && new Date(t.dueAt).getTime() < Date.now();
              return (
                <div
                  key={t.id}
                  className={`flex flex-wrap items-start justify-between gap-3 rounded-xl border bg-surface-2 p-4 ${
                    overdue ? 'border-danger/30' : 'border-border'
                  }`}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-semibold text-foreground">{t.title}</span>
                      <StatusBadge status={t.status} />
                      {overdue && <span className="text-xs font-medium text-danger">已逾期</span>}
                      {t.escalatedAt && (
                        <span className="rounded-full bg-warning-bg px-2 py-0.5 text-xs text-warning-text">
                          已升级
                        </span>
                      )}
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {[t.channel, t.dueAt ? new Date(t.dueAt).toLocaleString() : null]
                        .filter(Boolean)
                        .join(' · ') || '无渠道/截止'}
                      {t.ownerName ? ` · 负责人：${t.ownerName}` : ''}
                    </p>
                    {t.content && <p className="mt-1.5 text-xs leading-5 text-foreground">{t.content}</p>}
                  </div>
                  {t.status === 'pending' && (
                    <div className="flex flex-wrap justify-end gap-1.5">
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={busyId === t.id}
                        onClick={() => move(t.id, 'done')}
                        title="标记完成；任务会保留在已完成列表"
                      >
                        完成
                      </Button>
                      <Button
                        variant="neutral"
                        size="sm"
                        disabled={busyId === t.id}
                        onClick={() => move(t.id, 'cancelled')}
                        title="放弃跟进；任务保留在已取消列表"
                      >
                        取消
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => setEditing(t)} title="修改任务内容、渠道或截止时间">
                        编辑
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => setTransferring(t)} title="把任务负责人改为同事，任务状态不变">
                        转交
                      </Button>
                      {overdue && !t.escalatedAt && (
                        <Button variant="danger" size="sm" onClick={() => setEscalating(t)} title="逾期任务提醒上级介入；不会自动完成任务">
                          升级
                        </Button>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
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
