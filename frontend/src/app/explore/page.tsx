'use client';

import React, { useState, useEffect } from 'react';
import { getReports, type ReportListItem } from '@/lib/api/report';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

export default function ExplorePage() {
  const [keyword, setKeyword] = useState('');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [activeTag, setActiveTag] = useState('');
  const [sort, setSort] = useState('rating');
  const [reports, setReports] = useState<ReportListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [allTags, setAllTags] = useState<string[]>([]);
  const PAGE_SIZE = 12;

  useEffect(() => {
    setLoading(true);
    getReports(undefined, searchKeyword || undefined, page, PAGE_SIZE, activeTag || undefined, sort)
      .then((data) => {
        setReports(data.content || []);
        setTotal(data.total);
        setTotalPages(data.totalPages);
        // 初始加载时提取全部标签（仅一次）
        if (!searchKeyword && !activeTag && page === 1) {
          getReports(undefined, undefined, 1, 100)
            .then(d => {
              const tags = [...new Set((d.content || []).flatMap(r => r.sceneTags || []))].sort();
              setAllTags(tags);
            })
            .catch(() => {});
        }
      })
      .catch(() => setError('加载报告失败，请刷新重试'))
      .finally(() => setLoading(false));
  }, [searchKeyword, activeTag, sort, page]);

  const handleSearch = () => {
    setPage(1);
    setSearchKeyword(keyword);
  };
  const handleKeyDown = (e: React.KeyboardEvent) => { if (e.key === 'Enter') handleSearch(); };
  const handleTagClick = (tag: string) => {
    setPage(1);
    setActiveTag(tag === activeTag ? '' : tag);
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="mb-6 text-[28px] font-bold text-foreground">经验广场</h1>

        {error && (
          <div className="mb-4 rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-600">{error}</div>
        )}

        {/* 搜索栏 */}
        <div className="relative mb-4">
          <svg className="absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-muted-foreground-2" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
          </svg>
          <input type="text" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={handleKeyDown}
            placeholder="搜索报告标题…" className="w-full rounded-lg border border-border-strong py-3 pl-12 pr-4 text-sm outline-none focus:border-foreground" />
        </div>

        {/* 标签筛选 + 排序 */}
        <div className="mb-6 flex items-center justify-between flex-wrap gap-3">
          <div className="flex flex-wrap gap-2">
            {['全部', ...allTags].map(tag => (
              <button key={tag} onClick={() => handleTagClick(tag === '全部' ? '' : tag)}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  (tag === '全部' && !activeTag) || tag === activeTag
                    ? 'bg-foreground text-white'
                    : 'bg-primary-light text-primary hover:bg-border-strong'
                }`}>{tag}</button>
            ))}
          </div>
          <div className="flex gap-1 text-xs">
            {[
              { k: 'rating', l: '⭐ 评分' },
              { k: 'viewCount', l: '👁 浏览' },
              { k: 'createdAt', l: '🕐 最新' },
            ].map(s => (
              <button key={s.k} onClick={() => { setSort(s.k); setPage(1); }}
                className={`rounded px-2 py-1 ${sort === s.k ? 'bg-foreground text-white' : 'text-muted-foreground'}`}>{s.l}</button>
            ))}
          </div>
        </div>

        {/* 报告网格 */}
        {reports.length > 0 ? (
          <>
            <div className="grid gap-4 md:grid-cols-3">
              {reports.map(r => (
                <button key={r.id}
                  onClick={() => r.hasHtml && window.open(`/report/${r.id}`, '_blank')}
                  disabled={!r.hasHtml}
                  className={`group rounded-xl bg-surface-2 p-5 text-left shadow-md transition-all ${
                    r.hasHtml ? 'hover:-translate-y-1 hover:shadow-lg' : 'opacity-50 cursor-not-allowed'
                  }`}>
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
                    <span className="flex items-center gap-2">
                      {r.shareCode && <span className="text-green-600 text-[10px]">🔗</span>}
                      <span className="text-primary">⭐ {r.rating || 0}</span>
                    </span>
                  </div>
                </button>
              ))}
            </div>

            {/* 分页 */}
            {totalPages > 1 && (
              <div className="mt-8 flex justify-center gap-2">
                <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page <= 1}
                  className="rounded-lg border border-border px-4 py-2 text-sm disabled:opacity-30">上一页</button>
                <span className="flex items-center px-3 text-sm text-muted-foreground">
                  {page} / {totalPages}
                </span>
                <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={page >= totalPages}
                  className="rounded-lg border border-border px-4 py-2 text-sm disabled:opacity-30">下一页</button>
              </div>
            )}
          </>
        ) : (
          <div className="py-12 text-center">
            <p className="text-sm text-muted-foreground-2">没有找到匹配的经验</p>
          </div>
        )}
      </div>
    </div>
  );
}
