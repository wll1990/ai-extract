'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { apiClient, API_BASE } from '@/lib/api/client';
import { OrgDashboard } from '@/components/admin/OrgDashboard';
import { Pagination } from '@/components/ui/Pagination';
import { copyToClipboard } from '@/lib/clipboard';

interface OrgSkill {
  id: string;
  type: string;
  displayName: string;
  ownerName: string;
  ownerTitle: string;
  avatarUrl?: string;
  status: string;
  memberCount: number;
  stats?: { conversationCount: number; userCount: number; satisfactionRate: number };
}

interface SkillOption {
  id: string;
  ownerName: string;
  displayName: string;
  ownerTitle: string;
  department?: string;
}

export default function AdminOrgSkillsPage() {
  const [orgSkills, setOrgSkills] = useState<OrgSkill[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [allSkills, setAllSkills] = useState<SkillOption[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Form
  const [formName, setFormName] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [formAvatar, setFormAvatar] = useState('');
  const [formMembers, setFormMembers] = useState<string[]>([]);

  const load = useCallback(() => {
    setLoading(true);
    apiClient<{ content: OrgSkill[]; total: number; totalPages: number }>(`/admin/organization-skills?page=${page}&size=20`)
      .then(d => { setOrgSkills(d.content || []); setTotalPages(d.totalPages); setTotalElements(d.total); })
      .catch(e => console.error('加载组织分身失败:', e))
      .finally(() => setLoading(false));
  }, [page]);

  const loadAllSkills = () => {
    apiClient<{ content: SkillOption[] }>('/skills/list?size=100&status=published')
      .then(d => setAllSkills(d.content || []))
      .catch(() => {});
  };

  useEffect(() => { load(); loadAllSkills(); }, [load]);

  const resetForm = () => {
    setFormName(''); setFormDesc(''); setFormAvatar(''); setFormMembers([]);
    setEditingId(null); setShowForm(false);
  };

  const handleSubmit = async () => {
    if (!formName || formMembers.length === 0) return;
    const body = { name: formName, description: formDesc, avatarUrl: formAvatar, memberSkillIds: formMembers };
    try {
      if (editingId) {
        await apiClient(`/admin/organization-skills/${editingId}`, { method: 'PUT', body: JSON.stringify(body) });
      } else {
        await apiClient('/admin/organization-skills', { method: 'POST', body: JSON.stringify(body) });
      }
      resetForm(); load();
    } catch (e) { console.error('保存失败:', e); }
  };

  const handleEdit = async (org: OrgSkill) => {
    setEditingId(org.id);
    setFormName(org.displayName);
    setFormDesc(org.ownerTitle || '');
    setFormAvatar(org.avatarUrl || '');
    // 预填充成员：查详情获取 member IDs
    try {
      const detail = await apiClient<{ members?: Array<{ id: string }> }>(`/admin/organization-skills/${org.id}`);
      setFormMembers(detail?.members?.map(m => m.id) || []);
    } catch (e) {
      console.error('加载成员列表失败:', e);
      setFormMembers([]);
    }
    setShowForm(true);
  };

  const handleDelete = async (id: string) => {
    if (!confirm('确定删除这个组织分身？')) return;
    try { await apiClient(`/admin/organization-skills/${id}`, { method: 'DELETE' }); load(); }
    catch (e) { console.error('删除失败:', e); }
  };

  const [shareCode, setShareCode] = useState<string | null>(null);
  const [dashboardData, setDashboardData] = useState<any>(null);

  const handleDashboard = async (id: string) => {
    try {
      const data = await apiClient(`/admin/organization-skills/${id}/dashboard`);
      setDashboardData(data);
    } catch (e) { console.error('加载面板失败:', e); }
  };

  const handleShare = async (id: string) => {
    try {
      const data = await apiClient<{ shareCode: string }>(`/admin/organization-skills/${id}/share`, { method: 'POST' });
      setShareCode(data.shareCode);
    } catch (e) { console.error('生成分享失败:', e); }
  };

  const handlePublish = async (id: string, status: string) => {
    try {
      await apiClient(`/admin/organization-skills/${id}/status`, {
        method: 'PUT', body: JSON.stringify({ status }),
      });
      load();
    } catch (e) { console.error('状态变更失败:', e); }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold text-foreground">组织分身管理</h1>
            <p className="text-sm text-muted-foreground mt-1">创建部门级综合分身，聚合多位销冠的经验</p>
          </div>
          <button onClick={() => { resetForm(); setShowForm(true); loadAllSkills(); }}
            className="rounded-lg bg-indigo-600 text-white px-4 py-2 text-sm font-medium">
            + 新建组织分身
          </button>
        </div>

        {/* Form modal */}
        {showForm && (
          <div className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center" onClick={resetForm}>
            <div className="bg-white rounded-2xl p-6 w-full max-w-lg mx-4 shadow-xl" onClick={e => e.stopPropagation()}>
              <h2 className="text-lg font-bold mb-4">{editingId ? '编辑' : '新建'}组织分身</h2>
              <div className="space-y-3">
                <div>
                  <label className="block text-sm font-medium mb-1">名称 *</label>
                  <input value={formName} onChange={e => setFormName(e.target.value)}
                    className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="如：华东销售部" />
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">描述</label>
                  <input value={formDesc} onChange={e => setFormDesc(e.target.value)}
                    className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="综合N位销冠经验" />
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">成员分身 *（可多选）</label>
                  <div className="max-h-48 overflow-y-auto border rounded-lg p-2 space-y-1">
                    {allSkills.filter(s => s.id).map(s => (
                      <label key={s.id} className="flex items-center gap-2 text-sm py-1 cursor-pointer">
                        <input type="checkbox" checked={formMembers.includes(s.id)}
                          onChange={e => {
                            if (e.target.checked) setFormMembers([...formMembers, s.id]);
                            else setFormMembers(formMembers.filter(id => id !== s.id));
                          }} />
                        <span className="font-medium">{s.ownerName || s.displayName}</span>
                        <span className="text-muted-foreground-2 text-xs">{s.ownerTitle}</span>
                        {s.department && <span className="text-[10px] bg-gray-100 rounded px-1">{s.department}</span>}
                      </label>
                    ))}
                    {allSkills.length === 0 && <p className="text-xs text-muted-foreground p-2">暂无已发布的分身</p>}
                  </div>
                </div>
              </div>
              <div className="flex justify-end gap-3 mt-4">
                <button onClick={resetForm} className="px-4 py-2 text-sm rounded-lg border">取消</button>
                <button onClick={handleSubmit} disabled={!formName || formMembers.length === 0}
                  className="px-4 py-2 text-sm rounded-lg bg-indigo-600 text-white disabled:opacity-40">
                  保存
                </button>
              </div>
            </div>
          </div>
        )}

        {/* List */}
        {loading ? (
          <p className="text-sm text-muted-foreground">加载中...</p>
        ) : orgSkills.length === 0 ? (
          <div className="rounded-2xl bg-surface-2 p-12 text-center">
            <span className="text-4xl">🏢</span>
            <p className="mt-4 text-muted-foreground">暂无组织分身</p>
            <p className="text-sm text-muted-foreground-2">创建第一个组织分身，让新人一次向多位销冠请教</p>
          </div>
        ) : (
          <div className="space-y-3">
            {orgSkills.map(org => (
              <div key={org.id} className="rounded-xl bg-surface-2 p-4 shadow-sm flex items-center gap-4">
                <span className="text-2xl">🏢</span>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <h3 className="font-semibold text-foreground">{org.displayName}</h3>
                    <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${
                      org.status === 'published' ? 'bg-green-100 text-green-700' :
                      org.status === 'draft' ? 'bg-gray-100 text-gray-600' : 'bg-yellow-100 text-yellow-700'
                    }`}>{org.status === 'published' ? '已发布' : org.status === 'draft' ? '草稿' : org.status}</span>
                  </div>
                  <p className="text-xs text-muted-foreground-2">
                    {org.ownerTitle || `综合 ${org.memberCount} 位销冠`}
                    {org.stats && org.stats.conversationCount > 0 && (
                      <span className="ml-2">💬 {org.stats.conversationCount} 次 · 👍 {org.stats.satisfactionRate}%</span>
                    )}
                  </p>
                </div>
                <div className="flex gap-2">
                  {org.status !== 'published' && (
                    <button onClick={() => handlePublish(org.id, 'published')}
                      className="rounded-lg bg-green-600 text-white px-3 py-1 text-xs">发布</button>
                  )}
                  {org.status === 'published' && (
                    <button onClick={() => handlePublish(org.id, 'draft')}
                      className="rounded-lg bg-gray-200 text-gray-700 px-3 py-1 text-xs">下线</button>
                  )}
                  <button onClick={() => handleEdit(org)}
                    className="rounded-lg border px-3 py-1 text-xs">编辑</button>
                  {org.status === 'published' && (
                    <button onClick={() => handleShare(org.id)}
                      className="rounded-lg border border-blue-200 text-blue-600 px-3 py-1 text-xs">分享</button>
                  )}
                  {org.status === 'published' && (
                    <button onClick={() => handleDashboard(org.id)}
                      className="rounded-lg border border-purple-200 text-purple-600 px-3 py-1 text-xs">📊</button>
                  )}
                  <button onClick={() => handleDelete(org.id)}
                    className="rounded-lg border border-red-200 text-red-600 px-3 py-1 text-xs">删除</button>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Share code modal */}
        {shareCode && (
          <div className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center" onClick={() => setShareCode(null)}>
            <div className="bg-white rounded-2xl p-6 w-full max-w-sm mx-4 shadow-xl text-center" onClick={e => e.stopPropagation()}>
              <h3 className="text-lg font-bold mb-2">分享链接已生成</h3>
              <p className="text-xs text-muted-foreground-2 mb-4">将此链接分享给他人，即可在 H5 端使用组织分身</p>
              <div className="bg-gray-50 rounded-lg p-3 text-sm font-mono text-center mb-4 break-all">
                {typeof window !== 'undefined' ? `${window.location.origin}/s/${shareCode}` : `/s/${shareCode}`}
              </div>
              <button onClick={async () => {
                const url = `${window.location.origin}/s/${shareCode}`;
                const ok = await copyToClipboard(url);
                if (ok) alert('已复制到剪贴板');
              }}
                className="rounded-lg bg-indigo-600 text-white px-4 py-2 text-sm w-full mb-2">
                复制链接
              </button>
              <button onClick={() => setShareCode(null)}
                className="text-sm text-muted-foreground">关闭</button>
            </div>
          </div>
        )}
      </div>

      {/* Dashboard Modal */}
      {dashboardData && (
        <OrgDashboard data={dashboardData} onClose={() => setDashboardData(null)} />
      )}
      <Pagination page={page} totalPages={totalPages} totalElements={totalElements}
        onPageChange={p => setPage(p)} loading={loading} />
    </div>
  );
}
