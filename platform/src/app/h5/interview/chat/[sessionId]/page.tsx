'use client';

import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { getSession } from '@/lib/api/interview';
import { SalesInterviewChat } from '@/components/interview/SalesInterviewChat';

export default function H5InterviewChatPage() {
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
      <button onClick={() => router.push('/h5/interview/start')} className="mt-4 text-sm text-[#2147ff] font-medium">返回入口页</button>
    </div>
  );

  return (
    <div className="h5-interview-wrapper">
      <button
        onClick={() => { if (confirm('确定退出访谈吗？')) router.push(`/h5/interview/done?sessionId=${sessionId}`); }}
        className="fixed top-4 left-4 z-30 px-3 py-1.5 rounded-full bg-white/80 backdrop-blur border border-[#dfe6ff] text-sm text-[#747f9e]"
      >
        ← 退出
      </button>
      <SalesInterviewChat />
    </div>
  );
}
