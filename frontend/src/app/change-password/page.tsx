'use client';

import React, { useState } from 'react';
import { useRouter } from 'next/navigation';
import { changePassword, getCurrentUser } from '@/lib/api/auth';
import { setAuth } from '@/lib/storage';

/**
 * 修改密码 — 管理员重置/首次登录后强制进入；也可从个人入口主动修改。
 */
export default function ChangePasswordPage() {
  const router = useRouter();
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newPassword.length < 8) { setError('新密码至少 8 位'); return; }
    if (!/[A-Za-z]/.test(newPassword) || !/[0-9]/.test(newPassword)) {
      setError('新密码需同时包含字母和数字');
      return;
    }
    if (newPassword !== confirm) { setError('两次输入的新密码不一致'); return; }
    setSaving(true);
    setError('');
    try {
      const { token } = await changePassword(oldPassword, newPassword);
      const user = await getCurrentUser();
      setAuth(token, user as unknown as Record<string, unknown>);
      router.push('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : '修改失败，请稍后重试');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface px-6">
      <div className="w-full max-w-md rounded-2xl bg-surface-2 p-8 shadow-sm">
        <h1 className="text-xl font-bold text-foreground">修改密码</h1>
        <p className="mt-1 mb-6 text-sm text-muted-foreground">
          首次登录或管理员重置密码后，需要先修改密码才能继续使用。
        </p>
        <form onSubmit={submit} className="space-y-4">
          <label className="block text-sm">
            <span className="mb-1 block text-foreground">原密码</span>
            <input type="password" value={oldPassword} onChange={e => setOldPassword(e.target.value)}
              autoComplete="current-password"
              className="w-full rounded-lg border border-border-strong px-3 py-2 outline-none focus:border-foreground" />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-foreground">新密码（至少 8 位）</span>
            <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)}
              autoComplete="new-password"
              className="w-full rounded-lg border border-border-strong px-3 py-2 outline-none focus:border-foreground" />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-foreground">确认新密码</span>
            <input type="password" value={confirm} onChange={e => setConfirm(e.target.value)}
              autoComplete="new-password"
              className="w-full rounded-lg border border-border-strong px-3 py-2 outline-none focus:border-foreground" />
          </label>
          {error && <p className="text-sm text-danger">{error}</p>}
          <button type="submit" disabled={saving}
            className="w-full rounded-full bg-primary py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {saving ? '提交中…' : '确认修改'}
          </button>
        </form>
      </div>
    </div>
  );
}
