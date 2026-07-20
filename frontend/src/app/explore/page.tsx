'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getReports, type ReportListItem } from '@/lib/api/report';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

export default function ExplorePage() {
  const router = useRouter();
  const [keyword, setKeyword] = useState('');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [activeTag, setActiveTag] = useState('全部');
  const [sort, setSort] = useState('rating');
  const [reports, setReports] = useState<ReportListItem[]>([]);
  const [loading, setLoading] = useState(true);

  // 后端关键词搜索
  useEffect(() => {
    setLoading(true);
    getReports(undefined, searchKeyword || undefined, 1, 50)
      .then((data) => setReports(data.content || []))
      .catch(e => console.error('加载报告列表失败:', e))
      .finally(() => setLoading(false));
  }, [searchKeyword]);

  // 从报告数据中提取场景标签
  const tagsFromData = [...new Set(reports.flatMap((r) => r.sceneTags || []))];
  const sceneTagOptions = ['全部', ...tagsFromData.sort()];

  // 场景标签分布统计
  const tagDistribution = tagsFromData.map(tag => ({
    tag,
    count: reports.filter(r => (r.sceneTags || []).includes(tag)).length
  })).sort((a, b) => b.count - a.count);
  const maxTagCount = tagDistribution.length > 0 ? Math.max(...tagDistribution.map(d => d.count)) : 1;

  // 客户端标签筛选 + 排序
  const filtered = reports
    .filter(r => activeTag === '全部' || (r.sceneTags && r.sceneTags.includes(activeTag)))
    .sort((a, b) => sort === 'rating'
      ? (b.rating || 0) - (a.rating || 0)
      : (b.viewCount || 0) - (a.viewCount || 0));

  const handleSearch = () => setSearchKeyword(keyword);
  const handleKeyDown = (e: React.KeyboardEvent) => { if (e.key === 'Enter') handleSearch(); };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="mb-6 text-[28px] font-bold text-foreground">经验广场</h1>

        {/* 知识分布 */}
        {tagDistribution.length > 0 && (
          <div className="mb-6 rounded-2xl bg-surface-2 border border-border p-5 shadow-sm">
            <h3 className="text-sm font-semibold text-foreground mb-3">知识分布</h3>
            <div className="space-y-2">
              {tagDistribution.slice(0, 8).map(d => (
                <div key={d.tag} className="flex items-center gap-3">
                  <span className="w-20 text-xs text-muted-foreground flex-shrink-0 truncate">{d.tag}</span>
                  <div className="flex-1 h-4 bg-surface rounded-full overflow-hidden">
                    <div className="h-full rounded-full bg-gradient-to-r from-primary to-primary/60 transition-all"
                      style={{ width: `${Math.max((d.count / maxTagCount) * 100, 6)}%` }} />
                  </div>
                  <span className="text-xs font-medium text-foreground w-6 text-right">{d.count}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 搜索栏 */}
        <div className="relative mb-4">
          <svg className="absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-muted-foreground-2" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
          </svg>
          <input type="text" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={handleKeyDown}
            placeholder="搜索报告标题…" className="w-full rounded-lg border border-border-strong py-3 pl-12 pr-4 text-sm outline-none focus:border-foreground" />
        </div>

        {/* 场景标签筛选 + 排序 */}
        <div className="mb-6 flex items-center justify-between">
          <div className="flex flex-wrap gap-2">
            {sceneTagOptions.map(tag => (
              <button key={tag} onClick={() => setActiveTag(tag)}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  activeTag === tag ? 'bg-foreground text-white' : 'bg-primary-light text-primary hover:bg-border-strong'
                }`}>{tag}</button>
            ))}
          </div>
          <div className="flex gap-1 text-xs">
            <button onClick={() => setSort('rating')} className={`rounded px-2 py-1 ${sort==='rating'?'bg-foreground text-white':'text-muted-foreground'}`}>⭐ 评分</button>
            <button onClick={() => setSort('views')} className={`rounded px-2 py-1 ${sort==='views'?'bg-foreground text-white':'text-muted-foreground'}`}>👁 浏览</button>
          </div>
        </div>

        {/* 报告网格 */}
        {filtered.length > 0 ? (
          <div className="grid gap-4 md:grid-cols-3">
            {filtered.map(r => (
              <button key={r.id} onClick={() => router.push(`/report/${r.id}`)}
                className="group rounded-xl bg-surface-2 p-5 text-left shadow-md transition-all hover:-translate-y-1 hover:shadow-lg">
                {r.createdAt && new Date(r.createdAt).getTime() > Date.now() - 86400000 * 7 && (
                  <span className="absolute right-3 top-3 rounded bg-warning-bg/50 px-2 py-0.5 text-xs text-primary">新</span>
                )}
                <h3 className="text-lg font-bold text-foreground line-clamp-1">{r.title}</h3>
                <p className="mt-1 text-sm text-muted-foreground line-clamp-2">{r.subtitle || ''}</p>
                {r.sceneTags && r.sceneTags.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1">
                    {r.sceneTags.slice(0, 3).map(tag => (
                      <span key={tag} className="rounded-full bg-primary-light px-2 py-0.5 text-[11px] text-primary">{tag}</span>
                    ))}
                  </div>
                )}
                <div className="mt-3 flex items-center justify-between text-xs">
                  <span className="text-muted-foreground-2">{r.authorName || ''}</span>
                  <span className="text-primary">⭐ {r.rating || 0}</span>
                </div>
              </button>
            ))}
          </div>
        ) : (
          <div className="py-12 text-center">
            <p className="text-sm text-muted-foreground-2">没有找到匹配的经验</p>
          </div>
        )}
      </div>
    </div>
  );
}
