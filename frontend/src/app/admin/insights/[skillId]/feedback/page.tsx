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
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!skillId) return;
    setLoading(true);
    const ratingParam = filter !== 'all' ? `&rating=${filter}` : '';
    fetch(`${API_BASE}/admin/insights/${skillId}/feedback-logs?page=${page}&size=20${ratingParam}`, {
      headers: authHeaders(),
    }).then(res => res.json())
      .then(json => setItems(json.data?.content || json.data || []))
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
        <div className="rounded-xl bg-surface-2 p-8 text-center">
          <p className="text-muted-foreground">暂无反馈记录</p>
        </div>
      ) : (
        <div className="space-y-3">
          {items.map(f => (
            <div key={f.id} className="rounded-xl bg-surface-2 p-4 shadow-sm">
              {/* 头部 */}
              <div className="flex items-center gap-2 mb-2">
                <span className={`text-sm font-medium ${f.rating === 'down' ? 'text-red-500' : 'text-green-600'}`}>
                  {f.rating === 'down' ? '👎 没用' : '👍 有用'}
                </span>
                <span className="text-xs text-muted-foreground">
                  {f.createdAt ? f.createdAt.substring(0, 16) : ''}{f.sceneTag ? ` · 场景: ${f.sceneTag}` : ''}
                </span>
              </div>
              {/* 用户问题 */}
              {f.query && <p className="text-sm mt-1"><span className="text-muted-foreground">Q: </span>{f.query}</p>}
              {/* AI 回答（截取） */}
              {f.aiResponse && <p className="text-sm mt-1 text-muted-foreground line-clamp-3">A: {f.aiResponse}</p>}
              {/* 关联信息 */}
              <div className="flex items-center gap-3 mt-2 text-xs text-muted-foreground">
                {f.grainId && <span>🔗 颗粒: {f.grainTitle || f.grainId.substring(0, 8)}</span>}
                {f.ragScore != null && <span>RAG 匹配: {Math.round(f.ragScore * 100)}%</span>}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 分页 */}
      <div className="flex justify-center gap-2 mt-6">
        <button disabled={page === 0} onClick={() => setPage(p => p - 1)}
          className="text-sm rounded-lg px-4 py-2 border disabled:opacity-30">上一页</button>
        <button onClick={() => setPage(p => p + 1)}
          className="text-sm rounded-lg px-4 py-2 border">下一页</button>
      </div>
    </div>
  );
}
