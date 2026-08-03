'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getUser } from '@/lib/storage';
import { PortraitCard, StatBadge } from '@aiextract/shared-ui';
import { OrgSkillCard } from '@/components/skill/OrgSkillCard';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { listSkills, type SkillInfo } from '@/lib/api/skill';

type MainTab = 'published' | 'mine';
type SubTab = 'individual' | 'org';

export default function SkillsGalleryPage() {
  const router = useRouter();
  const [tab, setTab] = useState<MainTab>('published');
  const [subTab, setSubTab] = useState<SubTab>('individual');
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [upgradeNudge, setUpgradeNudge] = useState(false);
  const userId = (getUser() as any)?.id as string | undefined;

  const load = (p: number) => {
    setLoading(true);
    setPage(p);
    const status = tab === 'published' ? 'published' : undefined;
    const ownerId = tab === 'mine' ? userId : undefined;
    const type = subTab === 'org' ? 'organization' : 'individual';
    listSkills(p, 9, status, ownerId, type)
      .then(d => {
        setSkills(d.content);
        setTotalPages(d.totalPages);
        setUpgradeNudge((d as any).upgradeNudge === true);
      })
      .catch(e => console.error('加载分身列表失败:', e)).finally(() => setLoading(false));
  };

  useEffect(() => { load(1); }, [tab, subTab]);

  if (loading) return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <LoadingSpinner fullScreen={false} />
    </div>
  );

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="mb-2 text-2xl font-bold text-foreground">分身广场</h1>

        {/* Top-level Tab 切换 */}
        <div className="mb-3 flex rounded-lg bg-primary-light p-0.5 w-fit">
          <button onClick={() => { setTab('published'); setPage(1); }}
            className={`rounded-md px-4 py-1.5 text-sm font-medium ${tab === 'published' ? 'bg-surface-2 text-foreground shadow-sm' : 'text-muted-foreground'}`}>
            已发布
          </button>
          <button onClick={() => { setTab('mine'); setPage(1); }}
            className={`rounded-md px-4 py-1.5 text-sm font-medium ${tab === 'mine' ? 'bg-surface-2 text-foreground shadow-sm' : 'text-muted-foreground'}`}>
            我的分身
          </button>
        </div>

        {/* Sub-tabs — only under "已发布" */}
        {tab === 'published' && (
          <div className="mb-4 flex gap-1">
            <button onClick={() => setSubTab('individual')}
              className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                subTab === 'individual' ? 'bg-foreground text-white' : 'bg-gray-100 text-muted-foreground hover:bg-gray-200'
              }`}>
              个人
            </button>
            <button onClick={() => setSubTab('org')}
              className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                subTab === 'org' ? 'bg-foreground text-white' : 'bg-gray-100 text-muted-foreground hover:bg-gray-200'
              }`}>
              组织
            </button>
          </div>
        )}

        <p className="mb-6 text-sm text-muted-foreground">
          {tab === 'mine'
            ? '你创建的分身（含审核中的）'
            : subTab === 'org'
              ? '部门团队知识库，聚合多位销冠的集体经验'
              : '选择一位销冠，向他的 AI 分身请教或练习'}
        </p>

        {/* Upgrade nudge — only on individual sub-tab */}
        {upgradeNudge && tab === 'published' && subTab === 'individual' && (
          <div className="mb-4 rounded-xl bg-gradient-to-r from-indigo-50 to-purple-50 border border-indigo-200 p-4 flex items-center gap-3">
            <span className="text-2xl">🎯</span>
            <div className="flex-1">
              <p className="text-sm font-medium text-indigo-800">你已有 3 位以上销冠的 AI 分身</p>
              <p className="text-xs text-indigo-600">创建一个「组织分身」，让新人一次向多位销冠同时请教</p>
            </div>
            <button onClick={() => setSubTab('org')}
              className="rounded-lg bg-indigo-600 text-white px-3 py-1.5 text-xs font-medium whitespace-nowrap">
              查看组织
            </button>
          </div>
        )}

        {skills.length === 0 && (
          <div className="rounded-2xl bg-surface-2 p-12 text-center shadow-sm">
            <span className="text-4xl">{subTab === 'org' ? '🏢' : '🤖'}</span>
            <p className="mt-4 text-muted-foreground">
              {subTab === 'org' ? '暂无组织分身' : '暂无已发布的 AI 分身'}
            </p>
            <p className="text-sm text-muted-foreground-2">
              {subTab === 'org' ? '管理员创建组织分身后，将在此展示' : '管理员审核通过后，分身将在此展示'}
            </p>
          </div>
        )}

        <div className="grid gap-4 md:grid-cols-3">
          {skills.map(s => (
            subTab === 'org' ? (
              <button key={s.id}
                onClick={() => router.push(`/skill/${s.id}?spaceId=&name=${encodeURIComponent(s.displayName || s.ownerName || '')}&title=${encodeURIComponent(s.ownerTitle || '')}`)}
                className="block w-full">
                <OrgSkillCard skill={s} />
              </button>
            ) : (
              <button key={s.id}
                onClick={() => router.push(`/skill/${s.id}?spaceId=${s.spaceId || ''}&name=${encodeURIComponent(s.ownerName || '')}&title=${encodeURIComponent(s.ownerTitle || '')}`)}
                className="rounded-2xl bg-surface-2 p-6 text-left shadow-sm hover:shadow-md transition-all hover:-translate-y-0.5">
                <div className="flex items-center gap-4">
                  <div style={{ width: 72 }}>
                    <PortraitCard src={s.avatarUrl} alt={s.ownerName} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <h3 className="font-semibold text-foreground truncate">{s.ownerName}</h3>
                    <p className="text-xs text-muted-foreground-2">{s.ownerTitle || '资深销冠'}</p>
                    {s.stats && s.stats.conversationCount > 0 && (
                      <div className="mt-2 flex items-center justify-center gap-2 rounded-lg px-2 py-1"
                        style={{ background: '#f8faff' }}>
                        <StatBadge icon="💬" value={s.stats.conversationCount} label="次" size="sm" />
                        {s.stats.satisfactionRate > 0 && (
                          <><span className="text-[#d4d8e0] text-xs">·</span>
                          <StatBadge icon="👍" value={s.stats.satisfactionRate} label="%" size="sm" /></>
                        )}
                        {s.stats.userCount > 0 && (
                          <><span className="text-[#d4d8e0] text-xs">·</span>
                          <StatBadge icon="👤" value={s.stats.userCount} label="人" size="sm" /></>
                        )}
                      </div>
                    )}
                    {s.tags && s.tags.length > 0 && (
                      <div className="mt-2 flex flex-wrap justify-center gap-1">
                        {s.tags.slice(0, 3).map(tag => (
                          <span key={tag} className="rounded-full bg-[#eef2ff] px-2 py-0.5 text-[10px] text-[#475569]">
                            {tag}
                          </span>
                        ))}
                      </div>
                    )}
                    <div className="mt-1 flex items-center gap-2">
                      <span className="text-xs text-muted-foreground-2">{s.grainCount || 0} 条经验</span>
                    </div>
                  </div>
                </div>
              </button>
            )
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
