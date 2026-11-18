'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { listMySkills, type SkillInfo } from '@/lib/api/skill';
import { Pagination } from '@/components/ui/Pagination';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getUser } from '@/lib/storage';
import { Permission } from '@/lib/permissions';

const PAGE_SIZE = 12;

const STATUS_LABEL: Record<string, { text: string; cls: string }> = {
  generating: { text: '萃取中', cls: 'bg-amber-50 text-amber-700' },
  reviewing: { text: '待审核', cls: 'bg-blue-50 text-blue-700' },
  published: { text: '已发布', cls: 'bg-emerald-50 text-emerald-700' },
  discarded: { text: '已废弃', cls: 'bg-slate-100 text-slate-500' },
};

/**
 * 我的分身 —— 属主的日常入口：看自己的分身状态，进入试问台。
 *
 * 复用 GET /skills/my（已含未发布），不额外开接口。
 */
export default function MySkillsPage() {
  const router = useRouter();
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [error, setError] = useState('');

  const permissions = ((getUser() as { permissions?: string[] } | null)?.permissions) ?? [];
  const canAudit = permissions.includes(Permission.SKILL_MANAGE);

  const load = (p: number) => {
    setLoading(true);
    setError('');
    setPage(p);
    listMySkills(p, PAGE_SIZE)
      .then(d => { setSkills(d.content); setTotal(d.total); setTotalPages(d.totalPages); })
      .catch(e => {
        setSkills([]);
        setError(e instanceof Error && e.message ? e.message : '分身列表加载失败');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(1); }, []);

  return (
    <div className="min-h-[calc(100vh-3.5rem)] bg-[#F8FAFC] px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-1 flex items-center justify-between">
          <h1 className="text-[22px] font-semibold text-[#1E293B]">我的分身</h1>
          <span className="text-[12px] text-[#94A3B8]">共 {total} 个</span>
        </div>
        <p className="mb-6 text-[13px] text-[#64748B]">
          这里能看到你名下的分身（含未发布）。点「试问」先自己问几句，觉得哪里答偏了，直接跟它说怎么改。
        </p>

        {error && (
          <div className="mb-4 flex items-center justify-between rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            <span>{error}</span>
            <button onClick={() => load(page)} className="underline">重试</button>
          </div>
        )}

        {loading ? (
          <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>
        ) : skills.length === 0 ? (
          <div className="rounded-2xl bg-white p-12 text-center shadow-sm">
            <span className="text-4xl">🧭</span>
            <p className="mt-4 text-[15px] text-[#475569]">你还没有分身</p>
            <p className="mt-1 text-[13px] text-[#94A3B8]">
              先上传素材或完成一次销冠访谈，系统会从中萃取你的经验。
            </p>
            <div className="mt-5 flex justify-center gap-3">
              <button onClick={() => router.push('/admin/materials')}
                className="rounded-lg bg-[#2563EB] px-4 py-2 text-[13px] font-medium text-white">
                上传素材
              </button>
              <button onClick={() => router.push('/interview/create')}
                className="rounded-lg border border-[#E2E8F0] px-4 py-2 text-[13px] font-medium text-[#475569]">
                发起访谈
              </button>
            </div>
          </div>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {skills.map(s => {
              const status = STATUS_LABEL[s.status] ?? { text: s.status, cls: 'bg-slate-100 text-slate-500' };
              return (
                <div key={s.id} className="flex flex-col rounded-2xl bg-white p-5 shadow-sm">
                  <div className="flex items-start gap-3">
                    <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[#EEF2FF] text-[15px] font-semibold text-[#4F46E5]">
                      {(s.displayName || s.ownerName || '分').slice(0, 1)}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <h3 className="truncate text-[15px] font-semibold text-[#1E293B]">
                          {s.displayName || s.ownerName || '未命名分身'}
                        </h3>
                        <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] ${status.cls}`}>
                          {status.text}
                        </span>
                      </div>
                      <p className="mt-0.5 truncate text-[12px] text-[#94A3B8]">
                        {s.ownerTitle || '资深销冠'} · {s.grainCount ?? 0} 条经验
                      </p>
                    </div>
                  </div>

                  <div className="mt-4 flex flex-wrap gap-1.5">
                    {(s.tags ?? []).slice(0, 3).map(tag => (
                      <span key={tag} className="rounded-full bg-[#F1F5F9] px-2 py-0.5 text-[11px] text-[#64748B]">
                        {tag}
                      </span>
                    ))}
                  </div>

                  <div className="mt-auto flex items-center gap-2 pt-4">
                    <button
                      onClick={() => router.push(`/my/skills/${s.id}/trial`)}
                      className="flex-1 rounded-lg bg-[#2563EB] px-3 py-2 text-[13px] font-medium text-white hover:bg-[#1D4ED8]"
                    >
                      试问
                    </button>
                    {s.status === 'published' && (
                      <button
                        onClick={() => router.push(`/skill/${s.id}`)}
                        className="rounded-lg border border-[#E2E8F0] px-3 py-2 text-[13px] text-[#475569] hover:bg-[#F8FAFC]"
                      >
                        打开对话
                      </button>
                    )}
                    {s.status !== 'published' && canAudit && (
                      <button
                        onClick={() => router.push(`/admin/skills/${s.id}/audit`)}
                        className="rounded-lg border border-[#E2E8F0] px-3 py-2 text-[13px] text-[#475569] hover:bg-[#F8FAFC]"
                      >
                        审核发布
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}

        <Pagination
          page={page}
          totalPages={totalPages}
          totalElements={total}
          onPageChange={load}
          loading={loading}
        />
      </div>
    </div>
  );
}
