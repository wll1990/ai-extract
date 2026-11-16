'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { listSkills, updateSkillStatus, restoreSkill, retrySkillPipeline } from '@/lib/api/skill';
import ShareModal from '@/components/admin/ShareModal';
import ImPublishModal from '@/components/admin/ImPublishModal';
import { listImBots } from '@/lib/api/im';
import {
  SKILL_STATUS_LABELS as STATUS_LABELS,
  SKILL_ACTIVE_STATUSES,
  SKILL_PENDING_STATUSES,
  SKILL_INACTIVE_STATUSES,
} from '@/lib/skill-status';

export default function AdminSkillsPage() {
  const router = useRouter();
  const [skills, setSkills] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [shareOpenId, setShareOpenId] = useState<string | null>(null);
  const [feishuSkill, setFeishuSkill] = useState<{ id: string; name: string; avatar?: string } | null>(null);
  const [feishuBoundIds, setFeishuBoundIds] = useState<Set<string>>(new Set());
  const [error, setError] = useState('');
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);

  const loadFeishuBots = () => {
    listImBots().then(bots => setFeishuBoundIds(new Set(bots.map(b => b.skillId)))).catch(() => {});
  };

  const loadData = () => {
    setError('');
    listSkills(page, 20)
      .then(d => {
        setSkills(d.content);
        setTotalPages(Math.max(1, d.totalPages || 1));
      })
      .catch((e: unknown) => {
        setSkills([]);
        setError(e instanceof Error && e.message ? e.message : '分身列表加载失败，请稍后重试');
      }).finally(() => setLoading(false));
  };

  useEffect(() => { loadData(); }, [page]);
  useEffect(() => { loadFeishuBots(); }, []);

  const updateStatus = async (id: string, status: string) => {
    try {
      await updateSkillStatus(id, status);
      loadData();
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败，请重试');
    }
  };

  if (loading) return <LoadingSpinner />;

  const activeSkills = skills.filter((s: any) => SKILL_ACTIVE_STATUSES.includes(s.status));
  const pendingSkills = skills.filter((s: any) => SKILL_PENDING_STATUSES.includes(s.status));
  // 停用与驳回必须分开：discarded=审核驳回，paused=上线后停用
  const inactiveSkills = skills.filter((s: any) => SKILL_INACTIVE_STATUSES.includes(s.status));

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

        {error && (
          <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600 flex items-center justify-between">
            <span>{error}</span>
            <button onClick={loadData} className="text-xs font-medium underline">重试</button>
          </div>
        )}

        {/* 空状态 */}
        {!error && skills.length === 0 && (
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
                      <button onClick={() => setShareOpenId(shareOpenId === s.id ? null : s.id)} data-tour="admin-skills-share" className="rounded-lg px-3 py-1.5 text-xs font-medium text-primary hover:bg-primary-light">🔗 共享</button>
                      {shareOpenId === s.id && (
                        <ShareModal
                          skillId={s.id}
                          ownerName={s.ownerName || '分身'}
                          onClose={() => setShareOpenId(null)}
                        />
                      )}
                    </div>
                    <button onClick={() => router.push(`/skill/${s.id}`)} className="rounded-lg px-3 py-1.5 text-xs text-primary hover:bg-primary-light">查看</button>
                    {/* 组织分身 spaceId=null，审核/素材接口必然 403，直接不展示入口 */}
                    {s.type !== 'organization' && (
                      <>
                        <button onClick={() => router.push(`/admin/skills/${s.id}/audit`)} className="rounded-lg px-3 py-1.5 text-xs text-primary hover:bg-primary-light">审核</button>
                        <button onClick={() => router.push(`/admin/skills/${s.id}/materials`)} className="rounded-lg px-3 py-1.5 text-xs text-muted-foreground hover:bg-primary-light">素材</button>
                      </>
                    )}
                    {feishuBoundIds.has(s.id) ? (
                      <span className="self-center text-[10px] px-1.5 py-0.5 rounded-full bg-indigo-100 text-indigo-700">📡 已接入 IM</span>
                    ) : (
                      <button onClick={() => setFeishuSkill({ id: s.id, name: s.ownerName || s.displayName || '分身', avatar: s.avatarUrl })} className="rounded-lg px-3 py-1.5 text-xs text-indigo-600 hover:bg-primary-light">📡 发布到 IM</button>
                    )}
                    <button onClick={() => { if (confirm('确认停用该分身？停用后员工将看不到它，可随时重新激活。')) updateStatus(s.id, 'paused'); }} className="rounded-lg px-3 py-1.5 text-xs text-danger hover:bg-danger-bg">停用</button>
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
                  <div className="flex items-center gap-2">
                    {s.status === 'generating' && (
                      <button
                        onClick={async () => {
                          try {
                            const r = await retrySkillPipeline(s.id);
                            alert(`已重新排队 ${r.reset} 个素材，稍后刷新查看进度`);
                            loadData();
                          } catch (e) {
                            alert(e instanceof Error ? e.message : '重试失败');
                          }
                        }}
                        className="rounded-lg px-3 py-1.5 text-xs text-orange hover:bg-orange/10"
                      >
                        重新萃取
                      </button>
                    )}
                    <button onClick={() => router.push(`/admin/skills/${s.id}/audit`)} className="rounded-lg px-3 py-1.5 text-xs text-primary hover:bg-primary-light">去审核 →</button>
                  </div>
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
                  <button
                    onClick={async () => {
                      try {
                        if (s.status === 'discarded') {
                          await restoreSkill(s.id);
                        } else if (s.status === 'paused') {
                          // 暂停的分身恢复上线，不应被送去重新审核
                          await updateStatus(s.id, 'published');
                        } else {
                          await updateStatus(s.id, 'reviewing');
                        }
                        loadData();
                      } catch (e) {
                        alert(e instanceof Error ? e.message : '操作失败');
                      }
                    }}
                    className="rounded-lg px-3 py-1.5 text-xs text-success hover:bg-success-bg">
                    {s.status === 'discarded' ? '恢复' : '激活'}
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}

        {totalPages > 1 && (
          <div className="mb-6 flex items-center justify-center gap-3 text-sm">
            <button disabled={page <= 1} onClick={() => setPage(p => p - 1)}
              className="rounded-md border border-border px-3 py-1.5 disabled:opacity-40 hover:bg-primary-light">上一页</button>
            <span className="text-muted-foreground">{page} / {totalPages}</span>
            <button disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}
              className="rounded-md border border-border px-3 py-1.5 disabled:opacity-40 hover:bg-primary-light">下一页</button>
          </div>
        )}

        {/* Feishu publish modal */}
        {feishuSkill && (
          <ImPublishModal
            skillId={feishuSkill.id}
            skillName={feishuSkill.name}
            skillAvatar={feishuSkill.avatar}
            onClose={() => setFeishuSkill(null)}
            onPublished={() => { loadData(); loadFeishuBots(); }}
          />
        )}
      </div>
    </div>
  );
}
