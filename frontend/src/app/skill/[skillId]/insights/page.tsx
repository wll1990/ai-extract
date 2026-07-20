'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { getSkillOverview, getSceneTop, getRagDistribution, getTopGrains, getKnowledgeGaps, type SkillOverview, type SceneTopItem, type RagDistribution, type GrainRankItem, type KnowledgeGapItem } from '@/lib/api/admin-insights';
import { StatCards } from '@/components/admin/StatCards';
import { SceneBarChart } from '@/components/admin/SceneBarChart';
import { RagPieChart } from '@/components/admin/RagPieChart';
import { GrainRankTable } from '@/components/admin/GrainRankTable';
import { KnowledgeGapPanel } from '@/components/admin/KnowledgeGapPanel';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

/**
 * 分身主调优面板 —— 销冠本人查看自己分身的数据、反馈、缺口。
 *
 * 与 Admin 仪表盘的区别：
 * ① 只看自己的分身（通过 URL skillId 过滤）
 * ② 不提供系统配置/提示词管理
 * ③ 可编辑颗粒（跳转 /admin/grains 复用编辑能力）
 */
export default function SkillOwnerInsightsPage() {
  const params = useParams(); const router = useRouter();
  const skillId = params.skillId as string;
  const [overview, setOverview] = useState<SkillOverview | null>(null);
  const [sceneTop, setSceneTop] = useState<SceneTopItem[]>([]);
  const [ragDist, setRagDist] = useState<RagDistribution | null>(null);
  const [bestGrains, setBestGrains] = useState<GrainRankItem[]>([]);
  const [worstGrains, setWorstGrains] = useState<GrainRankItem[]>([]);
  const [gaps, setGaps] = useState<KnowledgeGapItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!skillId) return;
    Promise.all([
      getSkillOverview(skillId).catch(() => null),
      getSceneTop(skillId).catch(() => []),
      getRagDistribution(skillId).catch(() => null),
      getTopGrains(skillId, 'best').catch(() => []),
      getTopGrains(skillId, 'worst').catch(() => []),
      getKnowledgeGaps(skillId).catch(() => []),
    ]).then(([ov, st, rd, bg, wg, kg]) => {
      setOverview(ov); setSceneTop(st); setRagDist(rd);
      setBestGrains(bg); setWorstGrains(wg); setGaps(kg);
    }).finally(() => setLoading(false));
  }, [skillId]);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px] space-y-6">
        {/* 标题 */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-foreground">📊 我的分身数据</h1>
            <p className="text-sm text-muted-foreground mt-1">
              用户的每一次使用都在让分身变得更聪明——这就是飞轮效应
            </p>
          </div>
          <div className="flex gap-2">
            <button onClick={() => router.push(`/skill/${skillId}?tab=test`)}
              className="text-sm bg-primary text-white rounded-lg px-4 py-2">
              🧪 测试对话
            </button>
            <button onClick={() => router.push(`/skill/${skillId}`)}
              className="text-sm rounded-lg px-4 py-2 border">
              返回对话
            </button>
          </div>
        </div>

        {/* 汇总卡片 */}
        <StatCards stats={[
          { label: '💬 本周对话量', value: overview?.conversations?.toLocaleString() || '0', trend: 0 },
          { label: '👥 用过的人数', value: overview?.activeUsers?.toLocaleString() || '0', trend: 0 },
          { label: '👍 满意率', value: `${overview?.satisfactionRate || 0}%`, trend: 0 },
          { label: '📝 反馈总数', value: overview?.totalFeedback?.toLocaleString() || '0', trend: 0, unit: '条' },
        ]} />

        {/* 图表行 */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <SceneBarChart data={sceneTop} />
          {ragDist && <RagPieChart high={ragDist.high} refCount={ragDist.ref} none={ragDist.none} highPct={ragDist.highPct} refPct={ragDist.refPct} nonePct={ragDist.nonePct} />}
        </div>

        {/* 颗粒排行 — 点击编辑跳转 Admin 编辑页 */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <GrainRankTable grains={bestGrains} type="best"
            onGrainClick={id => router.push(`/admin/grains/${id}`)} />
          <GrainRankTable grains={worstGrains} type="worst"
            onGrainClick={id => router.push(`/admin/grains/${id}`)} />
        </div>

        {/* 知识缺口 */}
        <KnowledgeGapPanel gaps={gaps}
          onGapClick={id => router.push(`/admin/grains/new?from=gap&gapId=${id}`)} />

        {/* 快捷操作 */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <ActionCard icon="📋" title="查看反馈" desc="看看哪些回答被踩了"
            onClick={() => router.push(`/admin/insights/${skillId}/feedback`)} />
          <ActionCard icon="✏️" title="编辑颗粒" desc="修改话术让它更准确"
            onClick={() => router.push(`/skill/${skillId}/insights/grains`)} />
          <ActionCard icon="🧪" title="测试验证" desc="改完立即试试效果"
            onClick={() => router.push(`/admin/insights/${skillId}/test`)} />
        </div>
      </div>
    </div>
  );
}

function ActionCard({ icon, title, desc, onClick }: { icon: string; title: string; desc: string; onClick: () => void }) {
  return (
    <button onClick={onClick}
      className="rounded-xl bg-surface-2 border border-border p-5 text-left hover:bg-primary-light/30 transition-colors shadow-sm">
      <span className="text-2xl">{icon}</span>
      <h3 className="font-semibold text-foreground mt-2">{title}</h3>
      <p className="text-xs text-muted-foreground mt-1">{desc}</p>
    </button>
  );
}
