'use client';

import { useState } from 'react';
import { LeadCandidate, convertLead } from '@/lib/api/sales';

export function ConvertLeadModal({
  reviewId,
  draft,
  onClose,
  onConverted,
}: {
  reviewId: string;
  draft?: { name?: string; contactName?: string; contactPhone?: string; needSummary?: string };
  onClose: () => void;
  onConverted: (leadId: string) => void;
}) {
  const [form, setForm] = useState({
    name: draft?.name || '',
    contactName: draft?.contactName || '',
    contactPhone: draft?.contactPhone || '',
    needSummary: draft?.needSummary || '',
  });
  const [candidates, setCandidates] = useState<LeadCandidate[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (force = false) => {
    if (!form.name.trim()) {
      setError('线索名称不能为空');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const res = await convertLead(reviewId, { ...form, force });
      if (!res.created) {
        setCandidates(res.duplicateCandidates);
        return;
      }
      onConverted(res.lead!.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : '转线索失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-lg rounded bg-white p-5">
        <h3 className="mb-1 text-base font-medium">转为线索</h3>
        {draft && (
          <p className="mb-3 text-xs text-amber-700">
            ⚠️ 以下信息由 AI 从对话中预填，未经引用校验，请核对后再保存。
          </p>
        )}
        <div className="space-y-3">
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">线索名称 *</span>
            <input
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={form.name}
              onChange={e => setForm({ ...form, name: e.target.value })}
            />
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="block text-sm">
              <span className="mb-1 block text-gray-700">联系人</span>
              <input
                className="w-full rounded border border-gray-300 px-3 py-2"
                value={form.contactName}
                onChange={e => setForm({ ...form, contactName: e.target.value })}
              />
            </label>
            <label className="block text-sm">
              <span className="mb-1 block text-gray-700">电话</span>
              <input
                className="w-full rounded border border-gray-300 px-3 py-2"
                value={form.contactPhone}
                onChange={e => setForm({ ...form, contactPhone: e.target.value })}
              />
            </label>
          </div>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">需求摘要</span>
            <textarea
              rows={3}
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={form.needSummary}
              onChange={e => setForm({ ...form, needSummary: e.target.value })}
            />
          </label>
        </div>

        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

        {candidates && (
          <div className="mt-3 rounded border border-amber-200 bg-amber-50 p-3">
            <p className="mb-2 text-sm text-amber-900">可能重复的线索，请确认：</p>
            <ul className="space-y-1 text-sm">
              {candidates.map(c => (
                <li key={c.id} className="flex justify-between">
                  <span>{c.name}</span>
                  <span className="text-xs text-gray-500">{c.ownerName || '—'}</span>
                </li>
              ))}
            </ul>
            <button
              type="button"
              onClick={() => submit(true)}
              disabled={saving}
              className="mt-3 rounded border border-amber-400 px-3 py-1.5 text-sm text-amber-900"
            >
              仍要新建
            </button>
          </div>
        )}

        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded border px-3 py-2 text-sm">
            取消
          </button>
          <button
            type="button"
            onClick={() => submit(false)}
            disabled={saving}
            className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
          >
            {saving ? '提交中…' : '确认创建'}
          </button>
        </div>
      </div>
    </div>
  );
}
