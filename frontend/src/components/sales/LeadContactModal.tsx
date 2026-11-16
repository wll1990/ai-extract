'use client';

import { useState } from 'react';
import {
  LeadContact,
  LeadContactRole,
  addLeadContact,
  updateLeadContact,
} from '@/lib/api/sales';

const ROLE_OPTIONS: Array<{ value: LeadContactRole; label: string }> = [
  { value: 'decision_maker', label: '决策人' },
  { value: 'influencer', label: '影响人' },
  { value: 'user', label: '使用人' },
  { value: 'gatekeeper', label: '把关人' },
  { value: 'other', label: '其他' },
];

export function LeadContactModal({
  leadId,
  contact,
  defaultPrimary,
  onClose,
  onSaved,
}: {
  leadId: string;
  contact?: LeadContact;
  defaultPrimary?: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(contact?.name || '');
  const [phone, setPhone] = useState(contact?.phone || '');
  const [role, setRole] = useState<LeadContactRole>(contact?.role || 'other');
  const [primary, setPrimary] = useState(contact?.primary ?? Boolean(defaultPrimary));
  const [note, setNote] = useState(contact?.note || '');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    if (!name.trim()) {
      setError('联系人姓名不能为空');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const body = {
        name: name.trim(),
        phone: phone.trim() || undefined,
        role,
        primary,
        note: note.trim() || undefined,
      };
      if (contact) {
        await updateLeadContact(leadId, contact.id, body);
      } else {
        await addLeadContact(leadId, body);
      }
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded bg-white p-5">
        <h3 className="mb-3 text-base font-medium">
          {contact ? '编辑联系人' : '添加联系人'}
        </h3>
        <div className="space-y-2">
          <input
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            placeholder="姓名 *"
            value={name}
            onChange={e => setName(e.target.value)}
          />
          <input
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            placeholder="电话"
            value={phone}
            onChange={e => setPhone(e.target.value)}
          />
          <select
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            value={role}
            onChange={e => setRole(e.target.value as LeadContactRole)}
          >
            {ROLE_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={primary} onChange={e => setPrimary(e.target.checked)} />
            设为主联系人（一个线索只能有一个）
          </label>
          <textarea
            rows={2}
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            placeholder="备注"
            value={note}
            onChange={e => setNote(e.target.value)}
          />
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
