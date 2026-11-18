'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  getKnowledge, updateKnowledge,
  CATEGORY_LABELS, SOURCE_TYPE_LABELS,
  type KnowledgeEntry,
} from '@/lib/api/knowledge';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

const input = 'w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground';

/**
 * 知识编辑（仅 draft/pending）：审核发现问题时可直接修正，不必废弃重建。
 * 已生效内容不允许直接改，需「基于此版本修改」生成新草稿。
 */
export default function KnowledgeEditPage({ params }: { params: { id: string } }) {
  const router = useRouter();
  const { id } = params;
  const [entry, setEntry] = useState<KnowledgeEntry | null>(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const [title, setTitle] = useState('');
  const [category, setCategory] = useState('methodology');
  const [sourceType, setSourceType] = useState('tenant');
  const [content, setContent] = useState('');
  const [source, setSource] = useState('');
  const [expireAt, setExpireAt] = useState('');

  useEffect(() => {
    getKnowledge(id)
      .then(k => {
        setEntry(k);
        setTitle(k.title || '');
        setCategory(k.category || 'methodology');
        setSourceType(k.sourceType || 'tenant');
        setContent(k.content || '');
        setSource(k.source || '');
        setExpireAt(k.expireAt ? k.expireAt.slice(0, 16) : '');
      })
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'));
  }, [id]);

  const save = async () => {
    if (!title.trim() || !content.trim()) {
      alert('标题和内容为必填');
      return;
    }
    if (!expireAt) {
      alert('请设置失效时间（通过并生效时使用）');
      return;
    }
    setSaving(true);
    try {
      await updateKnowledge(id, {
        title: title.trim(),
        category,
        sourceType,
        content: content.trim(),
        source: source.trim() || undefined,
        expireAt: `${expireAt.length === 16 ? `${expireAt}:00` : expireAt}`,
      });
      router.push(`/admin/knowledge/${id}`);
    } catch (e) {
      alert(e instanceof Error ? e.message : '保存失败');
      setSaving(false);
    }
  };

  if (error) return <div className="p-6 text-sm text-danger">{error}</div>;
  if (!entry) return <div className="p-16"><LoadingSpinner /></div>;

  const editable = entry.status === 'draft' || entry.status === 'pending';
  if (!editable) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <p className="text-sm text-muted-foreground">
          只有草稿和待审核的知识可以直接编辑。已生效内容请回详情页用「基于此版本修改」生成新草稿。
        </p>
        <button onClick={() => router.back()} className="mt-3 text-sm text-primary underline">返回</button>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <button onClick={() => router.push(`/admin/knowledge/${id}`)} className="mb-4 text-sm text-muted-foreground hover:underline">
        ← 返回详情
      </button>
      <h1 className="mb-1 text-xl font-bold text-foreground">编辑知识</h1>
      <p className="mb-5 text-sm text-muted-foreground">当前状态：{entry.status === 'draft' ? '草稿' : '待审核'}，修改后仍需审核才会生效。</p>

      <div className="space-y-3">
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-foreground">标题 *</span>
          <input value={title} onChange={e => setTitle(e.target.value)} className={input} />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">分类</span>
            <select value={category} onChange={e => setCategory(e.target.value)} className={input}>
              {Object.entries(CATEGORY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">来源类型</span>
            <select value={sourceType} onChange={e => setSourceType(e.target.value)} className={input}>
              {Object.entries(SOURCE_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
        </div>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-foreground">正文 *</span>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={12} className={input} />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">出处</span>
            <input value={source} onChange={e => setSource(e.target.value)} className={input} />
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">失效时间</span>
            <input type="datetime-local" value={expireAt} onChange={e => setExpireAt(e.target.value)} className={input} />
          </label>
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <button onClick={() => router.push(`/admin/knowledge/${id}`)} className="rounded-lg border border-border px-4 py-2 text-sm text-muted-foreground">取消</button>
          <button onClick={save} disabled={saving} className="rounded-lg bg-primary px-5 py-2 text-sm font-medium text-white disabled:opacity-50">
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}
