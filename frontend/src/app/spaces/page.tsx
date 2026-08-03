'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getSpaces, type SpaceInfo } from '@/lib/api/spaces';
import { usePermissionGuard } from '@/lib/hooks/usePermissionGuard';
import { Permission } from '@/lib/permissions';

export default function SpacesOverviewPage() {
  const router = useRouter();
  const { allowed, checked } = usePermissionGuard([Permission.SPACE_OWN]);

  const [spaces, setSpaces] = useState<SpaceInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const PAGE_SIZE = 20;

  useEffect(() => {
    setError('');
    getSpaces(undefined, undefined, page, PAGE_SIZE)
      .then(d => { setSpaces(d.content || []); setTotalPages(d.totalPages); })
      .catch(() => setError('加载空间列表失败，请刷新重试'))
      .finally(() => setLoading(false));
  }, [page]);

  if (!checked) return <LoadingSpinner />;
  if (!allowed) return null;
  if (loading) return <LoadingSpinner />;
  if (error && spaces.length === 0) {
    return (
      <div className="min-h-screen bg-surface px-6 py-16 text-center">
        <p className="text-lg text-red-500">{error}</p>
        <button onClick={() => window.location.reload()} className="mt-4 rounded-lg bg-primary px-6 py-2 text-sm font-semibold text-white">重试</button>
      </div>
    );
  }

  const totalReports = spaces.reduce((s, x) => s + (x.reportCount || 0), 0);
  const totalGrains = spaces.reduce((s, x) => s + (x.grainCount || 0), 0);
  const readyCount = spaces.filter(s => s.skillStatus === 'published').length;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="text-2xl font-bold text-foreground mb-1">空间总览</h1>
        <p className="text-sm text-muted-foreground mb-6">
          {spaces.length > 0 ? `${spaces.length} 位销冠 · ${readyCount} 位分身已发布` : '暂无销冠空间'}
        </p>

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
              <p className="text-xs text-muted-foreground mt-1">位分身已发布</p>
            </div>
          </div>
        )}

        {spaces.length > 0 ? (
          <>
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
                        {s.skillStatus === 'published' ? '🤖 已发布'
                          : s.skillStatus === 'generating' ? '⏳ 萃取中'
                          : s.skillStatus === 'reviewing' ? '⏳ 待审核'
                          : s.skillStatus === 'discarded' ? '已驳回'
                          : '未创建'}
                      </span>
                    </div>
                  </div>
                </button>
              ))}
            </div>
            {totalPages > 1 && (
              <div className="mt-6 flex justify-center gap-2">
                <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page <= 1}
                  className="rounded-lg border border-border px-4 py-2 text-sm disabled:opacity-30">上一页</button>
                <span className="flex items-center px-3 text-sm text-muted-foreground">{page}/{totalPages}</span>
                <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={page >= totalPages}
                  className="rounded-lg border border-border px-4 py-2 text-sm disabled:opacity-30">下一页</button>
              </div>
            )}
          </>
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
