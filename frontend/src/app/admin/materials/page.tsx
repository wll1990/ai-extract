'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';

export default function MaterialsOverviewPage() {
  const router = useRouter();
  const [skills, setSkills] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient<any[]>('/admin/skills/picker').then(d => setSkills(d || [])).catch(() => {}).finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-4xl">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold text-foreground">素材管理</h1>
          <button onClick={() => router.push('/admin/skills/upload')}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-primary-hover">
            + 上传素材
          </button>
        </div>

        {skills.length === 0 ? (
          <div className="py-16 text-center text-muted-foreground-2">
            <p className="text-4xl mb-2">📁</p>
            <p>暂无分身，请先上传素材创建分身</p>
          </div>
        ) : (
          <div className="space-y-3">
            {skills.map((s: any) => (
              <div key={s.id} className="bg-surface-2 rounded-xl border border-border p-4 flex items-center justify-between hover:shadow-sm transition">
                <div className="flex items-center gap-4">
                  <div className="flex h-10 w-10 items-center justify-center rounded-full bg-foreground text-white font-bold text-sm">
                    {(s.name || '?')[0]}
                  </div>
                  <div>
                    <p className="font-medium text-foreground">{s.name}</p>
                    <p className="text-xs text-muted-foreground-2">状态: {s.status}</p>
                  </div>
                </div>
                <div className="flex gap-2">
                  <button onClick={() => router.push(`/admin/skills/${s.id}/materials`)}
                    className="rounded-lg px-3 py-1.5 text-xs text-primary hover:bg-primary-light">
                    📁 素材列表
                  </button>
                  <button onClick={() => router.push(`/admin/skills/${s.id}/audit`)}
                    className="rounded-lg px-3 py-1.5 text-xs text-muted-foreground hover:bg-primary-light">
                    审核 →
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
