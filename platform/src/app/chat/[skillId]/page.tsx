'use client';

import { useState, useEffect } from 'react';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { fetchSkillDetail, type SkillDetail } from '@/lib/api/skill';
import { getUser } from '@/lib/storage';
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
  const [user, setUser] = useState<{ name: string } | null>(null);

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
    if (u?.name) setUser({ name: u.name });
  }, []);

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
      {/* 顶部导航：返回 + 用户 */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '8px 20px', borderBottom: '1px solid var(--s5)',
        background: 'var(--surface)', flexShrink: 0,
      }}>
        <button onClick={() => router.back()} style={{
          background: 'none', border: 'none', cursor: 'pointer',
          fontSize: 13, color: 'var(--fg-mid)', fontFamily: 'inherit',
          display: 'flex', alignItems: 'center', gap: 4,
        }}>
          ← 返回
        </button>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {!mounted ? null : user ? (
            <span style={{ fontSize: 12, color: 'var(--fg-mid)' }}>
              {user.name}
            </span>
          ) : (
            <Link href="/login" style={{
              fontSize: 12, color: 'var(--tangerine)', textDecoration: 'none', fontWeight: 500,
            }}>
              登录
            </Link>
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
