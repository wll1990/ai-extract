'use client';

import { useEffect, useState, use } from 'react';
import { useRouter } from 'next/navigation';

export default function SkillDetailPage({ params }: { params: Promise<{ skillId: string }> }) {
  const { skillId } = use(params);
  const router = useRouter();
  const [tab, setTab] = useState<'grains' | 'materials' | 'share'>('grains');
  const [skill, setSkill] = useState<{ displayName: string; status: string; shareCode?: string } | null>(null);

  useEffect(() => {
    fetch(`/api/v1/skills/${skillId}/detail`, { credentials: 'include' })
      .then((r) => r.json())
      .then((d) => { if (d.code === 200) setSkill(d.data); })
      .catch(() => {});
  }, [skillId]);

  useEffect(() => {
    fetch(`/api/v1/skills/${skillId}/share`, { credentials: 'include' })
      .then((r) => r.json())
      .then((d) => {
        if (d.code === 200 && skill) setSkill({ ...skill, shareCode: d.data.shareCode });
      })
      .catch(() => {});
  }, [skillId, skill?.displayName]);

  const tabs = [
    { key: 'grains' as const, label: '颗粒' },
    { key: 'materials' as const, label: '素材' },
    { key: 'share' as const, label: '分享' },
  ];

  return (
    <div className="min-h-screen bg-[#f7f9ff] px-5 py-8" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="max-w-2xl mx-auto">
        <button onClick={() => router.back()} className="text-sm text-[#747f9e] mb-4 block">← 返回</button>
        <h1 className="text-xl font-bold text-[#10162f] mb-1">{skill?.displayName || '分身'}</h1>
        <p className="text-sm text-[#747f9e] mb-6">{skill?.status === 'published' ? '已发布' : skill?.status}</p>

        {/* Tabs */}
        <div className="flex gap-1 bg-white rounded-xl border border-[#dfe6ff] p-1 mb-6">
          {tabs.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
                tab === t.key ? 'bg-[#2147ff] text-white' : 'text-[#747f9e]'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        {/* Grains Tab */}
        {tab === 'grains' && (
          <div className="text-center py-12">
            <span className="text-4xl mb-3 block">📋</span>
            <p className="text-sm text-[#747f9e] mb-4">颗粒管理在审核页面操作</p>
            <button
              onClick={() => router.push(`/platform/my/${skillId}/audit`)}
              className="px-5 py-2 rounded-full bg-[#2147ff] text-white text-sm font-medium"
            >
              去审核
            </button>
          </div>
        )}

        {/* Materials Tab */}
        {tab === 'materials' && (
          <div className="text-center py-12">
            <span className="text-4xl mb-3 block">📎</span>
            <p className="text-sm text-[#747f9e]">上传素材功能即将上线</p>
            <p className="text-xs text-[#747f9e] mt-1">支持录音、文档、图片</p>
          </div>
        )}

        {/* Share Tab */}
        {tab === 'share' && (
          <div className="space-y-4">
            <div className="bg-white rounded-2xl border border-[#e1e7ff] p-5 shadow-[0_8px_30px_rgba(42,74,177,0.06)]">
              <h3 className="text-sm font-medium text-[#10162f] mb-3">对外分享</h3>
              {skill?.shareCode ? (
                <div className="space-y-3">
                  <div className="flex items-center gap-2">
                    <input
                      type="text" readOnly
                      value={`${typeof window !== 'undefined' ? window.location.origin : ''}/s/${skill.shareCode}`}
                      className="flex-1 px-3 py-2 rounded-lg border border-[#dfe6ff] bg-gray-50 text-xs text-[#747f9e]"
                    />
                    <button
                      onClick={() => navigator.clipboard.writeText(`${window.location.origin}/s/${skill.shareCode}`)}
                      className="px-3 py-2 rounded-lg bg-[#eef2ff] text-[#2147ff] text-xs font-medium"
                    >
                      复制
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  onClick={async () => {
                    const r = await fetch(`/api/v1/skills/${skillId}/share`, { method: 'POST', credentials: 'include' });
                    const d = await r.json();
                    if (d.code === 200 && skill) setSkill({ ...skill, shareCode: d.data.shareCode });
                  }}
                  className="px-4 py-2 rounded-full bg-[#2147ff] text-white text-sm font-medium"
                >
                  生成分享链接
                </button>
              )}
            </div>

            <div className="bg-white rounded-2xl border border-[#e1e7ff] p-5 shadow-[0_8px_30px_rgba(42,74,177,0.06)]">
              <h3 className="text-sm font-medium text-[#10162f] mb-3">对内分享</h3>
              <p className="text-xs text-[#747f9e] mb-3">平台登录用户可访问</p>
              <button
                onClick={async () => {
                  const r = await fetch(`/api/v1/i/${skillId}/share/internal`, { method: 'POST', credentials: 'include' });
                  const d = await r.json();
                  if (d.code === 200) {
                    alert(`对内分享码：${d.data.shareCode}`);
                  }
                }}
                className="px-4 py-2 rounded-full border border-[#cdd7ff] text-[#2147ff] text-sm font-medium"
              >
                生成对内分享
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
