'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { apiClient } from '@/lib/api/client';
import { Pagination } from '@/components/ui/Pagination';

interface Partner {
  id: string; appId: string; appName: string; status: string;
  contactName?: string; contactEmail?: string; createdAt?: string;
}

export default function PartnersPage() {
  const [partners, setPartners] = useState<Partner[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [showCreate, setShowCreate] = useState(false);
  const [newSK, setNewSK] = useState(''); // 新建成功后展示的 SK
  const [resetTarget, setResetTarget] = useState<Partner | null>(null);
  const [resetSK, setResetSK] = useState('');
  const [skCopied, setSkCopied] = useState(false);

  // 表单
  const [form, setForm] = useState({ appId: '', appName: '', contactName: '', contactEmail: '' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const copyToClipboard = (text: string) => {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(() => {
        setSkCopied(true); setTimeout(() => setSkCopied(false), 2000);
      }).catch(() => {});
    } else {
      const ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); setSkCopied(true); setTimeout(() => setSkCopied(false), 2000); } catch {}
      document.body.removeChild(ta);
    }
  };

  const load = useCallback(() => {
    setLoading(true);
    apiClient<{ content: Partner[]; total: number; totalPages: number }>(`/admin/partners?page=${page}&size=20`)
      .then(d => { setPartners(d.content || []); setTotalPages(d.totalPages); setTotalElements(d.total); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    if (!form.appId.trim()) { setError('appId 不能为空'); return; }
    setSaving(true); setError('');
    try {
      const res = await apiClient<any>('/admin/partners', {
        method: 'POST',
        body: JSON.stringify(form),
      });
      const data = res.data || res;
      setNewSK(data.secretKey || '');
      setShowCreate(false);
      setForm({ appId: '', appName: '', contactName: '', contactEmail: '' });
      load();
    } catch (e: any) { setError(e?.message || '创建失败'); }
    setSaving(false);
  };

  const handleToggleStatus = async (p: Partner) => {
    const newStatus = p.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    await apiClient(`/admin/partners/${p.id}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status: newStatus }),
    });
    load();
  };

  const handleResetSK = async () => {
    if (!resetTarget) return;
    setSaving(true);
    try {
      const res = await apiClient<any>(`/admin/partners/${resetTarget.id}/reset-sk`, { method: 'POST' });
      const data = res.data || res;
      setResetSK(data.secretKey || '');
    } catch { setError('重置失败'); }
    setSaving(false);
  };

  if (loading) return <div className="p-8 text-sm text-muted-foreground">加载中...</div>;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold text-foreground">合作方管理</h1>
          <button onClick={() => { setShowCreate(true); setError(''); }}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-primary-hover">
            + 新建合作方
          </button>
        </div>

        {/* 列表 */}
        {partners.length === 0 ? (
          <div className="py-16 text-center text-sm text-muted-foreground">暂无合作方</div>
        ) : (
          <div className="bg-white rounded-xl border border-border overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-surface-2 text-muted-foreground text-xs uppercase">
                <tr>
                  <th className="text-left px-4 py-3">appId</th>
                  <th className="text-left px-4 py-3">名称</th>
                  <th className="text-left px-4 py-3">状态</th>
                  <th className="text-left px-4 py-3">联系人</th>
                  <th className="text-left px-4 py-3">创建时间</th>
                  <th className="text-right px-4 py-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {partners.map(p => (
                  <tr key={p.id} className="border-t border-border hover:bg-surface-2/50">
                    <td className="px-4 py-3 font-mono text-xs">{p.appId}</td>
                    <td className="px-4 py-3 font-medium">{p.appName}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${
                        p.status === 'ENABLED' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                      }`}>
                        {p.status === 'ENABLED' ? '🟢 启用' : '🔴 停用'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {p.contactName || '-'}{p.contactEmail ? ` · ${p.contactEmail}` : ''}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground text-xs">
                      {p.createdAt ? p.createdAt.substring(0, 10) : '-'}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => handleToggleStatus(p)}
                        className="text-xs px-2 py-1 rounded hover:bg-surface-2 mr-1">
                        {p.status === 'ENABLED' ? '停用' : '启用'}
                      </button>
                      <button
                        onClick={() => { setResetTarget(p); setResetSK(''); setError(''); }}
                        className="text-xs px-2 py-1 rounded hover:bg-surface-2 text-amber-600">
                        重置 SK
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* 新建弹窗 */}
        {showCreate && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setShowCreate(false)}>
            <div className="bg-white rounded-2xl w-full max-w-md p-6 shadow-xl" onClick={e => e.stopPropagation()}>
              <h2 className="text-lg font-bold mb-4">新建合作方</h2>
              {error && <div className="mb-3 text-xs text-red-600 bg-red-50 px-3 py-2 rounded">{error}</div>}
              <div className="space-y-3">
                <div>
                  <label className="block text-xs font-medium text-muted-foreground mb-1">appId <span className="text-red-400">*</span></label>
                  <input value={form.appId} onChange={e => setForm(f => ({ ...f, appId: e.target.value }))}
                    placeholder="英文标识，如 alibaba" className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-muted-foreground mb-1">名称</label>
                  <input value={form.appName} onChange={e => setForm(f => ({ ...f, appName: e.target.value }))}
                    placeholder="中文显示名" className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">联系人</label>
                    <input value={form.contactName} onChange={e => setForm(f => ({ ...f, contactName: e.target.value }))}
                      className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">邮箱</label>
                    <input value={form.contactEmail} onChange={e => setForm(f => ({ ...f, contactEmail: e.target.value }))}
                      className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                </div>
              </div>
              <div className="flex justify-end gap-3 mt-5">
                <button onClick={() => setShowCreate(false)} className="px-4 py-2 text-sm text-muted-foreground hover:text-foreground">取消</button>
                <button onClick={handleCreate} disabled={saving}
                  className="px-4 py-2 text-sm bg-primary text-white rounded-lg hover:bg-primary-hover disabled:opacity-50">
                  {saving ? '创建中...' : '创建'}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* SK 展示弹窗（新建成功后） */}
        {newSK && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
            <div className="bg-white rounded-2xl w-full max-w-md p-6 shadow-xl text-center">
              <div className="text-3xl mb-3">⚠️</div>
              <h2 className="text-lg font-bold mb-2">请立即复制 SK</h2>
              <p className="text-xs text-muted-foreground mb-4">关闭后无法再次查看，请妥善保管</p>
              <div className="bg-surface-2 rounded-lg px-4 py-3 mb-4 font-mono text-sm break-all select-all">
                {newSK}
              </div>
              <button onClick={() => copyToClipboard(newSK)}
                className="px-4 py-2 text-sm bg-primary text-white rounded-lg hover:bg-primary-hover mb-2 min-w-[100px]">
                {skCopied ? '✅ 已复制' : '📋 复制 SK'}
              </button>
              <br />
              <button onClick={() => setNewSK('')} className="mt-2 text-sm text-muted-foreground hover:text-foreground">确定</button>
            </div>
          </div>
        )}

        {/* 重置 SK 确认弹窗 */}
        {resetTarget && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setResetTarget(null)}>
            <div className="bg-white rounded-2xl w-full max-w-md p-6 shadow-xl" onClick={e => e.stopPropagation()}>
              {resetSK ? (
                <>
                  <div className="text-3xl mb-3 text-center">⚠️</div>
                  <h2 className="text-lg font-bold mb-2 text-center">新 SK</h2>
                  <p className="text-xs text-muted-foreground mb-4 text-center">旧 SK 24 小时内仍有效，请通知合作方及时更新</p>
                  <div className="bg-surface-2 rounded-lg px-4 py-3 mb-4 font-mono text-sm break-all select-all">{resetSK}</div>
                  <button onClick={() => copyToClipboard(resetSK)}
                    className="w-full px-4 py-2 text-sm bg-primary text-white rounded-lg hover:bg-primary-hover mb-2">{skCopied ? '✅ 已复制' : '📋 复制新 SK'}</button>
                  <button onClick={() => { setResetTarget(null); setResetSK(''); }}
                    className="w-full text-sm text-muted-foreground hover:text-foreground">确定</button>
                </>
              ) : (
                <>
                  <h2 className="text-lg font-bold mb-3">重置 SK — {resetTarget.appName}</h2>
                  <p className="text-sm text-muted-foreground mb-2">⚠️ 重置后旧 SK 24 小时内仍有效</p>
                  <p className="text-xs text-muted-foreground mb-4">确保所有合作方在 24 小时内更新为新 SK</p>
                  {error && <div className="mb-3 text-xs text-red-600 bg-red-50 px-3 py-2 rounded">{error}</div>}
                  <div className="flex justify-end gap-3">
                    <button onClick={() => setResetTarget(null)} className="px-4 py-2 text-sm text-muted-foreground">取消</button>
                    <button onClick={handleResetSK} disabled={saving}
                      className="px-4 py-2 text-sm bg-amber-500 text-white rounded-lg hover:bg-amber-600 disabled:opacity-50">
                      {saving ? '重置中...' : '确认重置'}
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        )}
        <Pagination page={page} totalPages={totalPages} totalElements={totalElements}
          onPageChange={p => setPage(p)} loading={loading} />
      </div>
    </div>
  );
}
