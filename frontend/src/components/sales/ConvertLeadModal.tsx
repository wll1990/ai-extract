'use client';

import { useState } from 'react';
import { LeadCandidate, convertLead } from '@/lib/api/sales';
import { Button } from '@/components/ui/Button';

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
      <div className="w-full max-w-lg rounded-2xl bg-surface p-5 shadow-xl">
        <h3 className="mb-1 text-base font-semibold text-foreground">转为线索</h3>
        <p className="mb-3 text-xs leading-5 text-muted-foreground">
          把客户存进线索池，之后到「线索」页记录跟进。同一份复盘只能转一次。
        </p>
        {draft && (
          <p className="mb-3 rounded-lg bg-warning-bg px-3 py-2 text-xs text-warning-text">
            以下信息由 AI 从对话中预填，未经引用校验，请核对后再保存。
          </p>
        )}
        <div className="space-y-3">
          <label className="block text-sm">
            <span className="mb-1 block text-foreground">线索名称 *</span>
            <input
              className="w-full rounded-lg border border-border-strong bg-surface-2 px-3 py-2 text-foreground"
              value={form.name}
              onChange={e => setForm({ ...form, name: e.target.value })}
            />
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="block text-sm">
              <span className="mb-1 block text-foreground">联系人</span>
              <input
                className="w-full rounded-lg border border-border-strong bg-surface-2 px-3 py-2 text-foreground"
                value={form.contactName}
                onChange={e => setForm({ ...form, contactName: e.target.value })}
              />
            </label>
            <label className="block text-sm">
              <span className="mb-1 block text-foreground">电话</span>
              <input
                className="w-full rounded-lg border border-border-strong bg-surface-2 px-3 py-2 text-foreground"
                value={form.contactPhone}
                onChange={e => setForm({ ...form, contactPhone: e.target.value })}
              />
            </label>
          </div>
          <label className="block text-sm">
            <span className="mb-1 block text-foreground">需求摘要</span>
            <textarea
              rows={3}
              className="w-full rounded-lg border border-border-strong bg-surface-2 px-3 py-2 text-foreground"
              value={form.needSummary}
              onChange={e => setForm({ ...form, needSummary: e.target.value })}
            />
          </label>
        </div>

        {error && <p className="mt-3 text-sm text-danger">{error}</p>}

        {candidates && (
          <div className="mt-3 rounded-xl border border-warning-text/30 bg-warning-bg p-3">
            <p className="mb-2 text-sm text-warning-text">可能重复的线索，请确认：</p>
            <ul className="space-y-1 text-sm">
              {candidates.map(c => (
                <li key={c.id} className="flex justify-between">
                  <span>{c.name}</span>
                  <span className="text-xs text-muted-foreground">{c.ownerName || '—'}</span>
                </li>
              ))}
            </ul>
            <Button
              variant="neutral"
              size="sm"
              className="mt-3"
              onClick={() => submit(true)}
              disabled={saving}
              title="忽略重复提示，仍然创建一条新线索"
            >
              仍要新建
            </Button>
          </div>
        )}

        <div className="mt-4 flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>取消</Button>
          <Button
            variant="primary"
            onClick={() => submit(false)}
            disabled={saving}
            title="核对无误后创建线索；如发现重名会先提示，不会直接写入"
          >
            {saving ? '提交中…' : '确认创建'}
          </Button>
        </div>
      </div>
    </div>
  );
}
