'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface RetrievalItem {
  id: string; conversationId: string; originalQuery?: string;
  rewrittenQuery?: string; similarity: number; tier?: string;
  position: number; createdAt?: string;
}

interface FeedbackItem {
  id: string; conversationId?: string; rating: string;
  query?: string; aiResponse?: string; ragScore?: number; createdAt?: string;
}

interface GrainDiagnostics {
  id: string; spaceId: string; sceneTag?: string; sceneDescription?: string;
  expertThought?: string; standardScript?: string; commonMistakes?: string;
  applicableCondition?: string; weight: number; status: string;
  helpfulCount: number; unhelpfulCount: number; qualityScore?: number;
  createdAt?: string;
  retrievals: RetrievalItem[];
  feedbacks: FeedbackItem[];
}

/**
 * 颗粒诊断页 —— 诊断优先双栏布局。
 *
 * 左栏：颗粒内容。右栏：诊断指标。
 * 底部全宽：检索历史 + 用户反馈。
 */
export default function GrainDetailPage() {
  const params = useParams(); const router = useRouter();
  const grainId = params.grainId as string;
  const [data, setData] = useState<GrainDiagnostics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiClient<GrainDiagnostics>(`/admin/grains/${grainId}/diagnostics`)
      .then(setData).catch(e => setError(e.message || '加载失败'))
      .finally(() => setLoading(false));
  }, [grainId]);

  if (loading) return <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>;
  if (error) return <div className="p-8 text-center text-muted-foreground">加载失败: {error}</div>;
  if (!data) return <div className="p-8 text-center text-muted-foreground">颗粒不存在</div>;

  const downFeedbacks = data.feedbacks.filter(f => f.rating === 'down');
  const satisfactionRate = data.helpfulCount + data.unhelpfulCount > 0
    ? Math.round(data.helpfulCount / (data.helpfulCount + data.unhelpfulCount) * 100) : 0;
  const totalRetrievals = data.retrievals.length;
  const avgSimilarity = data.retrievals.length > 0
    ? Math.round(data.retrievals.reduce((s, r) => s + (r.similarity || 0), 0) / data.retrievals.length * 100) : 0;

  return (
    <div className="px-6 py-8 max-w-[1200px] mx-auto space-y-6">
      {/* 顶栏 */}
      <div className="flex items-center justify-between">
        <button onClick={() => router.push('/admin/insights')}
          className="text-sm text-[#64748B] hover:text-[#1E293B] transition-colors font-medium">
          ← 返回
        </button>
        <div className="flex items-center gap-2">
          <button onClick={() => router.push(`/admin/grains/${grainId}/edit`)}
            className="text-sm bg-primary text-white rounded-lg px-4 py-2 hover:bg-primary-hover transition-colors">
            编辑颗粒
          </button>
          <button onClick={async () => {
            if (!confirm('确定废弃此颗粒？')) return;
            try { await apiClient(`/admin/grains/${grainId}/deprecate`, { method: 'POST' }); router.back(); }
            catch { alert('操作失败'); }
          }} className="text-sm text-red-500 rounded-lg px-4 py-2 hover:bg-red-50 transition-colors">
            标记废弃
          </button>
        </div>
      </div>

      <h1 className="text-xl font-bold text-[#1E293B]">
        🔍 颗粒诊断：{data.sceneDescription || data.sceneTag || '未命名颗粒'}
      </h1>

      {/* 左-右双栏 */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* 左栏：颗粒内容（占 2/3） */}
        <div className="lg:col-span-2 rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_3px_rgba(15,23,42,0.04)] p-6">
          <h3 className="text-[14px] font-semibold text-[#1E293B] mb-4 flex items-center gap-2">
            <span className="w-1 h-4 rounded-full bg-[#3B82F6]" />
            📋 颗粒内容
          </h3>
          <div className="space-y-1 text-xs text-[#94A3B8] mb-4">
            场景: {data.sceneTag || '未分类'} · 质量: {data.qualityScore || '-'}/5 · 权重: {data.weight} · {data.status}
          </div>
          <Field label="🧠 专家思考" value={data.expertThought} />
          <Field label="💬 标准话术" value={data.standardScript} />
          <Field label="⚠️ 常见错误" value={data.commonMistakes} />
          <Field label="📌 适用条件" value={data.applicableCondition} />
        </div>

        {/* 右栏：诊断指标（占 1/3） */}
        <div className="space-y-4">
          <div className="rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_3px_rgba(15,23,42,0.04)] p-5">
            <h3 className="text-[14px] font-semibold text-[#1E293B] mb-4 flex items-center gap-2">
              <span className="w-1 h-4 rounded-full bg-[#22C55E]" />
              📊 使用数据
            </h3>
            <div className="grid grid-cols-2 gap-4">
              <MiniStat label="检索次数" value={totalRetrievals.toLocaleString()} />
              <MiniStat label="平均相似度" value={`${avgSimilarity}%`} />
              <MiniStat label="好评" value={data.helpfulCount.toLocaleString()} color="text-[#16A34A]" />
              <MiniStat label="差评" value={data.unhelpfulCount.toLocaleString()} color={data.unhelpfulCount > 0 ? 'text-[#DC2626]' : ''} />
            </div>
            <div className="mt-4 pt-4 border-t border-[#E8ECF1]">
              <MiniStat label="满意率" value={`${satisfactionRate}%`}
                color={satisfactionRate >= 70 ? 'text-[#16A34A]' : satisfactionRate >= 40 ? 'text-[#D97706]' : 'text-[#DC2626]'} />
            </div>
          </div>
        </div>
      </div>

      {/* 检索历史 */}
      <div className="rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_3px_rgba(15,23,42,0.04)] p-6">
        <h3 className="text-[14px] font-semibold text-[#1E293B] mb-4 flex items-center gap-2">
          <span className="w-1 h-4 rounded-full bg-[#F59E0B]" />
          💬 检索历史（最近 {totalRetrievals} 次）
        </h3>
        {totalRetrievals === 0 ? (
          <p className="text-sm text-muted-foreground text-center py-6">暂无检索记录</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-[#E8ECF1] text-[11px] text-[#94A3B8] uppercase tracking-[0.04em]">
                  <th className="text-left py-2 font-medium">时间</th>
                  <th className="text-left py-2 font-medium">用户提问</th>
                  <th className="text-right py-2 font-medium w-16">相似度</th>
                  <th className="text-center py-2 font-medium w-14">匹配</th>
                </tr>
              </thead>
              <tbody>
                {data.retrievals.map(r => (
                  <tr key={r.id} className="border-b border-[#E8ECF1]/50 hover:bg-[#F8FAFC] transition-colors">
                    <td className="py-2.5 text-[11px] text-[#94A3B8] whitespace-nowrap pr-3">
                      {r.createdAt?.substring(0, 16) || '-'}
                    </td>
                    <td className="py-2.5 text-[13px] text-[#334155] max-w-[400px] truncate pr-3"
                      title={r.originalQuery || r.rewrittenQuery || ''}>
                      {r.originalQuery || r.rewrittenQuery || '-'}
                    </td>
                    <td className="py-2.5 text-right tabular-nums pr-3">
                      <span className={`text-[13px] font-semibold ${
                        r.similarity >= 0.50 ? 'text-[#16A34A]' : r.similarity >= 0.30 ? 'text-[#D97706]' : 'text-[#DC2626]'
                      }`}>
                        {Math.round(r.similarity * 100)}%
                      </span>
                    </td>
                    <td className="py-2.5 text-center">
                      {r.tier === 'high' && <span className="text-[11px] bg-[#F0FDF4] text-[#16A34A] rounded-full px-2 py-0.5 font-medium">高匹配</span>}
                      {r.tier === 'ref' && <span className="text-[11px] bg-[#FFFBEB] text-[#D97706] rounded-full px-2 py-0.5 font-medium">参考</span>}
                      {!r.tier && <span className="text-[11px] bg-[#FEF2F2] text-[#DC2626] rounded-full px-2 py-0.5 font-medium">低匹配</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* 差评反馈 */}
      {downFeedbacks.length > 0 && (
        <div className="rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_3px_rgba(15,23,42,0.04)] p-6">
          <h3 className="text-[14px] font-semibold text-[#1E293B] mb-4 flex items-center gap-2">
            <span className="w-1 h-4 rounded-full bg-[#DC2626]" />
            👎 差评反馈（{downFeedbacks.length} 条）
          </h3>
          <div className="space-y-3">
            {downFeedbacks.map(f => (
              <div key={f.id} className="rounded-lg bg-[#FEF2F2] border border-[#FECACA] p-4">
                <div className="flex items-center gap-2 mb-2">
                  <span className="text-[11px] font-medium text-[#DC2626]">👎 差评</span>
                  {f.ragScore != null && (
                    <span className="text-[11px] text-[#94A3B8]">RAG: {Math.round(f.ragScore * 100)}%</span>
                  )}
                  <span className="text-[11px] text-[#94A3B8] ml-auto">
                    {f.createdAt?.substring(0, 16) || '-'}
                  </span>
                </div>
                {f.query && <p className="text-[13px] mb-1.5"><span className="text-[#94A3B8]">Q: </span><span className="text-[#334155]">{f.query}</span></p>}
                {f.aiResponse && <p className="text-[13px] text-[#64748B] line-clamp-2"><span className="text-[#94A3B8]">A: </span>{f.aiResponse}</p>}
                {f.conversationId && (
                  <button onClick={() => router.push(`/admin/conversations/${f.conversationId}`)}
                    className="text-[12px] text-primary hover:underline mt-2 font-medium">
                    → 查看完整对话
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value?: string }) {
  return (
    <div className="mb-3">
      <p className="text-xs text-[#94A3B8] mb-1">{label}</p>
      <p className="text-[13px] text-[#334155] bg-[#F8FAFC] p-3 rounded-lg whitespace-pre-wrap leading-relaxed">
        {value || '（未填写）'}
      </p>
    </div>
  );
}

function MiniStat({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div>
      <p className="text-[11px] text-[#94A3B8] leading-tight">{label}</p>
      <p className={`text-[20px] font-bold tabular-nums leading-tight ${color || 'text-[#1E293B]'}`}>
        {value}
      </p>
    </div>
  );
}
