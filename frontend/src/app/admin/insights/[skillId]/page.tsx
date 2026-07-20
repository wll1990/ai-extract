'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getSkillOverview, getSceneTop, getRagDistribution,
  getTopGrains, getKnowledgeGaps,
  type SkillOverview, type SceneTopItem, type RagDistribution,
  type GrainRankItem, type KnowledgeGapItem,
} from '@/lib/api/admin-insights';
import { KpiHero, type KpiItem } from '@/components/admin/KpiHero';
import { Card } from '@/components/admin/Card';
import { SceneBarChart } from '@/components/admin/SceneBarChart';
import { RagPieChart } from '@/components/admin/RagPieChart';
import { GrainRankTable } from '@/components/admin/GrainRankTable';
import { KnowledgeGapPanel } from '@/components/admin/KnowledgeGapPanel';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

/**
 * 单分身数据详情 v2 —— 三面板统一升级。
 *
 * KPI Hero + 图表白卡化 + 颗粒排行 + 知识缺口。
 */
export default function SkillInsightDetailPage() {
  const params = useParams();
  const router = useRouter();
  const skillId = params.skillId as string;

  const [overview, setOverview] = useState<SkillOverview | null>(null);
  const [sceneTop, setSceneTop] = useState<SceneTopItem[]>([]);
  const [ragDist, setRagDist] = useState<RagDistribution | null>(null);
  const [bestGrains, setBestGrains] = useState<GrainRankItem[]>([]);
  const [worstGrains, setWorstGrains] = useState<GrainRankItem[]>([]);
  const [gaps, setGaps] = useState<KnowledgeGapItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      getSkillOverview(skillId).catch(() => null),
      getSceneTop(skillId).catch(() => []),
      getRagDistribution(skillId).catch(() => null),
      getTopGrains(skillId, 'best').catch(() => []),
      getTopGrains(skillId, 'worst').catch(() => []),
      getKnowledgeGaps(skillId).catch(() => []),
    ]).then(([ov, st, rd, bg, wg, kg]) => {
      setOverview(ov);
      setSceneTop(st);
      setRagDist(rd);
      setBestGrains(bg);
      setWorstGrains(wg);
      setGaps(kg);
    }).finally(() => setLoading(false));
  }, [skillId]);

  if (loading) return <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>;

  const kpiItems: KpiItem[] = [
    {
      label: '本周对话',
      value: overview?.conversations?.toLocaleString() || '0',
      color: 'blue',
    },
    {
      label: '满意率',
      value: `${overview?.satisfactionRate || 0}%`,
      color: 'green',
    },
    {
      label: '活跃用户',
      value: overview?.activeUsers?.toLocaleString() || '0',
      color: 'white',
    },
    {
      label: '反馈总数',
      value: overview?.totalFeedback?.toLocaleString() || '0',
      color: 'amber',
    },
  ];

  return (
    <div className="px-6 py-8 max-w-[1200px] mx-auto space-y-8">
      {/* 顶栏 */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => router.push('/admin/insights')}
          className="text-sm text-[#2563EB] hover:underline font-medium"
        >
          ← 返回全局看板
        </button>
        <span className="text-[#CBD5E1]">|</span>
        <button
          onClick={() => router.push(`/admin/insights/${skillId}/feedback`)}
          className="ml-auto text-sm text-[#64748B] hover:text-[#1E293B] transition-colors"
        >
          📋 反馈审查 →
        </button>
      </div>

      {/* ── KPI Hero ── */}
      <KpiHero items={kpiItems} />

      {/* ── 图表两列 ── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <SceneBarChart data={sceneTop} />
        {ragDist && (
          <RagPieChart
            high={ragDist.high} refCount={ragDist.ref} none={ragDist.none}
            highPct={ragDist.highPct} refPct={ragDist.refPct} nonePct={ragDist.nonePct}
          />
        )}
      </div>

      {/* ── 颗粒排行两列 ── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <GrainRankTable
          grains={bestGrains} type="best"
          onGrainClick={id => router.push(`/admin/grains/${id}`)}
        />
        <GrainRankTable
          grains={worstGrains} type="worst"
          onGrainClick={id => router.push(`/admin/grains/${id}`)}
        />
      </div>

      {/* ── 知识缺口 ── */}
      <KnowledgeGapPanel
        gaps={gaps}
        onGapClick={id => router.push(`/admin/grains/new?skillId=${skillId}`)}
      />
    </div>
  );
}
