'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  getSkillOverview, getSceneTop, getRagDistribution,
  getTopGrains, getKnowledgeGaps, updateKnowledgeGap,
  type SkillOverview, type SceneTopItem, type RagDistribution,
  type GrainRankItem, type KnowledgeGapItem,
} from '@/lib/api/admin-insights';
import { KpiHero, type KpiItem } from '@/components/admin/KpiHero';
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

  const handleResolveGap = async (gapId: string) => {
    try {
      await updateKnowledgeGap(gapId, 'resolved');
      setGaps(prev => prev.filter(g => g.id !== gapId));
    } catch (err) { console.error('处理缺口失败', gapId, err); }
  };
  const handleIgnoreGap = async (gapId: string) => {
    try {
      await updateKnowledgeGap(gapId, 'ignored');
      setGaps(prev => prev.filter(g => g.id !== gapId));
    } catch (err) { console.error('忽略缺口失败', gapId, err); }
  };

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
      color: 'slate',
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
      <div className="flex items-center justify-between">
        <button
          onClick={() => router.push('/admin/insights')}
          className="text-sm text-[#64748B] hover:text-[#1E293B] transition-colors font-medium"
        >
          ← 返回全局看板
        </button>
        <button
          onClick={() => router.push(`/admin/insights/${skillId}/feedback`)}
          className="text-sm bg-[#F1F5F9] text-[#334155] rounded-lg px-4 py-2 hover:bg-[#E2E8F0] transition-colors font-medium"
        >
          📋 反馈审查 →
        </button>
      </div>

      {/* ── KPI Hero ── */}
      <KpiHero items={kpiItems} />

      {/* ── 数据分析 ── */}
      <SectionHeader icon="📊" title="数据分析" />
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <SceneBarChart data={sceneTop} />
        {ragDist && (
          <RagPieChart
            high={ragDist.high} refCount={ragDist.ref} none={ragDist.none}
            highPct={ragDist.highPct} refPct={ragDist.refPct} nonePct={ragDist.nonePct}
          />
        )}
      </div>

      {/* ── 颗粒管理 ── */}
      <SectionHeader icon="📦" title="颗粒管理" />
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
      <SectionHeader icon="🔴" title="知识缺口" />
      <KnowledgeGapPanel
        gaps={gaps}
        onGapClick={id => router.push(`/admin/grains/new?skillId=${skillId}`)}
        onResolve={handleResolveGap}
        onIgnore={handleIgnoreGap}
      />
    </div>
  );
}

/** 分区标题 —— 页面内容块之间的小型分隔标题，带蓝色短线和图标 */
function SectionHeader({ icon, title, subtitle }: { icon: string; title: string; subtitle?: string }) {
  return (
    <div className="flex items-center gap-2 pt-2">
      <span className="text-lg">{icon}</span>
      <h2 className="text-[15px] font-semibold text-[#1E293B]">{title}</h2>
      {subtitle && (
        <span className="text-[12px] text-[#94A3B8]">{subtitle}</span>
      )}
    </div>
  );
}
