'use client';

import { useEffect, useState } from 'react';

interface Props {
  open: boolean;
  title?: string;
  description?: string;
  defaultMonths?: number;
  onCancel: () => void;
  onConfirm: (isoExpireAt: string) => void;
}

/** 日期选择器，替代 window.prompt 手输 ISO：默认 1 年后，可选 3/6/12 个月 */
export function ExpiryDialog({ open, title = '设置失效时间', description, defaultMonths = 12, onCancel, onConfirm }: Props) {
  const [date, setDate] = useState('');

  useEffect(() => {
    if (!open) return;
    const d = new Date();
    d.setMonth(d.getMonth() + defaultMonths);
    setDate(d.toISOString().slice(0, 10));
  }, [open, defaultMonths]);

  if (!open) return null;

  const quick = (months: number) => {
    const d = new Date();
    d.setMonth(d.getMonth() + months);
    setDate(d.toISOString().slice(0, 10));
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40" onClick={onCancel}>
      <div className="w-full max-w-sm rounded-xl bg-surface p-5 shadow-xl" onClick={e => e.stopPropagation()}>
        <h3 className="mb-1 text-base font-semibold text-foreground">{title}</h3>
        <p className="mb-3 text-xs text-muted-foreground">
          {description || '通过后到期自动停止被分身检索；到期前可再次调整。'}
        </p>
        <div className="mb-2 flex gap-2">
          {[3, 6, 12].map(m => (
            <button key={m} type="button" onClick={() => quick(m)}
              className="rounded-full border border-border px-3 py-1 text-xs text-muted-foreground hover:bg-surface-2">
              {m} 个月
            </button>
          ))}
        </div>
        <input
          type="date"
          value={date}
          onChange={e => setDate(e.target.value)}
          className="mb-4 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
        />
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onCancel}
            className="rounded-lg border border-border px-4 py-2 text-sm text-muted-foreground">取消</button>
          <button
            type="button"
            disabled={!date}
            onClick={() => onConfirm(`${date}T00:00:00`)}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >确认</button>
        </div>
      </div>
    </div>
  );
}
