'use client';

import { useState, FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { register } from '@/lib/api/auth';
import { setAuth } from '@/lib/storage';

type RegTab = 'enterprise' | 'personal';

export default function RegisterPage() {
  const router = useRouter();

  const [tab, setTab] = useState<RegTab>('enterprise');

  // 企业注册
  const [companyId, setCompanyId] = useState('');
  const [bName, setBName] = useState('');
  const [bAccount, setBAccount] = useState('');
  const [bPassword, setBPassword] = useState('');

  // 个人注册
  const [cNickname, setCNickname] = useState('');
  const [cAccount, setCAccount] = useState('');
  const [cPassword, setCPassword] = useState('');

  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleEnterpriseRegister = async (e: FormEvent) => {
    e.preventDefault();
    if (!companyId.trim() || !bName.trim() || !bAccount.trim() || !bPassword.trim()) {
      setError('请填写所有字段');
      return;
    }
    if (bPassword.length < 6) { setError('密码至少 6 位'); return; }
    setLoading(true); setError('');
    try {
      const result = await register({
        companyId: companyId.trim(), name: bName.trim(),
        account: bAccount.trim(), password: bPassword, role: 'employee',
      });
      setAuth(result.token, result.user as Record<string, unknown>);
      router.push('/discover');
    } catch (err) {
      setError(err instanceof Error ? err.message : '注册失败');
    } finally {
      setLoading(false);
    }
  };

  const handlePersonalRegister = async (e: FormEvent) => {
    e.preventDefault();
    if (!cAccount.trim() || !cPassword.trim()) {
      setError('请填写所有字段');
      return;
    }
    if (cPassword.length < 6) { setError('密码至少 6 位'); return; }
    setLoading(true); setError('');
    try {
      const r = await fetch('/api/v1/c/auth/register/new', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          account: cAccount.trim(),
          password: cPassword,
          nickname: cNickname.trim() || ('用户' + cAccount.trim().substring(0, 6)),
        }),
      });
      const d = await r.json();
      if (d.code !== 200) throw new Error(d.message || '注册失败');
      localStorage.setItem('c_auth', JSON.stringify({ token: d.data.token, user: { userId: d.data.userId, nickname: d.data.nickname, status: d.data.status } }));
      router.push('/platform/my');
    } catch (err) {
      setError(err instanceof Error ? err.message : '注册失败');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = tab === 'enterprise' ? handleEnterpriseRegister : handlePersonalRegister;

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center',
      justifyContent: 'center', background: 'var(--s1)',
      padding: '0 20px',
    }}>
      <div style={{
        width: '100%', maxWidth: 420, padding: '40px 32px',
        background: 'var(--surface)', borderRadius: 'var(--radius-3xl)',
        boxShadow: 'var(--shadow-lg)',
      }}>
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
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
          <p style={{ fontSize: 13, color: 'var(--fg-mid)' }}>创建你的账户</p>
        </div>

        {/* Tab */}
        <div style={{
          display: 'flex', marginBottom: 24, background: 'var(--s3)',
          borderRadius: 12, padding: 3,
        }}>
          {(['enterprise', 'personal'] as RegTab[]).map(t => (
            <button
              key={t} type="button" onClick={() => { setTab(t); setError(''); }}
              style={{
                flex: 1, padding: '8px 0', borderRadius: 10,
                border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                fontSize: 13, fontWeight: 600,
                background: tab === t ? 'var(--surface)' : 'transparent',
                color: tab === t ? 'var(--fg-high)' : 'var(--fg-mid)',
                boxShadow: tab === t ? '0 1px 3px rgba(0,0,0,0.08)' : 'none',
                transition: 'all 0.15s',
              }}
            >
              {t === 'enterprise' ? '企业注册' : '个人注册'}
            </button>
          ))}
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
          {tab === 'enterprise' && (
            <>
              <div style={{ marginBottom: 14 }}>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>企业 ID</label>
                <input type="text" value={companyId} onChange={(e) => setCompanyId(e.target.value)}
                  placeholder="你的企业唯一标识"
                  style={{ width: '100%', padding: '10px 14px', borderRadius: 12, border: '1.5px solid var(--border-subtle)', background: 'var(--s1)', fontSize: 13, outline: 'none', fontFamily: 'inherit', color: 'var(--fg-high)', boxSizing: 'border-box' }} />
              </div>
              <div style={{ marginBottom: 14 }}>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>姓名</label>
                <input type="text" value={bName} onChange={(e) => setBName(e.target.value)}
                  placeholder="你的真实姓名"
                  style={{ width: '100%', padding: '10px 14px', borderRadius: 12, border: '1.5px solid var(--border-subtle)', background: 'var(--s1)', fontSize: 13, outline: 'none', fontFamily: 'inherit', color: 'var(--fg-high)', boxSizing: 'border-box' }} />
              </div>
              <div style={{ marginBottom: 14 }}>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>账号</label>
                <input type="text" value={bAccount} onChange={(e) => setBAccount(e.target.value)}
                  placeholder="登录时使用的账号"
                  style={{ width: '100%', padding: '10px 14px', borderRadius: 12, border: '1.5px solid var(--border-subtle)', background: 'var(--s1)', fontSize: 13, outline: 'none', fontFamily: 'inherit', color: 'var(--fg-high)', boxSizing: 'border-box' }} />
              </div>
              <div style={{ marginBottom: 20 }}>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>密码</label>
                <input type="password" value={bPassword} onChange={(e) => setBPassword(e.target.value)}
                  placeholder="至少 6 位密码"
                  style={{ width: '100%', padding: '10px 14px', borderRadius: 12, border: '1.5px solid var(--border-subtle)', background: 'var(--s1)', fontSize: 13, outline: 'none', fontFamily: 'inherit', color: 'var(--fg-high)', boxSizing: 'border-box' }} />
              </div>
              <button type="submit" disabled={loading} className="btn btn-primary btn-lg"
                style={{ width: '100%', justifyContent: 'center' }}>
                {loading ? '注册中...' : '企业注册'}
              </button>
              <p style={{ textAlign: 'center', marginTop: 20, fontSize: 12, color: 'var(--fg-low)' }}>
                已有账户？ <Link href="/login" style={{ color: 'var(--tangerine)', fontWeight: 600, textDecoration: 'none' }}>登录</Link>
              </p>
            </>
          )}

          {tab === 'personal' && (
            <>
              <div style={{ marginBottom: 14 }}>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>昵称（选填）</label>
                <input type="text" value={cNickname} onChange={(e) => setCNickname(e.target.value)}
                  placeholder="怎么称呼你"
                  style={{ width: '100%', padding: '10px 14px', borderRadius: 12, border: '1.5px solid var(--border-subtle)', background: 'var(--s1)', fontSize: 13, outline: 'none', fontFamily: 'inherit', color: 'var(--fg-high)', boxSizing: 'border-box' }} />
              </div>
              <div style={{ marginBottom: 14 }}>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>手机号 / 邮箱</label>
                <input type="text" value={cAccount} onChange={(e) => setCAccount(e.target.value)}
                  placeholder="输入手机号或邮箱"
                  style={{ width: '100%', padding: '10px 14px', borderRadius: 12, border: '1.5px solid var(--border-subtle)', background: 'var(--s1)', fontSize: 13, outline: 'none', fontFamily: 'inherit', color: 'var(--fg-high)', boxSizing: 'border-box' }} />
              </div>
              <div style={{ marginBottom: 20 }}>
                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 6 }}>密码</label>
                <input type="password" value={cPassword} onChange={(e) => setCPassword(e.target.value)}
                  placeholder="至少 6 位密码"
                  style={{ width: '100%', padding: '10px 14px', borderRadius: 12, border: '1.5px solid var(--border-subtle)', background: 'var(--s1)', fontSize: 13, outline: 'none', fontFamily: 'inherit', color: 'var(--fg-high)', boxSizing: 'border-box' }} />
              </div>
              <button type="submit" disabled={loading} className="btn btn-primary btn-lg"
                style={{ width: '100%', justifyContent: 'center' }}>
                {loading ? '注册中...' : '个人注册'}
              </button>
              <p style={{ textAlign: 'center', marginTop: 20, fontSize: 12, color: 'var(--fg-low)' }}>
                已有账户？{' '}
                <button type="button" onClick={() => router.push('/login')}
                  style={{ color: 'var(--tangerine)', fontWeight: 600, textDecoration: 'none', background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit', fontSize: 12 }}>
                  登录
                </button>
              </p>
            </>
          )}
        </form>
      </div>
    </div>
  );
}
