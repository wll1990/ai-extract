'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';
import type { MaterialItem, GrainItem } from '@/lib/api/materials';

export default function MaterialDetailPage() {
  const { id, materialId } = useParams<{ id: string; materialId: string }>();
  const router = useRouter();
  const [data, setData] = useState<MaterialItem | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient<MaterialItem>(`/admin/skills/${id}/materials/${materialId}/detail`)
      .then(setData).catch(() => {}).finally(() => setLoading(false));
  }, [id, materialId]);

  if (loading) return <LoadingSpinner />;
  if (!data) return <div className="p-6 text-center text-muted-foreground-2">素材不存在或加载失败</div>;

  const grains: GrainItem[] = data.grains || [];

  return (
    <div className="max-w-5xl mx-auto p-6">
      <button onClick={() => router.back()} className="text-sm text-muted-foreground hover:text-foreground mb-4">← 返回素材列表</button>
      <h1 className="text-xl font-bold mb-2">{data.fileName}</h1>
      <div className="flex gap-3 mb-6 text-sm text-muted-foreground">
        <span>状态: {data.status} v{data.version}</span>
        {data.reportVersion && <span className="text-gold">报告版本: {data.reportVersion}</span>}
        {data.verifiedCount != null && <span>验证通过: {data.verifiedCount}条</span>}
        {data.rejectedCount != null && <span className="text-red-400">拒绝: {data.rejectedCount}条</span>}
      </div>

      {/* 分析摘要 */}
      {data.analysisNotes && (
        <div className="bg-surface-2 rounded-lg border p-4 mb-4">
          <h3 className="text-sm font-semibold mb-2">📊 分析摘要</h3>
          <p className="text-sm text-muted-foreground whitespace-pre-wrap">{data.analysisNotes}</p>
        </div>
      )}

      {/* 模式发现 */}
      {data.patterns && (
        <div className="bg-surface-2 rounded-lg border p-4 mb-4">
          <h3 className="text-sm font-semibold text-gold mb-2">🧠 模式发现</h3>
          <p className="text-sm mb-1"><b>{data.patterns.methodologyName || ''}</b></p>
          <p className="text-sm mb-2 text-muted-foreground">{data.patterns.oneliner || ''}</p>
          <div className="flex flex-wrap gap-1">
            {(data.patterns.coreHabits || []).map((h: string) => (
              <span key={h} className="px-2 py-0.5 bg-success-bg text-success rounded text-xs">{h}</span>
            ))}
          </div>
        </div>
      )}

      {/* FAQ */}
      {data.faq && data.faq.length > 0 && (
        <div className="bg-surface-2 rounded-lg border p-4 mb-4">
          <h3 className="text-sm font-semibold mb-2">❓ 常见异议处理</h3>
          {data.faq.map((f: any, i: number) => (
            <div key={i} className="mb-2">
              <p className="text-sm font-medium text-red-600">{f.question}</p>
              <p className="text-sm text-muted-foreground">{f.answer}</p>
            </div>
          ))}
        </div>
      )}

      {/* 叙事 */}
      {data.narrative?.storyline?.phases && (
        <div className="bg-surface-2 rounded-lg border p-4 mb-4">
          <h3 className="text-sm font-semibold mb-2">📖 叙事重放</h3>
          {data.narrative.storyline.phases.map((p: any, i: number) => (
            <div key={i} className="flex gap-3 mb-2">
              <span className="w-6 h-6 rounded-full bg-navy text-white text-xs flex items-center justify-center shrink-0 mt-0.5">{p.order}</span>
              <div><p className="text-sm font-medium">{p.name}</p><p className="text-xs text-muted-foreground">{p.summary}</p></div>
            </div>
          ))}
        </div>
      )}

      {/* 颗粒列表 */}
      <div className="bg-surface-2 rounded-lg border p-4">
        <h3 className="text-sm font-semibold mb-3">🔬 该素材产生的颗粒（共{grains.length}条）</h3>
        {grains.length === 0 ? (
          <p className="text-sm text-muted-foreground-2 text-center py-8">暂无颗粒</p>
        ) : (
          <div className="space-y-3">
            {grains.map(g => (
              <div key={g.id} className="border rounded-lg p-3 text-sm">
                <div className="flex items-center gap-2 mb-1">
                  <span className="px-2 py-0.5 bg-purple-50 text-purple-700 rounded text-xs">{g.sceneTag || '-'}</span>
                  {g.qualityScore != null && <span className="text-xs text-muted-foreground-2">评分: {g.qualityScore?.toFixed(1)}</span>}
                  {g.difficultyLevel && <span className="text-xs text-muted-foreground-2">难度: {g.difficultyLevel}</span>}
                </div>
                <p className="text-foreground mb-1"><b>场景：</b>{g.sceneDescription || '-'}</p>
                <p className="text-foreground mb-1"><b>思考：</b>{g.expertThought || '-'}</p>
                <p className="text-muted-foreground"><b>话术：</b>{g.standardScript || '-'}</p>
                {g.commonMistakes && <p className="text-red-500 text-xs mt-1">⚠ 常见错误：{g.commonMistakes}</p>}
                {g.applicableCondition && <p className="text-muted-foreground-2 text-xs mt-1">条件：{g.applicableCondition}</p>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
