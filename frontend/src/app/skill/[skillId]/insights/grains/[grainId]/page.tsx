'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_BASE, authHeaders } from '@/lib/api/client';
import { csrfHeader } from '@/lib/csrf';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

/**
 * 分身主颗粒编辑 —— 员工没有 GRAIN_MANAGE，走 /skills/{skillId}/insights/grains 属主接口。
 * 保存后后端重新向量化，下次对话立即生效。
 */
export default function OwnerGrainEditPage() {
  const params = useParams(); const router = useRouter();
  const skillId = params.skillId as string;
  const grainId = params.grainId as string;
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [form, setForm] = useState({
    expertThought: '', standardScript: '', commonMistakes: '',
    applicableCondition: '', sceneTag: '', weight: 1.0,
  });

  useEffect(() => {
    fetch(`${API_BASE}/skills/${skillId}/insights/grains/${grainId}`, { headers: authHeaders() })
      .then(async r => {
        const d = await r.json().catch(() => null);
        if (!r.ok) throw new Error(d?.message || '颗粒加载失败');
        return d?.data;
      })
      .then(g => {
        if (!g) throw new Error('颗粒不存在');
        setForm({
          expertThought: g.expertThought || '', standardScript: g.standardScript || '',
          commonMistakes: g.commonMistakes || '', applicableCondition: g.applicableCondition || '',
          sceneTag: g.sceneTag || '', weight: g.weight || 1.0,
        });
      })
      .catch(e => setError(e instanceof Error ? e.message : '颗粒加载失败'))
      .finally(() => setLoading(false));
  }, [skillId, grainId]);

  const handleSave = async () => {
    setSaving(true); setError(''); setSaved(false);
    try {
      const res = await fetch(`${API_BASE}/skills/${skillId}/insights/grains/${grainId}`, {
        method: 'PUT',
        headers: { ...authHeaders(), ...csrfHeader(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...form, weight: String(form.weight) }),
      });
      const d = await res.json().catch(() => null);
      if (!res.ok) throw new Error(d?.message || `保存失败 (${res.status})`);
      setSaved(true);
      setTimeout(() => router.push(`/skill/${skillId}/insights/grains`), 600);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="max-w-2xl mx-auto px-6 py-8">
      <button onClick={() => router.back()} className="text-sm text-primary hover:underline mb-4 block">← 返回</button>
      <div className="rounded-xl bg-surface-2 p-6 shadow-sm">
        <h2 className="text-lg font-bold mb-1">✏️ 编辑颗粒</h2>
        <p className="text-xs text-muted-foreground mb-4">保存后会自动重新生成向量，下次对话立即生效</p>

        {error && (
          <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">{error}</div>
        )}
        {saved && (
          <div className="mb-4 rounded-lg border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-700">
            已保存，向量已更新
          </div>
        )}

        <EditField label="🧠 专家思考（这个场景下的判断逻辑）" value={form.expertThought}
          onChange={v => setForm(p => ({ ...p, expertThought: v }))} rows={4} />
        <EditField label="💬 标准话术（实际说过的原话）" value={form.standardScript}
          onChange={v => setForm(p => ({ ...p, standardScript: v }))} rows={4} />
        <EditField label="⚠️ 常见错误（新人容易犯的）" value={form.commonMistakes}
          onChange={v => setForm(p => ({ ...p, commonMistakes: v }))} rows={3} />
        <EditField label="📌 适用条件（什么情况下有效）" value={form.applicableCondition}
          onChange={v => setForm(p => ({ ...p, applicableCondition: v }))} rows={2} />

        <div className="grid grid-cols-2 gap-4 mt-4">
          <div>
            <label className="text-xs text-muted-foreground">场景标签</label>
            <input value={form.sceneTag} onChange={e => setForm(p => ({ ...p, sceneTag: e.target.value }))}
              className="w-full rounded-lg border px-3 py-2 text-sm mt-1" />
          </div>
          <div>
            <label className="text-xs text-muted-foreground">权重 (0.1-2.0)</label>
            <input type="number" step="0.1" min="0.1" max="2.0" value={form.weight}
              onChange={e => setForm(p => ({ ...p, weight: parseFloat(e.target.value) || 1.0 }))}
              className="w-full rounded-lg border px-3 py-2 text-sm mt-1" />
          </div>
        </div>

        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <button onClick={() => router.back()} className="text-sm rounded-lg px-4 py-2 border">取消</button>
          <button onClick={handleSave} disabled={saving}
            className="text-sm bg-primary text-white rounded-lg px-6 py-2 disabled:opacity-40">
            {saving ? '保存中...' : '💾 保存并重新生成向量'}
          </button>
        </div>
      </div>
    </div>
  );
}

function EditField({ label, value, onChange, rows }: { label: string; value: string; onChange: (v: string) => void; rows: number }) {
  return (
    <div className="mb-4">
      <label className="text-xs text-muted-foreground mb-1 block">{label}</label>
      <textarea value={value} onChange={e => onChange(e.target.value)} rows={rows}
        className="w-full rounded-lg border border-border px-3 py-2 text-sm outline-none focus:border-primary resize-y" />
    </div>
  );
}
