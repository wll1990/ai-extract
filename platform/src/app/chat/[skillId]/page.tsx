'use client';

import { useState, useEffect, useCallback } from 'react';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { fetchSkillDetail, type SkillDetail } from '@/lib/api/skill';
import { getUser, getToken, clearAuth } from '@/lib/storage';
import { logout as logoutApi } from '@/lib/api/auth';
import { createGuestBySkillId, setCAuth } from '@/lib/api/c';
import { ChatView } from '@/components/chat/ChatView';

export default function ChatPage() {
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const skillId = (params.skillId as string) || '';
  const initialQ = searchParams.get('q');
  const initialMode = searchParams.get('mode');
  const [skill, setSkill] = useState<SkillDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  const [user, setUser] = useState<{ name: string; role: string } | null>(null);

  useEffect(() => {
    if (!skillId) return;
    setLoading(true);
    fetchSkillDetail(skillId)
      .then(s => setSkill(s))
      .catch(() => setError('加载专家信息失败'))
      .finally(() => setLoading(false));
  }, [skillId]);

  useEffect(() => {
    setMounted(true);
    const u = getUser();
    if (u?.name) setUser({ name: u.name, role: u.role as string });
  }, []);

  const handleLogout = useCallback(async () => {
    try { await logoutApi(); } catch { /* ignore */ }
    clearAuth();
    setUser(null);
    router.push('/discover');
  }, [router]);

  // 未登录 → 静默领游客证（PC 聊天页游客也能聊，与分享页共用发证机制）
  useEffect(() => {
    if (!skillId) return;
    if (getToken()) return;
    createGuestBySkillId(skillId).then(resp => {
      if (resp.token) {
        setCAuth(resp.token, {
          userId: resp.userId,
          nickname: resp.nickname,
          status: resp.status,
          remaining: resp.remaining,
          limit: resp.limit,
        });
      }
    }).catch(() => { /* 静默失败：聊天接口会返回 401 提示登录 */ });
  }, [skillId]);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '60vh', color: 'var(--fg-mid)' }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
          <div style={{ width: 32, height: 32, border: '3px solid var(--s5)', borderTopColor: 'var(--tangerine)', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />
          <span style={{ fontSize: 13 }}>加载中...</span>
        </div>
      </div>
    );
  }

  if (error || !skill) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '60vh', color: 'var(--fg-mid)' }}>
        <div style={{ textAlign: 'center' }}>
          <div style={{ fontSize: 48, marginBottom: 16 }}>😕</div>
          <p style={{ fontSize: 14 }}>{error || '专家不存在'}</p>
          <p style={{ fontSize: 12, marginTop: 4, color: 'var(--fg-low)' }}>请确认链接是否正确，或返回发现页重新选择</p>
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      {/* 顶部导航 */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '8px 20px', borderBottom: '1px solid #e8ecf4',
        background: 'rgba(255,255,255,0.9)', backdropFilter: 'blur(8px)', flexShrink: 0,
      }}>
        <button onClick={() => router.back()} style={{
          background: 'none', border: 'none', cursor: 'pointer', padding: '6px 12px',
          borderRadius: 8, fontSize: 13, color: '#5b6886', fontFamily: 'inherit',
          display: 'flex', alignItems: 'center', gap: 4, marginLeft: -12, transition: 'all 0.15s',
        }}
          onMouseEnter={e => { e.currentTarget.style.background = '#f5f7fd'; e.currentTarget.style.color = '#10162f'; }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = '#5b6886'; }}
        >
          ← 返回
        </button>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {!mounted ? null : user && user.role !== 'guest' ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontSize: 12, fontWeight: 500, color: '#5b6886' }}>
                {user.name}
              </span>
              <button onClick={handleLogout} style={{
                background: 'none', border: 'none', cursor: 'pointer',
                fontSize: 12, color: '#94a3b8', padding: '4px 6px',
                borderRadius: 6, fontFamily: 'inherit',
              }}>退出</button>
            </div>
          ) : (
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <Link href="/login" style={{
                fontSize: 12, color: '#2147ff', textDecoration: 'none', fontWeight: 600,
              }}>
                登录
              </Link>
              <Link href="/register" style={{
                fontSize: 12, color: '#2147ff', textDecoration: 'none', fontWeight: 600,
                border: '1px solid #2147ff', borderRadius: 6, padding: '4px 12px',
              }}>
                注册
              </Link>
            </div>
          )}
        </div>
      </div>

      {/* 聊天主体 */}
      <div style={{ flex: 1, minHeight: 0 }}>
        <ChatView skill={skill} initialQuestion={initialQ || undefined} initialMode={initialMode || undefined} />
      </div>
    </div>
  );
}
