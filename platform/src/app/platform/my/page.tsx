'use client';

import { useEffect, useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import PlatformTopBar from '@/components/ui/PlatformTopBar';
import { getToken } from '@/lib/storage';
import { copyToClipboard } from '@/lib/clipboard';

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
  shareCode?: string;
}

// ── Design tokens — neutral-first, accent-only-for-CTA ──

const C = {
  pageBg: '#f8fafc',
  cardBg: '#ffffff',
  cardBorder: '#e2e8f0',
  cardBorderHover: '#cbd5e1',

  text: '#0f172a',
  textMid: '#475569',
  textLow: '#94a3b8',

  accent: '#2563eb',
  accentHover: '#1d4ed8',
  accentBg: '#eff6ff',

  green: '#16a34a', greenBg: '#f0fdf4',
  orange: '#d97706', orangeBg: '#fffbeb',
  red: '#dc2626', redBg: '#fef2f2',

  shadowSm: '0 1px 2px rgba(0,0,0,0.04)',
  shadowMd: '0 4px 12px rgba(0,0,0,0.06)',
  shadowLg: '0 12px 32px rgba(0,0,0,0.08)',
};

const transition = 'all 0.2s ease';

// ── Helpers ──

function statusLabel(status: string): string {
  switch (status) {
    case 'generating': return '萃取中';
    case 'reviewing': case 'draft': return '待审核';
    case 'published': return '已发布';
    default: return status;
  }
}

function statusColor(status: string): { color: string; bg: string; dot: string } {
  switch (status) {
    case 'generating': return { color: C.orange, bg: C.orangeBg, dot: C.orange };
    case 'reviewing': case 'draft': return { color: C.accent, bg: C.accentBg, dot: C.accent };
    case 'published': return { color: C.green, bg: C.greenBg, dot: C.green };
    default: return { color: C.textLow, bg: '#f1f5f9', dot: C.textLow };
  }
}

function domainLabel(domain: string): string {
  if (!domain) return '';
  const parts = domain.split('.');
  return parts[parts.length - 1] || domain;
}

// ── Component ──

