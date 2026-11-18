'use client';
import { Pagination } from '@/components/ui/Pagination';

import React, { useState, useEffect } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { listSkills, type SkillInfo } from '@/lib/api/skill';
import { apiClient } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

import { SKILL_STATUS_LABELS } from '@/lib/skill-status';

export default function AdminGrainsPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const skillId = searchParams.get('skillId');
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listSkills(1, 200)
      .then(data => setSkills(data.content || []))
      .catch(() => setError('加载失败，请确认有管理权限'))
      .finally(() => setLoading(false));
  }, []);

  if (skillId) return <SkillGrainList skillId={skillId} />;

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="mb-2 text-[28px] font-bold text-foreground">颗粒管理</h1>
        <p className="mb-6 text-sm text-muted-foreground">
          管理所有分身的经验颗粒，支持编辑、废弃、溯源
        </p>

        {error && (
          <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">
            {error}
            <button onClick={() => window.location.reload()} className="ml-3 underline">重试</button>
          </div>
        )}

        {!error && skills.length === 0 ? (
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
                <span className="text-sm font-medium text-foreground truncate">{s.displayName || s.ownerName}</span>
                <span className="text-sm text-muted-foreground truncate">{s.ownerName}</span>
                <span>
                  <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${
                    s.status === 'published' ? 'bg-success-bg text-success' : 'bg-warning-bg text-warning-text'
                  }`}>
                    {SKILL_STATUS_LABELS[s.status] || s.status}
                  </span>
                </span>
                <span className="text-sm text-foreground">{s.grainCount ?? 0}</span>
                <button
                  onClick={() => router.push(`/admin/grains?skillId=${s.id}`)}
                  className="text-sm text-primary font-medium hover:underline text-left"
                >
                  查看颗粒 →
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

interface GrainRow {
  id: string;
  sceneDescription?: string;
  sceneTag?: string;
  qualityScore?: number;
  weight?: number;
  status?: string;
  helpfulCount?: number;
  unhelpfulCount?: number;
}

/** 单个分身的颗粒列表（从「查看颗粒」进入，K27） */
function SkillGrainList({ skillId }: { skillId: string }) {
  const router = useRouter();
  const [grains, setGrains] = useState<GrainRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [sort, setSort] = useState('helpful');
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [total, setTotal] = useState(0);
  const size = 50;

  useEffect(() => {
    setLoading(true);
    setError('');
    apiClient<{ content: GrainRow[]; totalPages?: number; total?: number }>(
      `/admin/grains?skillId=${skillId}&sort=${sort}&page=${page}&size=${size}`)
      .then(d => {
        setGrains(d.content || []);
        setTotalPages(Math.max(1, d.totalPages || 1));
        setTotal(d.total ?? (d.content?.length ?? 0));
      })
      .catch((e: unknown) => {
        setGrains([]);
        setError(e instanceof Error && e.message ? e.message : '颗粒列表加载失败');
      })
      .finally(() => setLoading(false));
  }, [skillId, sort, page]);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <button onClick={() => router.push('/admin/grains')}
          className="mb-4 text-sm text-primary hover:underline">← 返回分身列表</button>
        <div className="mb-4 flex items-center justify-between">
          <h1 className="text-[24px] font-bold text-foreground">颗粒列表</h1>
          <select value={sort} onChange={e => { setSort(e.target.value); setPage(1); }}
            className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm">
            <option value="helpful">👍 最有用</option>
            <option value="unhelpful">👎 需改进</option>
            <option value="recent">📅 最新</option>
          </select>
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
            {error}
            <button onClick={() => location.reload()} className="ml-3 underline">重试</button>
          </div>
        )}
        {!error && grains.length === 0 && (
          <div className="rounded-xl border border-border bg-surface-2 px-4 py-10 text-center text-sm text-muted-foreground">
            这个分身还没有颗粒
          </div>
        )}
        <div className="overflow-hidden rounded-xl border border-border bg-surface-2">
          {grains.map(g => (
            <div key={g.id} className="grid grid-cols-6 items-center gap-3 border-b border-border/60 px-4 py-3 last:border-0">
              <span className="col-span-2 truncate text-sm text-foreground">{g.sceneDescription || g.sceneTag || '未命名'}</span>
              <span className="text-xs text-muted-foreground">{g.sceneTag || '未分类'}</span>
              <span className="text-xs text-muted-foreground">
                质量 {g.qualityScore != null ? `${g.qualityScore}/5` : '未评分'} · 权重 {g.weight ?? '-'}
              </span>
              <span className="text-xs">
                <span className={g.status === 'active' ? 'text-green-600' : 'text-gray-400'}>
                  {g.status === 'active' ? '生效中' : g.status === 'deprecated' ? '已废弃' : (g.status || '-')}
                </span>
                <span className="ml-2 text-muted-foreground">👍{g.helpfulCount ?? 0} 👎{g.unhelpfulCount ?? 0}</span>
              </span>
              <button onClick={() => router.push(`/admin/grains/${g.id}/edit`)}
                className="text-right text-sm text-primary hover:underline">编辑 →</button>
            </div>
          ))}
        </div>
        <Pagination page={page} totalPages={totalPages} totalElements={total} onPageChange={setPage} loading={loading} />
      </div>
    </div>
  );
}
