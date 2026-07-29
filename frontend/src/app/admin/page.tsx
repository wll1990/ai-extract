'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { KpiHero, type KpiItem } from '@/components/admin/KpiHero';
import { SkillCard } from '@/components/admin/SkillCard';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import type { SkillHealth } from '@/lib/api/admin-insights';

const PIPE_LABELS: Record<string, string> = {
  uploaded: '待处理', cleaning: '清洗中', analyzing: '分析中',
  analyzed: '已分析', extracted: '已萃取', rejected: '已拒绝', discarded: '已丢弃',
};
const PIPE_COLORS: Record<string, string> = {
  uploaded: '#93c5fd', cleaning: '#60a5fa', analyzing: '#fbbf24',
  analyzed: '#34d399', extracted: '#10b981', rejected: '#f87171', discarded: '#9ca3af',
};

interface DashboardV2Data {
  today: { conversations: number; users: number };
  trend: Array<{ date: string; count: number }>;
  satisfactionRate: number;
  hitRate: number;
  totalConversations: number;
  pipeline: Record<string, number>;
  enterprises?: Array<{ companyId: string; companyName: string; userCount: number }>;
  enterpriseCount?: number;
  skills: SkillHealth[];
  activeUsers: Array<{ userId: string; name: string; conversations: number }>;
  pending: Array<{ type: string; skillId?: string; spaceId?: string; name?: string; status: string; count?: number }>;
  recent: Array<{ type: string; title: string; time: string; spaceName: string }>;
}

