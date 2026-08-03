'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getSpace, type SpaceDetail } from '@/lib/api/spaces';

export default function SpaceDetailPage() {
  const params = useParams();
  const router = useRouter();
  const spaceId = (params.id as string) || '';

  const [space, setSpace] = useState<SpaceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reportPage, setReportPage] = useState(1);
  const [reportSort, setReportSort] = useState('createdAt');

  useEffect(() => {
    if (!spaceId) return;
    setLoading(true);
    getSpace(spaceId, reportPage, 20, reportSort)
      .then(data => { setSpace(data); setError(''); })
      .catch(() => setError('加载空间失败'))
      .finally(() => setLoading(false));
  }, [spaceId, reportPage, reportSort]);

  if (loading) return <LoadingSpinner />;
  if (error && !space) {
    return (
      <div className="min-h-screen bg-surface px-6 py-16 text-center">
        <p className="text-lg text-red-500">{error}</p>
        <button onClick={() => window.location.reload()} className="mt-4 rounded-lg bg-primary px-6 py-2 text-sm font-semibold text-white">重试</button>
      </div>
    );
  }
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
  const totalReportPages = space.reportTotalPages || 1;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px] space-y-8">
        <button onClick={() => router.push('/spaces')} className="text-sm text-muted-foreground hover:text-foreground mb-2 inline-block">
          ← 空间总览
        </button>

        <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
          <div className="flex items-start justify-between flex-wrap gap-4">
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
            <div className="flex gap-2">
              <button onClick={() => router.push(`/admin/skills/upload?spaceId=${spaceId}`)}
                className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-foreground hover:bg-primary-light transition-colors">
                📄 上传素材
              </button>
            </div>
          </div>

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
                  <><span className="text-lg">🤖</span><p className="text-xs text-success font-medium mt-0.5">已发布</p></>
                ) : (
                  <><span className="text-lg">⏳</span><p className="text-xs text-warning-text font-medium mt-0.5">
                    {space.skillStatus === 'generating' ? '萃取中' : space.skillStatus === 'reviewing' ? '待审核' : space.skillStatus === 'discarded' ? '已驳回' : '生成中'}
                  </p></>
                )
              ) : (
                <><span className="text-lg">🤖</span><p className="text-xs text-muted-foreground font-medium mt-0.5">未创建</p></>
              )}
            </div>
          </div>
        </div>

        {distribution.length > 0 && (
          <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
            <h3 className="font-semibold text-foreground mb-4">知识分布</h3>
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

        {/* 萃取报告 — 分页 + 排序 */}
        <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-foreground">
              萃取报告（{space.reportTotal || space.reports?.length || 0} 份）
            </h3>
            <div className="flex gap-1 text-xs">
              {[
                { k: 'createdAt', l: '最新' },
                { k: 'rating', l: '评分' },
                { k: 'viewCount', l: '浏览' },
              ].map(s => (
                <button key={s.k} onClick={() => { setReportSort(s.k); setReportPage(1); }}
                  className={`rounded px-2 py-1 ${reportSort === s.k ? 'bg-foreground text-white' : 'text-muted-foreground'}`}>{s.l}</button>
              ))}
            </div>
          </div>
          {space.reports && space.reports.length > 0 ? (
            <>
              <div className="space-y-2">
                {space.reports.map((r) => (
                  <button
                    key={r.id}
                    onClick={() => window.open(`/api/v1/reports/${r.id}/html`, '_blank')}
                    className="w-full flex items-center justify-between rounded-xl bg-surface border border-border px-4 py-3 text-left hover:border-primary/20 hover:shadow-sm transition-all"
                  >
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-foreground truncate">📄 {r.title}</span>
                        {r.sceneTags && r.sceneTags.length > 0 && (
                          <span className="text-xs text-muted-foreground-2">{r.sceneTags.slice(0, 2).join(' · ')}</span>
                        )}
                      </div>
                      {r.subtitle && <p className="text-xs text-muted-foreground-2 truncate mt-0.5">{r.subtitle}</p>}
                    </div>
                    <div className="flex items-center gap-4 text-xs text-muted-foreground flex-shrink-0 ml-4">
                      <span>⭐ {r.rating}</span>
                      <span>{r.createdAt ? new Date(r.createdAt).toLocaleDateString('zh-CN') : ''}</span>
                    </div>
                  </button>
                ))}
              </div>
              {totalReportPages > 1 && (
                <div className="mt-4 flex justify-center gap-2">
                  <button onClick={() => setReportPage(p => Math.max(1, p - 1))} disabled={reportPage <= 1}
                    className="rounded border border-border px-3 py-1 text-xs disabled:opacity-30">上一页</button>
                  <span className="flex items-center px-2 text-xs text-muted-foreground">{reportPage}/{totalReportPages}</span>
                  <button onClick={() => setReportPage(p => Math.min(totalReportPages, p + 1))} disabled={reportPage >= totalReportPages}
                    className="rounded border border-border px-3 py-1 text-xs disabled:opacity-30">下一页</button>
                </div>
              )}
            </>
          ) : (
            <p className="text-sm text-muted-foreground-2 text-center py-8">暂无报告</p>
          )}
        </div>
      </div>
    </div>
  );
}
