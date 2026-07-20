'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface DashboardData {
  stats: { spaceCount: number; reportCount: number; grainCount: number; materialCount: number };
  pending: Array<{ type: string; skillId?: string; spaceId?: string; name?: string; status: string; count?: number }>;
  recent: Array<{ type: string; title: string; time: string; spaceName: string }>;
}

export default function AdminDashboard() {
  const router = useRouter();
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient<DashboardData>('/admin/dashboard')
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner />;
  if (!data) return null;

  const { stats, pending, recent } = data;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px] space-y-8">
        <h1 className="text-2xl font-bold text-foreground">工作台</h1>

        {/* 数据总览 */}
        <div className="grid grid-cols-4 gap-4">
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{stats.spaceCount}</p>
            <p className="text-xs text-muted-foreground mt-1">位销冠</p>
          </div>
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{stats.reportCount}</p>
            <p className="text-xs text-muted-foreground mt-1">份报告</p>
          </div>
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{stats.grainCount}</p>
            <p className="text-xs text-muted-foreground mt-1">条锦囊</p>
          </div>
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{stats.materialCount}</p>
            <p className="text-xs text-muted-foreground mt-1">份素材</p>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* 待处理 */}
          <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
            <h2 className="font-semibold text-foreground mb-4">⚠️ 待处理</h2>
            {pending.length > 0 ? (
              <div className="space-y-2">
                {pending.map((item, i) => (
                  <div key={i}
                    className={`flex items-center justify-between rounded-lg px-4 py-3 text-sm ${
                      item.type === 'skill_review' ? 'bg-danger-bg border border-danger/20' : 'bg-warning-bg border border-warning/20'
                    }`}>
                    <div className="flex items-center gap-2 min-w-0">
                      <span>{item.type === 'skill_review' ? '🔴' : '🟡'}</span>
                      <span className="font-medium truncate">
                        {item.type === 'skill_review' ? `${item.name} · 分身待审核` : `${item.count} 份素材处理中`}
                      </span>
                    </div>
                    {item.type === 'skill_review' && item.spaceId && (
                      <button
                        onClick={() => router.push(`/admin/skills/${item.skillId}/audit`)}
                        className="flex-shrink-0 text-xs text-danger font-medium hover:underline ml-3">
                        去审核 →
                      </button>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground-2 text-center py-6">暂无待处理事项</p>
            )}
          </div>

          {/* 最近活动 */}
          <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
            <h2 className="font-semibold text-foreground mb-4">📈 最近活动</h2>
            {recent.length > 0 ? (
              <div className="space-y-2">
                {recent.map((item, i) => (
                  <div key={i} className="flex items-center justify-between rounded-lg bg-surface px-3 py-2.5 text-sm">
                    <div className="min-w-0">
                      <span className="text-foreground truncate block">📄 {item.title}</span>
                      {item.spaceName && (
                        <span className="text-xs text-muted-foreground-2">{item.spaceName}</span>
                      )}
                    </div>
                    <span className="text-xs text-muted-foreground-2 flex-shrink-0 ml-3">
                      {item.time ? new Date(item.time).toLocaleDateString('zh-CN') : ''}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground-2 text-center py-6">暂无活动</p>
            )}
          </div>
        </div>

        {/* 快捷入口 */}
        <div className="rounded-xl bg-surface-2 border border-border p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="font-semibold text-foreground">🎯 分身调优</h3>
              <p className="text-xs text-muted-foreground mt-1">查看使用数据 · RAG 分布 · 颗粒质量 · 知识缺口</p>
            </div>
            <button onClick={() => router.push('/admin/insights')}
              className="text-sm bg-primary text-white rounded-lg px-4 py-2 hover:bg-primary-hover transition-colors">
              进入 →
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
