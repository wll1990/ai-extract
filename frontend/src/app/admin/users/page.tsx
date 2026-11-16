'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { apiClient } from '@/lib/api/client';
import { register } from '@/lib/api/auth';
import { getUser } from '@/lib/storage';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Pagination } from '@/components/ui/Pagination';

interface UserItem {
  id: string; name: string; account: string; role: string;
  isActive: boolean; mustChangePassword?: boolean; createdAt: string;
}

interface InviteCode {
  id: string; code: string; enabled: boolean;
  maxUses: number; usedCount: number;
  expiresAt?: string | null; createdAt?: string | null; lastUsedAt?: string | null;
}

/** 员工注册码状态：停用 > 过期 > 用尽 > 有效 */
function inviteStatus(c: InviteCode) {
  if (!c.enabled) return { label: '已停用', cls: 'bg-gray-100 text-gray-600' };
  if (c.expiresAt && new Date(c.expiresAt).getTime() < Date.now()) {
    return { label: '已过期', cls: 'bg-amber-100 text-amber-700' };
  }
  if (c.maxUses > 0 && c.usedCount >= c.maxUses) {
    return { label: '已用尽', cls: 'bg-gray-100 text-gray-600' };
  }
  return { label: '有效', cls: 'bg-green-100 text-green-700' };
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
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // ── 邀请员工（注册码）──
  const [showInvite, setShowInvite] = useState(false);
  const [inviteCodes, setInviteCodes] = useState<InviteCode[]>([]);
  const [inviteExpiry, setInviteExpiry] = useState(0);
  const [newInviteCode, setNewInviteCode] = useState('');
  const [inviteBusy, setInviteBusy] = useState(false);
  const [inviteError, setInviteError] = useState('');

  const toggleActive = async (u: UserItem) => {
    const next = !u.isActive;
    if (!window.confirm(next ? `启用「${u.name}」的账号？` : `停用「${u.name}」的账号？停用后其登录态立即失效。`)) return;
    try {
      await apiClient(`/admin/users/${u.id}/status`, { method: 'PUT', body: JSON.stringify({ active: next }) });
      loadUsers();
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    }
  };

  const resetPassword = async (u: UserItem) => {
    if (!window.confirm(`重置「${u.name}」的密码？旧密码立即失效，对方下次登录需修改密码。`)) return;
    try {
      const r = await apiClient<{ temporaryPassword: string }>(`/admin/users/${u.id}/reset-password`, { method: 'POST' });
      window.prompt('临时密码（请通过安全渠道转交，对方首次登录需修改）：', r.temporaryPassword);
      loadUsers();
    } catch (e) {
      alert(e instanceof Error ? e.message : '重置失败');
    }
  };

  const loadUsers = useCallback(() => {
    setLoading(true);
    apiClient<{ content: UserItem[]; total: number; totalPages: number }>(`/admin/users?page=${page}&size=20`)
      .then(d => { setUsers(d.content); setTotalPages(d.totalPages); setTotalElements(d.total); })
      .catch(e => console.error('加载用户列表失败:', e))
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  const loadInviteCodes = useCallback(async () => {
    try {
      const list = await apiClient<InviteCode[]>('/admin/company/register-codes');
      setInviteCodes(Array.isArray(list) ? list : []);
    } catch {
      setInviteCodes([]);
    }
  }, []);

  const toggleInvitePanel = () => {
    const next = !showInvite;
    setShowInvite(next);
    if (next) void loadInviteCodes();
  };

  const generateInvite = async () => {
    setInviteBusy(true);
    setInviteError('');
    try {
      const created = await apiClient<InviteCode>('/admin/company/register-codes', {
        method: 'POST',
        body: JSON.stringify({ maxUses: 0, expiresInDays: inviteExpiry }),
      });
      setNewInviteCode(created.code);
      setInviteExpiry(0);
      await loadInviteCodes();
    } catch (e) {
      setInviteError(e instanceof Error ? e.message : '生成失败');
    } finally {
      setInviteBusy(false);
    }
  };

  const toggleInviteCode = async (c: InviteCode) => {
    setInviteBusy(true);
    try {
      await apiClient(`/admin/company/register-codes/${c.id}/status`, {
        method: 'PUT', body: JSON.stringify({ enabled: !c.enabled }),
      });
      await loadInviteCodes();
    } finally {
      setInviteBusy(false);
    }
  };

  const deleteInviteCode = async (c: InviteCode) => {
    if (!window.confirm(`确认删除注册码 ${c.code}？已用它注册的员工不受影响。`)) return;
    setInviteBusy(true);
    try {
      await apiClient(`/admin/company/register-codes/${c.id}`, { method: 'DELETE' });
      await loadInviteCodes();
    } finally {
      setInviteBusy(false);
    }
  };

  const copyInviteCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      setMsg(`已复制：${code}`);
    } catch {
      setMsg(`注册码：${code}`);
    }
  };

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
          <div className="flex gap-2">
            <button onClick={toggleInvitePanel} data-tour="user-invite"
              className="rounded-lg border border-border px-4 py-2 text-sm text-foreground hover:bg-surface-2">
              {showInvite ? '收起邀请' : '邀请员工'}
            </button>
            <button onClick={() => setShowCreate(!showCreate)} data-tour="user-create"
              className="rounded-lg bg-foreground px-4 py-2 text-sm text-white">
              {showCreate ? '取消' : '+ 创建用户'}
            </button>
          </div>
        </div>

        {showInvite && (
          <div className="mb-6 rounded-xl bg-surface-2 border border-border p-6">
            <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
              <div>
                <h3 className="font-semibold text-foreground">邀请员工</h3>
                <p className="mt-1 text-sm text-muted-foreground">
                  生成注册码发给员工，他们自助注册后自动归入本企业（员工角色）。码用完或过期后重新生成即可。
                </p>
              </div>
              <div className="flex items-center gap-2">
                <select value={inviteExpiry} onChange={e => setInviteExpiry(Number(e.target.value))}
                  className="rounded-lg border border-border bg-surface px-3 py-2 text-sm">
                  <option value={0}>有效期：不限期</option>
                  <option value={7}>有效期：7 天</option>
                  <option value={30}>有效期：30 天</option>
                </select>
                <button onClick={generateInvite} disabled={inviteBusy}
                  className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                  {inviteBusy ? '生成中…' : '生成注册码'}
                </button>
              </div>
            </div>

            {newInviteCode && (
              <div className="mb-4 flex flex-wrap items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3">
                <span className="text-xs text-amber-700">新注册码（关闭后仍可在下方列表查看）：</span>
                <code className="rounded bg-white px-2 py-1 font-mono text-sm text-amber-900">{newInviteCode}</code>
                <button onClick={() => copyInviteCode(newInviteCode)}
                  className="rounded border border-amber-300 px-2 py-1 text-xs text-amber-800 hover:bg-amber-100">
                  复制
                </button>
              </div>
            )}
            {inviteError && <p className="mb-3 text-sm text-danger">{inviteError}</p>}

            {inviteCodes.length === 0 ? (
              <p className="text-sm text-muted-foreground">还没有员工注册码，点「生成注册码」创建一个。</p>
            ) : (
              <table className="w-full text-sm">
                <thead className="text-xs text-muted-foreground">
                  <tr>
                    <th className="text-left py-2">注册码</th>
                    <th className="text-left py-2">状态</th>
                    <th className="text-left py-2">已用/上限</th>
                    <th className="text-left py-2">有效期</th>
                    <th className="text-left py-2">最近使用</th>
                    <th className="text-right py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {inviteCodes.map(c => {
                    const st = inviteStatus(c);
                    return (
                      <tr key={c.id} className="border-t border-border">
                        <td className="py-2 font-mono text-xs">{c.code}</td>
                        <td className="py-2">
                          <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${st.cls}`}>{st.label}</span>
                        </td>
                        <td className="py-2 text-xs text-muted-foreground">
                          {c.usedCount}/{c.maxUses > 0 ? c.maxUses : '∞'}
                        </td>
                        <td className="py-2 text-xs text-muted-foreground">
                          {c.expiresAt ? new Date(c.expiresAt).toLocaleDateString() : '长期有效'}
                        </td>
                        <td className="py-2 text-xs text-muted-foreground">
                          {c.lastUsedAt ? new Date(c.lastUsedAt).toLocaleString() : '—'}
                        </td>
                        <td className="py-2 text-right whitespace-nowrap">
                          <button onClick={() => copyInviteCode(c.code)}
                            className="mr-2 text-xs text-primary hover:underline">复制</button>
                          <button onClick={() => toggleInviteCode(c)} disabled={inviteBusy}
                            className="mr-2 text-xs text-muted-foreground hover:underline">
                            {c.enabled ? '停用' : '启用'}
                          </button>
                          <button onClick={() => deleteInviteCode(c)} disabled={inviteBusy}
                            className="text-xs text-danger hover:underline">删除</button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        )}

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
              <input type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)}
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
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">状态</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">创建时间</th>
                <th className="text-right px-4 py-3 font-medium text-muted-foreground">操作</th>
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
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${u.isActive ? 'bg-success-bg text-success' : 'bg-gray-100 text-gray-500'}`}>
                      {u.isActive ? '正常' : '已停用'}
                    </span>
                    {u.mustChangePassword && (
                      <span className="ml-1 rounded-full bg-warning-bg px-2 py-0.5 text-xs text-warning-text">待改密</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground-2">
                    {u.createdAt ? new Date(u.createdAt).toLocaleDateString('zh-CN') : '-'}
                  </td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    {u.role !== 'super_admin' && (
                      <>
                        <button onClick={() => toggleActive(u)}
                          className={`text-xs hover:underline ${u.isActive ? 'text-danger' : 'text-primary'}`}>
                          {u.isActive ? '停用' : '启用'}
                        </button>
                        <button onClick={() => resetPassword(u)}
                          className="ml-3 text-xs text-primary hover:underline">重置密码</button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {users.length === 0 && (
                <tr><td colSpan={6} className="px-4 py-8 text-center text-muted-foreground-2">暂无用户</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <Pagination page={page} totalPages={totalPages} totalElements={totalElements}
          onPageChange={p => setPage(p)} loading={loading} />
      </div>
    </div>
  );
}
