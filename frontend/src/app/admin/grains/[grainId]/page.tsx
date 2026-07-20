'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_BASE, authHeaders } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface GrainDetail {
  id: string; spaceId: string; sceneTag?: string; sceneDescription?: string;
  expertThought?: string; standardScript?: string; commonMistakes?: string;
  applicableCondition?: string; weight: number; status: string;
  helpfulCount: number; unhelpfulCount: number; qualityScore?: number;
  editedContent?: string; createdAt?: string;
}

/**
 * 颗粒详情 —— 查看完整内容，入口来自仪表盘排行榜。
 * Phase 3 增加编辑功能。
 */
export default function GrainDetailPage() {
  const params = useParams(); const router = useRouter();
  const grainId = params.grainId as string;
  const [grain, setGrain] = useState<GrainDetail | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(`${API_BASE}/admin/grains/${grainId}`, { headers: authHeaders() })
      .then(r => r.json()).then(d => setGrain(d.data)).catch(() => {})
      .finally(() => setLoading(false));
  }, [grainId]);

  if (loading) return <LoadingSpinner />;
  if (!grain) return <div className="p-8 text-center text-muted-foreground">颗粒不存在</div>;

  return (
    <div className="max-w-2xl mx-auto">
      <button onClick={() => router.back()} className="text-sm text-primary hover:underline mb-4 block">← 返回</button>
      <div className="rounded-xl bg-surface-2 p-6 shadow-sm">
        <h2 className="text-lg font-bold mb-2">🔍 {grain.sceneDescription || grain.sceneTag || '未命名颗粒'}</h2>
        <p className="text-xs text-muted-foreground mb-4">
          场景: {grain.sceneTag || '未分类'} · 质量评分: {grain.qualityScore || '-'}/5 · 权重: {grain.weight}
          · 👍{grain.helpfulCount} · 👎{grain.unhelpfulCount} · {grain.status}
        </p>

        <Field label="🧠 专家思考" value={grain.expertThought} />
        <Field label="💬 标准话术" value={grain.standardScript} />
        <Field label="⚠️ 常见错误" value={grain.commonMistakes} />
        <Field label="📌 适用条件" value={grain.applicableCondition} />

        <div className="flex gap-2 mt-4 pt-4 border-t border-border">
          <button onClick={() => router.push(`/admin/grains/${grainId}/edit`)}
            className="text-sm bg-primary text-white rounded-lg px-4 py-2">编辑颗粒</button>
          <button onClick={async () => {
            if (!confirm('确定废弃此颗粒？')) return;
            await fetch(`${API_BASE}/admin/grains/${grainId}/deprecate`, { method: 'POST', headers: authHeaders() });
            router.back();
          }} className="text-sm text-red-500 rounded-lg px-4 py-2 hover:bg-red-50">标记废弃</button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value?: string }) {
  return (
    <div className="mb-3">
      <p className="text-xs text-muted-foreground mb-1">{label}</p>
      <p className="text-sm bg-surface p-3 rounded-lg whitespace-pre-wrap">{value || '（未填写）'}</p>
    </div>
  );
}
