'use client';

import React from 'react';

interface Props {
  onCancel: () => void;
  onConfirm: () => void;
}

export default function EndConfirmModal({ onCancel, onConfirm }: Props) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onCancel}>
      <div className="mx-4 w-full max-w-sm rounded-2xl bg-surface-2 p-6 text-center shadow-xl" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-bold text-foreground">结束对练？</h3>
        <p className="mt-2 text-sm text-muted-foreground">AI 将对你的表现进行教练式复盘。</p>
        <div className="mt-6 flex gap-3">
          <button type="button" onClick={onCancel}
            className="flex-1 rounded-lg border border-border-strong py-2 text-sm">取消</button>
          <button type="button" onClick={onConfirm}
            className="flex-1 rounded-lg bg-foreground py-2 text-sm text-white">确定结束</button>
        </div>
      </div>
    </div>
  );
}
