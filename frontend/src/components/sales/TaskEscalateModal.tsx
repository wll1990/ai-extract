'use client';

import { useState } from 'react';
import { TaskView, escalateTask } from '@/lib/api/sales';

export function TaskEscalateModal({
  task,
  onClose,
  onEscalated,
}: {
  task: TaskView;
  onClose: () => void;
  onEscalated: () => void;
}) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      await escalateTask(task.id, reason.trim() || undefined);
      onEscalated();
    } catch (e) {
      setError(e instanceof Error ? e.message : '升级失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded bg-white p-5">
        <h3 className="mb-1 text-base font-medium">升级任务</h3>
        <p className="mb-3 text-xs text-gray-500">
          升级后会通知本企业管理员。任务：{task.title}
        </p>
        <textarea
          rows={3}
          className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
          placeholder="升级原因（可不填）"
          value={reason}
          onChange={e => setReason(e.target.value)}
        />
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded border px-3 py-2 text-sm">
            取消
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={saving}
            className="rounded bg-amber-600 px-4 py-2 text-sm text-white disabled:opacity-50"
          >
            {saving ? '提交中…' : '确认升级'}
          </button>
        </div>
      </div>
    </div>
  );
}
