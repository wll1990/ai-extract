'use client';

import { useEffect, useState } from 'react';
import {
  CoachSupplement,
  cancelCoachSupplement,
  requestCoachSupplement,
} from '@/lib/api/sales';

export function CoachSupplementPanel({
  reviewId,
  data,
  isOwner,
  onChanged,
}: {
  reviewId: string;
  data: CoachSupplement;
  isOwner: boolean;
  onChanged: () => void | Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState('');
  const [share, setShare] = useState(!!data.shareTranscript);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const notes = data.notes || [];

  // 已授权过原文时，勾选框要显示真实状态
  useEffect(() => {
    if (!open) setShare(data.shareTranscript);
  }, [data.shareTranscript, open]);

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      await requestCoachSupplement(reviewId, {
        message: message.trim() || undefined,
        shareTranscript: share,
      });
      setOpen(false);
      setMessage('');
      setShare(false);
      await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败');
    } finally {
      setSaving(false);
    }
  };

  const cancel = async () => {
    try {
      await cancelCoachSupplement(reviewId);
      await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : '取消失败');
    }
  };

  return (
    <section className="rounded border border-purple-200 bg-purple-50/40 p-4">
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-medium text-purple-900">真人教练补充</h3>
        {isOwner && !data.selfCoach && data.status !== 'pending' && (
          <button
            type="button"
            onClick={() => setOpen(v => !v)}
            className="text-xs text-purple-700 hover:underline"
          >
            请教练补充
          </button>
        )}
        {isOwner && !data.selfCoach && data.status === 'pending' && (
          <button
            type="button"
            onClick={cancel}
            className="text-xs text-gray-500 hover:underline"
          >
            撤回请求
          </button>
        )}
      </div>

      {data.selfCoach && (
        <p className="text-sm text-purple-800">
          你本人就是这个分身的教练，无需发起请求。
          <a href={`/sales/coach/requests/${reviewId}`} className="ml-1 underline hover:no-underline">
            直接去教练工作台补充 →
          </a>
        </p>
      )}
      {!data.selfCoach && !data.status && notes.length === 0 && (
        <p className="text-sm text-gray-500">
          AI 报告为主；需要人判断时，可以请教练补充一句。
        </p>
      )}
      {data.status === 'pending' && (
        <p className="text-sm text-purple-800">已请求教练补充，等待回复。</p>
      )}
      {data.status === 'done' && notes.length === 0 && (
        <p className="text-sm text-gray-500">教练已完成，但没有留言。</p>
      )}
      {data.status === 'cancelled' && (
        <p className="text-sm text-gray-500">本次补充请求已撤回，可以重新发起。</p>
      )}

      {notes.length > 0 && (
        <ul className="mt-3 space-y-2">
          {notes.map(n => (
            <li key={n.id} className="rounded bg-white p-3 text-sm">
              <div className="mb-1 text-xs text-gray-500">
                {n.coachName} · {new Date(n.createdAt).toLocaleString()}
              </div>
              <p className="whitespace-pre-wrap text-gray-800">{n.content}</p>
            </li>
          ))}
        </ul>
      )}

      {open && (
        <div className="mt-3 space-y-2">
          <textarea
            rows={3}
            value={message}
            onChange={e => setMessage(e.target.value)}
            maxLength={500}
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            placeholder="想请教练重点看什么？（可不填）"
          />
          <label className="flex items-center gap-2 text-xs text-gray-600">
            <input type="checkbox" checked={share} onChange={e => setShare(e.target.checked)} />
            同时授权教练查看完整原文
          </label>
          {error && <p className="text-xs text-red-600">{error}</p>}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="rounded border px-3 py-1.5 text-sm"
            >
              取消
            </button>
            <button
              type="button"
              onClick={submit}
              disabled={saving}
              className="rounded bg-purple-700 px-3 py-1.5 text-sm text-white disabled:opacity-50"
            >
              {saving ? '发送中…' : '发送请求'}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
