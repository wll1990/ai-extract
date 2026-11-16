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
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.allSettled([
      getSkillOverview(skillId),
      getSceneTop(skillId),
      getRagDistribution(skillId),
      getTopGrains(skillId, 'best'),
      getTopGrains(skillId, 'worst'),
      getKnowledgeGaps(skillId),
    ]).then(results => {
      if (cancelled) return;
      const val = <T,>(i: number, fallback: T): T =>
        results[i].status === 'fulfilled' ? (results[i] as PromiseFulfilledResult<T>).value : fallback;
      setOverview(val<SkillOverview | null>(0, null));
      setSceneTop(val<SceneTopItem[]>(1, []));
      setRagDist(val<RagDistribution | null>(2, null));
      setBestGrains(val<GrainRankItem[]>(3, []));
      setWorstGrains(val<GrainRankItem[]>(4, []));
      setGaps(val<KnowledgeGapItem[]>(5, []));
      const failed = results.filter(r => r.status === 'rejected').length;
      if (failed > 0) {
        const first = results.find(r => r.status === 'rejected') as PromiseRejectedResult | undefined;
        setError(`${failed}/6 项数据加载失败${first?.reason instanceof Error ? `：${first.reason.message}` : ''}`);
      }
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [skillId, reloadKey]);

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
      value: overview ? (overview.conversations ?? 0).toLocaleString() : '—',
      color: 'blue',
    },
    {
      label: '满意率',
      value: overview ? `${overview.satisfactionRate ?? 0}%` : '—',
      color: 'green',
    },
    {
      label: '活跃用户',
      value: overview ? (overview.activeUsers ?? 0).toLocaleString() : '—',
      color: 'slate',
    },
    {
      label: '反馈总数',
      value: overview ? (overview.totalFeedback ?? 0).toLocaleString() : '—',
      color: 'amber',
    },
  ];

  return (
    <div className="px-6 py-8 max-w-[1200px] mx-auto space-y-8" data-tour="insights-skill">
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

      {error && (
        <div className="flex items-center justify-between rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <span>{error}（失败项显示为 —，不代表数值为 0）</span>
          <button onClick={() => setReloadKey(k => k + 1)}
            className="ml-3 underline hover:no-underline">重试</button>
        </div>
      )}

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
            kcKnowledge={ragDist.kcKnowledge} kcCase={ragDist.kcCase}
            kcKnowledgePct={ragDist.kcKnowledgePct} kcCasePct={ragDist.kcCasePct}
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
        onGapClick={id => router.push(`/admin/grains/new?skillId=${skillId}&gapId=${id}`)}
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