export default function AdminDashboard() {
  const router = useRouter();
  const [data, setData] = useState<DashboardV2Data | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient<DashboardV2Data>('/admin/dashboard/v2')
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner />;
  if (!data) return null;

  const { today, trend, satisfactionRate, hitRate, pipeline, skills, activeUsers, pending, recent } = data;

  const activeSkills = skills.filter(s => s.conversations > 0).length;
  const idleSkills = skills.length - activeSkills;

  const kpis: KpiItem[] = [
    { label: '今日对话', value: String(today.conversations), color: 'blue' },
    { label: '今日用户', value: String(today.users), color: 'green' },
    { label: '活跃分身', value: `${activeSkills}/${skills.length}`, trend: idleSkills > 0 ? { direction: 'flat' as const, text: `${idleSkills}个闲置` } : undefined, color: 'slate' },
    { label: '满意度', value: `${satisfactionRate}%`, trend: satisfactionRate >= 80 ? { direction: 'up' as const, text: '良好' } : satisfactionRate >= 60 ? { direction: 'flat' as const, text: '一般' } : { direction: 'down' as const, text: '需关注' }, color: satisfactionRate >= 80 ? 'green' : 'amber' },
    ...(data.enterpriseCount != null ? [
      { label: '入驻企业', value: String(data.enterpriseCount), color: 'amber' as const }
    ] : []),
  ];

  const pipeEntries = Object.entries(PIPE_LABELS)
    .filter(([k]) => pipeline[k] != null)
    .map(([k, label]) => ({ key: k, label, count: pipeline[k], color: PIPE_COLORS[k] || '#9ca3af' }));
  const pipeTotal = pipeEntries.reduce((s, e) => s + e.count, 0);

  return (
    <div className="min-h-screen bg-surface">
      <div className="mx-auto max-w-[960px] px-6 py-8 space-y-6">
        <h1 className="text-2xl font-bold text-foreground">工作台</h1>

        {/* KPI Hero */}
        <KpiHero items={kpis} />

        {/* 7 天对话趋势 */}
        {trend && trend.length > 0 && (
          <div className="rounded-[12px] bg-surface-2 border border-border p-5 shadow-sm">
            <h2 className="font-semibold text-foreground mb-4">📈 7 天对话趋势</h2>
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={trend}>
                <defs>
                  <linearGradient id="colorConv" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#9ca3af" />
                <YAxis tick={{ fontSize: 11 }} stroke="#9ca3af" allowDecimals={false} />
                <Tooltip
                  contentStyle={{ borderRadius: 8, border: '1px solid #e5e7eb', fontSize: 12 }}
                  formatter={(value: number) => [value, '对话数']}
                />
                <Area type="monotone" dataKey="count" stroke="#3b82f6" fill="url(#colorConv)" strokeWidth={2} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        )}

        {/* 企业排行（仅 super_admin） */}
        {data.enterprises && data.enterprises.length > 0 && (
          <div>
            <h2 className="font-semibold text-foreground mb-3">🏢 企业排行</h2>
            <div className="rounded-[12px] bg-surface-2 border border-border overflow-hidden">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-muted-foreground text-xs">
                    <th className="text-left py-3 px-4 font-medium">企业</th>
                    <th className="text-right py-3 px-4 font-medium">用户数</th>
                  </tr>
                </thead>
                <tbody>
                  {data.enterprises.map((e, i) => (
                    <tr key={e.companyId} className="border-b border-border last:border-0 hover:bg-surface transition-colors">
                      <td className="py-3 px-4">
                        <div className="flex items-center gap-3">
                          <span className="text-xs text-muted-foreground w-5">{i + 1}</span>
                          <span className="font-medium text-foreground">{e.companyName}</span>
                        </div>
                      </td>
                      <td className="text-right py-3 px-4 text-foreground font-semibold">{e.userCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* 分身健康度 */}
        {skills.length > 0 && (
          <div>
            <h2 className="font-semibold text-foreground mb-3">分身健康度</h2>
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
              {skills.map(skill => (
                <SkillCard
                  key={skill.skillId}
                  skill={skill}
                  onClick={() => router.push(`/admin/insights/${skill.skillId}`)}
                />
              ))}
            </div>
          </div>
        )}

        {/* 萃取管道 + 团队活跃 */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* 萃取管道 */}
          <div className="rounded-[12px] bg-surface-2 border border-border p-5 shadow-sm">
            <h2 className="font-semibold text-foreground mb-4">🔧 萃取管道</h2>
            {pipeTotal > 0 ? (
              <div className="space-y-3">
                <div className="flex h-4 rounded-full overflow-hidden">
                  {pipeEntries.map(e => (
                    <div
                      key={e.key}
                      style={{ width: `${(e.count / pipeTotal) * 100}%`, background: e.color }}
                    />
                  ))}
                </div>
                <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                  {pipeEntries.map(e => (
                    <span key={e.key} className="flex items-center gap-1.5">
                      <span className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ background: e.color }} />
                      {e.label} <span className="font-semibold text-foreground">{e.count}</span>
                    </span>
                  ))}
                </div>
                <p className="text-xs text-muted-foreground">
                  RAG 命中率: <span className="font-semibold text-foreground">{hitRate}%</span>
                </p>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground text-center py-6">暂无素材数据</p>
            )}
          </div>

          {/* 团队活跃 */}
          <div className="rounded-[12px] bg-surface-2 border border-border p-5 shadow-sm">
            <h2 className="font-semibold text-foreground mb-4">👥 本周活跃 TOP 10</h2>
            {activeUsers.length > 0 ? (
              <div className="space-y-2">
                {activeUsers.slice(0, 10).map((u, i) => (
                  <div key={u.userId} className="flex items-center gap-3 py-1.5">
                    <span className="text-xs text-muted-foreground w-5">{i + 1}</span>
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-purple-500 flex items-center justify-center text-white text-xs font-bold flex-shrink-0">
                      {u.name.charAt(0)}
                    </div>
                    <span className="text-sm font-medium text-foreground flex-1 truncate">{u.name}</span>
                    <span className="text-xs text-muted-foreground">{u.conversations} 次</span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground text-center py-6">本周暂无活跃用户</p>
            )}
          </div>
        </div>

        {/* 待处理 + 最近活动 */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="rounded-[12px] bg-surface-2 border border-border p-5 shadow-sm">
            <h2 className="font-semibold text-foreground mb-4">⚠️ 待处理</h2>
            {pending.length > 0 ? (
              <div className="space-y-2">
                {pending.map((item, i) => (
                  <div key={i}
                    className={`flex items-center justify-between rounded-lg px-4 py-3 text-sm ${
                      item.type === 'skill_review' ? 'bg-danger-bg border border-danger/20' : 'bg-warning-bg border border-warning/20'
                    }`}>
                    <div className="flex items-center gap-2 min-w-0">
                      <span>{item.type === 'skill_review' ? '🔴' : '🟡'}</span>
                      <span className="font-medium truncate">
                        {item.type === 'skill_review' ? `${item.name} · 分身待审核` : `${item.count} 份素材处理中`}
                      </span>
                    </div>
                    {item.type === 'skill_review' && item.spaceId && (
                      <button
                        onClick={() => router.push(`/admin/skills/${item.skillId}/audit`)}
                        className="flex-shrink-0 text-xs text-danger font-medium hover:underline ml-3">
                        去审核 →
                      </button>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground text-center py-6">暂无待处理事项</p>
            )}
          </div>

          <div className="rounded-[12px] bg-surface-2 border border-border p-5 shadow-sm">
            <h2 className="font-semibold text-foreground mb-4">📈 最近活动</h2>
            {recent.length > 0 ? (
              <div className="space-y-2">
                {recent.map((item, i) => (
                  <div key={i} className="flex items-center justify-between rounded-lg bg-surface px-3 py-2.5 text-sm">
                    <div className="min-w-0">
                      <span className="text-foreground truncate block">📄 {item.title}</span>
                      {item.spaceName && (
                        <span className="text-xs text-muted-foreground">{item.spaceName}</span>
                      )}
                    </div>
                    <span className="text-xs text-muted-foreground flex-shrink-0 ml-3">
                      {item.time ? new Date(item.time).toLocaleDateString('zh-CN') : ''}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground text-center py-6">暂无活动</p>
            )}
          </div>
        </div>

        {/* 快捷入口 */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="rounded-xl bg-surface-2 border border-border p-5 shadow-sm">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="font-semibold text-foreground">🎯 分身调优</h3>
                <p className="text-xs text-muted-foreground mt-1">查看使用数据 · RAG 分布 · 颗粒质量 · 知识缺口</p>
              </div>
              <button onClick={() => router.push('/admin/tuning')}
                className="text-sm bg-primary text-white rounded-lg px-4 py-2 hover:bg-primary-hover transition-colors">
                进入 →
              </button>
            </div>
          </div>

          <div className="rounded-[12px] bg-surface-2 border border-border p-5 shadow-sm">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="font-semibold text-foreground">🏢 企业合作</h3>
                <p className="text-xs text-muted-foreground mt-1">管理企业信息 · 生成注册码 · 查看企业使用情况</p>
              </div>
              <button onClick={() => router.push('/admin/companies')}
                className="text-sm bg-primary text-white rounded-lg px-4 py-2 hover:bg-primary-hover transition-colors">
                进入 →
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
