'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { listSkills, type SkillInfo } from '@/lib/api/skill';
import { getTopGrains, getKnowledgeGaps, updateKnowledgeGap, type GrainRankItem, type KnowledgeGapItem } from '@/lib/api/admin-insights';
import { GrainRankTable } from '@/components/admin/GrainRankTable';
import { KnowledgeGapPanel } from '@/components/admin/KnowledgeGapPanel';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { API_BASE, authHeaders } from '@/lib/api/client';

type Tab = 'grains' | 'gaps' | 'feedback';

/**
 * 分身调优 —— 选分身后管理颗粒、处理缺口、审查反馈。
 * 是管理员日常深度工作的入口。
 */
export default function AdminTuningPage() {
  const router = useRouter();
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [selected, setSelected] = useState<SkillInfo | null>(null);
  const [tab, setTab] = useState<Tab>('grains');
  const [loading, setLoading] = useState(true);

  // 加载分身列表
  useEffect(() => {
    listSkills(1, 50)
      .then(d => {
        const published = (d.content || []).filter(s => s.status === 'published');
        setSkills(published);
        if (published.length > 0) setSelected(published[0]);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>;

  return (
    <div className="px-6 py-8 max-w-[1100px] mx-auto space-y-6">
      <h1 className="text-xl font-bold text-foreground">🎯 分身调优</h1>

      {/* 分身选择器 */}
      <div className="flex items-center gap-2 flex-wrap">
        {skills.map(s => (
          <button key={s.id}
            onClick={() => setSelected(s)}
            className={`flex items-center gap-2.5 rounded-full px-4 py-2 text-sm transition-all ${
              selected?.id === s.id
                ? 'bg-primary text-white shadow-md shadow-primary/20'
                : 'bg-surface-2 border border-border text-muted-foreground hover:border-primary/50 hover:text-foreground'
            }`}>
            <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold flex-shrink-0 ${
              selected?.id === s.id
                ? 'bg-white/20 text-white'
                : 'bg-gradient-to-br from-blue-500 to-purple-500 text-white'
            }`}>
              {(s.ownerName || '?')[0]}
            </div>
            <div className="text-left">
              <div className="font-medium leading-tight">{s.ownerName}</div>
            </div>
          </button>
        ))}
        {skills.length === 0 && <p className="text-sm text-muted-foreground">暂无已发布分身</p>}
      </div>

      {!selected ? (
        <p className="text-muted-foreground text-center py-16">请选择一个分身开始调优</p>
      ) : (
        <>
          {/* Tab 切换 */}
          <div className="flex gap-1 border-b border-border">
            {([
              ['grains', '📦 颗粒管理'],
              ['gaps', '🔴 知识缺口'],
              ['feedback', '📋 反馈审查'],
            ] as [Tab, string][]).map(([key, label]) => (
              <button key={key}
                onClick={() => setTab(key)}
                className={`px-4 py-2.5 text-sm font-medium transition-colors border-b-2 -mb-px ${
                  tab === key
                    ? 'text-primary border-primary'
                    : 'text-muted-foreground border-transparent hover:text-foreground'
                }`}>
                {label}
              </button>
            ))}
          </div>

          {/* Tab 内容 */}
          {tab === 'grains' && <GrainsTab skillId={selected.id} router={router} />}
          {tab === 'gaps' && <GapsTab skillId={selected.id} router={router} spaceId={selected.spaceId} />}
          {tab === 'feedback' && <FeedbackTab skillId={selected.id} router={router} />}
        </>
      )}
    </div>
  );
}

function GrainsTab({ skillId, router }: { skillId: string; router: ReturnType<typeof useRouter> }) {
  const [best, setBest] = useState<GrainRankItem[]>([]);
  const [worst, setWorst] = useState<GrainRankItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      getTopGrains(skillId, 'best'),
      getTopGrains(skillId, 'worst'),
    ]).then(([b, w]) => { setBest(b); setWorst(w); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [skillId]);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <GrainRankTable grains={best} type="best"
        onGrainClick={id => router.push(`/admin/grains/${id}`)} />
      <GrainRankTable grains={worst} type="worst"
        onGrainClick={id => router.push(`/admin/grains/${id}`)} />
    </div>
  );
}

function GapsTab({ skillId, router, spaceId }: { skillId: string; router: ReturnType<typeof useRouter>; spaceId: string }) {
  const [gaps, setGaps] = useState<KnowledgeGapItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getKnowledgeGaps(skillId)
      .then(setGaps)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [skillId]);

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

  if (loading) return <LoadingSpinner />;

  return (
    <KnowledgeGapPanel gaps={gaps}
      onGapClick={() => router.push(`/admin/grains/new?skillId=${skillId}&spaceId=${spaceId}`)}
      onResolve={handleResolveGap}
      onIgnore={handleIgnoreGap} />
  );
}

function FeedbackTab({ skillId, router }: { skillId: string; router: ReturnType<typeof useRouter> }) {
  const [items, setItems] = useState<Array<{ id: string; rating: string; query?: string; grainId?: string; grainTitle?: string; createdAt: string }>>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(`${API_BASE}/admin/insights/${skillId}/feedback-logs?page=0&size=5`, {
      headers: authHeaders(),
    }).then(res => res.json())
      .then(json => setItems(json.data?.content || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [skillId]);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-foreground">📋 最近反馈</h3>
        <button onClick={() => router.push(`/admin/insights/${skillId}/feedback`)}
          className="text-xs text-primary hover:underline font-medium">
          查看全部 →
        </button>
      </div>
      {items.length === 0 ? (
        <div className="rounded-[12px] bg-surface-2 border border-border p-8 text-center">
          <p className="text-sm text-muted-foreground">暂无反馈记录</p>
        </div>
      ) : (
        <div className="space-y-2">
          {items.map(f => (
            <div key={f.id} className="flex items-center gap-3 rounded-[12px] bg-surface-2 border border-border p-3 text-sm hover:shadow-sm transition-shadow">
              <span className={`flex-shrink-0 text-sm ${f.rating === 'down' ? 'text-red-500' : 'text-green-600'}`}>
                {f.rating === 'down' ? '👎' : '👍'}
              </span>
              <span className="flex-1 truncate text-muted-foreground text-[13px]">{f.query || '(无文本)'}</span>
              {f.grainId && (
                <button onClick={() => router.push(`/admin/grains/${f.grainId}`)}
                  className="text-xs text-primary hover:underline flex-shrink-0 font-medium">
                  {f.grainTitle || '查看颗粒'}
                </button>
              )}
              <span className="text-xs text-muted-foreground flex-shrink-0">
                {f.createdAt?.substring(0, 10)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