export default function PlatformMyPage() {
  const router = useRouter();
  const [skill, setSkill] = useState<SkillItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [inviteUrl, setInviteUrl] = useState('');
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [inviteCopied, setInviteCopied] = useState(false);

  const authHeaders = useCallback((): Record<string, string> => {
    const token = getToken();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;
    return headers;
  }, []);

  const fetchSkill = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await fetch('/api/v1/skills/my?page=1&size=1', { headers: authHeaders() });
      const d = await r.json();
      if (d.code === 200) {
        const skills: SkillItem[] = d.data?.content || [];
        setSkill(skills[0] || null);
      } else {
        setError(d.message || '加载失败');
      }
    } catch {
      setError('网络错误，请重试');
    } finally {
      setLoading(false);
    }
  }, [authHeaders]);

  useEffect(() => { fetchSkill(); }, [fetchSkill]);

  const handleInvite = async () => {
    try {
      const r = await fetch('/api/v1/interviews/invite', { method: 'POST', headers: authHeaders(), body: JSON.stringify({}) });
      const d = await r.json();
      if (d.code === 200) {
        const base = process.env.NEXT_PUBLIC_SHARE_BASE_URL || window.location.origin;
        setInviteUrl(base + '/h5/interview/m/' + encodeURIComponent(d.data.inviteCode));
        setShowInviteModal(true);
        setInviteCopied(false);
      } else { alert(d.message || '生成失败'); }
    } catch { alert('网络错误'); }
  };

  const st = skill ? statusColor(skill.status) : { color: C.textLow, bg: '#f1f5f9', dot: C.textLow };
  const stLabel = skill ? statusLabel(skill.status) : '';

  // ═══ Loading ═══
  if (loading) {
    return (
      <div style={{ minHeight: '100vh', background: C.pageBg }}>
        <PlatformTopBar backTo="/discover" backLabel="发现专家" />
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', paddingTop: 120 }}>
          <div style={{ textAlign: 'center' }}>
            <div style={{
              width: 48, height: 48, borderRadius: 12, margin: '0 auto 20px',
              background: `linear-gradient(135deg, ${C.accent}, #6366f1)`,
              animation: 'pulse 2s ease-in-out infinite',
            }} />
            <p style={{ fontSize: 14, color: C.textLow }}>加载中...</p>
          </div>
        </div>
        <style>{`@keyframes pulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.5;transform:scale(.92)}}`}</style>
      </div>
    );
  }

  // ═══ Empty state ═══
  if (!skill) {
    return (
      <div style={{ minHeight: '100vh', background: C.pageBg }}>
        <PlatformTopBar backTo="/discover" backLabel="发现专家" />

        {error && (
          <div style={{ maxWidth: 800, margin: '20px auto 0', padding: '0 20px' }}>
            <div style={{
              padding: '12px 16px', borderRadius: 12, fontSize: 13,
              background: C.redBg, border: `1px solid #fecaca`, color: C.red,
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
              <span>{error}</span>
              <button onClick={fetchSkill} style={{ background: 'none', border: 'none', cursor: 'pointer', color: C.red, fontWeight: 600, fontSize: 12, fontFamily: 'inherit' }}>重试</button>
            </div>
          </div>
        )}

        <div style={{ maxWidth: 560, margin: '0 auto', padding: '80px 20px', textAlign: 'center' }}>
          <div style={{ fontSize: 56, marginBottom: 24 }}>🧠</div>
          <h1 style={{ fontSize: 24, fontWeight: 700, color: C.text, margin: '0 0 10px', letterSpacing: '-0.02em' }}>
            创建你的 AI 分身
          </h1>
          <p style={{ fontSize: 15, color: C.textMid, margin: '0 0 36px', lineHeight: 1.6, maxWidth: 400, marginLeft: 'auto', marginRight: 'auto' }}>
            AI 会从你的对话中自动萃取经验，生成一个可以模拟你思考和回答的 AI 分身
          </p>

          <div style={{ display: 'flex', gap: 12, justifyContent: 'center', flexWrap: 'wrap' }}>
            <button onClick={() => router.push('/h5/interview/start')} style={{
              padding: '12px 28px', borderRadius: 100, border: 'none', cursor: 'pointer',
              background: C.accent, color: '#fff', fontSize: 14, fontWeight: 600, fontFamily: 'inherit', transition,
              boxShadow: '0 2px 8px rgba(37,99,235,0.25)',
            }}
              onMouseEnter={e => { e.currentTarget.style.background = C.accentHover; e.currentTarget.style.transform = 'translateY(-1px)'; }}
              onMouseLeave={e => { e.currentTarget.style.background = C.accent; e.currentTarget.style.transform = 'translateY(0)'; }}
            >开始萃取</button>

            <button onClick={() => router.push('/discover')} style={{
              padding: '12px 28px', borderRadius: 100, cursor: 'pointer',
              border: `1.5px solid ${C.cardBorder}`, background: C.cardBg,
              color: C.textMid, fontSize: 14, fontWeight: 600, fontFamily: 'inherit', transition,
            }}
              onMouseEnter={e => { e.currentTarget.style.borderColor = C.cardBorderHover; e.currentTarget.style.background = '#f8fafc'; }}
              onMouseLeave={e => { e.currentTarget.style.borderColor = C.cardBorder; e.currentTarget.style.background = C.cardBg; }}
            >发现专家</button>
          </div>
        </div>
      </div>
    );
  }

  // ═══ Has skill — dashboard ═══
  const initial = (skill.displayName || '?').charAt(0);

  return (
    <div style={{ minHeight: '100vh', background: C.pageBg }}>
      <PlatformTopBar backTo="/discover" backLabel="发现专家" />

      {/* ═══ Hero (clean, no gradient) ═══ */}
      <div style={{
        background: C.cardBg, borderBottom: `1px solid ${C.cardBorder}`,
        padding: '36px 20px 32px',
      }}>
        <div style={{ maxWidth: 800, margin: '0 auto' }}>
          {/* Title + CTAs row */}
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16, marginBottom: 24 }}>
            <div>
              <h1 style={{ fontSize: 28, fontWeight: 700, color: C.text, margin: '0 0 6px', letterSpacing: '-0.02em' }}>
                我的 AI 分身
              </h1>
              <p style={{ fontSize: 14, color: C.textMid, margin: 0 }}>
                {skill.displayName || '未命名'} · {skill.ownerTitle || '经验萃取'} · {skill.grainCount} 条经验
              </p>
            </div>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
              <button onClick={handleInvite} style={{
                padding: '9px 18px', borderRadius: 100, cursor: 'pointer',
                border: `1.5px solid ${C.cardBorder}`, background: C.cardBg,
                color: C.textMid, fontSize: 13, fontWeight: 600, fontFamily: 'inherit', transition,
              }}
                onMouseEnter={e => { e.currentTarget.style.borderColor = C.cardBorderHover; e.currentTarget.style.background = '#f8fafc'; }}
                onMouseLeave={e => { e.currentTarget.style.borderColor = C.cardBorder; e.currentTarget.style.background = C.cardBg; }}
              >邀请专家</button>

              <button onClick={() => router.push('/h5/interview/start')} style={{
                padding: '9px 20px', borderRadius: 100, border: 'none', cursor: 'pointer',
                background: C.accent, color: '#fff', fontSize: 13, fontWeight: 600, fontFamily: 'inherit', transition,
              }}
                onMouseEnter={e => { e.currentTarget.style.background = C.accentHover; }}
                onMouseLeave={e => { e.currentTarget.style.background = C.accent; }}
              >开始萃取</button>
            </div>
          </div>

          {/* Stats — white cards with colored left accent */}
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <StatCard
              accent={C.accent}
              value={skill.grainCount}
              label="经验颗粒"
            />
            <StatCard
              accent={st.dot}
              value={stLabel}
              label="当前状态"
            />
            <StatCard
              accent="#8b5cf6"
              value={domainLabel(skill.domain)}
              label="领域"
            />
          </div>
        </div>
      </div>

      {/* ═══ Content ═══ */}
      <div style={{ maxWidth: 800, margin: '0 auto', padding: '28px 20px 80px' }}>
        {error && (
          <div style={{
            marginBottom: 24, padding: '12px 16px', borderRadius: 12, fontSize: 13,
            background: C.redBg, border: `1px solid #fecaca`, color: C.red,
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          }}>
            <span>{error}</span>
            <button onClick={fetchSkill} style={{ background: 'none', border: 'none', cursor: 'pointer', color: C.red, fontWeight: 600, fontSize: 12, fontFamily: 'inherit' }}>重试</button>
          </div>
        )}

        {/* ═══ Persona card ═══ */}
        <div style={{
          background: C.cardBg, borderRadius: 16, border: `1px solid ${C.cardBorder}`,
          padding: '28px', boxShadow: C.shadowSm,
        }}>
          {/* Avatar + info row */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 18, marginBottom: 24 }}>
            <div style={{
              width: 60, height: 60, borderRadius: 16, flexShrink: 0,
              background: skill.status === 'generating'
                ? `linear-gradient(135deg, ${C.orange}, #f59e0b)`
                : `linear-gradient(135deg, ${C.accent}, #6366f1)`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: '#fff', fontSize: 26, fontWeight: 700,
              animation: skill.status === 'generating' ? 'pulse 2s ease-in-out infinite' : undefined,
            }}>
              {initial}
            </div>

            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6, flexWrap: 'wrap' }}>
                <span style={{ fontSize: 20, fontWeight: 700, color: C.text }}>
                  {skill.displayName || '未命名'}
                </span>
                <span style={{
                  padding: '3px 10px', borderRadius: 100, fontSize: 11, fontWeight: 700,
                  background: st.bg, color: st.color, flexShrink: 0,
                  border: `1px solid ${st.color}20`,
                }}>
                  {stLabel}
                </span>
              </div>
              <div style={{ fontSize: 14, color: C.textMid }}>
                {skill.ownerTitle && <span>{skill.ownerTitle}</span>}
                {skill.ownerTitle ? ' · ' : ''}覆盖 {skill.grainCount} 条经验
              </div>
            </div>
          </div>

          {/* CTA buttons */}
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            {skill.status === 'published' && (
              <button onClick={async () => {
                try {
                  let shareCode = skill.shareCode;
                  if (!shareCode) {
                    const r = await fetch(`/api/v1/skills/${skill.id}/share`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' } });
                    const d = await r.json();
                    if (d.code === 200) shareCode = d.data.shareCode;
                  }
                  if (shareCode) router.push(`/skill/${skill.id}`);
                } catch { alert('网络错误'); }
              }} style={{
                flex: 1, minWidth: 140, padding: '12px 20px', borderRadius: 12,
                border: 'none', cursor: 'pointer', background: C.accent, color: '#fff',
                fontSize: 14, fontWeight: 600, fontFamily: 'inherit', transition,
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                boxShadow: '0 2px 8px rgba(37,99,235,0.2)',
              }}
                onMouseEnter={e => { e.currentTarget.style.background = C.accentHover; }}
                onMouseLeave={e => { e.currentTarget.style.background = C.accent; }}
              >开始对话</button>
            )}

            {(skill.status === 'draft' || skill.status === 'reviewing') && (
              <button onClick={() => router.push(`/platform/my/${skill.id}/audit`)} style={{
                flex: 1, minWidth: 140, padding: '12px 20px', borderRadius: 12,
                border: 'none', cursor: 'pointer', background: C.accent, color: '#fff',
                fontSize: 14, fontWeight: 600, fontFamily: 'inherit', transition,
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                boxShadow: '0 2px 8px rgba(37,99,235,0.2)',
              }}
                onMouseEnter={e => { e.currentTarget.style.background = C.accentHover; }}
                onMouseLeave={e => { e.currentTarget.style.background = C.accent; }}
              >去审核</button>
            )}

            {skill.status === 'generating' && (
              <div style={{
                flex: 1, minWidth: 140, padding: '12px 20px', borderRadius: 12,
                background: C.orangeBg, color: C.orange,
                fontSize: 14, fontWeight: 600, textAlign: 'center', fontFamily: 'inherit',
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                border: `1px solid #fde68a`,
              }}>
                AI 正在萃取你的经验...
              </div>
            )}

            <button onClick={() => router.push(`/platform/my/${skill.id}/materials`)} style={{
              padding: '12px 20px', borderRadius: 12, cursor: 'pointer',
              border: `1.5px solid ${C.cardBorder}`, background: C.cardBg,
              color: C.textMid, fontSize: 14, fontWeight: 600, fontFamily: 'inherit', transition,
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
            }}
              onMouseEnter={e => { e.currentTarget.style.borderColor = C.cardBorderHover; e.currentTarget.style.background = '#f8fafc'; }}
              onMouseLeave={e => { e.currentTarget.style.borderColor = C.cardBorder; e.currentTarget.style.background = C.cardBg; }}
            >管理素材</button>
          </div>
        </div>

        {/* ═══ Quick actions grid ═══ */}
        <div style={{ marginTop: 24 }}>
          <h3 style={{ fontSize: 11, fontWeight: 700, color: C.textLow, textTransform: 'uppercase', letterSpacing: '0.08em', margin: '0 0 12px 4px' }}>
            快捷操作
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 10 }}>
            <ActionCard
              icon="🎙"
              label="开始萃取"
              desc="创建新的经验萃取访谈"
              onClick={() => router.push('/h5/interview/start')}
            />
            <ActionCard
              icon="🔗"
              label="邀请专家"
              desc="生成邀请链接发送给专家"
              onClick={handleInvite}
            />
            <ActionCard
              icon="📎"
              label="上传素材"
              desc="上传对话录音或文字记录"
              onClick={() => router.push(`/platform/my/${skill.id}/materials`)}
            />
            <ActionCard
              icon={skill.status === 'published' ? '📊' : '📋'}
              label={skill.status === 'published' ? '查看报告' : '审核颗粒'}
              desc={skill.status === 'published' ? '查看萃取报告' : '审核和编辑已萃取的颗粒'}
              onClick={() => router.push(`/platform/my/${skill.id}${skill.status === 'published' ? '?tab=report' : '/audit'}`)}
            />
          </div>
        </div>
      </div>

      {/* ═══ Invite Modal ═══ */}
      {showInviteModal && (
        <div onClick={() => setShowInviteModal(false)} style={{
          position: 'fixed', inset: 0, zIndex: 200, background: 'rgba(15,23,42,0.3)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
          backdropFilter: 'blur(4px)',
        }}>
          <div onClick={e => e.stopPropagation()} style={{
            background: C.cardBg, borderRadius: 20, padding: '28px', maxWidth: 380, width: '90%',
            boxShadow: C.shadowLg, textAlign: 'center',
          }}>
            <h3 style={{ fontSize: 18, fontWeight: 700, color: C.text, margin: '0 0 6px' }}>邀请专家</h3>
            <p style={{ fontSize: 13, color: C.textMid, margin: '0 0 20px' }}>扫码或复制链接发送给专家</p>

            <div style={{
              display: 'inline-block', padding: 8, background: C.cardBg,
              border: `1px solid ${C.cardBorder}`, borderRadius: 14, marginBottom: 16,
            }}>
              <img src={`https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(inviteUrl)}`}
                width={180} height={180} alt="邀请二维码" style={{ borderRadius: 8, display: 'block' }} />
            </div>

            <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
              <input value={inviteUrl} readOnly style={{
                flex: 1, padding: '10px 14px', borderRadius: 12, border: `1.5px solid ${C.cardBorder}`,
                background: '#f8fafc', fontSize: 12, color: C.textMid, outline: 'none', fontFamily: 'inherit',
              }} />
              <button onClick={() => { copyToClipboard(inviteUrl); setInviteCopied(true); }} style={{
                padding: '10px 18px', borderRadius: 12, border: 'none', cursor: 'pointer',
                background: C.accent, color: '#fff', fontSize: 13, fontWeight: 600, fontFamily: 'inherit', whiteSpace: 'nowrap', transition,
              }}>
                {inviteCopied ? '已复制' : '复制'}
              </button>
            </div>

            <button onClick={() => setShowInviteModal(false)} style={{
              width: '100%', padding: '10px 0', borderRadius: 100,
              border: `1.5px solid ${C.cardBorder}`, cursor: 'pointer',
              background: C.cardBg, color: C.textMid, fontSize: 13, fontWeight: 500, fontFamily: 'inherit', transition,
            }}
              onMouseEnter={e => { e.currentTarget.style.background = '#f8fafc'; }}
              onMouseLeave={e => { e.currentTarget.style.background = C.cardBg; }}
            >关闭</button>
          </div>
        </div>
      )}

      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.5; transform: scale(0.92); }
        }
      `}</style>
    </div>
  );
}

// ── Sub-components ──

function StatCard({ accent, value, label }: { accent: string; value: string | number; label: string }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12,
      background: '#ffffff', borderRadius: 12, padding: '12px 18px',
      border: `1px solid #e2e8f0`, borderLeft: `3px solid ${accent}`,
      boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
    }}>
      <div>
        <div style={{ fontSize: 20, fontWeight: 700, color: '#0f172a', lineHeight: 1.2 }}>{value}</div>
        <div style={{ fontSize: 12, color: '#94a3b8', marginTop: 2 }}>{label}</div>
      </div>
    </div>
  );
}

