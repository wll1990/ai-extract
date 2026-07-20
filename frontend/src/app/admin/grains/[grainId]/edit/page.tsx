'use client';

import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_BASE, authHeaders } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

/**
 * 颗粒编辑器 —— Admin 可在线修改颗粒的 4 个文本字段。
 *
 * 保存后后端自动重新向量化（re-embed），下次对话立即生效，无需重启。
 * Phase 3 上线，Phase 4 会增加修改原因和编辑历史。
 */
export default function GrainEditPage() {
  const params = useParams(); const router = useRouter();
  const grainId = params.grainId as string;
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    expertThought: '', standardScript: '', commonMistakes: '',
    applicableCondition: '', sceneTag: '', weight: 1.0,
  });

  useEffect(() => {
    fetch(`${API_BASE}/admin/grains/${grainId}`, { headers: authHeaders() })
      .then(r => r.json()).then(d => {
        const g = d.data;
        setForm({
          expertThought: g.expertThought || '', standardScript: g.standardScript || '',
          commonMistakes: g.commonMistakes || '', applicableCondition: g.applicableCondition || '',
          sceneTag: g.sceneTag || '', weight: g.weight || 1.0,
        });
      }).catch(() => {}).finally(() => setLoading(false));
  }, [grainId]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await fetch(`${API_BASE}/admin/grains/${grainId}`, {
        method: 'PUT', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
      });
      alert('✅ 已保存，向量已更新，下次对话生效');
      router.push(`/admin/grains/${grainId}`);
    } catch {
      alert('保存失败');
    } finally { setSaving(false); }
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="max-w-2xl mx-auto">
      <button onClick={() => router.back()} className="text-sm text-primary hover:underline mb-4 block">← 返回</button>
      <div className="rounded-xl bg-surface-2 p-6 shadow-sm">
        <h2 className="text-lg font-bold mb-4">✏️ 编辑颗粒</h2>

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
