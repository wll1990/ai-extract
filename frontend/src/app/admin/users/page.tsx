'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { apiClient } from '@/lib/api/client';
import { register } from '@/lib/api/auth';
import { getUser } from '@/lib/storage';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface UserItem {
  id: string; name: string; account: string; role: string;
  isActive: boolean; createdAt: string;
}

export default function AdminUsersPage() {
  const [users, setUsers] = useState<UserItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState('employee');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const [msg, setMsg] = useState('');

  const loadUsers = useCallback(() => {
    setLoading(true);
    apiClient<UserItem[]>('/admin/users')
      .then(u => setUsers(Array.isArray(u) ? u : []))
      .catch(e => console.error('加载用户列表失败:', e))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  const handleCreate = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name || !account || !password) { setError('请填写所有字段'); return; }
    setCreating(true); setError(''); setMsg('');
    try {
      const currentUser = getUser();
      const companyId = (currentUser?.companyId as string) || '';
      await register({ companyId, name, account, password, role });
      setMsg('创建成功');
      setName(''); setAccount(''); setPassword(''); setRole('employee');
      setShowCreate(false);
      loadUsers();
    } catch (err) { setError(err instanceof Error ? err.message : '创建失败'); }
    finally { setCreating(false); }
  }, [name, account, password, role, loadUsers]);

  if (loading) return <LoadingSpinner fullScreen={false} />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-3xl">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-xl font-bold text-foreground">用户管理</h1>
            <p className="text-sm text-muted-foreground mt-1">管理企业内的所有用户</p>
          </div>
          <button onClick={() => setShowCreate(!showCreate)}
            className="rounded-lg bg-foreground px-4 py-2 text-sm text-white">
            {showCreate ? '取消' : '+ 创建用户'}
          </button>
        </div>

        {showCreate && (
          <form onSubmit={handleCreate} className="mb-6 rounded-xl bg-surface-2 border border-border p-6 space-y-4">
            <h3 className="font-semibold text-foreground">创建新用户</h3>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-foreground mb-1">真实姓名</label>
                <input type="text" value={name} onChange={e => setName(e.target.value)}
                  placeholder="例如：潘露婷"
                  className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-primary" />
              </div>
              <div>
                <label className="block text-sm font-medium text-foreground mb-1">登录账号</label>
                <input type="text" value={account} onChange={e => setAccount(e.target.value)}
                  placeholder="英文或拼音"
                  className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-primary" />
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">密码</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                placeholder="请输入密码"
                className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-primary" />
            </div>
            <div>
              <label className="block text-sm font-medium text-foreground mb-1">角色</label>
              <select value={role} onChange={e => setRole(e.target.value)}
                className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-primary">
                <option value="employee">员工 (employee)</option>
                <option value="company_admin">企业管理员 (company_admin)</option>
              </select>
            </div>
            {error && <p className="text-sm text-danger">{error}</p>}
            {msg && <p className="text-sm text-success">{msg}</p>}
            <button type="submit" disabled={creating}
              className="rounded-lg bg-foreground px-6 py-2 text-sm text-white disabled:opacity-50">
              {creating ? '创建中...' : '创建'}
            </button>
          </form>
        )}

        <div className="rounded-xl bg-surface-2 border border-border overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-surface border-b border-border">
              <tr>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">姓名</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">账号</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">角色</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">创建时间</th>
              </tr>
            </thead>
            <tbody>
              {users.map(u => (
                <tr key={u.id} className="border-b border-border last:border-0">
                  <td className="px-4 py-3 font-medium text-foreground">{u.name}</td>
                  <td className="px-4 py-3 text-muted-foreground">{u.account}</td>
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                      u.role === 'super_admin' ? 'bg-primary-light text-primary'
                        : u.role === 'company_admin' ? 'bg-blue-100 text-blue-700'
                        : 'bg-success-bg text-success'
                    }`}>
                      {u.role === 'super_admin' ? '超级管理员'
                        : u.role === 'company_admin' ? '企业管理员'
                        : '员工'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground-2">
                    {u.createdAt ? new Date(u.createdAt).toLocaleDateString('zh-CN') : '-'}
                  </td>
                </tr>
              ))}
              {users.length === 0 && (
                <tr><td colSpan={4} className="px-4 py-8 text-center text-muted-foreground-2">暂无用户</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
