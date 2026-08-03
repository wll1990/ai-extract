'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { apiClient } from '@/lib/api/client';
import { Pagination } from '@/components/ui/Pagination';

interface Company {
  id: string; name: string; logoUrl?: string; brandColor?: string;
  contactName?: string; contactPhone?: string; contactEmail?: string;
  address?: string; industry?: string; scale?: string; notes?: string;
  status: string; createdAt?: string;
}

interface RegisterCode {
  id: string; code: string; enabled: boolean;
  maxUses: number; usedCount: number;
  createdAt?: string; expiresAt?: string | null;
}

const EMPTY_FORM = {
  name: '', logoUrl: '', brandColor: '',
  contactName: '', contactPhone: '', contactEmail: '',
  address: '', industry: '', scale: '', notes: '',
};

export default function CompaniesPage() {
  const [companies, setCompanies] = useState<Company[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // ── 企业 Modal ──
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  // ── 注册码 Modal ──
  const [codeTarget, setCodeTarget] = useState<Company | null>(null);
  const [codes, setCodes] = useState<RegisterCode[]>([]);
  const [codesLoading, setCodesLoading] = useState(false);
  const [newCode, setNewCode] = useState(''); // 刚生成的明文码
  const [codeRole, setCodeRole] = useState('employee'); // 注册码默认角色
  const [codeCopied, setCodeCopied] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    apiClient<{ content: Company[]; total: number; totalPages: number }>(`/admin/companies?page=${page}&size=20`)
      .then(d => { setCompanies(d.content || []); setTotalPages(d.totalPages); setTotalElements(d.total); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => { load(); }, [load]);

  // ── 企业 CRUD ──

  const openCreate = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setError('');
    setShowForm(true);
  };

  const openEdit = (c: Company) => {
    setEditingId(c.id);
    setForm({
      name: c.name || '',
      logoUrl: c.logoUrl || '',
      brandColor: c.brandColor || '',
      contactName: c.contactName || '',
      contactPhone: c.contactPhone || '',
      contactEmail: c.contactEmail || '',
      address: c.address || '',
      industry: c.industry || '',
      scale: c.scale || '',
      notes: c.notes || '',
    });
    setError('');
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!form.name.trim()) { setError('企业名称不能为空'); return; }
    setSaving(true); setError('');
    try {
      if (editingId) {
        await apiClient(`/admin/companies/${editingId}`, {
          method: 'PUT', body: JSON.stringify(form),
        });
      } else {
        await apiClient('/admin/companies', {
          method: 'POST', body: JSON.stringify(form),
        });
      }
      setShowForm(false);
      load();
    } catch (e: any) { setError(e?.message || '保存失败'); }
    setSaving(false);
  };

  const handleToggleStatus = async (c: Company) => {
    const newStatus = c.status === 'archived' ? 'active' : 'archived';
    await apiClient(`/admin/companies/${c.id}/status`, {
      method: 'PUT', body: JSON.stringify({ status: newStatus }),
    });
    load();
  };

  // ── 注册码管理 ──

  const openCodes = async (c: Company) => {
    setCodeTarget(c);
    setNewCode('');
    setCodesLoading(true);
    try {
      const d = await apiClient<RegisterCode[]>(`/admin/companies/${c.id}/codes`);
      setCodes(Array.isArray(d) ? d : []);
    } catch { setCodes([]); }
    setCodesLoading(false);
  };

  const handleGenerateCode = async () => {
    if (!codeTarget) return;
    setSaving(true);
    try {
      const res = await apiClient<any>(`/admin/companies/${codeTarget.id}/codes`, {
        method: 'POST', body: JSON.stringify({ maxUses: 0, defaultRole: codeRole }),
      });
      const data = res; // apiClient already unwraps .data
      setNewCode(data.code || '');
      setCodeCopied(false);
      // refresh code list
      const d = await apiClient<RegisterCode[]>(`/admin/companies/${codeTarget.id}/codes`);
      setCodes(Array.isArray(d) ? d : []);
    } catch (e: any) { setError(e?.message || '生成失败'); }
    setSaving(false);
  };

  const handleCopy = (text: string) => {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(() => {
        setCodeCopied(true);
        setTimeout(() => setCodeCopied(false), 2000);
      }).catch(() => {});
    } else {
      // fallback for non-HTTPS
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); setCodeCopied(true); setTimeout(() => setCodeCopied(false), 2000); } catch {}
      document.body.removeChild(ta);
    }
  };

  const handleToggleCode = async (code: RegisterCode) => {
    if (!codeTarget) return;
    await apiClient(`/admin/companies/${codeTarget.id}/codes/${code.id}/status`, {
      method: 'PUT', body: JSON.stringify({ enabled: !code.enabled }),
    });
    // refresh
    const d = await apiClient<RegisterCode[]>(`/admin/companies/${codeTarget.id}/codes`);
    setCodes(Array.isArray(d) ? d : []);
  };

  const handleDeleteCode = async (code: RegisterCode) => {
    if (!codeTarget || !confirm('确定删除此注册码？')) return;
    await apiClient(`/admin/companies/${codeTarget.id}/codes/${code.id}`, { method: 'DELETE' });
    const d = await apiClient<RegisterCode[]>(`/admin/companies/${codeTarget.id}/codes`);
    setCodes(Array.isArray(d) ? d : []);
  };

  if (loading) return <div className="p-8 text-sm text-muted-foreground">加载中...</div>;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1100px]">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold text-foreground">企业合作</h1>
          <button onClick={openCreate}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-primary-hover">
            + 新建企业
          </button>
        </div>

        {/* 企业列表 */}
        {companies.length === 0 ? (
          <div className="py-16 text-center text-sm text-muted-foreground">暂无企业</div>
        ) : (
          <div className="bg-white rounded-xl border border-border overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-surface-2 text-muted-foreground text-xs uppercase">
                <tr>
                  <th className="text-left px-4 py-3">企业名称</th>
                  <th className="text-left px-4 py-3">行业</th>
                  <th className="text-left px-4 py-3">联系人</th>
                  <th className="text-left px-4 py-3">规模</th>
                  <th className="text-left px-4 py-3">状态</th>
                  <th className="text-right px-4 py-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {companies.map(c => (
                  <tr key={c.id} className="border-t border-border hover:bg-surface-2/50">
                    <td className="px-4 py-3 font-medium">{c.name}</td>
                    <td className="px-4 py-3 text-muted-foreground">{c.industry || '-'}</td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {c.contactName || '-'}{c.contactPhone ? ` · ${c.contactPhone}` : ''}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{c.scale || '-'}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${
                        c.status === 'archived' ? 'bg-red-100 text-red-700' : 'bg-green-100 text-green-700'
                      }`}>
                        {c.status === 'archived' ? '📦 已归档' : '🟢 合作中'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => openEdit(c)}
                        className="text-xs px-2 py-1 rounded hover:bg-surface-2 mr-1">编辑</button>
                      <button onClick={() => handleToggleStatus(c)}
                        className="text-xs px-2 py-1 rounded hover:bg-surface-2 mr-1">
                        {c.status === 'archived' ? '启用' : '归档'}
                      </button>
                      <button onClick={() => openCodes(c)}
                        className="text-xs px-2 py-1 rounded hover:bg-surface-2 text-primary font-medium">
                        注册码
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* ── 新建/编辑企业 Modal ── */}
        {showForm && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setShowForm(false)}>
            <div className="bg-white rounded-2xl w-full max-w-lg max-h-[85vh] overflow-y-auto p-6 shadow-xl" onClick={e => e.stopPropagation()}>
              <h2 className="text-lg font-bold mb-4">{editingId ? '编辑企业' : '新建企业'}</h2>
              {error && <div className="mb-3 text-xs text-red-600 bg-red-50 px-3 py-2 rounded">{error}</div>}
              <div className="space-y-3">
                <div>
                  <label className="block text-xs font-medium text-muted-foreground mb-1">企业名称 <span className="text-red-400">*</span></label>
                  <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">Logo URL</label>
                    <input value={form.logoUrl} onChange={e => setForm(f => ({ ...f, logoUrl: e.target.value }))}
                      className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">品牌色</label>
                    <input value={form.brandColor} onChange={e => setForm(f => ({ ...f, brandColor: e.target.value }))}
                      placeholder="#1a73e8" className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                </div>
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">联系人</label>
                    <input value={form.contactName} onChange={e => setForm(f => ({ ...f, contactName: e.target.value }))}
                      className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">电话</label>
                    <input value={form.contactPhone} onChange={e => setForm(f => ({ ...f, contactPhone: e.target.value }))}
                      className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">邮箱</label>
                    <input value={form.contactEmail} onChange={e => setForm(f => ({ ...f, contactEmail: e.target.value }))}
                      className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-medium text-muted-foreground mb-1">地址</label>
                  <input value={form.address} onChange={e => setForm(f => ({ ...f, address: e.target.value }))}
                    className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">行业</label>
                    <input value={form.industry} onChange={e => setForm(f => ({ ...f, industry: e.target.value }))}
                      placeholder="如：互联网/金融/制造" className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-muted-foreground mb-1">规模</label>
                    <input value={form.scale} onChange={e => setForm(f => ({ ...f, scale: e.target.value }))}
                      placeholder="如：50-200人" className="w-full border border-border rounded-lg px-3 py-2 text-sm" />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-medium text-muted-foreground mb-1">备注</label>
                  <textarea value={form.notes} onChange={e => setForm(f => ({ ...f, notes: e.target.value }))}
                    rows={2} className="w-full border border-border rounded-lg px-3 py-2 text-sm resize-none" />
                </div>
              </div>
              <div className="flex justify-end gap-3 mt-5">
                <button onClick={() => setShowForm(false)} className="px-4 py-2 text-sm text-muted-foreground hover:text-foreground">取消</button>
                <button onClick={handleSave} disabled={saving}
                  className="px-4 py-2 text-sm bg-primary text-white rounded-lg hover:bg-primary-hover disabled:opacity-50">
                  {saving ? '保存中...' : '保存'}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* ── 注册码管理 Modal ── */}
        {codeTarget && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setCodeTarget(null)}>
            <div className="bg-white rounded-2xl w-full max-w-xl max-h-[85vh] overflow-y-auto p-6 shadow-xl" onClick={e => e.stopPropagation()}>
              <h2 className="text-lg font-bold mb-1">注册码管理</h2>
              <p className="text-xs text-muted-foreground mb-4">{codeTarget.name}</p>

              {error && <div className="mb-3 text-xs text-red-600 bg-red-50 px-3 py-2 rounded">{error}</div>}

              {/* 生成新码 */}
              <div className="flex items-center gap-3 mb-4">
                <button onClick={handleGenerateCode} disabled={saving}
                  className="px-3 py-1.5 text-xs bg-primary text-white rounded-lg hover:bg-primary-hover disabled:opacity-50">
                  {saving ? '生成中...' : '+ 生成注册码'}
                </button>
                <select value={codeRole} onChange={e => setCodeRole(e.target.value)}
                  className="border border-border rounded-lg px-3 py-1.5 text-xs bg-white">
                  <option value="employee">角色：员工</option>
                  <option value="company_admin">角色：企业管理员</option>
                </select>
              </div>

              {newCode && (
                <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-4">
                  <p className="text-xs text-amber-700 font-medium mb-2">⚠️ 请立即复制并分发给员工，关闭后将无法再次查看</p>
                  <div className="flex items-center gap-2">
                    <code className="bg-white rounded px-3 py-1.5 text-sm font-mono select-all border border-border">{newCode}</code>
                    <button onClick={() => handleCopy(newCode)}
                      className="text-xs bg-primary text-white px-3 py-1.5 rounded-lg hover:bg-primary-hover flex-shrink-0 min-w-[64px]">{codeCopied ? '✅ 已复制' : '📋 复制'}</button>
                  </div>
                </div>
              )}

              {/* 注册码列表 */}
              {codesLoading ? (
                <p className="text-sm text-muted-foreground py-4">加载中...</p>
              ) : codes.length === 0 ? (
                <p className="text-sm text-muted-foreground py-4">暂无注册码</p>
              ) : (
                <table className="w-full text-sm">
                  <thead className="bg-surface-2 text-muted-foreground text-xs uppercase">
                    <tr>
                      <th className="text-left px-3 py-2">注册码</th>
                      <th className="text-left px-3 py-2">状态</th>
                      <th className="text-left px-3 py-2">已用/上限</th>
                      <th className="text-left px-3 py-2">创建时间</th>
                      <th className="text-right px-3 py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {codes.map(c => (
                      <tr key={c.id} className="border-t border-border hover:bg-surface-2/50">
                        <td className="px-3 py-2 font-mono text-xs">{c.code}</td>
                        <td className="px-3 py-2">
                          <span className={`inline-flex text-xs font-medium px-2 py-0.5 rounded-full ${
                            c.enabled ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                          }`}>
                            {c.enabled ? '启用' : '停用'}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-muted-foreground text-xs">
                          {c.usedCount}/{c.maxUses > 0 ? c.maxUses : '∞'}
                        </td>
                        <td className="px-3 py-2 text-muted-foreground text-xs">
                          {c.createdAt ? c.createdAt.substring(0, 10) : '-'}
                        </td>
                        <td className="px-3 py-2 text-right">
                          <button onClick={() => handleToggleCode(c)}
                            className="text-xs px-2 py-1 rounded hover:bg-surface-2 mr-1">
                            {c.enabled ? '停用' : '启用'}
                          </button>
                          <button onClick={() => handleDeleteCode(c)}
                            className="text-xs px-2 py-1 rounded hover:bg-surface-2 text-red-600">
                            删除
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
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
