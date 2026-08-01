'use client';

import { useEffect, useState, useRef, use, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { getToken } from '@/lib/storage';
import { updateGrain, deprecateGrain, restoreGrain } from '@/lib/api/grain';

interface GrainItem {
  id: string;
  sceneTag: string;
  sceneDescription: string;
  expertThought: string;
  standardScript: string;
  commonMistakes: string;
  status: string;
  sourceType?: string;
  sourceMaterialId?: string;
  sourceMaterialName?: string;
}

const SOURCE_LABELS: Record<string, string> = {
  file_upload: '素材上传',
  interview: 'AI 访谈',
};

export default function AuditPage({ params }: { params: Promise<{ skillId: string }> }) {
  const { skillId } = use(params);
  const router = useRouter();
  const [grains, setGrains] = useState<GrainItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [publishing, setPublishing] = useState(false);
  const publishLock = useRef(false);

  /* ── 编辑 Modal state ── */
  const [editingGrain, setEditingGrain] = useState<GrainItem | null>(null);
  const [editForm, setEditForm] = useState({ sceneTag: '', expertThought: '', standardScript: '', commonMistakes: '' });
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const authHeaders = (): Record<string, string> => {
    const token = getToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  };

  const fetchGrains = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const r = await fetch(`/api/v1/skills/${skillId}/grains`, { headers: authHeaders(), signal });
      const d = await r.json();
      if (d.code === 200) {
        setGrains(d.data || []);
      } else if (d.code === 403) {
        setError('无权访问此分身');
      } else {
        throw new Error(d.message || '加载失败');
      }
    } catch (e: any) {
      if (e.name !== 'AbortError') setError(e.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [skillId]);

  useEffect(() => {
    const ac = new AbortController();
    fetchGrains(ac.signal);
    return () => ac.abort();
  }, [fetchGrains]);

  /* ── 发布 ── */
  const handlePublish = async () => {
    if (publishLock.current) return;
    publishLock.current = true;
    setPublishing(true);
    try {
      const r = await fetch(`/api/v1/skills/${skillId}/status`, {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'published' }),
      });
      const d = await r.json();
      if (d.code === 200) {
        router.push('/platform/my');
      } else {
        setError(d.message || '发布失败');
      }
    } catch {
      setError('网络错误');
    } finally {
      setPublishing(false);
      publishLock.current = false;
    }
  };

  /* ── 编辑 ── */
  const openEdit = (g: GrainItem) => {
    setEditingGrain(g);
    setEditForm({
      sceneTag: g.sceneTag || '',
      expertThought: g.expertThought || '',
      standardScript: g.standardScript || '',
      commonMistakes: g.commonMistakes || '',
    });
    setEditError(null);
  };

  const handleSaveEdit = async () => {
    if (!editingGrain) return;
    setSaving(true);
    setEditError(null);
    try {
      const result = await updateGrain(editingGrain.id, editForm);
      setGrains(prev => prev.map(g => g.id === editingGrain.id ? {
        ...g,
        sceneTag: result.sceneTag,
        expertThought: result.expertThought,
        standardScript: result.standardScript,
        commonMistakes: editForm.commonMistakes,
        status: result.status,
      } : g));
      setEditingGrain(null);
    } catch (e: any) {
      setEditError(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  /* ── 废弃 / 恢复 ── */
  const handleDeprecate = async (grainId: string) => {
    if (!confirm('确定废弃这条颗粒吗？废弃后不会在对话中被检索。')) return;
    try {
      await deprecateGrain(grainId);
      setGrains(prev => prev.map(g => g.id === grainId ? { ...g, status: 'deprecated' } : g));
    } catch (e: any) {
      alert(e.message || '操作失败');
    }
  };

  const handleRestore = async (grainId: string) => {
    try {
      await restoreGrain(grainId);
      setGrains(prev => prev.map(g => g.id === grainId ? { ...g, status: 'active' } : g));
    } catch (e: any) {
      alert(e.message || '操作失败');
    }
  };

  const activeGrains = grains.filter(g => g.status !== 'deprecated');

  if (loading) return <div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center text-sm text-[#747f9e]">加载中...</div>;

  return (
    <div className="min-h-screen bg-[#f7f9ff] px-5 py-8" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="max-w-2xl mx-auto">
        <button onClick={() => router.back()} className="text-sm text-[#747f9e] mb-4 block">← 返回</button>
        <h1 className="text-xl font-bold text-[#10162f] mb-1">审核颗粒</h1>
        <p className="text-sm text-[#747f9e] mb-6">
          共 {grains.length} 条颗粒 · {activeGrains.length} 条有效{grains.filter(g => g.status === 'deprecated').length > 0 && ` · ${grains.filter(g => g.status === 'deprecated').length} 条已废弃`}
        </p>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-xl bg-red-50 border border-red-200 text-sm text-red-600 flex justify-between">
            <span>{error}</span>
            <button onClick={() => { setError(null); fetchGrains(); }} className="text-red-600 font-medium">重试</button>
          </div>
        )}

        {grains.length === 0 && !error && (
          <div className="text-center py-12">
            <span className="text-4xl mb-3 block">📋</span>
            <p className="text-sm text-[#747f9e]">暂无颗粒，AI 可能还在分析中</p>
          </div>
        )}

        <div className="space-y-3 mb-8">
          {grains.map((g) => {
            const isDeprecated = g.status === 'deprecated';
            const sourceLabel = g.sourceType ? (SOURCE_LABELS[g.sourceType] || g.sourceType) : null;
            return (
              <div
                key={g.id}
                style={{
                  opacity: isDeprecated ? 0.5 : 1,
                  transition: 'opacity 0.3s',
                }}
                className="bg-white rounded-2xl border border-[#e1e7ff] p-4 shadow-[0_8px_30px_rgba(42,74,177,0.06)]"
              >
                {/* 顶行：场景标签 + 状态 badge + 操作按钮 */}
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <span className="px-2 py-0.5 rounded-full bg-[#eef2ff] text-[#3150db] text-xs font-medium">
                      {g.sceneTag || '通用'}
                    </span>
                    <span style={{
                      display: 'inline-flex', alignItems: 'center', gap: 4,
                      padding: '1px 8px', borderRadius: 100,
                      fontSize: 10, fontWeight: 600,
                      background: isDeprecated ? '#f3f4f6' : '#d1fae5',
                      color: isDeprecated ? '#6b7280' : '#059669',
                    }}>
                      {isDeprecated ? '已废弃' : '有效'}
                    </span>
                  </div>
                  <div className="flex items-center gap-1">
                    {!isDeprecated && (
                      <>
                        <button
                          onClick={() => openEdit(g)}
                          className="px-2 py-1 rounded text-xs text-[#2147ff] hover:bg-[#eef2ff] transition-colors"
                        >
                          编辑
                        </button>
                        <button
                          onClick={() => handleDeprecate(g.id)}
                          className="px-2 py-1 rounded text-xs text-[#dc2626] hover:bg-red-50 transition-colors"
                        >
                          废弃
                        </button>
                      </>
                    )}
                    {isDeprecated && (
                      <button
                        onClick={() => handleRestore(g.id)}
                        className="px-2 py-1 rounded text-xs text-[#059669] hover:bg-green-50 transition-colors"
                      >
                        恢复
                      </button>
                    )}
                  </div>
                </div>

                {/* 内容 */}
                {g.expertThought && (
                  <div className="mb-2">
                    <span className="text-xs text-[#747f9e]">思路：</span>
                    <span className="text-sm text-[#10162f]">{g.expertThought}</span>
                  </div>
                )}
                {g.standardScript && (
                  <div className="mb-2">
                    <span className="text-xs text-[#747f9e]">话术：</span>
                    <span className="text-sm text-[#10162f] italic">"{g.standardScript}"</span>
                  </div>
                )}
                {g.commonMistakes && (
                  <div className="text-xs text-[#747f9e]">避坑：{g.commonMistakes}</div>
                )}

                {/* 来源溯源 */}
                {sourceLabel && (
                  <div className="mt-2 pt-2 border-t border-[#f0f0ff] flex items-center gap-1">
                    <span className="text-[10px] text-[#a0aec0]">
                      {sourceLabel === '素材上传' && g.sourceMaterialName
                        ? `📎 来自素材: ${g.sourceMaterialName}`
                        : sourceLabel === '素材上传'
                          ? `📎 来自素材上传`
                          : `🎙️ 来自 AI 访谈`}
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {grains.length > 0 && (
          <div className="flex gap-3">
            <button
              onClick={handlePublish}
              disabled={publishing}
              className="flex-1 py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40 hover:translate-y-[-1px] transition-transform"
            >
              {publishing ? '发布中...' : `一键发布 (${activeGrains.length} 条有效颗粒)`}
            </button>
            <button
              onClick={() => router.back()}
              className="px-6 py-3 rounded-full border border-[#cdd7ff] text-[#2147ff] text-sm font-medium"
            >
              返回
            </button>
          </div>
        )}
      </div>

      {/* ═══ 编辑 Modal ═══ */}
      {editingGrain && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="编辑颗粒"
          onClick={() => setEditingGrain(null)}
          onKeyDown={e => { if (e.key === 'Escape') setEditingGrain(null); }}
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200, padding: 20 }}
        >
          <div
            onClick={e => e.stopPropagation()}
            onKeyDown={e => { if (e.key === 'Escape') setEditingGrain(null); }}
            style={{ background: '#fff', borderRadius: 20, padding: '28px 24px', width: '90%', maxWidth: 480, boxShadow: '0 20px 60px rgba(0,0,0,0.2)' }}
          >
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-bold text-[#10162f]">编辑颗粒</h3>
              <button onClick={() => setEditingGrain(null)} aria-label="关闭编辑弹窗" className="text-[#747f9e] text-lg leading-none">&times;</button>
            </div>

            {editError && (
              <div className="mb-3 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-xs text-red-600">{editError}</div>
            )}

            <div className="space-y-3">
              <div>
                <label htmlFor="grain-sceneTag" className="block text-xs font-medium text-[#747f9e] mb-1">场景标签</label>
                <input
                  id="grain-sceneTag"
                  value={editForm.sceneTag}
                  onChange={e => setEditForm(f => ({ ...f, sceneTag: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border border-[#dfe6ff] text-sm text-[#10162f] outline-none focus:border-[#2147ff]"
                />
              </div>
              <div>
                <label htmlFor="grain-expertThought" className="block text-xs font-medium text-[#747f9e] mb-1">思路</label>
                <textarea
                  id="grain-expertThought"
                  value={editForm.expertThought}
                  onChange={e => setEditForm(f => ({ ...f, expertThought: e.target.value }))}
                  rows={3}
                  className="w-full px-3 py-2 rounded-lg border border-[#dfe6ff] text-sm text-[#10162f] outline-none focus:border-[#2147ff] resize-none"
                />
              </div>
              <div>
                <label htmlFor="grain-standardScript" className="block text-xs font-medium text-[#747f9e] mb-1">话术</label>
                <textarea
                  id="grain-standardScript"
                  value={editForm.standardScript}
                  onChange={e => setEditForm(f => ({ ...f, standardScript: e.target.value }))}
                  rows={3}
                  className="w-full px-3 py-2 rounded-lg border border-[#dfe6ff] text-sm text-[#10162f] outline-none focus:border-[#2147ff] resize-none"
                />
              </div>
              <div>
                <label htmlFor="grain-commonMistakes" className="block text-xs font-medium text-[#747f9e] mb-1">避坑</label>
                <textarea
                  id="grain-commonMistakes"
                  value={editForm.commonMistakes}
                  onChange={e => setEditForm(f => ({ ...f, commonMistakes: e.target.value }))}
                  rows={2}
                  className="w-full px-3 py-2 rounded-lg border border-[#dfe6ff] text-sm text-[#10162f] outline-none focus:border-[#2147ff] resize-none"
                />
              </div>
            </div>

            <div className="flex gap-3 mt-5">
              <button
                onClick={handleSaveEdit}
                disabled={saving}
                className="flex-1 py-2.5 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40"
              >
                {saving ? '保存中...' : '保存'}
              </button>
              <button
                onClick={() => setEditingGrain(null)}
                className="px-6 py-2.5 rounded-full border border-[#cdd7ff] text-[#2147ff] text-sm font-medium"
              >
                取消
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
