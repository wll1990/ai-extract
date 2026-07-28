'use client';

import { useEffect, useState, use } from 'react';
import { useRouter } from 'next/navigation';

interface GrainItem {
  id: string;
  sceneTag: string;
  sceneDescription: string;
  expertThought: string;
  standardScript: string;
  commonMistakes: string;
  status: string;
}

export default function AuditPage({ params }: { params: Promise<{ skillId: string }> }) {
  const { skillId } = use(params);
  const router = useRouter();
  const [grains, setGrains] = useState<GrainItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [publishing, setPublishing] = useState(false);

  const fetchGrains = async () => {
    setLoading(true);
    try {
      const r = await fetch(`/api/v1/skills/${skillId}/grains`, { credentials: 'include' });
      const d = await r.json();
      if (d.code === 200) {
        setGrains(d.data || []);
      } else if (d.code === 403) {
        setError('无权访问此分身');
      } else {
        throw new Error(d.message || '加载失败');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchGrains(); }, [skillId]);

  const handlePublish = async () => {
    setPublishing(true);
    try {
      const r = await fetch(`/api/v1/skills/${skillId}/status`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ status: 'published' }),
      });
      const d = await r.json();
      if (d.code === 200) {
        router.push('/platform/my');
      } else {
        setError(d.message || '发布失败');
      }
    } catch {
      setError('网络错误');
    } finally {
      setPublishing(false);
    }
  };

  if (loading) return <div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center text-sm text-[#747f9e]">加载中...</div>;

  return (
    <div className="min-h-screen bg-[#f7f9ff] px-5 py-8" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="max-w-2xl mx-auto">
        <button onClick={() => router.back()} className="text-sm text-[#747f9e] mb-4 block">← 返回</button>
        <h1 className="text-xl font-bold text-[#10162f] mb-1">审核颗粒</h1>
        <p className="text-sm text-[#747f9e] mb-6">访谈产生 {grains.length} 条经验颗粒</p>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-xl bg-red-50 border border-red-200 text-sm text-red-600 flex justify-between">
            <span>{error}</span>
            <button onClick={fetchGrains} className="text-red-600 font-medium">重试</button>
          </div>
        )}

        {grains.length === 0 && !error && (
          <div className="text-center py-12">
            <span className="text-4xl mb-3 block">📋</span>
            <p className="text-sm text-[#747f9e]">暂无颗粒，AI 可能还在分析中</p>
          </div>
        )}

        <div className="space-y-3 mb-8">
          {grains.map((g) => (
            <div key={g.id} className="bg-white rounded-2xl border border-[#e1e7ff] p-4 shadow-[0_8px_30px_rgba(42,74,177,0.06)]">
              <div className="flex items-center gap-2 mb-2">
                <span className="px-2 py-0.5 rounded-full bg-[#eef2ff] text-[#3150db] text-xs font-medium">{g.sceneTag || '通用'}</span>
              </div>
              {g.expertThought && (
                <div className="mb-2">
                  <span className="text-xs text-[#747f9e]">思路：</span>
                  <span className="text-sm text-[#10162f]">{g.expertThought}</span>
                </div>
              )}
              {g.standardScript && (
                <div className="mb-2">
                  <span className="text-xs text-[#747f9e]">话术：</span>
                  <span className="text-sm text-[#10162f] italic">"{g.standardScript}"</span>
                </div>
              )}
              {g.commonMistakes && (
                <div className="text-xs text-[#747f9e]">避坑：{g.commonMistakes}</div>
              )}
            </div>
          ))}
        </div>

        {grains.length > 0 && (
          <div className="flex gap-3">
            <button
              onClick={handlePublish}
              disabled={publishing}
              className="flex-1 py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40 hover:translate-y-[-1px] transition-transform"
            >
              {publishing ? '发布中...' : '一键发布'}
            </button>
            <button
              onClick={() => router.push('/platform/my')}
              className="px-6 py-3 rounded-full border border-[#cdd7ff] text-[#2147ff] text-sm font-medium"
            >
              暂存草稿
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
