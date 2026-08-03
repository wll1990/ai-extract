'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { listSkills, updateSkillStatus } from '@/lib/api/skill';
import ShareModal from '@/components/admin/ShareModal';

export default function AdminSkillsPage() {
  const router = useRouter();
  const [skills, setSkills] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [shareOpenId, setShareOpenId] = useState<string | null>(null);

  const loadData = () => {
    listSkills(1, 50)
      .then(d => setSkills(d.content))
      .catch(() => {}).finally(() => setLoading(false));
  };

  useEffect(() => { loadData(); }, []);

  const updateStatus = async (id: string, status: string) => {
    await updateSkillStatus(id, status);
    loadData();
  };

  if (loading) return <LoadingSpinner />;

  const STATUS_LABELS: Record<string, string> = {
    generating: '萃取中...', reviewing: '待审核', published: '已发布', discarded: '已驳回'
  };
  const activeSkills = skills.filter((s: any) => s.status === 'published');
  const pendingSkills = skills.filter((s: any) => s.status === 'generating' || s.status === 'reviewing');
  const inactiveSkills = skills.filter((s: any) => s.status === 'discarded');

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold text-foreground">分身管理</h1>
          <button onClick={() => router.push('/admin/skills/upload')}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-primary-hover">
            + 上传素材
          </button>
        </div>

        {/* 空状态 */}
        {skills.length === 0 && (
          <div className="py-16 text-center">
            <div className="text-5xl mb-4">🤖</div>
            <p className="text-lg font-medium text-foreground">暂无 AI 分身</p>
            <p className="mt-2 text-sm text-muted-foreground">
              完成一次访谈萃取后，AI 分身将自动生成并出现在这里
            </p>
            <button
              onClick={() => router.push('/interview/create')}
              className="mt-6 rounded-lg bg-primary px-5 py-2.5 text-sm font-semibold text-white hover:bg-primary-hover"
            >
              创建访谈 →
            </button>
          </div>
        )}

        {/* 激活分身 */}
        {activeSkills.length > 0 && (
          <div className="mb-6">
            <h2 className="mb-3 text-sm font-semibold text-muted-foreground">● 已激活 · {activeSkills.length}位</h2>
            <div className="space-y-2">
              {activeSkills.map((s: any) => (
                <div key={s.id} className="flex items-center justify-between rounded-lg bg-surface-2 p-4 shadow-sm">
                  <div className="flex items-center gap-4">
                    <div className="flex h-10 w-10 items-center justify-center rounded-full bg-primary text-white font-bold text-sm">{(s.ownerName || '?')[0]}</div>
                    <div>
                      <p className="font-medium text-foreground">{s.ownerName}</p>
                      <p className="text-xs text-muted-foreground-2">{s.ownerTitle} · {s.grainCount || 0}条锦囊</p>
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <div style={{ position: 'relative' }}>
                      <button onClick={() => setShareOpenId(shareOpenId === s.id ? null : s.id)} className="rounded-lg px-3 py-1.5 text-xs font-medium text-primary hover:bg-primary-light">🔗 共享</button>
                      {shareOpenId === s.id && (
                        <ShareModal
                          skillId={s.id}
                          ownerName={s.ownerName || '分身'}
                          onClose={() => setShareOpenId(null)}
                        />
                      )}
                    </div>
                    <button onClick={() => router.push(`/skill/${s.id}`)} className="rounded-lg px-3 py-1.5 text-xs text-primary hover:bg-primary-light">查看</button>
                    <button onClick={() => router.push(`/admin/skills/${s.id}/audit`)} className="rounded-lg px-3 py-1.5 text-xs text-primary hover:bg-primary-light">审核</button>
                    <button onClick={() => router.push(`/admin/skills/${s.id}/materials`)} className="rounded-lg px-3 py-1.5 text-xs text-muted-foreground hover:bg-primary-light">素材</button>
                    <button onClick={() => updateStatus(s.id, 'failed')} className="rounded-lg px-3 py-1.5 text-xs text-danger hover:bg-danger-bg">停用</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 待审核/测试分身 */}
        {pendingSkills.length > 0 && (
          <div className="mb-6">
            <h2 className="mb-3 text-sm font-semibold text-orange">◉ 审核/测试中 · {pendingSkills.length}位</h2>
            <div className="space-y-2">
              {pendingSkills.map((s: any) => (
                <div key={s.id} className="flex items-center justify-between rounded-lg bg-surface-2 p-4 shadow-sm">
                  <div className="flex items-center gap-4">
                    <div className="flex h-10 w-10 items-center justify-center rounded-full bg-orange/10 text-orange font-bold text-sm">{(s.ownerName || '?')[0]}</div>
                    <div>
                      <p className="font-medium text-foreground">{s.ownerName}</p>
                      <p className="text-xs text-orange">{STATUS_LABELS[s.status] || s.status}</p>
                    </div>
                  </div>
                  <button onClick={() => router.push(`/admin/skills/${s.id}/audit`)} className="rounded-lg px-3 py-1.5 text-xs text-primary hover:bg-primary-light">去审核 →</button>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 未激活分身 */}
        {inactiveSkills.length > 0 && (
          <div>
            <h2 className="mb-3 text-sm font-semibold text-muted-foreground-2">生成中/未激活 · {inactiveSkills.length}位</h2>
            <div className="space-y-2">
              {inactiveSkills.map((s: any) => (
                <div key={s.id} className="flex items-center justify-between rounded-lg bg-surface-2 p-4 shadow-sm opacity-60">
                  <div className="flex items-center gap-4">
                    <div className="flex h-10 w-10 items-center justify-center rounded-full bg-border text-muted-foreground-2 font-bold text-sm">{(s.ownerName || '?')[0]}</div>
                    <div>
                      <p className="font-medium text-muted-foreground-2">{s.ownerName}</p>
                      <p className="text-xs text-muted-foreground-2">{STATUS_LABELS[s.status] || s.status}</p>
                    </div>
                  </div>
                  <button onClick={() => updateStatus(s.id, 'active')} className="rounded-lg px-3 py-1.5 text-xs text-success hover:bg-success-bg">激活</button>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
