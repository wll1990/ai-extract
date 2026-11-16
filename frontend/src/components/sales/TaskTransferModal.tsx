'use client';

import { useCallback, useEffect, useState } from 'react';
import { TaskOwnerOption, TaskView, listTaskOwners, transferTask } from '@/lib/api/sales';

export function TaskTransferModal({
  task,
  onClose,
  onTransferred,
}: {
  task: TaskView;
  onClose: () => void;
  onTransferred: () => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [owners, setOwners] = useState<TaskOwnerOption[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listTaskOwners(keyword || undefined, 1, 20);
      setOwners(res.content.filter(o => o.id !== task.ownerId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, task.ownerId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 250);
    return () => window.clearTimeout(timer);
  }, [load]);

  const submit = async () => {
    if (!selected) {
      setError('请选择新的负责人');
      return;
    }
    try {
      await transferTask(task.id, selected);
      onTransferred();
    } catch (e) {
      setError(e instanceof Error ? e.message : '转交失败');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-lg rounded bg-white p-5">
        <h3 className="mb-1 text-base font-medium">转交任务</h3>
        <p className="mb-3 text-xs text-gray-500">
          当前负责人：{task.ownerName || '—'}。只能转交给本企业在职员工。
        </p>
        <input
          className="mb-2 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          placeholder="搜索姓名/账号"
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
        />
        <div className="max-h-64 space-y-1 overflow-auto">
          {loading && <p className="py-3 text-sm text-gray-500">加载中…</p>}
          {!loading && owners.length === 0 && (
            <p className="py-3 text-sm text-gray-500">没有可转交的人</p>
          )}
          {owners.map(o => (
            <label
              key={o.id}
              className="flex cursor-pointer items-center justify-between rounded border border-gray-100 px-3 py-2 text-sm hover:bg-gray-50"
            >
              <span>
                {o.name}
                <span className="ml-2 text-xs text-gray-500">{o.role}</span>
              </span>
              <input
                type="radio"
                name="owner"
                checked={selected === o.id}
                onChange={() => setSelected(o.id)}
              />
            </label>
          ))}
        </div>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded border px-3 py-2 text-sm">
            取消
          </button>
          <button
            type="button"
            onClick={submit}
            className="rounded bg-blue-600 px-4 py-2 text-sm text-white"
          >
            确认转交
          </button>
        </div>
      </div>
    </div>
  );
}
