'use client';

import React, { useEffect } from 'react';
import { Button } from '@/components/ui/Button';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  children: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * 通用确认弹窗：用于「点下去会调用模型/产生成本」这类需要解释清楚的确认。
 * 与 window.confirm 不同，可以承载多行说明和列表。
 */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel = '确认',
  cancelLabel = '取消',
  busy,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onCancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, busy, onCancel]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      onClick={() => { if (!busy) onCancel(); }}
    >
      <div
        className="w-full max-w-[480px] rounded-2xl bg-surface p-6 shadow-xl"
        onClick={e => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold text-foreground">{title}</h3>
        <div className="mt-2 space-y-2 text-sm leading-6 text-muted-foreground">{children}</div>
        <div className="mt-5 flex justify-end gap-2">
          <Button variant="neutral" onClick={onCancel} disabled={busy}>{cancelLabel}</Button>
          <Button variant="primary" onClick={onConfirm} disabled={busy}>
            {busy ? '处理中…' : confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
