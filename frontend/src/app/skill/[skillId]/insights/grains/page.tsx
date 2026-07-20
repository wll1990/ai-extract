'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_BASE, authHeaders } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface GrainItem {
  id: string; sceneDescription?: string; sceneTag?: string;
  weight: number; status: string; helpfulCount: number; unhelpfulCount: number;
}
type SortMode = 'helpful' | 'unhelpful' | 'recent';

/**
 * 分身主颗粒管理 —— 查看、编辑自己的所有颗粒。
 */
export default function SkillOwnerGrainsPage() {
  const params = useParams(); const router = useRouter();
  const skillId = params.skillId as string;
  const [grains, setGrains] = useState<GrainItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [sort, setSort] = useState<SortMode>('helpful');

  useEffect(() => {
    setLoading(true);
    fetch(`${API_BASE}/admin/grains?skillId=${skillId}&sort=${sort}`, { headers: authHeaders() })
      .then(r => r.json()).then(d => setGrains(d.data || [])).catch(() => {})
      .finally(() => setLoading(false));
  }, [skillId, sort]);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px] space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-foreground">✏️ 我的颗粒</h1>
            <p className="text-sm text-muted-foreground mt-1">
              共 {grains.length} 条 · 点击可查看和编辑
            </p>
          </div>
          <div className="flex gap-2">
            <select value={sort} onChange={e => setSort(e.target.value as SortMode)}
              className="rounded-lg border px-3 py-2 text-sm bg-surface-2">
              <option value="helpful">👍 最有用</option>
              <option value="unhelpful">👎 需改进</option>
              <option value="recent">📅 最新</option>
            </select>
            <button onClick={() => router.push(`/admin/grains/new?skillId=${skillId}`)}
              className="text-sm bg-primary text-white rounded-lg px-4 py-2">
              + 新增颗粒
            </button>
            <button onClick={() => router.back()}
              className="text-sm rounded-lg px-4 py-2 border">返回</button>
          </div>
        </div>

        <div className="space-y-2">
          {grains.map((g: GrainItem) => (
            <div key={g.id}
              onClick={() => router.push(`/admin/grains/${g.id}`)}
              className="rounded-xl bg-surface-2 border border-border p-4 hover:bg-primary-light/30 cursor-pointer transition-colors">
              <div className="flex items-center justify-between">
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium truncate">{g.sceneDescription || g.sceneTag || '未命名'}</p>
                  <p className="text-xs text-muted-foreground mt-1">
                    {g.sceneTag} · 权重 {g.weight} · {g.status}
                  </p>
                </div>
                <div className="flex items-center gap-4 ml-4 text-xs">
                  <span className="text-green-600">👍 {g.helpfulCount}</span>
                  <span className="text-red-400">👎 {g.unhelpfulCount}</span>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