function ActionCard({ icon, label, desc, onClick }: { icon: string; label: string; desc: string; onClick: () => void }) {
  return (
    <button onClick={onClick} style={{
      display: 'flex', alignItems: 'center', gap: 14,
      padding: '16px 20px', borderRadius: 14, cursor: 'pointer',
      border: `1px solid #e2e8f0`, background: '#ffffff',
      textAlign: 'left', fontFamily: 'inherit', transition: 'all 0.2s ease',
      boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
    }}
      onMouseEnter={e => {
        e.currentTarget.style.borderColor = '#cbd5e1';
        e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,0.06)';
        e.currentTarget.style.transform = 'translateY(-1px)';
      }}
      onMouseLeave={e => {
        e.currentTarget.style.borderColor = '#e2e8f0';
        e.currentTarget.style.boxShadow = '0 1px 2px rgba(0,0,0,0.03)';
        e.currentTarget.style.transform = 'translateY(0)';
      }}
    >
      <span style={{ fontSize: 22, flexShrink: 0 }}>{icon}</span>
      <div>
        <div style={{ fontSize: 13, fontWeight: 600, color: '#0f172a' }}>{label}</div>
        <div style={{ fontSize: 12, color: '#94a3b8', marginTop: 1 }}>{desc}</div>
      </div>
    </button>
  );
}
