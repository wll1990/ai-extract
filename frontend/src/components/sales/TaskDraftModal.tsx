'use client';

import { useState } from 'react';
import { NextStep, createTasks } from '@/lib/api/sales';
import { Button } from '@/components/ui/Button';

function pad(n: number) {
  return String(n).padStart(2, '0');
}

/** 后端是 LocalDateTime，不能发 UTC ISO，否则按时区偏移 */
function toLocalInput(d: Date) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function toLocalDateTime(value: string) {
  return value.length === 16 ? `${value}:00` : value;
}

type Row = {
  selected: boolean;
  title: string;
  channel: string;
  dueAt: string;
  content: string;
};

export function TaskDraftModal({
  reviewId,
  steps,
  primaryAction,
  onClose,
  onCreated,
}: {
  reviewId: string;
  steps: NextStep[];
  /** 报告给出的唯一主行动；放在第一条，默认选中 */
  primaryAction?: NextStep | null;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [rows, setRows] = useState<Row[]>(() => {
    const merged = primaryAction
      ? [primaryAction, ...steps.filter(s => s.title !== primaryAction.title)]
      : steps;
    return merged.slice(0, 3).map(s => ({
      selected: true,
      title: s.title || '',
      channel: s.channel || '',
      dueAt: s.dueInDays != null
        ? toLocalInput(new Date(Date.now() + s.dueInDays * 86400000))
        : '',
      content: s.content || '',
    }));
  });
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const update = (index: number, patch: Partial<Row>) => {
    setRows(prev => prev.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  };

  const toggle = (index: number) => {
    setRows(prev => {
      const row = prev[index];
      if (!row) return prev;
      if (!row.selected && prev.filter(r => r.selected).length >= 3) {
        return prev;
      }
      return prev.map((r, i) => (i === index ? { ...r, selected: !r.selected } : r));
    });
  };

  const submit = async () => {
    const selected = rows.filter(r => r.selected);
    if (selected.length === 0) {
      setError('至少选择 1 条');
      return;
    }
    const now = Date.now();
    for (const row of selected) {
      if (!row.title.trim()) {
        setError('任务标题不能为空');
        return;
      }
      if (row.dueAt && new Date(row.dueAt).getTime() < now) {
        setError(`截止时间不能早于现在：${row.title}`);
        return;
      }
    }
    setSaving(true);
    setError(null);
    try {
      const res = await createTasks(
        reviewId,
        selected.map(row => ({
          title: row.title.trim(),
          channel: row.channel.trim() || undefined,
          dueAt: row.dueAt ? toLocalDateTime(row.dueAt) : undefined,
          content: row.content.trim() || undefined,
        })),
      );
      if (res.skipped && res.skipped.length > 0) {
        alert(`已创建 ${res.created} 条；以下同名任务已跳过：\n${res.skipped.join('\n')}`);
      }
      onCreated();
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建任务失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="max-h-[90vh] w-full max-w-2xl overflow-auto rounded-2xl bg-surface p-5 shadow-xl">
        <h3 className="mb-1 text-base font-semibold text-foreground">生成跟进任务</h3>
        <p className="mb-3 text-xs leading-5 text-muted-foreground">
          把报告里的「下一步」复制成待办。最多选 3 条；每条都能改标题、渠道、截止时间和话术，
          创建后到「跟进任务」页完成。
        </p>
        <div className="space-y-3">
          {rows.map((row, i) => (
            <div key={i} className="rounded-xl border border-border bg-surface-2 p-3">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={row.selected} onChange={() => toggle(i)} />
                <span className="font-medium text-foreground">第 {i + 1} 条</span>
                <span className="text-xs text-muted-foreground">
                  {row.selected ? '会创建为跟进任务' : '不创建'}
                </span>
              </label>
              {row.selected && (
                <div className="mt-2 space-y-2">
                  <label className="block text-xs text-muted-foreground">
                    任务标题
                    <input
                      className="mt-1 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-foreground"
                      placeholder="如：周五前电话确认预算审批进度"
                      value={row.title}
                      onChange={e => update(i, { title: e.target.value })}
                    />
                  </label>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <label className="block text-xs text-muted-foreground">
                      渠道（可选）
                      <input
                        className="mt-1 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-foreground"
                        placeholder="电话 / 微信 / 邮件"
                        value={row.channel}
                        onChange={e => update(i, { channel: e.target.value })}
                      />
                    </label>
                    <label className="block text-xs text-muted-foreground">
                      截止时间（可选）
                      <input
                        type="datetime-local"
                        className="mt-1 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-foreground"
                        value={row.dueAt}
                        onChange={e => update(i, { dueAt: e.target.value })}
                      />
                    </label>
                  </div>
                  <label className="block text-xs text-muted-foreground">
                    跟进话术 / 要点（可选）
                    <textarea
                      rows={2}
                      className="mt-1 w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-foreground"
                      placeholder="照着说什么、要确认哪些信息"
                      value={row.content}
                      onChange={e => update(i, { content: e.target.value })}
                    />
                  </label>
                </div>
              )}
            </div>
          ))}
        </div>
        {error && <p className="mt-3 text-sm text-danger">{error}</p>}
        <p className="mt-3 text-[11px] text-muted-foreground">
          同名任务会自动跳过，不会重复创建；创建后仍可在任务页编辑或转交。
        </p>
        <div className="mt-3 flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>取消</Button>
          <Button variant="primary" onClick={submit} disabled={saving}>
            {saving ? '创建中…' : `创建 ${rows.filter(r => r.selected).length} 条任务`}
          </Button>
        </div>
      </div>
    </div>
  );
}
