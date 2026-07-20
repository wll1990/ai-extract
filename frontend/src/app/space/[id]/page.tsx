'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getSpace, type SpaceDetail } from '@/lib/api/spaces';

/**
 * 空间详情页 — 一个人的知识全景
 */
export default function SpaceDetailPage() {
  const params = useParams();
  const router = useRouter();
  const spaceId = (params.id as string) || '';

  const [space, setSpace] = useState<SpaceDetail | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!spaceId) return;
    getSpace(spaceId)
      .then(data => setSpace(data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [spaceId]);

  if (loading) return <LoadingSpinner />;
  if (!space) {
    return (
      <div className="min-h-screen bg-surface px-6 py-16 text-center">
        <p className="text-lg text-muted-foreground">空间不存在或无权访问</p>
        <button onClick={() => router.push('/spaces')}
          className="mt-4 rounded-lg bg-primary px-6 py-2 text-sm font-semibold text-white">返回空间总览</button>
      </div>
    );
  }

  const stats = space.stats || { reportCount: 0, viewCount: 0, grainCount: 0, interviewCount: 0, materialCount: 0 };
  const distribution = space.grainDistribution || [];
  const maxGrain = distribution.length > 0 ? Math.max(...distribution.map(d => d.count)) : 1;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px] space-y-8">
        {/* ====== 概览卡片 ====== */}
        <button onClick={() => router.push('/spaces')} className="text-sm text-muted-foreground hover:text-foreground mb-2 inline-block">
          ← 空间总览
        </button>

        <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
          <div className="flex items-start justify-between flex-wrap gap-4">
            {/* 个人信息 */}
            <div className="flex items-center gap-4">
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-foreground text-xl font-bold text-white flex-shrink-0">
                {space.ownerName?.[0] || '?'}
              </div>
              <div>
                <h2 className="text-xl font-bold text-foreground">{space.ownerName}</h2>
                <p className="text-sm text-muted-foreground">{space.ownerTitle || space.description || ''}</p>
                {(space.ownerTags || []).length > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {space.ownerTags.map((t: string) => (
                      <span key={t} className="rounded-full bg-primary-light px-2 py-0.5 text-xs text-primary">{t}</span>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* 操作按钮 */}
            <div className="flex gap-2">
              <button onClick={() => router.push(`/admin/skills/upload?spaceId=${spaceId}`)}
                className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-foreground hover:bg-primary-light transition-colors">
                📄 上传素材
              </button>
              <button onClick={() => router.push(`/interview/create?spaceId=${spaceId}`)}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-primary-hover transition-colors">
                💬 新建访谈
              </button>
            </div>
          </div>

          {/* 数据卡片 */}
          <div className="mt-6 grid grid-cols-4 sm:grid-cols-5 gap-3">
            <div className="rounded-xl bg-surface border border-border p-3 text-center">
              <p className="text-2xl font-bold text-foreground">{stats.reportCount}</p>
              <p className="text-xs text-muted-foreground mt-0.5">份报告</p>
            </div>
            <div className="rounded-xl bg-surface border border-border p-3 text-center">
              <p className="text-2xl font-bold text-foreground">{stats.grainCount}</p>
              <p className="text-xs text-muted-foreground mt-0.5">条锦囊</p>
            </div>
            <div className="rounded-xl bg-surface border border-border p-3 text-center">
              <p className="text-2xl font-bold text-foreground">{stats.interviewCount}</p>
              <p className="text-xs text-muted-foreground mt-0.5">次访谈</p>
            </div>
            <div className="rounded-xl bg-surface border border-border p-3 text-center">
              <p className="text-2xl font-bold text-foreground">{stats.materialCount}</p>
              <p className="text-xs text-muted-foreground mt-0.5">份素材</p>
            </div>
            <div className="rounded-xl bg-surface border border-border p-3 text-center flex flex-col items-center justify-center">
              {space.skillId ? (
                space.skillStatus === 'published' ? (
                  <>
                    <span className="text-lg">🤖</span>
                    <p className="text-xs text-success font-medium mt-0.5">已就绪</p>
                  </>
                ) : (
                  <>
                    <span className="text-lg">⏳</span>
                    <p className="text-xs text-warning-text font-medium mt-0.5">
                      {space.skillStatus === 'reviewing' ? '审核中' : '生成中'}
                    </p>
                  </>
                )
              ) : (
                <>
                  <span className="text-lg">🤖</span>
                  <p className="text-xs text-muted-foreground font-medium mt-0.5">未创建</p>
                </>
              )}
            </div>
          </div>
        </div>

        {/* ====== 知识分布 ====== */}
        {distribution.length > 0 && (
          <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
            <h3 className="font-semibold text-foreground mb-4">知识分布</h3>
            {/* 标签云 — 字号按数量分档，一眼看出核心场景 */}
            <div className="flex flex-wrap gap-2">
              {distribution.map((d) => {
                const ratio = d.count / maxGrain;
                const sizeClass = ratio >= 0.8 ? 'text-sm px-3 py-1.5 font-semibold'
                  : ratio >= 0.5 ? 'text-sm px-2.5 py-1 font-medium'
                  : 'text-xs px-2 py-0.5';
                const colorClass = ratio >= 0.8 ? 'bg-primary text-white'
                  : ratio >= 0.5 ? 'bg-primary-light text-primary border border-primary/20'
                  : 'bg-surface text-muted-foreground border border-border';
                return (
                  <span key={d.tag} className={`inline-flex items-center gap-1.5 rounded-full transition-colors ${sizeClass} ${colorClass}`}>
                    {d.tag}
                    <span className={ratio >= 0.8 ? 'text-white/70' : 'text-muted-foreground-2'}>{d.count}</span>
                  </span>
                );
              })}
            </div>
            <p className="mt-4 text-xs text-muted-foreground-2">
              {distribution.length} 个场景标签 · 共 {stats.grainCount} 条锦囊
            </p>
          </div>
        )}

        {/* ====== 经验报告 ====== */}
        <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
          <h3 className="font-semibold text-foreground mb-4">
            萃取报告（{space.reports?.length || 0} 份）
          </h3>
          {space.reports && space.reports.length > 0 ? (
            <div className="space-y-2">
              {space.reports.map((r) => (
                <button
                  key={r.id}
                  onClick={() => router.push(`/report/${r.id}`)}
                  className="w-full flex items-center justify-between rounded-xl bg-surface border border-border px-4 py-3 text-left hover:border-primary/20 hover:shadow-sm transition-all"
                >
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-foreground truncate">📄 {r.title}</span>
                      {r.sceneTags && r.sceneTags.length > 0 && (
                        <span className="text-xs text-muted-foreground-2">
                          {r.sceneTags.slice(0, 2).join(' · ')}
                        </span>
                      )}
                    </div>
                    {r.subtitle && (
                      <p className="text-xs text-muted-foreground-2 truncate mt-0.5">{r.subtitle}</p>
                    )}
                  </div>
                  <div className="flex items-center gap-4 text-xs text-muted-foreground flex-shrink-0 ml-4">
                    <span>⭐ {r.rating}</span>
                    <span>{r.createdAt ? new Date(r.createdAt).toLocaleDateString('zh-CN') : ''}</span>
                  </div>
                </button>
              ))}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground-2 text-center py-8">
              暂无报告，上传素材或新建访谈后自动生成
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
