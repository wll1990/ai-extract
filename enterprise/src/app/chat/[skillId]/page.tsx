'use client';

import { useState, useEffect } from 'react';
import { useParams } from 'next/navigation';
import { fetchSkillDetail, type SkillDetail } from '@/lib/api/skill';
import { ChatView } from '@/components/chat/ChatView';

export default function ChatPage() {
  const params = useParams();
  const skillId = (params.skillId as string) || '';
  const [skill, setSkill] = useState<SkillDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!skillId) return;
    setLoading(true);
    fetchSkillDetail(skillId)
      .then(s => setSkill(s))
      .catch(() => setError('加载专家信息失败'))
      .finally(() => setLoading(false));
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

  return <ChatView skill={skill} />;
}
