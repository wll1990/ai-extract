'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { listSkills, type SkillInfo } from '@/lib/api/skill';
import { getTopGrains, getKnowledgeGaps, type GrainRankItem, type KnowledgeGapItem } from '@/lib/api/admin-insights';
import { GrainRankTable } from '@/components/admin/GrainRankTable';
import { KnowledgeGapPanel } from '@/components/admin/KnowledgeGapPanel';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

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
            className={`rounded-full px-4 py-1.5 text-sm transition-colors ${
              selected?.id === s.id
                ? 'bg-primary text-white'
                : 'bg-surface-2 border border-border text-muted-foreground hover:border-primary'
            }`}>
            {s.ownerName}
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
        onGrainClick={id => router.push(`/admin/grains/${id}/edit`)} />
      <GrainRankTable grains={worst} type="worst"
        onGrainClick={id => router.push(`/admin/grains/${id}/edit`)} />
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

  if (loading) return <LoadingSpinner />;

  return (
    <KnowledgeGapPanel gaps={gaps}
      onGapClick={() => router.push(`/admin/grains/new?skillId=${skillId}&spaceId=${spaceId}`)} />
  );
}

function FeedbackTab({ skillId, router }: { skillId: string; router: ReturnType<typeof useRouter> }) {
  return (
    <div className="text-center py-8">
      <p className="text-muted-foreground text-sm mb-3">查看该分身的用户反馈</p>
      <button onClick={() => router.push(`/admin/insights/${skillId}/feedback`)}
        className="text-sm bg-primary text-white rounded-lg px-4 py-2">
        进入反馈审查 →
      </button>
    </div>
  );
}
