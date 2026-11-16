'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { getCase, updateCase, type IndustryCase } from '@/lib/api/knowledge';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

const input = 'w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground';

function readProfile(raw: string | null): { industry: string; scale: string; role: string } {
  if (!raw) return { industry: '', scale: '', role: '' };
  try {
    const v = JSON.parse(raw);
    if (v && typeof v === 'object') {
      return {
        industry: String(v.industry ?? v['行业'] ?? ''),
        scale: String(v.scale ?? v['规模'] ?? ''),
        role: String(v.role ?? v['角色'] ?? ''),
      };
    }
  } catch { /* 非 JSON 时按文本展示 */ }
  return { industry: raw, scale: '', role: '' };
}

function readQuotes(raw: string | null): string {
  if (!raw) return '';
  try {
    const v = JSON.parse(raw);
    if (Array.isArray(v)) return v.map(String).join('\n');
  } catch { /* ignore */ }
  return raw;
}

/**
 * 案例编辑（仅 draft/pending）：审核发现问题时可直接修正；
 * 已生效案例需「基于此版本修改」生成新草稿。
 */
export default function CaseEditPage({ params }: { params: { id: string } }) {
  const router = useRouter();
  const { id } = params;
  const [item, setItem] = useState<IndustryCase | null>(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const [title, setTitle] = useState('');
  const [sceneTag, setSceneTag] = useState('');
  const [result, setResult] = useState('');
  const [objection, setObjection] = useState('');
  const [keyActions, setKeyActions] = useState('');
  const [scripts, setScripts] = useState('');
  const [reusablePoints, setReusablePoints] = useState('');
  const [failurePoints, setFailurePoints] = useState('');
  const [source, setSource] = useState('');
  const [expireAt, setExpireAt] = useState('');
  const [profile, setProfile] = useState({ industry: '', scale: '', role: '' });
  const [quotes, setQuotes] = useState('');
  const [emotionChange, setEmotionChange] = useState('');
  const [advanceNodes, setAdvanceNodes] = useState('');

  useEffect(() => {
    getCase(id)
      .then(c => {
        setItem(c);
        setTitle(c.title || '');
        setSceneTag(c.sceneTag || '');
        setResult(c.result || '');
        setObjection(c.objection || '');
        setKeyActions(c.keyActions || '');
        setScripts(c.scripts || '');
        setReusablePoints(c.reusablePoints || '');
        setFailurePoints(c.failurePoints || '');
        setSource(c.source || '');
        setExpireAt(c.expireAt ? c.expireAt.slice(0, 16) : '');
        setProfile(readProfile(c.customerProfile));
        setQuotes(readQuotes(c.dialogueQuote));
        setEmotionChange(c.emotionChange || '');
        setAdvanceNodes(c.advanceNodes || '');
      })
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'));
  }, [id]);

  const save = async () => {
    if (!title.trim()) { alert('标题为必填'); return; }
    setSaving(true);
    const entries = [['industry', profile.industry], ['scale', profile.scale], ['role', profile.role]]
      .map(([k, v]) => [k, v.trim()] as const)
      .filter(([, v]) => v);
    const quoteList = quotes.split('\n').map(s => s.trim()).filter(Boolean);
    try {
      await updateCase(id, {
        title: title.trim(),
        customerProfile: entries.length ? JSON.stringify(Object.fromEntries(entries)) : undefined,
        dialogueQuote: quoteList.length ? JSON.stringify(quoteList) : undefined,
        emotionChange: emotionChange.trim() || undefined,
        advanceNodes: advanceNodes.trim() || undefined,
        sceneTag: sceneTag.trim() || undefined,
        result: result || undefined,
        objection: objection.trim() || undefined,
        keyActions: keyActions.trim() || undefined,
        scripts: scripts.trim() || undefined,
        reusablePoints: reusablePoints.trim() || undefined,
        failurePoints: failurePoints.trim() || undefined,
        source: source.trim() || undefined,
        expireAt: expireAt ? `${expireAt.length === 16 ? `${expireAt}:00` : expireAt}` : undefined,
      });
      router.push(`/admin/cases/${id}`);
    } catch (e) {
      alert(e instanceof Error ? e.message : '保存失败');
      setSaving(false);
    }
  };

  if (error) return <div className="p-6 text-sm text-danger">{error}</div>;
  if (!item) return <div className="p-16"><LoadingSpinner /></div>;

  const editable = item.status === 'draft' || item.status === 'pending';
  if (!editable) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <p className="text-sm text-muted-foreground">
          只有草稿和待审核的案例可以直接编辑。已生效案例请回详情页用「基于此版本修改」生成新草稿。
        </p>
        <button onClick={() => router.back()} className="mt-3 text-sm text-primary underline">返回</button>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <button onClick={() => router.push(`/admin/cases/${id}`)} className="mb-4 text-sm text-muted-foreground hover:underline">← 返回详情</button>
      <h1 className="mb-1 text-xl font-bold text-foreground">编辑案例</h1>
      <p className="mb-5 text-sm text-muted-foreground">当前状态：{item.status === 'draft' ? '草稿' : '待审核'}，修改后仍需审批才会生效。</p>

      <div className="space-y-3">
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-foreground">标题 *</span>
          <input value={title} onChange={e => setTitle(e.target.value)} className={input} />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">场景标签</span>
            <input value={sceneTag} onChange={e => setSceneTag(e.target.value)} className={input} />
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">结果</span>
            <select value={result} onChange={e => setResult(e.target.value)} className={input}>
              <option value="">未指定</option>
              <option value="win">赢单</option>
              <option value="loss">丢单</option>
              <option value="delay">搁置</option>
            </select>
          </label>
        </div>
        <div>
          <span className="mb-1 block text-sm font-medium text-foreground">客户画像</span>
          <div className="grid grid-cols-3 gap-3">
            <input value={profile.industry} onChange={e => setProfile({ ...profile, industry: e.target.value })} className={input} placeholder="行业" />
            <input value={profile.scale} onChange={e => setProfile({ ...profile, scale: e.target.value })} className={input} placeholder="规模" />
            <input value={profile.role} onChange={e => setProfile({ ...profile, role: e.target.value })} className={input} placeholder="关键角色" />
          </div>
        </div>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-foreground">客户原话（每行一句）</span>
          <textarea value={quotes} onChange={e => setQuotes(e.target.value)} rows={3} className={input} />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">情绪变化</span>
            <textarea value={emotionChange} onChange={e => setEmotionChange(e.target.value)} rows={2} className={input} />
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">推进节点</span>
            <textarea value={advanceNodes} onChange={e => setAdvanceNodes(e.target.value)} rows={2} className={input} />
          </label>
        </div>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-foreground">核心异议</span>
          <textarea value={objection} onChange={e => setObjection(e.target.value)} rows={2} className={input} />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-foreground">关键动作</span>
          <textarea value={keyActions} onChange={e => setKeyActions(e.target.value)} rows={3} className={input} />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-foreground">关键话术</span>
          <textarea value={scripts} onChange={e => setScripts(e.target.value)} rows={3} className={input} />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">可复用点</span>
            <textarea value={reusablePoints} onChange={e => setReusablePoints(e.target.value)} rows={3} className={input} />
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-foreground">失败点</span>
            <textarea value={failurePoints} onChange={e => setFailurePoints(e.target.value)} rows={3} className={input} />
          </label>
        </div>
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
          <button onClick={() => router.push(`/admin/cases/${id}`)} className="rounded-lg border border-border px-4 py-2 text-sm text-muted-foreground">取消</button>
          <button onClick={save} disabled={saving} className="rounded-lg bg-primary px-5 py-2 text-sm font-medium text-white disabled:opacity-50">
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}
