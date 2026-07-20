'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getUser } from '@/lib/storage';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { listSkills, type SkillInfo } from '@/lib/api/skill';

export default function SkillsGalleryPage() {
  const router = useRouter();
  const [tab, setTab] = useState<'published' | 'mine'>('published');
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const userId = (getUser() as any)?.id as string | undefined;

  const load = (p: number) => {
    setLoading(true);
    setPage(p);
    const status = tab === 'published' ? 'published' : undefined;
    const ownerId = tab === 'mine' ? userId : undefined;
    listSkills(p, 9, status, ownerId)
      .then(d => { setSkills(d.content); setTotalPages(d.totalPages); })
      .catch(e => console.error('加载分身列表失败:', e)).finally(() => setLoading(false));
  };

  useEffect(() => { load(1); }, [tab]);

  if (loading) return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <LoadingSpinner fullScreen={false} />
    </div>
  );

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="mb-2 text-2xl font-bold text-foreground">分身广场</h1>

        {/* Tab 切换 */}
        <div className="mb-4 flex rounded-lg bg-primary-light p-0.5 w-fit">
          <button onClick={() => { setTab('published'); setPage(1); }}
            className={`rounded-md px-4 py-1.5 text-sm font-medium ${tab === 'published' ? 'bg-surface-2 text-foreground shadow-sm' : 'text-muted-foreground'}`}>
            已发布
          </button>
          <button onClick={() => { setTab('mine'); setPage(1); }}
            className={`rounded-md px-4 py-1.5 text-sm font-medium ${tab === 'mine' ? 'bg-surface-2 text-foreground shadow-sm' : 'text-muted-foreground'}`}>
            我的分身
          </button>
        </div>
        <p className="mb-6 text-sm text-muted-foreground">
          {tab === 'published' ? '选择一位销冠，向他的 AI 分身请教或练习' : '你创建的分身（含审核中的）'}
        </p>

        {skills.length === 0 && (
          <div className="rounded-2xl bg-surface-2 p-12 text-center shadow-sm">
            <span className="text-4xl">🤖</span>
            <p className="mt-4 text-muted-foreground">暂无已发布的 AI 分身</p>
            <p className="text-sm text-muted-foreground-2">管理员审核通过后，分身将在此展示</p>
          </div>
        )}

        <div className="grid gap-4 md:grid-cols-3">
          {skills.map(s => (
            <button key={s.id}
              onClick={() => router.push(`/skill/${s.id}?spaceId=${s.spaceId || ''}&name=${encodeURIComponent(s.ownerName || '')}&title=${encodeURIComponent(s.ownerTitle || '')}`)}
              className="rounded-2xl bg-surface-2 p-6 text-left shadow-sm hover:shadow-md transition-all hover:-translate-y-0.5">
              <div className="flex items-center gap-4">
                <div className="flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-navy to-primary text-white text-xl font-bold">
                  {(s.ownerName || '?')[0]}
                </div>
                <div className="flex-1 min-w-0">
                  <h3 className="font-semibold text-foreground truncate">{s.ownerName}</h3>
                  <p className="text-xs text-muted-foreground-2">{s.ownerTitle || '资深销冠'}</p>
                  <div className="mt-1 flex items-center gap-2">
                    <span className="inline-flex items-center gap-1 rounded-full bg-success-bg px-2 py-0.5 text-xs text-success">
                      ● 在线
                    </span>
                    <span className="text-xs text-muted-foreground-2">{s.grainCount || 0} 条锦囊</span>
                  </div>
                </div>
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="mt-8 flex justify-center gap-4">
          <button onClick={() => load(page - 1)} disabled={page <= 1}
            className="rounded-lg border px-4 py-2 text-sm disabled:opacity-30">上一页</button>
          <span className="py-2 text-sm text-muted-foreground">{page} / {totalPages}</span>
          <button onClick={() => load(page + 1)} disabled={page >= totalPages}
            className="rounded-lg border px-4 py-2 text-sm disabled:opacity-30">下一页</button>
        </div>
      )}
    </div>
  );
}
