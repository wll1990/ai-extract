'use client';

import { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import type { SkillDetail } from '@/lib/api/skill';
import { fetchRecommendedQuestions, getOrCreateShare } from '@/lib/api/skill';
import { getUser, clearAuth, getSkinPreference, setSkinPreference } from '@/lib/storage';
import { logout as logoutApi } from '@/lib/api/auth';
import { copyToClipboard } from '@/lib/clipboard';

const ORG_AVATAR_COLORS = ['#818cf8', '#a78bfa', '#c084fc', '#e879f9'];

/* ═══════════════════════════════════════════════════════
   Theme CSS — injected into <head>
   ═══════════════════════════════════════════════════════ */
const THEME_CSS = `
/* === 深空黑 (默认) === */
:root,
body:not([data-skin]) {
  --sk-bg-body: #09090b;
  --sk-bg-page-gradient: linear-gradient(180deg, #09090b 0%, #1a1a2e 50%, #09090b 100%);
  --sk-bg-topbar: rgba(9,9,11,0.85);
  --sk-bg-card: rgba(255,255,255,0.04);
  --sk-bg-card-dim: rgba(255,255,255,0.03);
  --sk-bg-card-hover: rgba(255,255,255,0.07);
  --sk-bg-card-solid: #18181b;
  --sk-bg-cta: rgba(24,24,27,0.94);
  --sk-bg-btn-ghost: rgba(255,255,255,0.06);
  --sk-bg-user-btn: rgba(255,255,255,0.08);
  --sk-border-subtle: rgba(255,255,255,0.06);
  --sk-border-medium: rgba(255,255,255,0.10);
  --sk-border-strong: rgba(255,255,255,0.15);
  --sk-text-primary: #fafafa;
  --sk-text-secondary: #a1a1aa;
  --sk-text-tertiary: #71717a;
  --sk-text-dim: #52525b;
  --sk-accent: #f59e0b;
  --sk-accent-light: #fbbf24;
  --sk-accent-soft: rgba(245,158,11,0.08);
  --sk-accent-glow: rgba(245,158,11,0.15);
  --sk-accent-border: rgba(245,158,11,0.25);
  --sk-accent-ring: rgba(245,158,11,0.4);
  --sk-accent-gradient: linear-gradient(135deg, #f59e0b, #d97706);
  --sk-purple: #a78bfa;
  --sk-purple-light: #c084fc;
  --sk-purple-soft: rgba(167,139,250,0.10);
  --sk-green: #34d399;
  --sk-green-light: #6ee7b7;
  --sk-green-soft: rgba(52,211,153,0.10);
  --sk-amber: #fbbf24;
  --sk-amber-soft: rgba(245,158,11,0.12);
  --sk-danger: #ef4444;
  --sk-danger-bg: #dc2626;
  --sk-quote-color: #d4d4d8;
  --sk-quote-border: rgba(245,158,11,0.3);
  --sk-avatar-gradient: linear-gradient(135deg, #6366f1, #a78bfa);
  --sk-avatar-ring: rgba(245,158,11,0.4);
  --sk-avatar-shadow: 0 8px 32px rgba(0,0,0,0.4);
  --sk-org-avatar-border: #09090b;
  --sk-divider-gradient: linear-gradient(90deg, #f59e0b, transparent);
  --sk-shadow-cta: 0 8px 32px rgba(0,0,0,0.5), 0 0 0 1px rgba(245,158,11,0.06);
  --sk-shadow-modal: 0 20px 60px rgba(0,0,0,0.5);
}

/* === 暖沙色 === */
body[data-skin="warm-sand"] {
  --sk-bg-body: #f8f5fb;
  --sk-bg-page-gradient: radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%);
  --sk-bg-topbar: rgba(248,245,251,0.88);
  --sk-bg-card: #ffffff;
  --sk-bg-card-dim: #f9f7fc;
  --sk-bg-card-hover: #fefaf5;
  --sk-bg-card-solid: #ffffff;
  --sk-bg-cta: rgba(255,255,255,0.94);
  --sk-bg-btn-ghost: rgba(0,0,0,0.04);
  --sk-bg-user-btn: rgba(0,0,0,0.04);
  --sk-border-subtle: rgba(0,0,0,0.06);
  --sk-border-medium: rgba(0,0,0,0.10);
  --sk-border-strong: rgba(0,0,0,0.15);
  --sk-text-primary: #2b180a;
  --sk-text-secondary: #665a51;
  --sk-text-tertiary: #94877c;
  --sk-text-dim: #bfb5aa;
  --sk-accent: #ff5c00;
  --sk-accent-light: #ff7b3d;
  --sk-accent-soft: rgba(255,92,0,0.06);
  --sk-accent-glow: rgba(255,92,0,0.10);
  --sk-accent-border: rgba(255,92,0,0.20);
  --sk-accent-ring: rgba(255,92,0,0.35);
  --sk-accent-gradient: linear-gradient(135deg, #ff5c00, #e55300);
  --sk-purple: #7e22ce;
  --sk-purple-light: #a855f7;
  --sk-purple-soft: rgba(126,34,206,0.06);
  --sk-green: #16a34a;
  --sk-green-light: #22c55e;
  --sk-green-soft: rgba(22,163,74,0.08);
  --sk-amber: #d97706;
  --sk-amber-soft: rgba(217,119,6,0.08);
  --sk-danger: #dc2626;
  --sk-danger-bg: #dc2626;
  --sk-quote-color: #665a51;
  --sk-quote-border: rgba(255,92,0,0.25);
  --sk-avatar-gradient: linear-gradient(135deg, #ff5c00, #f97316);
  --sk-avatar-ring: rgba(255,92,0,0.4);
  --sk-avatar-shadow: 0 4px 20px rgba(0,0,0,0.12);
  --sk-org-avatar-border: #ffffff;
  --sk-divider-gradient: linear-gradient(90deg, #ff5c00, transparent);
  --sk-shadow-cta: 0 2px 16px rgba(0,0,0,0.06), 0 0 0 1px rgba(0,0,0,0.04);
  --sk-shadow-modal: 0 20px 60px rgba(0,0,0,0.15);
}

/* === 极光紫 === */
body[data-skin="aurora"] {
  --sk-bg-body: #0a0a1a;
  --sk-bg-page-gradient: linear-gradient(180deg, #0a0a1a 0%, #12102a 50%, #0a0a1a 100%);
  --sk-bg-topbar: rgba(10,10,26,0.85);
  --sk-bg-card: rgba(255,255,255,0.04);
  --sk-bg-card-dim: rgba(255,255,255,0.03);
  --sk-bg-card-hover: rgba(255,255,255,0.07);
  --sk-bg-card-solid: #12102a;
  --sk-bg-cta: rgba(16,16,38,0.94);
  --sk-bg-btn-ghost: rgba(255,255,255,0.06);
  --sk-bg-user-btn: rgba(255,255,255,0.08);
  --sk-border-subtle: rgba(255,255,255,0.06);
  --sk-border-medium: rgba(255,255,255,0.10);
  --sk-border-strong: rgba(255,255,255,0.15);
  --sk-text-primary: #f0ebff;
  --sk-text-secondary: #a8a0c8;
  --sk-text-tertiary: #6b6390;
  --sk-text-dim: #4b4570;
  --sk-accent: #a78bfa;
  --sk-accent-light: #c4b5fd;
  --sk-accent-soft: rgba(167,139,250,0.08);
  --sk-accent-glow: rgba(167,139,250,0.15);
  --sk-accent-border: rgba(167,139,250,0.25);
  --sk-accent-ring: rgba(167,139,250,0.4);
  --sk-accent-gradient: linear-gradient(135deg, #a78bfa, #8b5cf6);
  --sk-purple: #c084fc;
  --sk-purple-light: #d8b4fe;
  --sk-purple-soft: rgba(192,132,252,0.10);
  --sk-green: #6ee7b7;
  --sk-green-light: #a7f3d0;
  --sk-green-soft: rgba(110,231,183,0.10);
  --sk-amber: #fbbf24;
  --sk-amber-soft: rgba(251,191,36,0.10);
  --sk-danger: #f87171;
  --sk-danger-bg: #dc2626;
  --sk-quote-color: #c4b5fd;
  --sk-quote-border: rgba(167,139,250,0.3);
  --sk-avatar-gradient: linear-gradient(135deg, #8b5cf6, #a78bfa);
  --sk-avatar-ring: rgba(167,139,250,0.5);
  --sk-avatar-shadow: 0 8px 32px rgba(0,0,0,0.4);
  --sk-org-avatar-border: #0a0a1a;
  --sk-divider-gradient: linear-gradient(90deg, #a78bfa, transparent);
  --sk-shadow-cta: 0 8px 32px rgba(0,0,0,0.5), 0 0 0 1px rgba(167,139,250,0.08);
  --sk-shadow-modal: 0 20px 60px rgba(0,0,0,0.5);
}

/* === 瓷白 === */
body[data-skin="porcelain"] {
  --sk-bg-body: #fafafa;
  --sk-bg-page-gradient: linear-gradient(180deg, #fafafa 0%, #f4f4f5 100%);
  --sk-bg-topbar: rgba(250,250,250,0.88);
  --sk-bg-card: #ffffff;
  --sk-bg-card-dim: #f8f8f8;
  --sk-bg-card-hover: #f4f4f5;
  --sk-bg-card-solid: #ffffff;
  --sk-bg-cta: rgba(255,255,255,0.94);
  --sk-bg-btn-ghost: rgba(0,0,0,0.04);
  --sk-bg-user-btn: rgba(0,0,0,0.04);
  --sk-border-subtle: rgba(0,0,0,0.06);
  --sk-border-medium: rgba(0,0,0,0.10);
  --sk-border-strong: rgba(0,0,0,0.15);
  --sk-text-primary: #18181b;
  --sk-text-secondary: #52525b;
  --sk-text-tertiary: #a1a1aa;
  --sk-text-dim: #d4d4d8;
  --sk-accent: #6366f1;
  --sk-accent-light: #818cf8;
  --sk-accent-soft: rgba(99,102,241,0.06);
  --sk-accent-glow: rgba(99,102,241,0.10);
  --sk-accent-border: rgba(99,102,241,0.20);
  --sk-accent-ring: rgba(99,102,241,0.35);
  --sk-accent-gradient: linear-gradient(135deg, #6366f1, #4f46e5);
  --sk-purple: #8b5cf6;
  --sk-purple-light: #a78bfa;
  --sk-purple-soft: rgba(139,92,246,0.06);
  --sk-green: #22c55e;
  --sk-green-light: #4ade80;
  --sk-green-soft: rgba(34,197,94,0.08);
  --sk-amber: #f59e0b;
  --sk-amber-soft: rgba(245,158,11,0.08);
  --sk-danger: #ef4444;
  --sk-danger-bg: #dc2626;
  --sk-quote-color: #52525b;
  --sk-quote-border: rgba(99,102,241,0.25);
  --sk-avatar-gradient: linear-gradient(135deg, #6366f1, #8b5cf6);
  --sk-avatar-ring: rgba(99,102,241,0.35);
  --sk-avatar-shadow: 0 4px 20px rgba(0,0,0,0.10);
  --sk-org-avatar-border: #ffffff;
  --sk-divider-gradient: linear-gradient(90deg, #6366f1, transparent);
  --sk-shadow-cta: 0 2px 16px rgba(0,0,0,0.06), 0 0 0 1px rgba(0,0,0,0.04);
  --sk-shadow-modal: 0 20px 60px rgba(0,0,0,0.15);
}

/* === 墨绿金 === */
body[data-skin="forest"] {
  --sk-bg-body: #0a0f0a;
  --sk-bg-page-gradient: linear-gradient(180deg, #0a0f0a 0%, #111a11 50%, #0a0f0a 100%);
  --sk-bg-topbar: rgba(10,15,10,0.85);
  --sk-bg-card: rgba(255,255,255,0.04);
  --sk-bg-card-dim: rgba(255,255,255,0.03);
  --sk-bg-card-hover: rgba(255,255,255,0.07);
  --sk-bg-card-solid: #121a12;
  --sk-bg-cta: rgba(18,26,18,0.94);
  --sk-bg-btn-ghost: rgba(255,255,255,0.06);
  --sk-bg-user-btn: rgba(255,255,255,0.08);
  --sk-border-subtle: rgba(255,255,255,0.06);
  --sk-border-medium: rgba(255,255,255,0.10);
  --sk-border-strong: rgba(255,255,255,0.15);
  --sk-text-primary: #f0f5f0;
  --sk-text-secondary: #a0b0a0;
  --sk-text-tertiary: #6b7a6b;
  --sk-text-dim: #4a5a4a;
  --sk-accent: #c9a227;
  --sk-accent-light: #e4c34f;
  --sk-accent-soft: rgba(201,162,39,0.08);
  --sk-accent-glow: rgba(201,162,39,0.15);
  --sk-accent-border: rgba(201,162,39,0.25);
  --sk-accent-ring: rgba(201,162,39,0.4);
  --sk-accent-gradient: linear-gradient(135deg, #c9a227, #a8861f);
  --sk-purple: #a78bfa;
  --sk-purple-light: #c4b5fd;
  --sk-purple-soft: rgba(167,139,250,0.10);
  --sk-green: #4ade80;
  --sk-green-light: #86efac;
  --sk-green-soft: rgba(74,222,128,0.10);
  --sk-amber: #fbbf24;
  --sk-amber-soft: rgba(251,191,36,0.10);
  --sk-danger: #ef4444;
  --sk-danger-bg: #dc2626;
  --sk-quote-color: #c0d0c0;
  --sk-quote-border: rgba(201,162,39,0.3);
  --sk-avatar-gradient: linear-gradient(135deg, #2d5a27, #4a7c3f);
  --sk-avatar-ring: rgba(201,162,39,0.45);
  --sk-avatar-shadow: 0 8px 32px rgba(0,0,0,0.4);
  --sk-org-avatar-border: #0a0f0a;
  --sk-divider-gradient: linear-gradient(90deg, #c9a227, transparent);
  --sk-shadow-cta: 0 8px 32px rgba(0,0,0,0.5), 0 0 0 1px rgba(201,162,39,0.06);
  --sk-shadow-modal: 0 20px 60px rgba(0,0,0,0.5);
}

/* ═══ 头像光环 ═══ */
/* 跨浏览器兼容：WebKit 用渐变边框光环，Firefox 降级为纯色边框 */
.sk-avatar-ring {
  position: absolute; inset: -6px; border-radius: 28px;
  border: 2px solid var(--sk-accent);
  opacity: 0.6;
}
@supports (-webkit-mask-composite: xor) {
  .sk-avatar-ring {
    border: 2px solid transparent;
    background: linear-gradient(135deg, var(--sk-accent), var(--sk-accent-light), var(--sk-accent)) border-box;
    -webkit-mask: linear-gradient(#fff 0 0) padding-box, linear-gradient(#fff 0 0);
    -webkit-mask-composite: xor;
    mask-composite: exclude;
    opacity: 1;
  }
}

/* 光环动画 */
@keyframes sk-ring-pulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}
@keyframes sk-glow-breathe {
  0%, 100% { opacity: 0.5; transform: scale(0.95); }
  50% { opacity: 0.9; transform: scale(1.05); }
}
`;

const KEYFRAME_ID = 'skill-card-keyframes';
const THEME_STYLE_ID = 'skill-card-theme-vars';

if (typeof document !== 'undefined') {
  if (!document.getElementById(KEYFRAME_ID)) {
    const style = document.createElement('style');
    style.id = KEYFRAME_ID;
    style.textContent = `
      @keyframes float { 0%,100%{transform:translateY(0)} 50%{transform:translateY(-6px)} }
      @keyframes shimmer { 0%{background-position:200% 0} 100%{background-position:-200% 0} }
      @keyframes fadeUp { from{opacity:0;transform:translateY(20px)} to{opacity:1;transform:translateY(0)} }
      @media (min-width: 768px) {
        .card-content-desktop { flex-direction: row !important; }
        .right-panel-desktop { display: flex !important; }
        .left-panel-desktop { max-width: 480px; }
      }
    `;
    document.head.appendChild(style);
  }
  if (!document.getElementById(THEME_STYLE_ID)) {
    const themeStyle = document.createElement('style');
    themeStyle.id = THEME_STYLE_ID;
    themeStyle.textContent = THEME_CSS;
    document.head.appendChild(themeStyle);
  }
}

/* ═══ 主题定义 ═══ */
const SKINS = [
  { key: '', label: '深空黑', dot: '#09090b' },
  { key: 'warm-sand', label: '暖沙色', dot: '#f8f5fb' },
  { key: 'aurora', label: '极光紫', dot: '#0a0a1a' },
  { key: 'porcelain', label: '瓷白', dot: '#fafafa' },
  { key: 'forest', label: '墨绿金', dot: '#0a0f0a' },
] as const;

/* ═══ StatBadge ═══ */
function StatBadge({ value, label, sub, color }: { value: string; label: string; sub?: string; color: string }) {
  return (
    <div style={{ textAlign: 'center' }}>
      <div style={{ fontSize: 26, fontWeight: 800, color, letterSpacing: '-0.5px', lineHeight: 1 }}>{value}</div>
      <div style={{ fontSize: 11, color: 'var(--sk-text-tertiary)', marginTop: 4 }}>{label}</div>
      {sub && <div style={{ fontSize: 10, color: 'var(--sk-text-dim)', marginTop: 2, fontStyle: 'italic' }}>{sub}</div>}
    </div>
  );
}

/* ═══ QuestionRow — 聊天气泡风 ═══ */
function QuestionRow({ q, onClick }: { q: string; onClick: () => void }) {
  return (
    <button onClick={onClick} aria-label={`提问: ${q}`} style={{
      display: 'flex', alignItems: 'center', gap: 10,
      width: '100%', padding: '12px 16px',
      background: 'var(--sk-bg-card)', border: '1px solid var(--sk-border-subtle)',
      borderRadius: 14, borderTopLeftRadius: 4, cursor: 'pointer',
      fontSize: 13, color: 'var(--sk-text-secondary)', textAlign: 'left',
      fontFamily: 'inherit', lineHeight: 1.5,
      transition: 'all 0.25s',
    }}
      onMouseEnter={e => { e.currentTarget.style.background = 'var(--sk-accent-soft)'; e.currentTarget.style.borderColor = 'var(--sk-accent-border)'; e.currentTarget.style.color = 'var(--sk-text-primary)'; e.currentTarget.style.transform = 'translateX(4px)'; }}
      onMouseLeave={e => { e.currentTarget.style.background = 'var(--sk-bg-card)'; e.currentTarget.style.borderColor = 'var(--sk-border-subtle)'; e.currentTarget.style.color = 'var(--sk-text-secondary)'; e.currentTarget.style.transform = 'translateX(0)'; }}
    >
      <span style={{
        width: 28, height: 28, borderRadius: '50%', flexShrink: 0,
        background: 'var(--sk-accent-soft)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13,
      }}>💬</span>
      <span>{q}</span>
    </button>
  );
}

/* ═══ ShareLinkRow ═══ */
function ShareLinkRow({ icon, label, desc, url, copied, onCopy }: {
  icon: string; label: string; desc: string; url: string; copied: boolean; onCopy: () => void;
}) {
  return (
    <div style={{ marginBottom: 10, textAlign: 'left' }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--sk-text-secondary)', marginBottom: 4 }}>
        {icon} {label} <span style={{ fontWeight: 400, color: 'var(--sk-text-tertiary)' }}>— {desc}</span>
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        <div style={{
          flex: 1, background: 'var(--sk-bg-btn-ghost)', border: '1px solid var(--sk-border-medium)',
          borderRadius: 8, padding: '8px 12px', fontSize: 11,
          color: 'var(--sk-text-tertiary)', fontFamily: 'monospace', wordBreak: 'break-all',
          display: 'flex', alignItems: 'center',
        }}>{url}</div>
        <button onClick={onCopy} style={{
          padding: '8px 14px', borderRadius: 8, flexShrink: 0,
          background: copied ? 'var(--sk-green)' : 'var(--sk-accent)', color: '#fff', border: 'none',
          cursor: 'pointer', fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
        }}>{copied ? '✓ 已复制' : '📋'}</button>
      </div>
    </div>
  );
}

/* ═══ 主组件 ═══ */
export function SkillCardPage({ skill }: Props) {
  const router = useRouter();
  const name = skill.displayName || skill.ownerName || '专家';
  const title = skill.ownerTitle || '';
  const isOrg = skill.type === 'organization';
  const orgMembers = skill.members || [];
  const previewMembers = orgMembers.slice(0, 4);

  const [user, setUser] = useState<{ name: string; role: string; avatarUrl?: string } | null>(null);
  const [mounted, setMounted] = useState(false);
  const [showUserMenu, setShowUserMenu] = useState(false);

  /* ═══ 主题 state ═══ */
  const [skin, setSkin] = useState<string>('');

  useEffect(() => {
    const u = getUser();
    if (u?.name) setUser({ name: u.name, role: u.role as string, avatarUrl: u.avatarUrl as string | undefined });
    const savedSkin = getSkinPreference();
    if (savedSkin) setSkin(savedSkin);
    setMounted(true);
  }, []);

  /* 同步 skin 到 DOM + localStorage */
  useEffect(() => {
    if (!mounted) return;
    if (skin) {
      document.body.setAttribute('data-skin', skin);
      setSkinPreference(skin);
    } else {
      document.body.removeAttribute('data-skin');
      setSkinPreference('');
    }
  }, [skin, mounted]);

  /* 仅在真正离开 /skill/* 路由时清理 data-skin，避免同名页面间切换闪烁 */
  useEffect(() => {
    const cleanup = () => {
      // 延迟检查：让 Next.js 路由先完成，判断新路径是否还在 skill 页
      setTimeout(() => {
        if (!window.location.pathname.startsWith('/skill/')) {
          document.body.removeAttribute('data-skin');
        }
      }, 0);
    };
    return cleanup;
  }, []);

  useEffect(() => {
    if (!showUserMenu) return;
    const handler = () => setShowUserMenu(false);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [showUserMenu]);

  const handleLogout = useCallback(async () => {
    try { await logoutApi(); } catch { /* ignore */ }
    clearAuth(); setUser(null); setShowUserMenu(false);
    router.push('/');
  }, [router]);

  const userInitial = user?.name?.[0] || '?';

  const [questions, setQuestions] = useState<string[]>(() => (skill.recommendedQuestions || []).slice(0, 5));
  const [questionsLoading, setQuestionsLoading] = useState(!(skill.recommendedQuestions && skill.recommendedQuestions.length > 0));

  useEffect(() => {
    if (questionsLoading && skill.id) {
      fetchRecommendedQuestions(skill.id)
        .then(qs => { if (Array.isArray(qs) && qs.length > 0) setQuestions(qs.slice(0, 5)); })
        .catch(() => {}).finally(() => setQuestionsLoading(false));
    }
  }, [skill.id, questionsLoading]);

  const [inputValue, setInputValue] = useState('');
  const [placeholderIdx, setPlaceholderIdx] = useState(0);
  const hasInput = inputValue.trim().length > 0;

  useEffect(() => {
    if (questions.length === 0) return;
    const timer = setInterval(() => setPlaceholderIdx(i => (i + 1) % questions.length), 3000);
    return () => clearInterval(timer);
  }, [questions]);

  const currentPlaceholder = questions.length > 0 ? questions[placeholderIdx] : '试着问他一个问题吧…';

  const doAction = useCallback((text?: string) => {
    const q = (text || inputValue.trim());
    if (q) router.push(`/chat/${skill.id}?q=${encodeURIComponent(q)}&mode=talk`);
    else router.push(`/chat/${skill.id}`);
  }, [inputValue, skill.id, router]);

  const [showShareModal, setShowShareModal] = useState(false);
  const [pubCode, setPubCode] = useState<string | null>(null);
  const [cardCode, setCardCode] = useState<string | null>(null);
  const [shareLoading, setShareLoading] = useState(false);
  const [shareError, setShareError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const handleShare = useCallback(async () => {
    setShareLoading(true); setShareError(null);
    try {
      const [pub, card] = await Promise.all([getOrCreateShare(skill.id), getOrCreateShare(skill.id, 'card')]);
      setPubCode(pub.shareCode); setCardCode(card.shareCode); setShowShareModal(true);
    } catch { setShareError('分享失败，请先登录'); }
    finally { setShareLoading(false); }
  }, [skill.id]);

  const handleCopy = useCallback((code: string) => {
    const base = process.env.NEXT_PUBLIC_SHARE_BASE_URL || window.location.origin;
    copyToClipboard(`${base}/s/${code}`).then(ok => {
      if (ok) { setCopied(code); setTimeout(() => setCopied(null), 2000); }
    });
  }, []);

  const intro = skill.introProfile;
  const introHeadline = intro?.headline || `关于 ${name}`;
  const introBody = intro?.body || skill.openingMessage || `${name}的AI分身`;
  const introClosing = intro?.closing || '';
  const stats = skill.stats;
  const grainCount = skill.grainCount || 0;

  /* tagline: 优先用 introProfile.headline，否则构造 */
  const tagline = intro?.headline || (title ? `${title} · ${skill.domain === 'sales' ? 'B2B企业服务' : skill.domain || ''}` : '');

  /* ═══ 主题选择器 state ═══ */
  const [showSkinPicker, setShowSkinPicker] = useState(false);

  useEffect(() => {
    if (!showSkinPicker) return;
    const handler = () => setShowSkinPicker(false);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [showSkinPicker]);

  return (
    <div style={{
      display: 'flex', justifyContent: 'center',
      minHeight: '100vh',
      background: 'var(--sk-bg-page-gradient)',
    }}>
      <div style={{ width: '100%', maxWidth: 960, minHeight: '100vh', position: 'relative' }}>

        {/* ═══ Top Bar ═══ */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '12px 20px', position: 'sticky', top: 0, zIndex: 10,
          background: 'var(--sk-bg-topbar)', backdropFilter: 'blur(12px)',
          borderBottom: '1px solid var(--sk-border-subtle)',
        }}>
          <button onClick={() => router.push('/discover')} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            fontSize: 13, color: 'var(--sk-text-tertiary)', fontFamily: 'inherit', padding: 0,
          }}>← 返回发现</button>

          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {!mounted ? <span style={{ width: 100, height: 34 }} /> : user ? (
              <>
                <div style={{ position: 'relative' }}>
                  <button onClick={() => setShowUserMenu(!showUserMenu)} style={{
                    display: 'flex', alignItems: 'center', gap: 6, padding: '4px 10px 4px 4px',
                    borderRadius: 100, background: 'var(--sk-bg-user-btn)', border: '1px solid var(--sk-border-medium)',
                    cursor: 'pointer', fontFamily: 'inherit',
                  }}>
                    {user.avatarUrl ? (
                      <img src={user.avatarUrl} alt={user.name} style={{ width: 26, height: 26, borderRadius: 8, objectFit: 'cover' }} />
                    ) : (
                      <span style={{
                        width: 26, height: 26, borderRadius: 8,
                        background: 'var(--sk-avatar-gradient)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: '#fff', fontSize: 12, fontWeight: 700,
                      }}>{userInitial}</span>
                    )}
                    <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--sk-text-secondary)' }}>{user.name}</span>
                    <span style={{ fontSize: 10, color: 'var(--sk-text-tertiary)' }}>▾</span>
                  </button>
                  {showUserMenu && (
                    <div onClick={e => e.stopPropagation()} style={{
                      position: 'absolute', top: '100%', right: 0, marginTop: 4,
                      background: 'var(--sk-bg-card-solid)', borderRadius: 12,
                      boxShadow: 'var(--sk-shadow-modal)',
                      border: '1px solid var(--sk-border-medium)', padding: '4px 0', minWidth: 140, zIndex: 20,
                    }}>
                      <button onClick={() => { router.push('/platform/my'); setShowUserMenu(false); }} style={{
                        display: 'block', width: '100%', textAlign: 'left', padding: '8px 16px',
                        fontSize: 13, color: 'var(--sk-text-secondary)', background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                      }}>📋 我的分身</button>
                      <button onClick={handleLogout} style={{
                        display: 'block', width: '100%', textAlign: 'left', padding: '8px 16px',
                        fontSize: 13, color: 'var(--sk-danger)', background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                      }}>🚪 退出登录</button>
                    </div>
                  )}
                </div>
                <button onClick={handleShare} disabled={shareLoading} style={{
                  fontSize: 12, background: 'var(--sk-bg-btn-ghost)', color: 'var(--sk-text-secondary)',
                  border: '1px solid var(--sk-border-medium)', borderRadius: 8,
                  padding: '6px 14px', cursor: 'pointer', fontWeight: 500,
                  fontFamily: 'inherit', opacity: shareLoading ? 0.5 : 1,
                }}>📤 分享</button>
              </>
            ) : (
              <>
                <Link href="/login" style={{
                  fontSize: 12, color: 'var(--sk-text-secondary)', background: 'var(--sk-bg-btn-ghost)',
                  border: '1px solid var(--sk-border-medium)', borderRadius: 8,
                  padding: '6px 14px', cursor: 'pointer', fontWeight: 500,
                  fontFamily: 'inherit', textDecoration: 'none',
                }}>登录</Link>
                <Link href="/login?redirect=/platform/my" style={{
                  fontSize: 12, background: 'var(--sk-accent)', color: '#fff', border: 'none',
                  borderRadius: 8, padding: '6px 14px', cursor: 'pointer', fontWeight: 600,
                  fontFamily: 'inherit', textDecoration: 'none',
                }}>创建我的分身</Link>
              </>
            )}
          </div>
        </div>

        {/* ═══ 主体 ═══ */}
        <div className="card-content-desktop" style={{ display: 'flex', flexDirection: 'column', padding: '0 20px' }}>

          {/* ═══ 左栏 ═══ */}
          <div className="left-panel-desktop" style={{ width: '100%', paddingTop: 32, animation: 'fadeUp 0.5s ease-out' }}>

            {/* Hero */}
            <div style={{ textAlign: 'center', marginBottom: 28 }}>
              <div style={{ position: 'relative', display: 'inline-block', marginBottom: 20 }}>
                {/* 光环 — v2，跨浏览器兼容 */}
                <div className="sk-avatar-ring" style={{ animation: 'sk-ring-pulse 3s ease-in-out infinite' }} />
                {/* 底光 — v2 */}
                <div style={{
                  position: 'absolute', inset: -16, borderRadius: 28,
                  background: 'radial-gradient(circle, var(--sk-accent-glow) 0%, transparent 60%)',
                  animation: 'sk-glow-breathe 3s ease-in-out infinite',
                }} />
                {isOrg ? (
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 0, position: 'relative' }}>
                    {previewMembers.length > 0 ? previewMembers.map((m, i) => (
                      m.avatarUrl ? (
                        <img key={m.id} src={m.avatarUrl} alt={m.ownerName} style={{
                          width: 64, height: 64, borderRadius: '50%',
                          border: '3px solid var(--sk-org-avatar-border)', objectFit: 'cover',
                          marginLeft: i > 0 ? -16 : 0, zIndex: 4 - i,
                        }} />
                      ) : (
                        <div key={m.id} style={{
                          width: 64, height: 64, borderRadius: '50%',
                          border: '3px solid var(--sk-org-avatar-border)',
                          background: ORG_AVATAR_COLORS[i % 4],
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                          color: '#fff', fontSize: 22, fontWeight: 700,
                          marginLeft: i > 0 ? -16 : 0, zIndex: 4 - i,
                        }}>{(m.ownerName || '?')[0]}</div>
                      )
                    )) : <span style={{ fontSize: 56 }}>🏢</span>}
                    {orgMembers.length > 4 && (
                      <div style={{
                        width: 64, height: 64, borderRadius: '50%',
                        border: '3px solid var(--sk-org-avatar-border)',
                        background: 'var(--sk-bg-card)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: 'var(--sk-text-tertiary)', fontSize: 14, fontWeight: 600,
                        marginLeft: -16,
                      }}>+{orgMembers.length - 4}</div>
                    )}
                  </div>
                ) : skill.avatarUrl ? (
                  <img src={skill.avatarUrl} alt={name} style={{
                    width: 96, height: 96, borderRadius: 24, objectFit: 'cover', position: 'relative', zIndex: 1,
                    border: '3px solid var(--sk-avatar-ring)', boxShadow: 'var(--sk-avatar-shadow)',
                  }} />
                ) : (
                  <div style={{
                    width: 96, height: 96, borderRadius: 24, position: 'relative', zIndex: 1,
                    background: 'var(--sk-avatar-gradient)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: '#fff', fontSize: 40, fontWeight: 800,
                    border: '3px solid var(--sk-avatar-ring)',
                    boxShadow: 'var(--sk-avatar-shadow)',
                  }}>{name[0]}</div>
                )}
              </div>

              <h1 style={{ fontSize: 28, fontWeight: 800, color: 'var(--sk-text-primary)', letterSpacing: '-0.6px', margin: '0 0 4px' }}>{name}</h1>

              {/* tagline — v2 */}
              {tagline && (
                <p style={{ fontSize: 15, color: 'var(--sk-accent)', fontWeight: 500, fontStyle: 'italic', margin: '0 0 8px', maxWidth: 360, marginLeft: 'auto', marginRight: 'auto', lineHeight: 1.5 }}>
                  {tagline.startsWith('"') ? tagline : `"${tagline}"`}
                </p>
              )}

              <p style={{ fontSize: 13, color: 'var(--sk-text-tertiary)', margin: 0 }}>
                {isOrg ? (
                  <><span style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: 'var(--sk-green)', marginRight: 4, boxShadow: '0 0 6px var(--sk-green)' }} />{skill.memberCount || orgMembers.length} 位成员可对话</>
                ) : (
                  <><span style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: 'var(--sk-green)', marginRight: 4, boxShadow: '0 0 6px var(--sk-green)' }} />可对话{title ? ` · ${title}` : ''}{skill.domain ? ` · ${skill.domain === 'sales' ? 'B2B企业服务' : skill.domain}` : ''}</>
                )}
              </p>

              {grainCount > 0 && (
                <div style={{ display: 'flex', justifyContent: 'center', gap: 6, marginTop: 12, flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 11, padding: '4px 10px', borderRadius: 20, background: 'var(--sk-accent-soft)', color: 'var(--sk-accent-light)' }}>📚 {grainCount} 条实战经验</span>
                  {skill.sceneTags && skill.sceneTags.length > 0 && (
                    <span style={{ fontSize: 11, padding: '4px 10px', borderRadius: 20, background: 'var(--sk-amber-soft)', color: 'var(--sk-amber)' }}>🎯 {skill.sceneTags.length} 个业务场景</span>
                  )}
                  {stats && stats.satisfactionRate > 0 && (
                    <span style={{ fontSize: 11, padding: '4px 10px', borderRadius: 20, background: 'var(--sk-green-soft)', color: 'var(--sk-green-light)' }}>👍 {stats.satisfactionRate}% 满意率</span>
                  )}
                </div>
              )}
            </div>

            {/* KPI Stats — v2: 加人性化副文案 */}
            {stats && (stats.conversationCount > 0 || stats.userCount > 0) && (
              <div style={{
                display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 1,
                background: 'var(--sk-bg-card)', borderRadius: 20,
                marginBottom: 24, padding: 20,
                border: '1px solid var(--sk-border-subtle)',
              }}>
                <StatBadge
                  value={stats.conversationCount.toLocaleString()} label="次对话"
                  sub={stats.conversationCount > 0 ? `帮助了 ${stats.conversationCount.toLocaleString()} 位销售同行` : undefined}
                  color="var(--sk-accent)"
                />
                <StatBadge
                  value={stats.userCount.toLocaleString()} label="位用户"
                  sub={stats.userCount > 0 ? `来自 ${Math.max(1, Math.floor(stats.userCount / 3))}+ 家企业` : undefined}
                  color="var(--sk-purple)"
                />
                <StatBadge
                  value={`${stats.satisfactionRate}%`} label="满意率"
                  sub={stats.satisfactionRate > 0 ? '觉得"很有帮助"' : undefined}
                  color="var(--sk-green)"
                />
              </div>
            )}

            {/* Self Intro — v2: 引用块 + 邀请式结尾 */}
            <div style={{
              background: 'var(--sk-bg-card)', borderRadius: 20,
              border: '1px solid var(--sk-border-subtle)',
              padding: 24, marginBottom: 24,
            }}>
              <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--sk-text-primary)', marginBottom: 12 }}>{introHeadline}</div>
              <div style={{ width: 32, height: 2, background: 'var(--sk-divider-gradient)', borderRadius: 1, marginBottom: 14 }} />
              <div style={{ fontSize: 14, color: 'var(--sk-text-secondary)', lineHeight: 1.85 }}>
                {introBody.split('\n').filter(Boolean).map((p, i) => {
                  // 检测是否是引用块（以 > 开头）
                  if (p.startsWith('>')) {
                    return (
                      <blockquote key={i} style={{
                        borderLeft: '2px solid var(--sk-quote-border)', paddingLeft: 14,
                        margin: '12px 0', color: 'var(--sk-quote-color)', fontStyle: 'italic',
                      }}>{p.replace(/^>\s*/, '')}</blockquote>
                    );
                  }
                  return <p key={i} style={{ marginBottom: 8 }}>{p}</p>;
                })}
              </div>
              {introClosing && (
                <div style={{
                  marginTop: 14, fontSize: 13, color: 'var(--sk-accent)',
                  fontWeight: 500, display: 'flex', alignItems: 'center', gap: 6,
                }}>
                  💬 {introClosing}
                </div>
              )}
              {!introClosing && (
                <div style={{
                  marginTop: 14, fontSize: 13, color: 'var(--sk-accent)',
                  fontWeight: 500, display: 'flex', alignItems: 'center', gap: 6,
                }}>
                  💬 想聊聊吗？试着问我一个问题 👇
                </div>
              )}
            </div>

            {/* Questions — v2: 聊天气泡风 */}
            {(questionsLoading || questions.length > 0) && (
              <div style={{ marginBottom: 24 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--sk-text-secondary)' }}>💬 大家都在问</span>
                </div>
                <div style={{ fontSize: 11, color: 'var(--sk-text-tertiary)', marginBottom: 10 }}>
                  基于 {grainCount > 0 ? grainCount : '...'} 条实战经验{stats?.conversationCount ? ` · ${stats.conversationCount.toLocaleString()} 次真实对话` : ''}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {questionsLoading
                    ? Array.from({ length: 3 }).map((_, i) => (
                        <div key={i} style={{
                          height: 50, borderRadius: 14, borderTopLeftRadius: 4,
                          background: 'var(--sk-bg-card)',
                          animation: 'shimmer 2s ease-in-out infinite', backgroundSize: '200% 100%',
                          backgroundImage: 'linear-gradient(90deg, var(--sk-bg-card) 25%, var(--sk-bg-card-hover) 50%, var(--sk-bg-card) 75%)',
                        }} />
                      ))
                    : questions.map((q, i) => (
                        <QuestionRow key={i} q={q} onClick={() => doAction(q)} />
                      ))
                  }
                </div>
              </div>
            )}
          </div>

          {/* ═══ 右栏 ═══ */}
          <div className="right-panel-desktop" style={{ display: 'none', flexDirection: 'column', padding: '32px 0 32px 32px', width: '100%' }}>
            {/* Scene Tags */}
            {skill.sceneTags && skill.sceneTags.length > 0 && (
              <div style={{ marginBottom: 24 }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--sk-text-secondary)', marginBottom: 12, letterSpacing: '0.5px', textTransform: 'uppercase' }}>擅长场景</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                  {skill.sceneTags.map(st => (
                    <span key={st.tag} style={{
                      padding: '6px 14px', borderRadius: 100,
                      background: 'var(--sk-accent-soft)', border: '1px solid var(--sk-accent-border)',
                      fontSize: 12, color: 'var(--sk-accent-light)',
                    }}>{st.tag} <span style={{ color: 'var(--sk-text-tertiary)', marginLeft: 4 }}>{st.count}</span></span>
                  ))}
                </div>
              </div>
            )}

            {/* About */}
            <div style={{ marginBottom: 24 }}>
              <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--sk-text-secondary)', marginBottom: 10, letterSpacing: '0.5px', textTransform: 'uppercase' }}>关于{isOrg ? '团队' : 'TA'}</div>
              <div style={{ fontSize: 13, color: 'var(--sk-text-tertiary)', lineHeight: 1.8 }}>
                {introBody.length > 200 ? introBody.slice(0, 200).replace(/\n/g, ' ') + '...' : introBody.replace(/\n/g, ' ')}
              </div>
            </div>

            {/* Stats */}
            {stats && (stats.conversationCount > 0 || stats.userCount > 0) && (
              <div style={{
                background: 'var(--sk-bg-card-dim)', borderRadius: 16,
                border: '1px solid var(--sk-border-subtle)', padding: 20,
              }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--sk-text-secondary)', marginBottom: 16, letterSpacing: '0.5px', textTransform: 'uppercase' }}>互动数据</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontSize: 13, color: 'var(--sk-text-tertiary)' }}>💬 对话次数</span>
                    <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--sk-text-primary)' }}>{stats.conversationCount.toLocaleString()}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontSize: 13, color: 'var(--sk-text-tertiary)' }}>👤 咨询用户</span>
                    <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--sk-text-primary)' }}>{stats.userCount.toLocaleString()}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontSize: 13, color: 'var(--sk-text-tertiary)' }}>👍 满意率</span>
                    <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--sk-green)' }}>{stats.satisfactionRate}%</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>

        <div style={{ height: 120 }} />
      </div>

      {/* ═══ 主题选择器 ═══ */}
      <div style={{ position: 'fixed', bottom: 120, right: 20, zIndex: 100 }}>
        {showSkinPicker && (
          <div onClick={e => e.stopPropagation()} style={{
            position: 'absolute', bottom: '100%', right: 0, marginBottom: 8,
            background: 'var(--sk-bg-card-solid)', borderRadius: 14,
            boxShadow: 'var(--sk-shadow-modal)',
            border: '1px solid var(--sk-border-medium)', padding: 8, minWidth: 160,
          }}>
            <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--sk-text-tertiary)', padding: '4px 8px 6px' }}>🎨 背景色</div>
            {SKINS.map(s => (
              <button
                key={s.key}
                onClick={() => { setSkin(s.key); setShowSkinPicker(false); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                  padding: '7px 10px', borderRadius: 8, border: 'none', cursor: 'pointer',
                  fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
                  background: skin === s.key ? 'var(--sk-accent-soft)' : 'transparent',
                  color: skin === s.key ? 'var(--sk-accent)' : 'var(--sk-text-secondary)',
                  transition: 'all 0.15s',
                }}
                onMouseEnter={e => { if (skin !== s.key) e.currentTarget.style.background = 'var(--sk-bg-card-hover)'; }}
                onMouseLeave={e => { if (skin !== s.key) e.currentTarget.style.background = 'transparent'; }}
              >
                <span style={{
                  width: 14, height: 14, borderRadius: '50%', flexShrink: 0,
                  background: s.dot,
                  border: '2px solid var(--sk-border-medium)',
                }} />
                {s.label || '深空黑'}
                {skin === s.key && <span style={{ marginLeft: 'auto', fontSize: 11 }}>✓</span>}
              </button>
            ))}
          </div>
        )}
        <button
          onClick={() => setShowSkinPicker(!showSkinPicker)}
          style={{
            width: 40, height: 40, borderRadius: 12,
            background: 'var(--sk-bg-cta)', border: '1px solid var(--sk-border-medium)',
            cursor: 'pointer', fontSize: 18,
            boxShadow: 'var(--sk-shadow-cta)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            backdropFilter: 'blur(12px)',
            transition: 'transform 0.2s',
          }}
          onMouseEnter={e => { e.currentTarget.style.transform = 'scale(1.08)'; }}
          onMouseLeave={e => { e.currentTarget.style.transform = 'scale(1)'; }}
          aria-label="切换背景色"
        >🎨</button>
      </div>

      {/* ═══ 底部输入栏 ═══ */}
      <div style={{
        position: 'fixed', bottom: 0, left: 0, right: 0, margin: '0 auto',
        width: '100%', maxWidth: 600, zIndex: 50, padding: '0 20px 24px',
      }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10,
          background: 'var(--sk-bg-cta)', backdropFilter: 'blur(20px)',
          border: '1px solid var(--sk-border-medium)', borderRadius: 18,
          padding: '4px 4px 4px 18px',
          boxShadow: 'var(--sk-shadow-cta)',
        }}>
          <input
            value={inputValue}
            onChange={e => setInputValue(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && hasInput) doAction(); }}
            placeholder={currentPlaceholder}
            style={{
              flex: 1, border: 'none', background: 'transparent', outline: 'none',
              fontSize: 14, padding: '12px 0', color: 'var(--sk-text-primary)', minWidth: 0,
              fontFamily: 'inherit',
            }}
          />
          {hasInput && (
            <button onClick={() => setInputValue('')} aria-label="清除输入" style={{
              width: 28, height: 28, borderRadius: '50%', background: 'var(--sk-bg-btn-ghost)',
              border: 'none', cursor: 'pointer', fontSize: 12, color: 'var(--sk-text-tertiary)',
              display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
            }}>✕</button>
          )}
          <button onClick={() => doAction()} style={{
            padding: '11px 20px', borderRadius: 14,
            background: 'var(--sk-accent)', color: '#fff', border: 'none', cursor: 'pointer',
            fontSize: 14, fontWeight: 600, whiteSpace: 'nowrap', flexShrink: 0,
            fontFamily: 'inherit',
          }}>
            {hasInput ? '发送' : '开始对话'} <span>{hasInput ? '➤' : '→'}</span>
          </button>
        </div>
        <div style={{ fontSize: 11, color: 'var(--sk-text-tertiary)', textAlign: 'center', marginTop: 8 }}>
          💡 放心问，不满意不花钱{hasInput ? ' · 按 Enter 发送' : ' · 或点上面的推荐问题'}
        </div>
      </div>

      {/* ═══ 分享弹窗 ═══ */}
      {showShareModal && (pubCode || cardCode) && (
        <div onClick={() => setShowShareModal(false)} style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,.6)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200, padding: 20,
        }}>
          <div onClick={e => e.stopPropagation()} style={{
            background: 'var(--sk-bg-card-solid)', borderRadius: 20, padding: '28px 24px',
            width: '90%', maxWidth: 380, textAlign: 'center',
            border: '1px solid var(--sk-border-medium)',
            boxShadow: 'var(--sk-shadow-modal)',
          }}>
            <h3 style={{ fontSize: 17, fontWeight: 700, color: 'var(--sk-text-primary)', margin: '0 0 4px' }}>📤 分享名片</h3>
            <p style={{ fontSize: 12, color: 'var(--sk-text-tertiary)', margin: '0 0 16px' }}>两种分享方式，选一个发给对方</p>
            {pubCode && <ShareLinkRow icon="📱" label="经典版" desc="标准落地页，含销冠名片+三模式入口"
              url={`${typeof window !== 'undefined' ? (process.env.NEXT_PUBLIC_SHARE_BASE_URL || window.location.origin) : ''}/s/${pubCode}`}
              copied={copied === pubCode} onCopy={() => handleCopy(pubCode!)} />}
            {cardCode && <ShareLinkRow icon="🎴" label="名片版" desc="新卡片式布局，更简洁直观"
              url={`${typeof window !== 'undefined' ? (process.env.NEXT_PUBLIC_SHARE_BASE_URL || window.location.origin) : ''}/s/${cardCode}`}
              copied={copied === cardCode} onCopy={() => handleCopy(cardCode!)} />}
            <button onClick={() => setShowShareModal(false)} style={{
              marginTop: 12, padding: '10px 24px', borderRadius: 10,
              background: 'var(--sk-bg-btn-ghost)', color: 'var(--sk-text-tertiary)',
              border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
              fontFamily: 'inherit', width: '100%',
            }}>关闭</button>
          </div>
        </div>
      )}

      {shareError && (
        <div style={{
          position: 'fixed', bottom: 120, left: '50%', transform: 'translateX(-50%)',
          padding: '10px 20px', borderRadius: 100, background: 'var(--sk-danger-bg)',
          color: '#fff', fontSize: 13, fontWeight: 500, zIndex: 200,
          boxShadow: '0 4px 12px rgba(220,38,38,.3)',
          display: 'flex', alignItems: 'center', gap: 10,
        }}>
          <span>⚠️ {shareError}</span>
          <button onClick={() => router.push('/login')} style={{
            background: 'rgba(255,255,255,.2)', border: 'none', borderRadius: 6,
            color: '#fff', padding: '3px 10px', cursor: 'pointer', fontSize: 12,
            fontWeight: 600, fontFamily: 'inherit',
          }}>去登录</button>
          <span onClick={() => setShareError(null)} style={{ cursor: 'pointer', fontSize: 14 }}>✕</span>
        </div>
      )}
    </div>
  );
}

interface Props { skill: SkillDetail; }
