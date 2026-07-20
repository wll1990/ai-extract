'use client';

import React, { useState } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import { API_BASE, authHeaders } from '@/lib/api/client';

/**
 * 新增颗粒 —— 从知识缺口「补充颗粒」按钮跳入，分身已预选。
 */
export default function NewGrainPage() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const skillId = searchParams.get('skillId') || '';
  const spaceId = searchParams.get('spaceId') || '';

  const [form, setForm] = useState({
    sceneTag: '', sceneDescription: '',
    expertThought: '', standardScript: '', commonMistakes: '', applicableCondition: '',
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);

  const validate = (): boolean => {
    const e: Record<string, string> = {};
    if (!skillId) e.skillId = '缺少分身ID';
    if (!form.sceneTag.trim()) e.sceneTag = '场景标签必填';
    if (!form.sceneDescription.trim()) e.sceneDescription = '场景描述必填';
    if (!form.expertThought.trim()) e.expertThought = '专家思考必填';
    if (!form.standardScript.trim()) e.standardScript = '标准话术必填';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) return;
    setSaving(true);
    try {
      await fetch(`${API_BASE}/admin/grains`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ skillId, spaceId, ...form }),
      });
      alert('✅ 颗粒已创建，向量已生成，下次对话生效');
      router.back();
    } catch { alert('保存失败'); }
    finally { setSaving(false); }
  };

  if (!skillId) {
    return (
      <div className="max-w-2xl mx-auto px-6 py-8 text-center">
        <p className="text-red-500 text-sm">缺少分身ID参数，请从知识缺口页面进入。</p>
        <button onClick={() => router.back()} className="mt-4 text-sm text-primary hover:underline">← 返回</button>
      </div>
    );
  }

  const fields = [
    { key: 'sceneTag', label: '场景标签', placeholder: '如: 价格谈判、异议处理...', type: 'input' },
    { key: 'sceneDescription', label: '场景描述（一句话概括）', placeholder: '如: 客户说XX比你便宜时如何回应', type: 'input' },
    { key: 'expertThought', label: '🧠 专家思考', placeholder: '这个场景下的判断逻辑和策略选择', rows: 4 },
    { key: 'standardScript', label: '💬 标准话术', placeholder: '实际说过的原话，越具体越有说服力', rows: 4 },
    { key: 'commonMistakes', label: '⚠️ 常见错误（可选）', placeholder: '新人容易在这个场景下犯的错', rows: 3 },
    { key: 'applicableCondition', label: '📌 适用条件（可选）', placeholder: '什么情况下这个话术有效', rows: 2 },
  ];

  return (
    <div className="max-w-2xl mx-auto px-6 py-8">
      <button onClick={() => router.back()} className="text-sm text-primary hover:underline mb-4 block">← 返回</button>
      <div className="rounded-xl bg-surface-2 p-6 shadow-sm">
        <h2 className="text-lg font-bold mb-4">➕ 新增颗粒</h2>
        <p className="text-xs text-muted-foreground mb-4">为分身补充缺失的知识点，保存后自动向量化</p>

        {fields.map(f => (
          <div key={f.key} className="mb-4">
            <label className="text-xs text-muted-foreground mb-1 block">
              {f.label} {['sceneTag', 'sceneDescription', 'expertThought', 'standardScript'].includes(f.key) && <span className="text-red-400">*</span>}
            </label>
            {f.type === 'input' ? (
              <input value={(form as any)[f.key]} onChange={e => setForm(p => ({ ...p, [f.key]: e.target.value }))}
                placeholder={f.placeholder}
                className={`w-full rounded-lg border px-3 py-2 text-sm ${errors[f.key] ? 'border-red-400' : ''}`} />
            ) : (
              <textarea value={(form as any)[f.key]} onChange={e => setForm(p => ({ ...p, [f.key]: e.target.value }))}
                rows={f.rows} placeholder={f.placeholder}
                className={`w-full rounded-lg border px-3 py-2 text-sm outline-none focus:border-primary resize-y ${errors[f.key] ? 'border-red-400' : ''}`} />
            )}
            {errors[f.key] && <p className="text-xs text-red-400 mt-1">{errors[f.key]}</p>}
          </div>
        ))}

        <div className="flex gap-2 mt-6 pt-4 border-t border-border">
          <button onClick={() => router.back()} className="text-sm rounded-lg px-4 py-2 border">取消</button>
          <button onClick={handleSave} disabled={saving}
            className="text-sm bg-primary text-white rounded-lg px-6 py-2 disabled:opacity-40">
            {saving ? '保存中...' : '💾 创建并生成向量'}
          </button>
        </div>
      </div>
    </div>
  );
}
