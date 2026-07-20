'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getSpaces, type SpaceInfo } from '@/lib/api/spaces';

/**
 * 空间总览页 — 销冠知识大盘
 */
export default function SpacesOverviewPage() {
  const router = useRouter();
  const [spaces, setSpaces] = useState<SpaceInfo[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getSpaces(undefined, undefined, 1, 50)
      .then(d => setSpaces(d.content || []))
      .catch(e => console.error('加载空间列表失败:', e))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner />;

  const totalReports = spaces.reduce((s, x) => s + (x.reportCount || 0), 0);
  const totalGrains = spaces.reduce((s, x) => s + (x.grainCount || 0), 0);
  const readyCount = spaces.filter(s => s.skillStatus === 'published').length;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="text-2xl font-bold text-foreground mb-1">空间总览</h1>
        <p className="text-sm text-muted-foreground mb-6">
          {spaces.length > 0 ? `${spaces.length} 位销冠 · ${readyCount} 位分身已就绪` : '暂无销冠空间'}
        </p>

        {/* 全公司汇总 */}
        {spaces.length > 0 && (
          <div className="grid grid-cols-3 gap-4 mb-8">
            <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
              <p className="text-3xl font-bold text-foreground">{totalReports}</p>
              <p className="text-xs text-muted-foreground mt-1">份萃取报告</p>
            </div>
            <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
              <p className="text-3xl font-bold text-foreground">{totalGrains}</p>
              <p className="text-xs text-muted-foreground mt-1">条经验锦囊</p>
            </div>
            <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
              <p className="text-3xl font-bold text-foreground">{readyCount}</p>
              <p className="text-xs text-muted-foreground mt-1">位分身已就绪</p>
            </div>
          </div>
        )}

        {/* 空间列表 */}
        {spaces.length > 0 ? (
          <div className="space-y-3">
            {spaces.map((s) => (
              <button
                key={s.id}
                onClick={() => router.push(`/space/${s.id}`)}
                className="w-full rounded-xl bg-surface-2 border border-border p-5 text-left shadow-sm hover:shadow-md hover:border-primary/20 transition-all"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-4 min-w-0">
                    <div className="flex h-11 w-11 items-center justify-center rounded-full bg-foreground text-base font-bold text-white flex-shrink-0">
                      {(s.ownerName || '?')[0]}
                    </div>
                    <div className="min-w-0">
                      <h3 className="font-semibold text-foreground truncate">{s.ownerName || '未命名'}</h3>
                      <p className="text-xs text-muted-foreground truncate">{s.description || s.title || ''}</p>
                    </div>
                  </div>

                  <div className="flex items-center gap-5 flex-shrink-0">
                    <div className="hidden sm:flex items-center gap-4 text-xs text-muted-foreground">
                      <span>📊 {s.reportCount || 0} 报告</span>
                      <span>💎 {s.grainCount || 0} 锦囊</span>
                    </div>
                    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${
                      s.skillStatus === 'published'
                        ? 'bg-success-bg text-success'
                        : s.skillStatus === 'generating' || s.skillStatus === 'reviewing'
                          ? 'bg-warning-bg text-warning-text'
                          : 'bg-primary-light text-muted-foreground'
                    }`}>
                      {s.skillStatus === 'published' ? '🤖 已就绪'
                        : s.skillStatus === 'generating' || s.skillStatus === 'reviewing' ? '⏳ 生成中'
                        : '未创建'}
                    </span>
                  </div>
                </div>
              </button>
            ))}
          </div>
        ) : (
          <div className="py-16 text-center">
            <p className="text-4xl mb-3">📭</p>
            <p className="text-muted-foreground">暂无销冠空间</p>
            <p className="mt-1 text-sm text-muted-foreground-2">创建用户后自动生成个人空间</p>
          </div>
        )}
      </div>
    </div>
  );
}
