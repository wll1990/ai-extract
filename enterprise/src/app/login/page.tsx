'use client';

import { useState, FormEvent } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { login } from '@/lib/api/auth';
import { setAuth } from '@/lib/storage';

export default function LoginPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirect = searchParams.get('redirect') || '/discover';

  const [companyId, setCompanyId] = useState('');
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!companyId.trim() || !account.trim() || !password.trim()) {
      setError('请填写所有字段');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const result = await login({ companyId: companyId.trim(), account: account.trim(), password });
      setAuth(result.token, result.user as Record<string, unknown>);
      router.push(redirect);
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center',
      justifyContent: 'center', background: 'var(--s1)',
      padding: '0 20px',
    }}>
      <div style={{
        width: '100%', maxWidth: 380, padding: '40px 32px',
        background: 'var(--surface)', borderRadius: 'var(--radius-3xl)',
        boxShadow: 'var(--shadow-lg)',
      }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <Link href="/" style={{
            display: 'inline-flex', alignItems: 'center', gap: 8,
            textDecoration: 'none', color: 'var(--fg-high)',
            fontWeight: 800, fontSize: 20, letterSpacing: '-0.03em',
            marginBottom: 8,
          }}>
            <span style={{
              width: 36, height: 36, borderRadius: 10,
              background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: '#fff', fontSize: 20,
            }}>💎</span>
            MindForge
          </Link>
          <p style={{ fontSize: 13, color: 'var(--fg-mid)' }}>登录你的账户</p>
        </div>

        {error && (
          <div style={{
            padding: '10px 14px', borderRadius: 12, marginBottom: 16,
            background: 'rgba(220,38,38,0.08)', color: '#dc2626',
            fontSize: 12, fontWeight: 500,
          }}>
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>
              企业 ID
            </label>
            <input
              type="text" value={companyId} onChange={(e) => setCompanyId(e.target.value)}
              placeholder="你的企业唯一标识"
              style={{
                width: '100%', padding: '10px 14px', borderRadius: 12,
                border: '1.5px solid var(--border-subtle)', background: 'var(--s1)',
                fontSize: 13, outline: 'none', fontFamily: 'inherit',
                color: 'var(--fg-high)', boxSizing: 'border-box',
              }}
            />
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>
              账号
            </label>
            <input
              type="text" value={account} onChange={(e) => setAccount(e.target.value)}
              placeholder="你的登录账号"
              style={{
                width: '100%', padding: '10px 14px', borderRadius: 12,
                border: '1.5px solid var(--border-subtle)', background: 'var(--s1)',
                fontSize: 13, outline: 'none', fontFamily: 'inherit',
                color: 'var(--fg-high)', boxSizing: 'border-box',
              }}
            />
          </div>

          <div style={{ marginBottom: 20 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>
              密码
            </label>
            <input
              type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              placeholder="输入密码"
              style={{
                width: '100%', padding: '10px 14px', borderRadius: 12,
                border: '1.5px solid var(--border-subtle)', background: 'var(--s1)',
                fontSize: 13, outline: 'none', fontFamily: 'inherit',
                color: 'var(--fg-high)', boxSizing: 'border-box',
              }}
            />
          </div>

          <button
            type="submit" disabled={loading}
            className="btn btn-primary btn-lg"
            style={{ width: '100%', justifyContent: 'center' }}
          >
            {loading ? '登录中...' : '登录'}
          </button>
        </form>

        <p style={{ textAlign: 'center', marginTop: 20, fontSize: 12, color: 'var(--fg-low)' }}>
          还没有账户？ <Link href="/register" style={{ color: 'var(--tangerine)', fontWeight: 600, textDecoration: 'none' }}>注册</Link>
        </p>
      </div>
    </div>
  );
}
