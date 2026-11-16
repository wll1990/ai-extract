'use client';

import React, { useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { setAuth } from '@/lib/storage';

export default function RegisterPage() {
  const router = useRouter();
  const [companyCode, setCompanyCode] = useState('');
  const [name, setName] = useState('');
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleRegister = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    if (!companyCode.trim()) { setError('请输入企业注册码'); return; }
    if (!name || !account || !password) { setError('请填写所有字段'); return; }
    if (password.length < 6) { setError('密码至少6位'); return; }
    setLoading(true); setError('');
    try {
      const r = await fetch('/api/v1/auth/register/with-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ companyCode: companyCode.trim(), name, account, password }),
      });
      const d = await r.json();
      if (d.code !== 200) throw new Error(d.message || '注册失败');
      setAuth(d.data.token, d.data.user);
      router.push('/');
    } catch (err) { setError(err instanceof Error ? err.message : '注册失败，请重试'); }
    finally { setLoading(false); }
  }, [companyCode, name, account, password, router]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface px-6">
      <div className="w-full max-w-md rounded-2xl bg-surface-2 p-8 shadow-lg">
        <h1 className="text-2xl font-bold text-foreground text-center">创建账号</h1>
        <p className="mt-2 text-sm text-muted-foreground text-center">注册后即可创建你的AI分身</p>

        <form onSubmit={handleRegister} className="mt-8 space-y-4">
          <div>
            <label className="block text-sm font-medium text-foreground mb-1">企业注册码</label>
            <input type="text" value={companyCode} onChange={e => setCompanyCode(e.target.value)}
              placeholder="管理员提供的注册码"
              className="w-full rounded-lg border border-border bg-surface px-4 py-2.5 text-sm text-foreground outline-none focus:border-primary" />
          </div>
          <div>
            <label className="block text-sm font-medium text-foreground mb-1">真实姓名</label>
            <input type="text" value={name} onChange={e => setName(e.target.value)}
              placeholder="例如：潘露婷"
              className="w-full rounded-lg border border-border bg-surface px-4 py-2.5 text-sm text-foreground outline-none focus:border-primary" />
          </div>
          <div>
            <label className="block text-sm font-medium text-foreground mb-1">登录账号</label>
            <input type="text" value={account} onChange={e => setAccount(e.target.value)}
              placeholder="英文或拼音"
              className="w-full rounded-lg border border-border bg-surface px-4 py-2.5 text-sm text-foreground outline-none focus:border-primary" />
          </div>
          <div>
            <label className="block text-sm font-medium text-foreground mb-1">密码</label>
            <input type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)}
              placeholder="至少6位"
              className="w-full rounded-lg border border-border bg-surface px-4 py-2.5 text-sm text-foreground outline-none focus:border-primary" />
          </div>
          {error && <p className="text-sm text-danger">{error}</p>}

          <button type="submit" disabled={loading}
            className="w-full rounded-lg bg-foreground py-2.5 text-sm font-semibold text-white disabled:opacity-50">
            {loading ? '注册中...' : '注册'}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-muted-foreground">
          已有账号？<Link href="/login" className="text-primary hover:underline">去登录</Link>
        </p>
      </div>
    </div>
  );
}
