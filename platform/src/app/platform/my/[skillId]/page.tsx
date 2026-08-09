'use client';

import { useEffect, useState } from 'react';
import { useRouter, useParams, useSearchParams } from 'next/navigation';
import PageHeader from '@/components/ui/PageHeader';
import { listSkillMaterials } from '@/lib/api/materials';
import { getToken } from '@/lib/storage';
import { copyToClipboard } from '@/lib/clipboard';

export default function SkillDetailPage() {
  const { skillId } = useParams<{ skillId: string }>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const initialTab = (searchParams.get('tab') as 'grains' | 'materials' | 'share' | 'report') || 'grains';
  const [tab, setTab] = useState<'grains' | 'materials' | 'share' | 'report'>(initialTab);
  const [skill, setSkill] = useState<{ displayName: string; status: string; shareCode?: string } | null>(null);
  const [shareCopied, setShareCopied] = useState(false);
  const [materialCount, setMaterialCount] = useState(0);

  const authHeaders = (): Record<string, string> => {
    const token = getToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  };

  useEffect(() => {
    fetch(`/api/v1/skills/${skillId}/detail`, { headers: authHeaders() })
      .then((r) => r.json())
      .then((d) => { if (d.code === 200) setSkill(d.data); })
      .catch(() => {});
  }, [skillId]);

  useEffect(() => {
    listSkillMaterials(skillId, 1, 3).then((d) => setMaterialCount(d.totalElements || 0)).catch(() => {});
  }, [skillId]);

  useEffect(() => {
    fetch(`/api/v1/skills/${skillId}/share`, { headers: authHeaders() })
      .then((r) => r.json())
      .then((d) => {
        if (d.code === 200 && skill) setSkill({ ...skill, shareCode: d.data.shareCode });
      })
      .catch(() => {});
  }, [skillId, skill?.displayName]);

  const tabs = [
    { key: 'grains' as const, label: '颗粒' },
    { key: 'materials' as const, label: '素材' },
    { key: 'report' as const, label: '报告' },
    { key: 'share' as const, label: '分享' },
  ];

  return (
    <div className="min-h-screen bg-[#f7f9ff] px-5 py-8" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="max-w-2xl mx-auto">
        <PageHeader
          backTo="/platform/my" backLabel="我的分身"
          title={skill?.displayName || '分身详情'}
          subtitle={skill?.status === 'published' ? '已发布' : skill?.status || ''}
          actions={
            <button onClick={() => router.push(`/skill/${skillId}`)} style={{
              padding: '8px 16px', borderRadius: 8, border: '1px solid #e8ecf4', cursor: 'pointer',
              background: '#fff', color: '#5b6886', fontSize: 13, fontWeight: 500, fontFamily: 'inherit',
              transition: 'all 0.15s',
            }}
              onMouseEnter={e => { e.currentTarget.style.background = '#f5f7fd'; }}
              onMouseLeave={e => { e.currentTarget.style.background = '#fff'; }}
            >
              预览名片
            </button>
          }
          transparent
        />

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
          <div className="space-y-4">
            <div className="bg-white rounded-2xl border border-[#e1e7ff] p-5 shadow-[0_8px_30px_rgba(42,74,177,0.06)]">
              <h3 className="text-sm font-medium text-[#10162f] mb-3">素材管理</h3>
              {materialCount > 0 ? (
                <div className="text-center py-4">
                  <p className="text-2xl font-bold text-[#10162f]">{materialCount}</p>
                  <p className="text-xs text-[#747f9e] mt-1">个素材文件</p>
                </div>
              ) : (
                <div className="text-center py-6">
                  <span className="text-4xl block mb-3">📎</span>
                  <p className="text-sm text-[#747f9e]">还没有上传素材</p>
                  <p className="text-xs text-[#747f9e] mt-1">上传对话记录、经验心得或访谈实录</p>
                </div>
              )}
              <button
                onClick={() => router.push(`/platform/my/${skillId}/materials`)}
                className="mt-3 w-full py-2.5 rounded-full bg-[#2147ff] text-white text-sm font-medium"
              >
                {materialCount > 0 ? '管理全部素材' : '上传素材'}
              </button>
            </div>
          </div>
        )}

        {/* Report Tab */}
        {tab === 'report' && <ReportTab skillId={skillId} />}

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
                      value={`${typeof window !== 'undefined' ? (process.env.NEXT_PUBLIC_SHARE_BASE_URL || window.location.origin) : ''}/s/${skill.shareCode}`}
                      className="flex-1 px-3 py-2 rounded-lg border border-[#dfe6ff] bg-gray-50 text-xs text-[#747f9e]"
                    />
                    <button
                      onClick={async () => {
                        const url = `${process.env.NEXT_PUBLIC_SHARE_BASE_URL || window.location.origin}/s/${skill.shareCode}`;
                        const ok = await copyToClipboard(url);
                        if (ok) {
                          setShareCopied(true);
                          setTimeout(() => setShareCopied(false), 2000);
                        }
                      }}
                      className="px-3 py-2 rounded-lg bg-[#eef2ff] text-[#2147ff] text-xs font-medium"
                    >
                      {shareCopied ? '已复制' : '复制'}
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  onClick={async () => {
                    const r = await fetch(`/api/v1/skills/${skillId}/share`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' } });
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
                  const r = await fetch(`/api/v1/i/${skillId}/share/internal`, { method: 'POST', headers: { Authorization: `Bearer ${getToken()}` } });
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

// ── Report tab sub-component ──

function ReportTab({ skillId }: { skillId: string }) {
  const [report, setReport] = useState<{ id: string; title: string; subtitle?: string; createdAt?: string } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const token = getToken();
    fetch(`/api/v1/reports/by-skill/${encodeURIComponent(skillId)}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
      .then(r => r.json())
      .then(d => {
        if (d.code === 200) setReport(d.data);
        else setError(d.message || '加载失败');
      })
      .catch(() => setError('网络错误'))
      .finally(() => setLoading(false));
  }, [skillId]);

  const handlePreview = async () => {
    try {
      const token = getToken();
      const r = await fetch(`/api/v1/reports/by-skill/${encodeURIComponent(skillId)}/html`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!r.ok) throw new Error('加载失败');
      const html = await r.text();
      const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
      window.open(URL.createObjectURL(blob), '_blank');
    } catch { alert('加载报告失败'); }
  };

  const handleDownload = async () => {
    if (!report?.id) return;
    try {
      const token = getToken();
      const r = await fetch(`/api/v1/reports/${encodeURIComponent(report.id)}/download`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!r.ok) throw new Error('下载失败');
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${report.title || '萃取报告'}.html`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch { alert('下载失败'); }
  };

  if (loading) {
    return <p style={{ textAlign: 'center', padding: '40px 0', fontSize: 14, color: '#94a3b8' }}>加载中...</p>;
  }

  if (error) {
    return (
      <div style={{ textAlign: 'center', padding: '40px 0' }}>
        <div style={{ fontSize: 40, marginBottom: 12 }}>📄</div>
        <p style={{ fontSize: 14, fontWeight: 600, color: '#0f172a', margin: '0 0 4px' }}>报告尚未生成</p>
        <p style={{ fontSize: 13, color: '#94a3b8', margin: 0 }}>
          {error === '报告尚未生成，请等待萃取完成'
            ? 'AI 萃取完成后会自动生成报告'
            : error}
        </p>
      </div>
    );
  }

  return (
    <div style={{ textAlign: 'center', padding: '24px 0' }}>
      <div style={{ fontSize: 40, marginBottom: 12 }}>📊</div>
      <h3 style={{ fontSize: 18, fontWeight: 700, color: '#0f172a', margin: '0 0 4px' }}>
        {report!.title || '萃取报告'}
      </h3>
      {report!.subtitle && (
        <p style={{ fontSize: 13, color: '#475569', margin: '0 0 4px' }}>{report!.subtitle}</p>
      )}
      {report!.createdAt && (
        <p style={{ fontSize: 12, color: '#94a3b8', margin: '0 0 24px' }}>
          {report!.createdAt.replace('T', ' ').substring(0, 19)}
        </p>
      )}

      <div style={{ display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' }}>
        <button onClick={handlePreview} style={{
          padding: '10px 24px', borderRadius: 100, border: 'none', cursor: 'pointer',
          background: '#2563eb', color: '#fff', fontSize: 14, fontWeight: 600,
          fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
          预览报告
        </button>
        <button onClick={handleDownload} style={{
          padding: '10px 24px', borderRadius: 100, cursor: 'pointer',
          border: '1.5px solid #e2e8f0', background: '#fff',
          color: '#475569', fontSize: 14, fontWeight: 600, fontFamily: 'inherit',
          display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          下载报告
        </button>
      </div>
    </div>
  );
}
