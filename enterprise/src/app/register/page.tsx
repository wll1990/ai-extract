'use client';

import { useState, FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { register } from '@/lib/api/auth';
import { setAuth } from '@/lib/storage';

const ROLES = [
  { key: 'employee', label: '我是员工', desc: '向专家学习、对练提升' },
  { key: 'expert', label: '我是专家', desc: '创建 AI 分身，分享经验' },
];

export default function RegisterPage() {
  const router = useRouter();
  const [companyId, setCompanyId] = useState('');
  const [name, setName] = useState('');
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState('employee');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!companyId.trim() || !name.trim() || !account.trim() || !password.trim()) {
      setError('请填写所有字段');
      return;
    }
    if (password.length < 6) {
      setError('密码至少 6 位');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const result = await register({
        companyId: companyId.trim(), name: name.trim(),
        account: account.trim(), password, role,
      });
      setAuth(result.token, result.user as Record<string, unknown>);
      router.push('/discover');
    } catch (err) {
      setError(err instanceof Error ? err.message : '注册失败');
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
        width: '100%', maxWidth: 420, padding: '40px 32px',
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
          <p style={{ fontSize: 13, color: 'var(--fg-mid)' }}>创建你的账户</p>
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
          {/* Role selector */}
          <div style={{ marginBottom: 18 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-mid)', display: 'block', marginBottom: 8 }}>
              选择角色
            </label>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              {ROLES.map(r => (
                <button
                  key={r.key} type="button"
                  onClick={() => setRole(r.key)}
                  style={{
                    padding: '12px', borderRadius: 14, border: '1.5px solid',
                    cursor: 'pointer', textAlign: 'center',
                    fontFamily: 'inherit', transition: 'all 0.15s',
                    background: role === r.key ? 'rgba(255,92,0,0.05)' : 'var(--s1)',
                    borderColor: role === r.key ? 'var(--tangerine)' : 'var(--border-subtle)',
                  }}
                >
                  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)', marginBottom: 2 }}>
                    {r.label}
                  </div>
                  <div style={{ fontSize: 10, color: 'var(--fg-dim)' }}>{r.desc}</div>
                </button>
              ))}
            </div>
          </div>

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
              姓名
            </label>
            <input
              type="text" value={name} onChange={(e) => setName(e.target.value)}
              placeholder="你的真实姓名"
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
              placeholder="登录时使用的账号"
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
              placeholder="至少 6 位密码"
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
            {loading ? '注册中...' : '注册'}
          </button>
        </form>

        <p style={{ textAlign: 'center', marginTop: 20, fontSize: 12, color: 'var(--fg-low)' }}>
          已有账户？ <Link href="/login" style={{ color: 'var(--tangerine)', fontWeight: 600, textDecoration: 'none' }}>登录</Link>
        </p>
      </div>
    </div>
  );
}
