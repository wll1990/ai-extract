'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface SkillSummary {
  id: string;
  displayName: string;
  ownerName: string;
  status: string;
  grainCount: number;
  activeGrains: number;
  deprecatedGrains: number;
}

export default function AdminGrainsPage() {
  const router = useRouter();
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient<{ content: SkillSummary[] }>('/admin/skills?size=200')
      .then(data => setSkills(data.content || []))
      .catch(() => setSkills([]))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="mb-2 text-[28px] font-bold text-foreground">颗粒管理</h1>
        <p className="mb-6 text-sm text-muted-foreground">
          管理所有分身的经验颗粒，支持编辑、废弃、溯源
        </p>

        {skills.length === 0 ? (
          <div className="py-20 text-center">
            <span className="text-5xl">📋</span>
            <h2 className="mt-4 text-lg font-semibold text-foreground">暂无颗粒数据</h2>
            <p className="mt-2 text-sm text-muted-foreground">
              还没有已发布或待审核的分身。请先创建分身并完成经验萃取。
            </p>
            <button
              onClick={() => router.push('/admin/skills')}
              className="mt-6 inline-block rounded-full bg-primary px-6 py-2.5 text-sm font-medium text-white hover:opacity-90 transition-opacity"
            >
              去分身管理 →
            </button>
          </div>
        ) : (
          <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
            <div className="grid grid-cols-5 gap-4 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border">
              <span>分身名称</span>
              <span>创建者</span>
              <span>状态</span>
              <span>颗粒数</span>
              <span>操作</span>
            </div>
            {skills.map(s => (
              <div
                key={s.id}
                className="grid grid-cols-5 gap-4 px-5 py-3.5 border-b border-border last:border-0 items-center hover:bg-primary-light transition-colors"
              >
                <span className="text-sm font-medium text-foreground truncate">{s.displayName}</span>
                <span className="text-sm text-muted-foreground truncate">{s.ownerName}</span>
                <span>
                  <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${
                    s.status === 'published' ? 'bg-success-bg text-success' : 'bg-warning-bg text-warning-text'
                  }`}>
                    {s.status === 'published' ? '已发布' : s.status === 'draft' ? '待审核' : s.status}
                  </span>
                </span>
                <span className="text-sm text-foreground">
                  {s.activeGrains ?? s.grainCount}
                  {s.deprecatedGrains > 0 && (
                    <span className="text-muted-foreground ml-1">（{s.deprecatedGrains} 已废弃）</span>
                  )}
                </span>
                <button
                  onClick={() => router.push(`/admin/audit/${s.id}`)}
                  className="text-sm text-primary font-medium hover:underline text-left"
                >
                  审核颗粒 →
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
