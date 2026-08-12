'use client';

import React, { useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { setAuth } from '@/lib/storage';
import { login } from '@/lib/api/auth';

export default function LoginPage() {
  const router = useRouter();
  const [companyCode, setCompanyCode] = useState('');
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleLogin = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    if (!account || !password) { setError('请输入账号和密码'); return; }
    setLoading(true); setError('');
    try {
      const result = await login({ companyCode: companyCode.trim().toUpperCase(), account, password });
      setAuth(result.token, result.user);
      router.push('/');
    } catch (err) { setError(err instanceof Error ? err.message : '网络错误，请检查连接'); }
    finally { setLoading(false); }
  }, [companyCode, account, password, router]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface px-6">
      <div className="flex w-full max-w-[960px] overflow-hidden rounded-3xl bg-surface-2 shadow-xl">
        {/* 左侧表单 */}
        <div className="flex-1 px-10 py-14">
          <div className="mb-10 text-center">
            <h1 className="text-2xl font-bold text-foreground">AI经验萃取平台</h1>
            <p className="mt-2 text-sm text-muted-foreground">登录你的企业账号</p>
          </div>
          <form onSubmit={handleLogin} className="space-y-5">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">企业注册码</label>
              <input type="text" value={companyCode} onChange={e => setCompanyCode(e.target.value)}
                placeholder="请输入企业注册码"
                className={`w-full rounded-lg border px-4 py-3 text-sm outline-none transition-colors ${error ? 'border-danger' : 'border-border-strong focus:border-foreground'}`} />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">账号</label>
              <input type="text" value={account} onChange={e => setAccount(e.target.value)} placeholder="请输入账号"
                className={`w-full rounded-lg border px-4 py-3 text-sm outline-none transition-colors ${error ? 'border-danger' : 'border-border-strong focus:border-foreground'}`} />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">密码</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="请输入密码"
                className={`w-full rounded-lg border px-4 py-3 text-sm outline-none transition-colors ${error ? 'border-danger' : 'border-border-strong focus:border-foreground'}`} />
            </div>
            {error && <p className="text-sm text-danger">{error}</p>}
            <button type="submit" disabled={loading}
              className="w-full rounded-lg bg-foreground py-3 text-sm font-semibold text-white transition-colors hover:bg-primary disabled:opacity-50">
              {loading ? '验证中...' : '登录'}
            </button>
          </form>
          <p className="mt-6 text-center text-sm text-muted-foreground">
            没有账号？<Link href="/register" prefetch={false} className="text-primary hover:underline">去注册</Link>
          </p>
        </div>
        {/* 右侧品牌区 */}
        <div className="hidden w-[460px] flex-col items-center justify-center bg-gradient-to-br from-navy to-navy-light p-12 lg:flex relative overflow-hidden">
          {/* 网格点阵 */}
          <div className="absolute inset-0 opacity-20"
            style={{ backgroundImage: 'radial-gradient(circle, rgba(255,255,255,0.3) 1px, transparent 1px)', backgroundSize: '28px 28px' }} />
          {/* 发光球体 */}
          <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-72 h-72 rounded-full"
            style={{ background: 'radial-gradient(circle at 40% 40%, rgba(59,130,246,0.5) 0%, rgba(37,99,235,0.2) 40%, transparent 70%)' }} />
          <div className="absolute top-1/3 right-1/4 w-48 h-48 rounded-full"
            style={{ background: 'radial-gradient(circle, rgba(6,182,212,0.3) 0%, transparent 60%)' }} />
          <div className="relative text-center text-white">
            <div className="mb-8">
              <h2 className="text-3xl font-bold tracking-tight">MindSmith</h2>
              <p className="mt-2 text-sm text-white/50">AI 经验萃取平台</p>
            </div>
            <div className="space-y-4 text-left">
              {[
                { icon: '🎯', title: '销冠访谈', desc: 'AI 萃取师四阶段深度访谈，挖掘隐性经验' },
                { icon: '📋', title: '六章报告', desc: '案例、步骤、决策、心法、边界、清单' },
                { icon: '🤖', title: 'AI 分身', desc: '随时问答对练，把销冠装进口袋' },
              ].map(item => (
                <div key={item.title} className="flex items-start gap-3 rounded-xl bg-white/5 px-4 py-3 backdrop-blur">
                  <span className="mt-0.5 text-lg">{item.icon}</span>
                  <div>
                    <p className="text-sm font-semibold">{item.title}</p>
                    <p className="text-xs text-white/50">{item.desc}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
