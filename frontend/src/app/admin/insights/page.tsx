'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getGlobalOverview, getDiscoveries, getDiscoveryDetail, approveCandidateGrain, rejectCandidateGrain, resolveDiscovery, type GlobalOverview, type SkillHealth, type AutoInsight, type CandidateGrain } from '@/lib/api/admin-insights';
import { KpiHero, type KpiItem } from '@/components/admin/KpiHero';
import { Card } from '@/components/admin/Card';
import { SkillCard } from '@/components/admin/SkillCard';
import { InsightCard } from '@/components/admin/InsightCard';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

type Tab = 'overview' | 'discovery';

/**
 * 数据看板 v2 —— 三面板统一升级。
 *
 * 深色 KPI Hero + Sigma 分段控制器 + 分身卡片网格 + 自动发现 tab。
 */
export default function AdminInsightsPage() {
  const router = useRouter();
  const [data, setData] = useState<GlobalOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<Tab>('overview');

  useEffect(() => {
    getGlobalOverview()
      .then(setData)
      .catch(err => { console.error('加载全局看板失败', err); })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>;
  }
  if (!data) {
    return <p className="text-muted-foreground text-center py-16">加载失败，请刷新重试</p>;
  }

  // KPI 项
  const kpiItems: KpiItem[] = [
    {
      label: '本周对话',
      value: data.totalConversations.toLocaleString(),
      color: 'blue',
    },
    {
      label: '满意率',
      value: `${data.satisfactionRate}%`,
      color: 'green',
    },
    {
      label: '活跃用户',
      value: data.activeUsers.toLocaleString(),
      color: 'slate',
    },
    {
      label: '待处理缺口',
      value: data.totalOpenGaps.toLocaleString(),
      trend: data.totalOpenGaps > 0
        ? { direction: 'up' as const, text: '需关注' }
        : { direction: 'flat' as const, text: '正常' },
      color: 'amber',
    },
  ];

  // 告警汇总
  const allAlerts = data.skills.flatMap(s => s.alerts.map(a => ({ skill: s, alert: a })));

  return (
    <div className="px-6 py-8 max-w-[1200px] mx-auto space-y-8">
      {/* ── KPI Hero ── */}
      <KpiHero items={kpiItems} />

      {/* ── Sigma 分段控制器 ── */}
      <div className="flex items-center gap-0">
        {([
          { key: 'overview' as Tab, icon: '📊', label: '全局看板' },
          { key: 'discovery' as Tab, icon: '🔍', label: '自动发现' },
        ]).map(t => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className="relative px-5 py-2.5 text-sm font-medium transition-colors"
            style={{
              color: tab === t.key ? '#1E293B' : '#94A3B8',
            }}
          >
            {t.icon} {t.label}
            {tab === t.key && (
              <span
                className="absolute bottom-0 left-1/2 -translate-x-1/2 h-[2px] w-[60%] rounded-full"
                style={{ background: '#2563EB' }}
              />
            )}
          </button>
        ))}
        <div className="flex-1 border-b border-[#E8ECF1]" />
      </div>

      {/* ── 全局看板 tab ── */}
      {tab === 'overview' && (
        <>
          {/* 分身卡片网格 */}
          {data.skills.length === 0 ? (
            <EmptyState message="暂无已发布分身" actionLabel="去创建分身" actionHref="/admin/skills/new" router={router} />
          ) : (
            <>
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5">
                {data.skills.map(s => (
                  <SkillCard
                    key={s.skillId}
                    skill={s}
                    onClick={() => router.push(`/admin/insights/${s.skillId}`)}
                  />
                ))}
              </div>
              {data.totalSkills > data.skills.length && (
                <div className="text-center py-3">
                  <p className="text-[13px] text-[#94A3B8]">
                    显示最活跃的 {data.skills.length}/{data.totalSkills} 个分身
                    <button onClick={() => router.push('/admin/tuning')}
                      className="text-primary hover:underline ml-2 font-medium">
                      查看全部 →
                    </button>
                  </p>
                </div>
              )}
            </>
          )}

          {/* 额外指标卡：命中率 + 颗粒总数 */}
          <div className="grid grid-cols-2 gap-5">
            <Card title="平台概览" flush>
              <div className="grid grid-cols-2 gap-6">
                <div className="text-center py-2">
                  <p className="text-[28px] font-extrabold text-[#1E293B] tabular-nums">
                    {data.hitRate}%
                  </p>
                  <p className="text-[12px] text-[#64748B] font-medium mt-0.5">知识命中率</p>
                </div>
                <div className="text-center py-2">
                  <p className="text-[28px] font-extrabold text-[#1E293B] tabular-nums">
                    {data.totalGrains.toLocaleString()}
                  </p>
                  <p className="text-[12px] text-[#64748B] font-medium mt-0.5">颗粒总数</p>
                </div>
              </div>
            </Card>

            <Card title="分身总数" flush>
              <div className="text-center py-2">
                <p className="text-[28px] font-extrabold text-[#1E293B] tabular-nums">
                  {data.totalSkills}
                </p>
                <p className="text-[12px] text-[#64748B] font-medium mt-0.5">已发布分身</p>
              </div>
              {/* 迷你告警汇总 */}
              {allAlerts.length > 0 && (
                <div className="mt-3 pt-3 border-t border-[#E8ECF1]">
                  <div className="flex flex-wrap gap-1.5">
                    {allAlerts.slice(0, 4).map((a, i) => (
                      <span
                        key={i}
                        className="inline-flex items-center gap-1 text-[12px] px-3 py-1.5 rounded-full cursor-pointer hover:opacity-80 transition-opacity"
                        style={{ background: '#FEF2F2', color: '#991B1B' }}
                        onClick={() => router.push(`/admin/insights/${a.skill.skillId}`)}
                      >
                        <span className="w-1 h-1 rounded-full bg-[#DC2626]" />
                        {a.skill.name}: {a.alert}
                      </span>
                    ))}
                    {allAlerts.length > 4 && (
                      <span className="text-[11px] text-[#64748B] px-1">
                        +{allAlerts.length - 4} 更多
                      </span>
                    )}
                  </div>
                </div>
              )}
            </Card>
          </div>
        </>
      )}

      {/* ── 自动发现 tab ── */}
      {tab === 'discovery' && <DiscoveryTab />}
    </div>
  );
}

