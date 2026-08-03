'use client';

import { useEffect, useState, Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import EmptyState from '@/components/ui/EmptyState';

interface CompanyInfo {
  companyId: string;
  companyName: string;
}

function RegisterForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const code = searchParams.get('code') || '';

  const [company, setCompany] = useState<CompanyInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [account, setAccount] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!code) {
      setError('缺少企业注册码，请联系企业管理员获取');
      setLoading(false);
      return;
    }
    fetch(`/api/v1/public/company-code/${encodeURIComponent(code)}`)
      .then((r) => {
        if (!r.ok) throw new Error();
        return r.json();
      })
      .then((d) => {
        if (d.code === 200) setCompany(d.data);
        else throw new Error(d.message || '注册码无效');
      })
      .catch(() => setError('注册码无效或已过期，请联系企业管理员'))
      .finally(() => setLoading(false));
  }, [code]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!account.trim() || !name.trim() || password.length < 6) return;
    setSubmitting(true);
    try {
      const r = await fetch('/api/v1/auth/register/with-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ companyCode: code, account: account.trim(), name: name.trim(), password }),
      });
      const d = await r.json();
      if (d.code === 200) {
        setDone(true);
      } else {
        setError(d.message || '注册失败');
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
        <h1 className="text-xl font-bold text-[#10162f] mb-1">加入{company?.companyName || '企业'}</h1>
        <p className="text-sm text-[#747f9e] mb-6">使用企业注册码创建账号</p>

        {loading && <div className="text-center py-12 text-[#747f9e] text-sm">加载中...</div>}

        {error && !loading && (
          <EmptyState icon="🔒" title={error} description="请联系企业管理员获取有效的注册码" />
        )}

        {done && (
          <div className="text-center py-12">
            <span className="text-4xl mb-3 block">✅</span>
            <h2 className="text-lg font-semibold text-[#10162f] mb-2">注册成功！</h2>
            <p className="text-sm text-[#747f9e] mb-6">请使用刚才的账号密码登录</p>
            <button
              onClick={() => router.push('/login')}
              className="px-6 py-2.5 rounded-full bg-[#2147ff] text-white text-sm font-medium"
            >
              去登录
            </button>
          </div>
        )}

        {!loading && !error && !done && (
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm text-[#747f9e] mb-1">企业码</label>
              <input
                type="text" value={code} disabled
                className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-gray-50 text-[#747f9e] text-sm"
              />
            </div>
            <div>
              <label className="block text-sm text-[#747f9e] mb-1">公司</label>
              <input
                type="text" value={company?.companyName || ''} disabled
                className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-gray-50 text-[#747f9e] text-sm"
              />
            </div>
            <div>
              <label className="block text-sm text-[#747f9e] mb-1">账号</label>
              <input
                type="text" value={account} onChange={(e) => setAccount(e.target.value)}
                placeholder="手机号或邮箱" required minLength={4} maxLength={50}
                className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]"
              />
            </div>
            <div>
              <label className="block text-sm text-[#747f9e] mb-1">姓名</label>
              <input
                type="text" value={name} onChange={(e) => setName(e.target.value)}
                placeholder="你的真实姓名" required maxLength={50}
                className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]"
              />
            </div>
            <div>
              <label className="block text-sm text-[#747f9e] mb-1">密码</label>
              <input
                type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                placeholder="至少6位" required minLength={6} maxLength={64}
                className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]"
              />
            </div>
            <button
              type="submit" disabled={submitting}
              className="w-full py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40 hover:translate-y-[-1px] transition-transform"
            >
              {submitting ? '注册中...' : '注册'}
            </button>
            <p className="text-center text-xs text-[#747f9e]">
              已有账号？
              <button type="button" onClick={() => router.push('/login')} className="text-[#2147ff] ml-1">去登录</button>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}

export default function H5RegisterPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center text-sm text-[#747f9e]">加载中...</div>}>
      <RegisterForm />
    </Suspense>
  );
}
