'use client';

import { useEffect, useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import EmptyState from '@/components/ui/EmptyState';

interface SkillItem {
  id: string;
  spaceId: string;
  status: string;
  displayName: string;
  ownerTitle: string;
  avatarUrl: string | null;
  grainCount: number;
  domain: string;
  tags: string[];
}

export default function PlatformMyPage() {
  const router = useRouter();
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchSkills = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await fetch('/api/v1/skills/list?size=50', { credentials: 'include' });
      const d = await r.json();
      if (d.code === 200) {
        setSkills(d.data?.content || []);
      } else {
        setError(d.message || '加载失败');
      }
    } catch {
      setError('网络错误，请重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchSkills(); }, [fetchSkills]);

  const generating = skills.filter((s) => s.status === 'generating');
  const draft = skills.filter((s) => s.status === 'draft');
  const published = skills.filter((s) => s.status === 'published');

  if (loading) {
    return (
      <div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center">
        <div className="text-sm text-[#747f9e]">加载中...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#f7f9ff] px-5 py-8" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="max-w-2xl mx-auto">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-xl font-bold text-[#10162f]">我的分身</h1>
          <div className="flex gap-2">
            {skills.length > 0 && (
              <button
                onClick={() => router.push(`/platform/my/${skills[0].id}/materials`)}
                className="px-4 py-2 rounded-full border border-[#cdd7ff] text-[#2147ff] text-sm font-medium hover:bg-[#eef2ff] transition-colors"
              >
                + 上传素材
              </button>
            )}
            <button
              onClick={() => router.push('/h5/interview/start')}
              className="px-4 py-2 rounded-full bg-[#2147ff] text-white text-sm font-medium"
            >
              + 开始萃取
            </button>
          </div>
        </div>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-xl bg-red-50 border border-red-200 text-sm text-red-600 flex justify-between items-center">
            <span>{error}</span>
            <button onClick={fetchSkills} className="text-red-600 font-medium">重试</button>
          </div>
        )}

        {!error && skills.length === 0 && (
          <EmptyState
            icon="🧠"
            title="还没有分身"
            description="开始你的第一次经验萃取，AI 会帮你把经验变成可对话的分身"
            action={{ label: '开始萃取', onClick: () => router.push('/h5/interview/start') }}
          />
        )}

        {generating.length > 0 && (
          <section className="mb-6">
            <h2 className="text-sm font-medium text-[#747f9e] mb-3">萃取中</h2>
            {generating.map((s) => (
              <div key={s.id} className="bg-white rounded-2xl border border-[#e1e7ff] p-4 mb-3 opacity-60">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-gray-200 animate-pulse" />
                  <div>
                    <div className="text-sm font-medium text-[#10162f]">{s.displayName || '萃取中...'}</div>
                    <div className="text-xs text-[#747f9e]">预计 2-3 分钟</div>
                  </div>
                </div>
              </div>
            ))}
          </section>
        )}

        {draft.length > 0 && (
          <section className="mb-6">
            <h2 className="text-sm font-medium text-[#747f9e] mb-3">待审核</h2>
            {draft.map((s) => (
              <div key={s.id} className="bg-white rounded-2xl border border-[#e1e7ff] p-4 mb-3 shadow-[0_8px_30px_rgba(42,74,177,0.06)]">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-full bg-gradient-to-br from-[#2147ff] to-[#ff4d5f] flex items-center justify-center text-white text-sm font-bold">
                      {(s.displayName || '?').charAt(0)}
                    </div>
                    <div>
                      <div className="text-sm font-medium text-[#10162f]">{s.displayName}</div>
                      {s.ownerTitle && <div className="text-xs text-[#747f9e]">{s.ownerTitle}</div>}
                      <div className="text-xs text-[#747f9e] mt-0.5">{s.grainCount} 条颗粒</div>
                    </div>
                  </div>
                  <button
                    onClick={() => router.push(`/platform/my/${s.id}/audit`)}
                    className="px-4 py-1.5 rounded-full bg-[#2147ff] text-white text-xs font-medium"
                  >
                    审核发布
                  </button>
                </div>
              </div>
            ))}
          </section>
        )}

        {published.length > 0 && (
          <section>
            <h2 className="text-sm font-medium text-[#747f9e] mb-3">已发布</h2>
            {published.map((s) => (
              <div key={s.id} className="bg-white rounded-2xl border border-[#e1e7ff] p-4 mb-3 shadow-[0_8px_30px_rgba(42,74,177,0.06)] cursor-pointer hover:border-[#2147ff] transition-colors"
                onClick={() => router.push(`/platform/my/${s.id}`)}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-full bg-gradient-to-br from-[#2147ff] to-[#ff4d5f] flex items-center justify-center text-white text-sm font-bold">
                      {(s.displayName || '?').charAt(0)}
                    </div>
                    <div>
                      <div className="text-sm font-medium text-[#10162f]">{s.displayName}</div>
                      {s.ownerTitle && <div className="text-xs text-[#747f9e]">{s.ownerTitle}</div>}
                      <div className="text-xs text-[#747f9e] mt-0.5">{s.grainCount} 条颗粒 · 已发布</div>
                    </div>
                  </div>
                  <span className="text-[#747f9e]">→</span>
                </div>
              </div>
            ))}
          </section>
        )}
      </div>
    </div>
  );
}
