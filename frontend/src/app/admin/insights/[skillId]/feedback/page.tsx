'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_BASE, authHeaders } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface FeedbackItem {
  id: string;
  rating: string;
  query?: string;
  aiResponse?: string;
  ragScore?: number;
  grainId?: string;
  grainTitle?: string;
  sceneTag?: string;
  createdAt: string;
}

/**
 * 反馈审查列表 —— 管理员逐条查看用户打分，定位问题回答。
 *
 * 默认按时间倒序，支持按 👍/👎 筛选。
 */
export default function FeedbackReviewPage() {
  const params = useParams();
  const router = useRouter();
  const skillId = params.skillId as string;
  const [items, setItems] = useState<FeedbackItem[]>([]);
  const [filter, setFilter] = useState<'all' | 'up' | 'down'>('all');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!skillId) return;
    setLoading(true);
    const ratingParam = filter !== 'all' ? `&rating=${filter}` : '';
    fetch(`${API_BASE}/admin/insights/${skillId}/feedback-logs?page=${page}&size=20${ratingParam}`, {
      headers: authHeaders(),
    }).then(res => res.json())
      .then(json => {
        setItems(json.data?.content || json.data || []);
        setTotalPages(json.data?.totalPages || 0);
        setTotalElements(json.data?.totalElements || 0);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [skillId, filter, page]);

  if (loading) return <LoadingSpinner />;

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <button onClick={() => router.push('/admin/insights')} className="text-sm text-primary hover:underline">
          ← 返回调优面板
        </button>
        <div className="flex gap-2">
          {(['all', 'up', 'down'] as const).map(f => (
            <button key={f} onClick={() => { setFilter(f); setPage(0); }}
              className={`text-sm rounded-lg px-3 py-1.5 ${filter === f ? 'bg-primary text-white' : 'bg-surface-2 text-muted-foreground hover:bg-surface'}`}>
              {f === 'all' ? '全部' : f === 'up' ? '👍 有用' : '👎 没用'}
            </button>
          ))}
        </div>
      </div>

      <h2 className="mb-6 text-xl font-bold text-foreground">📋 反馈审查</h2>

      {items.length === 0 ? (
        <div className="rounded-[12px] bg-surface-2 p-8 text-center">
          <p className="text-muted-foreground mb-4">暂无反馈记录</p>
          <button onClick={() => router.push(`/admin/insights/${skillId}`)}
            className="text-sm text-primary hover:underline font-medium">
            ← 返回分身详情
          </button>
        </div>
      ) : (
        <div className="space-y-3">
          {items.map(f => (
            <div key={f.id} className="rounded-[12px] bg-surface-2 p-3 shadow-sm hover:shadow-sm transition-shadow">
              {/* 头部 */}
              <div className="flex items-center gap-2 mb-1.5">
                <span className={`text-[13px] font-medium flex-shrink-0 ${f.rating === 'down' ? 'text-red-500' : 'text-green-600'}`}>
                  {f.rating === 'down' ? '👎 没用' : '👍 有用'}
                </span>
                <span className="text-[11px] text-muted-foreground truncate">
                  {f.createdAt ? f.createdAt.substring(0, 16) : ''}{f.sceneTag ? ` · ${f.sceneTag}` : ''}
                </span>
                {f.ragScore != null && (
                  <span className="text-[11px] text-muted-foreground flex-shrink-0 ml-auto">
                    RAG: {Math.round(f.ragScore * 100)}%
                  </span>
                )}
              </div>
              {/* 用户问题 + AI回答 紧凑两行 */}
              <div className="grid grid-cols-1 gap-1 text-[13px]">
                {f.query && <p className="truncate"><span className="text-muted-foreground">Q: </span><span className="text-foreground">{f.query}</span></p>}
                {f.aiResponse && <p className="truncate text-muted-foreground"><span className="text-foreground/50">A: </span>{f.aiResponse}</p>}
              </div>
              {/* 颗粒链接 */}
              {f.grainId && (
                <button onClick={() => router.push(`/admin/grains/${f.grainId}`)}
                  className="text-[12px] text-primary hover:underline cursor-pointer mt-1.5 font-medium">
                  🔗 {f.grainTitle || f.sceneTag || '查看颗粒详情'}
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 分页 */}
      <div className="flex items-center justify-center gap-3 mt-6">
        <button disabled={page === 0} onClick={() => setPage(p => p - 1)}
          className="text-sm rounded-lg px-4 py-2 border disabled:opacity-30">上一页</button>
        <span className="text-xs text-muted-foreground">
          第 {page + 1}/{totalPages || 1} 页 · 共 {totalElements} 条
        </span>
        <button disabled={page + 1 >= totalPages} onClick={() => setPage(p => p + 1)}
          className="text-sm rounded-lg px-4 py-2 border disabled:opacity-30">下一页</button>
      </div>
    </div>
  );
}
