'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import PlatformTopBar from '@/components/ui/PlatformTopBar';

// ── Design tokens (same neutral system) ──

const C = {
  pageBg: '#f8fafc',
  cardBg: '#ffffff',
  cardBorder: '#e2e8f0',
  text: '#0f172a',
  textMid: '#475569',
  textLow: '#94a3b8',
  accent: '#2563eb',
  accentHover: '#1d4ed8',
  red: '#dc2626',
  redBg: '#fef2f2',
  shadowMd: '0 4px 12px rgba(0,0,0,0.06)',
};

export default function H5InterviewStartPage() {
  const router = useRouter();
  const [topic, setTopic] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cUser, setCUser] = useState<{ userId: string; extractionRemaining?: number; extractionLimit?: number } | null>(null);
  const [authChecked, setAuthChecked] = useState(false);
  const [activeSession, setActiveSession] = useState<{ sessionId: string; topic: string } | null>(null);
  const [stopping, setStopping] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('c_auth');
    if (stored) {
      try {
        const session = JSON.parse(stored);
        if (session?.token) {
          fetch('/api/v1/c/auth/me', {
            headers: { Authorization: `Bearer ${session.token}` },
          })
            .then((r) => r.json())
            .then((d) => {
              if (d.code === 200) setCUser(d.data);
            })
            .catch(() => {});

          fetch('/api/v1/interviews/active', {
            headers: { Authorization: `Bearer ${session.token}` },
          })
            .then((r) => r.json())
            .then((d) => {
              if (d.code === 200 && d.data?.sessions?.length) setActiveSession(d.data.sessions[0]);
            })
            .catch(() => {});
        }
      } catch {}
    }
    setAuthChecked(true);
  }, []);

  const handleStart = async () => {
    if (!topic.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const stored = localStorage.getItem('c_auth');
      const token = stored ? JSON.parse(stored)?.token : null;
      if (!token) {
        setError('请先登录或注册');
        setSubmitting(false);
        return;
      }
      const r = await fetch('/api/v1/interviews', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ topic: topic.trim() }),
      });
      const d = await r.json();
      if (d.code === 200 && d.data?.sessionId) {
        router.push(`/h5/interview/chat/${d.data.sessionId}`);
      } else if (d.code === 402) {
        setError('免费萃取次数已用完，请升级会员');
      } else {
        setError(d.message || '创建访谈失败');
      }
    } catch {
      setError('网络错误，请重试');
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = async () => {
    if (!activeSession) return;
    setStopping(true);
    try {
      const stored = localStorage.getItem('c_auth');
      const token = stored ? JSON.parse(stored)?.token : null;
      const r = await fetch(`/api/v1/interviews/${activeSession.sessionId}/cancel`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      });
      const d = await r.json();
      if (d.code === 200) {
        setActiveSession(null);
      } else {
        setError(d.message || '停止失败');
      }
    } catch {
      setError('网络错误，请重试');
    } finally {
      setStopping(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', background: C.pageBg }}>
      <PlatformTopBar backTo="/platform/my" backLabel="我的分身" />

      <div style={{ maxWidth: 440, margin: '0 auto', padding: '48px 20px 80px' }}>
        {/* Header */}
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <div style={{ fontSize: 48, marginBottom: 16 }}>🧠</div>
          <h1 style={{ fontSize: 24, fontWeight: 700, color: C.text, margin: '0 0 8px', letterSpacing: '-0.02em' }}>
            AI 经验萃取师
          </h1>
          <p style={{ fontSize: 14, color: C.textMid, margin: 0 }}>
            发现你未被看见的价值
          </p>
        </div>

        {/* Active session card */}
        {activeSession && (
          <div style={{
            background: C.cardBg, borderRadius: 16, border: `1px solid ${C.cardBorder}`,
            padding: '20px', marginBottom: 20, boxShadow: C.shadowMd,
          }}>
            <p style={{ fontSize: 14, fontWeight: 600, color: C.text, margin: '0 0 4px' }}>有正在进行的萃取</p>
            <p style={{ fontSize: 13, color: C.textMid, margin: '0 0 16px' }}>「{activeSession.topic}」</p>
            <div style={{ display: 'flex', gap: 10 }}>
              <button onClick={() => router.push(`/h5/interview/chat/${activeSession.sessionId}`)} style={{
                flex: 1, padding: '10px 20px', borderRadius: 100, border: 'none', cursor: 'pointer',
                background: C.accent, color: '#fff', fontSize: 13, fontWeight: 600, fontFamily: 'inherit',
              }}>
                继续萃取 →
              </button>
              <button onClick={handleCancel} disabled={stopping} style={{
                padding: '10px 18px', borderRadius: 100, cursor: stopping ? 'not-allowed' : 'pointer',
                border: '1px solid #fecaca', background: '#fff', color: C.red,
                fontSize: 13, fontWeight: 500, fontFamily: 'inherit', transition: 'all 0.15s',
                opacity: stopping ? 0.5 : 1,
              }}
                onMouseEnter={e => { if (!stopping) { e.currentTarget.style.background = C.redBg; e.currentTarget.style.borderColor = '#fca5a5'; } }}
                onMouseLeave={e => { if (!stopping) { e.currentTarget.style.background = '#fff'; e.currentTarget.style.borderColor = '#fecaca'; } }}
              >
                {stopping ? '停止中...' : '停止萃取'}
              </button>
            </div>
          </div>
        )}

        {/* Remaining quota */}
        {authChecked && cUser && cUser.extractionRemaining !== undefined && (
          <div style={{
            padding: '10px 16px', borderRadius: 12, marginBottom: 20,
            background: C.cardBg, border: `1px solid ${C.cardBorder}`,
            fontSize: 13, color: C.textMid,
          }}>
            剩余 <span style={{ fontWeight: 700, color: C.accent }}>{cUser.extractionRemaining}</span> 次免费萃取
            {cUser.extractionRemaining === 0 && (
              <button onClick={() => router.push('/platform/my')} style={{
                background: 'none', border: 'none', cursor: 'pointer',
                color: C.accent, fontWeight: 600, fontSize: 13, fontFamily: 'inherit',
                marginLeft: 8,
              }}>升级会员</button>
            )}
          </div>
        )}

        {/* Topic input card */}
        <div style={{
          background: C.cardBg, borderRadius: 16, border: `1px solid ${C.cardBorder}`,
          padding: '24px', marginBottom: 20, boxShadow: C.shadowMd,
        }}>
          <label style={{ display: 'block', fontSize: 14, fontWeight: 600, color: C.text, marginBottom: 10 }}>
            这次想萃取什么经验？
          </label>
          <textarea
            value={topic} onChange={(e) => setTopic(e.target.value)}
            placeholder='比如"搞定说太贵的客户"'
            maxLength={100} rows={3}
            style={{
              width: '100%', padding: '12px 14px', borderRadius: 12,
              border: `1.5px solid ${C.cardBorder}`, background: '#f8fafc',
              fontSize: 14, color: C.text, outline: 'none', resize: 'none',
              fontFamily: 'inherit', lineHeight: 1.5, boxSizing: 'border-box',
              transition: 'border-color 0.15s',
            }}
            onFocus={e => { e.currentTarget.style.borderColor = C.accent; e.currentTarget.style.background = '#fff'; }}
            onBlur={e => { e.currentTarget.style.borderColor = C.cardBorder; e.currentTarget.style.background = '#f8fafc'; }}
          />
        </div>

        {/* Error */}
        {error && (
          <div style={{
            padding: '12px 16px', borderRadius: 12, marginBottom: 20,
            background: C.redBg, border: '1px solid #fecaca',
            fontSize: 13, color: C.red,
          }}>{error}</div>
        )}

        {/* Submit button */}
        <button
          onClick={handleStart} disabled={submitting || !topic.trim()}
          style={{
            width: '100%', padding: '14px 0', borderRadius: 100,
            border: 'none', cursor: submitting || !topic.trim() ? 'not-allowed' : 'pointer',
            background: submitting || !topic.trim() ? '#94a3b8' : C.accent,
            color: '#fff', fontSize: 15, fontWeight: 600, fontFamily: 'inherit',
            transition: 'all 0.15s', opacity: submitting || !topic.trim() ? 0.7 : 1,
          }}
          onMouseEnter={e => { if (!submitting && topic.trim()) e.currentTarget.style.background = C.accentHover; }}
          onMouseLeave={e => { if (!submitting && topic.trim()) e.currentTarget.style.background = C.accent; }}
        >
          {submitting ? '创建中...' : '开始萃取'}
        </button>

        {/* Login/register links */}
        {authChecked && !cUser && (
          <div style={{ marginTop: 24, textAlign: 'center', fontSize: 13, color: C.textLow }}>
            <button onClick={() => router.push('/login')} style={{
              background: 'none', border: 'none', cursor: 'pointer', color: C.accent, fontWeight: 600, fontSize: 13, fontFamily: 'inherit',
            }}>登录</button>
            <span style={{ margin: '0 6px' }}>/</span>
            <button onClick={() => router.push('/h5/register')} style={{
              background: 'none', border: 'none', cursor: 'pointer', color: C.accent, fontWeight: 600, fontSize: 13, fontFamily: 'inherit',
            }}>注册</button>
          </div>
        )}
      </div>
    </div>
  );
}
