'use client';

import { useState } from 'react';
import { TaskView, updateTask } from '@/lib/api/sales';

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

export function TaskEditModal({
  task,
  onClose,
  onSaved,
}: {
  task: TaskView;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [title, setTitle] = useState(task.title);
  const [channel, setChannel] = useState(task.channel || '');
  const [dueAt, setDueAt] = useState(toLocalInput(task.dueAt));
  const [content, setContent] = useState(task.content || '');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    if (!title.trim()) {
      setError('任务标题不能为空');
      return;
    }
    const dueChanged = dueAt !== toLocalInput(task.dueAt);
    if (dueChanged && dueAt && new Date(dueAt).getTime() < Date.now()) {
      setError('截止时间不能早于现在');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await updateTask(task.id, {
        title: title.trim(),
        channel: channel.trim() || undefined,
        dueAt: dueAt ? toLocalDateTime(dueAt) : null,
        content: content.trim() || undefined,
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
        <h3 className="mb-3 text-base font-medium">编辑任务</h3>
        <div className="space-y-2">
          <input
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="任务标题"
          />
          <div className="grid grid-cols-2 gap-2">
            <input
              className="rounded border border-gray-300 px-3 py-2 text-sm"
              value={channel}
              onChange={e => setChannel(e.target.value)}
              placeholder="渠道"
            />
            <input
              type="datetime-local"
              className="rounded border border-gray-300 px-3 py-2 text-sm"
              value={dueAt}
              onChange={e => setDueAt(e.target.value)}
            />
          </div>
          <textarea
            rows={3}
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            value={content}
            onChange={e => setContent(e.target.value)}
            placeholder="跟进话术/要点"
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
