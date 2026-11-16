'use client';

import { useState } from 'react';
import { LeadDetail, updateLead } from '@/lib/api/sales';

function pad(n: number) {
  return String(n).padStart(2, '0');
}

function toLocalInput(value: string | null) {
  if (!value) return '';
  const d = new Date(value);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function toLocalDateTime(value: string) {
  return value.length === 16 ? `${value}:00` : value;
}

export function LeadEditModal({
  detail,
  onClose,
  onSaved,
}: {
  detail: LeadDetail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(detail.summary.name);
  const [needSummary, setNeedSummary] = useState(detail.needSummary || '');
  const [nextFollowUpAt, setNextFollowUpAt] = useState(toLocalInput(detail.nextFollowUpAt));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    if (!name.trim()) {
      setError('线索名称不能为空');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await updateLead(detail.summary.id, {
        name: name.trim(),
        needSummary: needSummary.trim() || undefined,
        nextFollowUpAt: nextFollowUpAt ? toLocalDateTime(nextFollowUpAt) : null,
      });
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-lg rounded bg-white p-5">
        <h3 className="mb-3 text-base font-medium">编辑线索资料</h3>
        <div className="space-y-2">
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">线索名称</span>
            <input
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={name}
              onChange={e => setName(e.target.value)}
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">下次跟进</span>
            <input
              type="datetime-local"
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={nextFollowUpAt}
              onChange={e => setNextFollowUpAt(e.target.value)}
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">需求摘要</span>
            <textarea
              rows={4}
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={needSummary}
              onChange={e => setNeedSummary(e.target.value)}
            />
          </label>
        </div>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded border px-3 py-2 text-sm">
            取消
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={saving}
            className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
          >
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}