/** 自动发现 tab —— 洞察卡片流 + 筛选条 + 空状态 */
function DiscoveryTab() {
  const router = useRouter();
  const [insights, setInsights] = useState<AutoInsight[]>([]);
  const [candidateGrainsMap, setCandidateGrainsMap] = useState<Record<string, CandidateGrain[]>>({});
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<'all' | 'critical' | 'warning' | 'info'>('all');
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    const params: Record<string, string> = {};
    if (filter !== 'all') params.severity = filter;
    getDiscoveries(params)
      .then(async (list) => {
        setInsights(list);
        // 加载每条洞察的候选颗粒
        const map: Record<string, CandidateGrain[]> = {};
        await Promise.all(list.map(async (insight) => {
          try {
            const detail = await getDiscoveryDetail(insight.id);
            if (detail.candidateGrains && detail.candidateGrains.length > 0) {
              map[insight.id] = detail.candidateGrains;
            }
          } catch (err) { console.error('加载洞察详情失败', insight.id, err); }
        }));
        setCandidateGrainsMap(map);
      })
      .catch(err => { console.error('加载自动发现失败', err); })
      .finally(() => setLoading(false));
  }, [filter]);

  const handleApprove = async (grainId: string) => {
    setActionLoading(grainId);
    try {
      await approveCandidateGrain(grainId);
      // 刷新候选颗粒状态
      setCandidateGrainsMap(prev => {
        const next = { ...prev };
        for (const [insightId, grains] of Object.entries(next)) {
          next[insightId] = grains.map(g =>
            g.id === grainId ? { ...g, status: 'approved' as const } : g
          );
        }
        return next;
      });
    } catch (err) { console.error('审核通过候选颗粒失败', grainId, err); }
    setActionLoading(null);
  };

  const handleReject = async (grainId: string) => {
    setActionLoading(grainId);
    try {
      await rejectCandidateGrain(grainId);
      setCandidateGrainsMap(prev => {
        const next = { ...prev };
        for (const [insightId, grains] of Object.entries(next)) {
          next[insightId] = grains.map(g =>
            g.id === grainId ? { ...g, status: 'rejected' as const } : g
          );
        }
        return next;
      });
    } catch (err) { console.error('拒绝候选颗粒失败', grainId, err); }
    setActionLoading(null);
  };

  const handleResolve = async (insightId: string) => {
    setActionLoading(insightId);
    try {
      await resolveDiscovery(insightId, 'resolved');
      setInsights(prev => prev.map(i =>
        i.id === insightId ? { ...i, status: 'resolved' as const } : i
      ));
    } catch (err) { console.error('处理洞察失败', insightId, err); }
    setActionLoading(null);
  };

  const handleIgnore = async (insightId: string) => {
    setActionLoading(insightId);
    try {
      await resolveDiscovery(insightId, 'ignored');
      setInsights(prev => prev.map(i =>
        i.id === insightId ? { ...i, status: 'ignored' as const } : i
      ));
    } catch (err) { console.error('忽略洞察失败', insightId, err); }
    setActionLoading(null);
  };

  if (loading) {
    return <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>;
  }

  const filterCounts = {
    all: insights.length,
    critical: insights.filter(i => i.severity === 'critical').length,
    warning: insights.filter(i => i.severity === 'warning').length,
    info: insights.filter(i => i.severity === 'info').length,
  };

  /** 所有指标正常的空状态 */
  if (insights.length === 0) {
    return (
      <div className="rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_2px_rgba(15,23,42,0.06)] p-12 text-center">
        <div className="w-16 h-16 mx-auto mb-5 rounded-2xl flex items-center justify-center"
          style={{ background: 'linear-gradient(135deg, #F0FDF4, #DCFCE7)' }}>
          <span className="text-2xl">✅</span>
        </div>
        <h3 className="text-[17px] font-semibold text-[#1E293B] mb-2">所有指标正常</h3>
        <p className="text-[14px] text-[#64748B] max-w-md mx-auto leading-relaxed mb-6">
          AI 分析了最近的对话数据和知识缺口，未发现需要关注的异常。
        </p>
        <div className="inline-flex items-center gap-3 text-[12px] text-[#94A3B8]">
          <span className="inline-flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[#CBD5E1] animate-pulse" />
            下次自动分析：明天 03:00
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Sigma 风格筛选条 */}
      <div className="flex items-center gap-0.5">
        {([
          { key: 'all' as const, label: '全部' },
          { key: 'critical' as const, icon: '🔴', label: '严重' },
          { key: 'warning' as const, icon: '🟡', label: '警告' },
          { key: 'info' as const, icon: '🔵', label: '提示' },
        ]).map(f => (
          <button
            key={f.key}
            onClick={() => setFilter(f.key)}
            className={`relative px-4 py-2 text-[13px] font-medium transition-colors ${
              filter === f.key ? 'text-[#1E293B]' : 'text-[#94A3B8] hover:text-[#64748B]'
            }`}
          >
            {f.icon ? <>{f.icon} {f.label}</> : f.label}
            <span className="text-[#CBD5E1] ml-1">({filterCounts[f.key]})</span>
            {filter === f.key && (
              <span
                className="absolute bottom-0 left-1/2 -translate-x-1/2 h-[2px] w-[50%] rounded-full"
                style={{ background: '#2563EB' }}
              />
            )}
          </button>
        ))}
      </div>

      {/* 洞察卡片流 —— 两列响应式网格 */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
        {insights.map(insight => (
          <InsightCard
            key={insight.id}
            insight={insight}
            candidateGrains={candidateGrainsMap[insight.id]}
            onApprove={handleApprove}
            onReject={handleReject}
            onResolve={handleResolve}
            onIgnore={handleIgnore}
          />
        ))}
      </div>
    </div>
  );
}

function EmptyState({ message, actionLabel, actionHref, router }: { message: string; actionLabel?: string; actionHref?: string; router?: ReturnType<typeof useRouter> }) {
  return (
    <div className="rounded-[12px] bg-white border border-[#E8ECF1] p-12 text-center">
      <p className="text-[14px] text-[#64748B] mb-4">{message}</p>
      {actionLabel && actionHref && router && (
        <button onClick={() => router.push(actionHref)}
          className="text-sm bg-primary text-white rounded-lg px-5 py-2.5 hover:bg-primary-hover transition-colors font-medium">
          {actionLabel}
        </button>
      )}
    </div>
  );
}
