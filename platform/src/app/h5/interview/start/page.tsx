'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';

export default function H5InterviewStartPage() {
  const router = useRouter();
  const [topic, setTopic] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cUser, setCUser] = useState<{ userId: string; extractionRemaining?: number; extractionLimit?: number } | null>(null);
  const [authChecked, setAuthChecked] = useState(false);
  const [activeSession, setActiveSession] = useState<{ sessionId: string; topic: string } | null>(null);

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

          // 检测是否有进行中的萃取会话
          fetch('/api/v1/interviews/active', {
            headers: { Authorization: `Bearer ${session.token}` },
          })
            .then((r) => r.json())
            .then((d) => {
              if (d.code === 200 && d.data) setActiveSession(d.data);
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

  return (
    <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center px-5 py-10" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="w-full max-w-sm">
        <button onClick={() => router.push('/platform/my')} className="text-sm text-[#747f9e] hover:text-[#10162f] mb-6">← 返回</button>
        <h1 className="text-xl font-bold text-[#10162f] mb-1">AI 经验萃取师</h1>
        <p className="text-sm text-[#747f9e] mb-8">发现你未被看见的价值</p>

        {activeSession && (
          <div className="mb-4 p-4 rounded-xl bg-white border border-[#2147ff]/20 shadow-sm">
            <p className="text-sm font-medium text-[#10162f] mb-1">有正在进行的萃取</p>
            <p className="text-xs text-[#747f9e] mb-3">「{activeSession.topic}」</p>
            <button onClick={() => router.push(`/h5/interview/chat/${activeSession.sessionId}`)}
              className="px-4 py-2 rounded-full bg-[#2147ff] text-white text-sm font-medium">
              继续萃取 →
            </button>
          </div>
        )}

        {authChecked && cUser && cUser.extractionRemaining !== undefined && (
          <div className="mb-4 px-4 py-2.5 rounded-xl bg-white border border-[#dfe6ff] text-sm text-[#10162f]">
            剩余 <span className="font-semibold text-[#2147ff]">{cUser.extractionRemaining}</span> 次免费萃取
            {cUser.extractionRemaining === 0 && (
              <button className="ml-2 text-[#2147ff] font-medium" onClick={() => router.push('/platform/my')}>升级会员</button>
            )}
          </div>
        )}

        <div className="bg-white rounded-[26px] border border-[#e1e7ff] p-5 shadow-[0_18px_50px_rgba(42,74,177,0.10)] mb-4">
          <label className="block text-sm font-medium text-[#10162f] mb-2">这次想萃取什么经验？</label>
          <textarea
            value={topic} onChange={(e) => setTopic(e.target.value)}
            placeholder='比如"搞定说太贵的客户"'
            maxLength={100} rows={3}
            className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff] resize-none"
          />
        </div>

        {error && (
          <div className="mb-4 px-4 py-2.5 rounded-xl bg-red-50 border border-red-200 text-sm text-red-600">{error}</div>
        )}

        <button
          onClick={handleStart} disabled={submitting || !topic.trim()}
          className="w-full py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40 hover:translate-y-[-1px] transition-transform"
        >
          {submitting ? '创建中...' : '开始萃取'}
        </button>

        {authChecked && !cUser && (
          <div className="mt-6 text-center text-sm text-[#747f9e]">
            <button onClick={() => router.push('/login')} className="text-[#2147ff] font-medium">登录</button>
            <span className="mx-1">/</span>
            <button onClick={() => router.push('/h5/register')} className="text-[#2147ff] font-medium">注册</button>
          </div>
        )}
      </div>
    </div>
  );
}
