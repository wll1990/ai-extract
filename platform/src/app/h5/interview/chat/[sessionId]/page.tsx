'use client';

import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { getSession } from '@/lib/api/interview';
import { SalesInterviewChat } from '@/components/interview/SalesInterviewChat';
import { useRequireAuth } from '@/hooks/useRequireAuth';

export default function H5InterviewChatPage() {
  useRequireAuth();
  const params = useParams();
  const sessionId = params.sessionId as string;
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId) { setLoading(false); return; }
    getSession(sessionId)
      .then(() => setLoading(false))
      .catch(() => { setError('会话不存在'); setLoading(false); });
  }, [sessionId]);

  if (loading) return (
    <div className="flex min-h-screen items-center justify-center bg-[#f7f9ff]">
      <LoadingSpinner fullScreen={false} />
    </div>
  );

  if (error) return (
    <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center justify-center px-6 text-center">
      <span className="text-4xl mb-3">⚠️</span>
      <h2 className="text-lg font-semibold text-[#10162f] mb-2">{error}</h2>
      <button onClick={() => router.push('/h5/interview/start')} style={{
        marginTop: 12, padding: '8px 20px', borderRadius: 100, border: 'none', cursor: 'pointer',
        background: '#2147ff', color: '#fff', fontSize: 13, fontWeight: 600, fontFamily: 'inherit',
      }}>返回入口页</button>
    </div>
  );

  return (
    <div className="h5-interview-wrapper">
      <button
        onClick={() => { if (confirm('确定退出访谈吗？')) router.push(`/h5/interview/done?sessionId=${sessionId}`); }}
        style={{
          position: 'fixed', top: 12, left: 12, zIndex: 30,
          padding: '6px 14px', borderRadius: 100, border: '1px solid #e8ecf4',
          background: 'rgba(255,255,255,0.85)', backdropFilter: 'blur(8px)',
          fontSize: 12, color: '#5b6886', cursor: 'pointer', fontFamily: 'inherit',
          fontWeight: 500, transition: 'all 0.15s',
        }}
        onMouseEnter={e => { e.currentTarget.style.background = '#fff'; e.currentTarget.style.color = '#10162f'; }}
        onMouseLeave={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.85)'; e.currentTarget.style.color = '#5b6886'; }}
      >
        ← 退出
      </button>
      <SalesInterviewChat />
    </div>
  );
}
