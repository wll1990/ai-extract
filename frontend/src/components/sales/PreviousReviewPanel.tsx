'use client';

import Link from 'next/link';
import { CustomerSignal, PreviousReview } from '@/lib/api/sales';

const SIGNAL_FIELDS = ['预算', '决策人', '时间线', '需求', '异议'] as const;

const TASK_STATUS: Record<string, { label: string; className: string }> = {
  done: { label: '已完成', className: 'bg-green-50 text-green-700' },
  cancelled: { label: '已放弃', className: 'bg-gray-100 text-gray-500' },
  pending: { label: '未完成', className: 'bg-amber-50 text-amber-700' },
  overdue: { label: '已逾期', className: 'bg-red-50 text-red-700' },
};

function taskState(task: PreviousReview['tasks'][number]): keyof typeof TASK_STATUS {
  if (task.status !== 'pending') return task.status;
  if (task.dueAt && new Date(task.dueAt).getTime() < Date.now()) return 'overdue';
  return 'pending';
}

function signalOf(signals: CustomerSignal[] | null | undefined, field: string): CustomerSignal | undefined {
  return signals?.find(s => s.field === field);
}

/**
 * 同一客户的上一次复盘对比：上次承诺的下一步做了没有、客户信号变了没有。
 * 只在复盘关联了客户且存在更早复盘时出现。
 */
export function PreviousReviewPanel({
  previous,
  currentSignals,
}: {
  previous: PreviousReview;
  currentSignals?: CustomerSignal[];
}) {
  const date = new Date(previous.createdAt).toLocaleDateString('zh-CN');
  const pending = previous.tasks.filter(t => taskState(t) === 'pending' || taskState(t) === 'overdue');

  return (
    <section className="rounded border border-blue-200 bg-blue-50/40 p-4" data-tour="previous-review">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-medium text-gray-900">
          上次复盘对比 · {date}
          {previous.topic ? ` · ${previous.topic}` : ''}
        </h3>
        <Link
          href={`/sales/reviews/${previous.sessionId}`}
          className="text-xs text-blue-700 hover:underline"
        >
          查看上次复盘
        </Link>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div>
          <h4 className="mb-1 text-xs font-medium text-gray-600">上次的下一步</h4>
          {previous.tasks.length === 0 ? (
            <p className="text-xs text-gray-500">上次没有生成跟进任务。</p>
          ) : (
            <ul className="space-y-1">
              {previous.tasks.map(t => {
                const state = taskState(t);
                const badge = TASK_STATUS[state];
                return (
                  <li key={t.id} className="flex items-start justify-between gap-2 text-xs">
                    <span className="text-gray-800">{t.title}</span>
                    <span className={`shrink-0 rounded px-1.5 py-0.5 ${badge.className}`}>
                      {badge.label}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
          {pending.length > 0 && (
            <p className="mt-2 text-xs text-amber-700">
              还有 {pending.length} 条上次的动作没有收口，本次先确认是否已经做过。
            </p>
          )}
        </div>

        <div>
          <h4 className="mb-1 text-xs font-medium text-gray-600">客户信号变化</h4>
          <ul className="space-y-1">
            {SIGNAL_FIELDS.map(field => {
              const before = signalOf(previous.customerSignals, field);
              const after = signalOf(currentSignals, field);
              const beforeValue = before?.value?.trim() || null;
              const afterValue = after?.value?.trim() || null;
              let hint = '';
              if (!beforeValue && afterValue) hint = '本次确认';
              else if (beforeValue && !afterValue) hint = '本次未确认';
              else if (beforeValue && afterValue && beforeValue !== afterValue) hint = '有变化';
              return (
                <li key={field} className="text-xs text-gray-800">
                  <span className="mr-1 text-gray-500">{field}</span>
                  <span>{beforeValue ?? '未确认'}</span>
                  <span className="mx-1 text-gray-400">→</span>
                  <span>{afterValue ?? '本次未确认'}</span>
                  {hint && <span className="ml-1 text-gray-500">（{hint}）</span>}
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    </section>
  );
}
