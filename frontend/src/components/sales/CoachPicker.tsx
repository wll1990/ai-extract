'use client';

import { useEffect, useRef, useState } from 'react';
import { CoachOption, searchCoaches } from '@/lib/api/sales';

export function CoachPicker({
  value,
  onChange,
  error,
}: {
  value: { id: string; name: string } | null;
  onChange: (v: { id: string; name: string } | null) => void;
  error?: string;
}) {
  const [open, setOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<CoachOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const seq = useRef(0);

  useEffect(() => {
    if (!open) return;
    const current = ++seq.current;
    const timer = window.setTimeout(async () => {
      setLoading(true);
      setLoadError(null);
      try {
        const res = await searchCoaches({
          keyword: keyword || undefined,
          size: 20,
        });
        if (current === seq.current) setItems(res.content);
      } catch (e) {
        if (current === seq.current) {
          setItems([]);
          setLoadError(e instanceof Error ? e.message : '加载失败');
        }
      } finally {
        if (current === seq.current) setLoading(false);
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [open, keyword, reloadKey]);

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen(v => !v)}
        className={`flex w-full items-center justify-between rounded border px-3 py-2 text-left text-sm ${
          error ? 'border-red-400' : 'border-gray-300'
        }`}
      >
        <span className={value ? 'text-gray-900' : 'text-gray-400'}>
          {value ? value.name : '选择一位教练（本企业已发布分身）'}
        </span>
        <span className="text-gray-400">▾</span>
      </button>
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
      {open && (
        <div className="absolute z-30 mt-1 w-full rounded border border-gray-200 bg-white shadow-lg">
          <div className="border-b p-2">
            <input
              autoFocus
              className="w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
              placeholder="搜索姓名/分身名"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
            />
          </div>
          <div className="max-h-64 overflow-auto">
            {loading && <p className="p-3 text-sm text-gray-500">搜索中…</p>}
            {!loading && loadError && (
              <div className="p-3 text-sm text-red-600">
                {loadError}
                <button
                  type="button"
                  onClick={() => setReloadKey(k => k + 1)}
                  className="ml-2 text-blue-600 hover:underline"
                >
                  重试
                </button>
              </div>
            )}
            {!loading && !loadError && items.length === 0 && (
              <p className="p-3 text-sm text-gray-500">没有可选教练</p>
            )}
            {items.map(item => {
              const thin = (item.grainCount ?? 0) < 10;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => {
                    onChange({ id: item.id, name: item.name });
                    setOpen(false);
                  }}
                  className="block w-full px-3 py-2 text-left text-sm hover:bg-gray-50"
                >
                  <span className="flex items-center justify-between">
                    <span className="text-gray-900">{item.name}</span>
                    <span className="text-xs text-gray-500">
                      {item.grainCount ?? 0} 颗粒 · {item.sceneCount ?? 0} 场景
                    </span>
                  </span>
                  {thin && (
                    <span className="mt-0.5 block text-xs text-amber-600">
                      经验较少，建议换一个教练或先补充素材
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
