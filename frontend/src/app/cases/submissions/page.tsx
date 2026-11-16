'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  MySubmission,
  SUBMISSION_STATUS,
  listMyCaseSubmissions,
  submitCase,
  withdrawCase,
} from '@/lib/api/submissions';
import { PageHelp } from '@/components/ui/PageHelp';

const RESULTS = [
  { value: '', label: '未指定' },
  { value: 'win', label: '赢单' },
  { value: 'loss', label: '丢单' },
  { value: 'delay', label: '搁置' },
];

export default function CaseSubmissionsPage() {
  const [rows, setRows] = useState<MySubmission[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    title: '', sceneTag: '', result: '', objection: '',
    keyActions: '', scripts: '', reusablePoints: '', failurePoints: '', source: '',
  });
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);

  const update = (key: keyof typeof form, value: string) =>
    setForm(prev => ({ ...prev, [key]: value }));

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listMyCaseSubmissions(page, 20)
      .then(res => {
        setRows(res.content || []);
        setTotalPages(Math.max(1, res.totalPages || 1));
      })
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!form.title.trim() || !form.objection.trim()) {
      setError('标题和核心异议不能为空');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await submitCase({
        title: form.title.trim(),
        sceneTag: form.sceneTag.trim() || undefined,
        result: form.result || undefined,
        objection: form.objection.trim(),
        keyActions: form.keyActions.trim() || undefined,
        scripts: form.scripts.trim() || undefined,
        reusablePoints: form.reusablePoints.trim() || undefined,
        failurePoints: form.failurePoints.trim() || undefined,
        source: form.source.trim() || undefined,
      });
      setForm({
        title: '', sceneTag: '', result: '', objection: '',
        keyActions: '', scripts: '', reusablePoints: '', failurePoints: '', source: '',
      });
      setPage(1);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交失败');
    } finally {
      setSaving(false);
    }
  };

  const withdraw = async (id: string) => {
    if (!window.confirm('撤回后将从待审核队列移除，确认撤回？')) return;
    try {
      await withdrawCase(id);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '撤回失败');
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <div className="mb-5 flex items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold text-foreground">提交案例</h1>
            <p className="mt-1 text-xs text-muted-foreground">
              把一次真实赢单/丢单的关键动作与原话提交给知识运营；审核通过后可用于培训和分身参考。
            </p>
          </div>
          <PageHelp
            title="提交案例"
            intro="一线销售可以提交案例草稿，管理员审核通过后生效；案例只在本企业可见。"
            steps={[
              { title: '写真实过程', desc: '客户背景、核心异议、关键动作、原话与结果，缺一项都会影响可用性。' },
              { title: '提交审核', desc: '提交后状态为「待审核」，管理员会补充或审批。' },
              { title: '撤回', desc: '未审核前可以撤回。' },
            ]}
            notes={['结果建议明确选赢单/丢单/搁置；没有结论的案例审批时会被拦下。']}
          />
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
            {error}
          </div>
        )}

        <section className="mb-6 rounded-xl border border-border bg-white p-5">
          <h2 className="mb-3 font-semibold text-foreground">新建提交</h2>
          <div className="space-y-3">
            <input className="w-full rounded-lg border border-border px-3 py-2 text-sm"
              placeholder="案例标题（客户/项目）" value={form.title}
              onChange={e => update('title', e.target.value)} />
            <div className="grid gap-3 md:grid-cols-3">
              <input className="rounded-lg border border-border px-3 py-2 text-sm"
                placeholder="场景标签（可选）" value={form.sceneTag}
                onChange={e => update('sceneTag', e.target.value)} />
              <select className="rounded-lg border border-border px-3 py-2 text-sm"
                value={form.result} onChange={e => update('result', e.target.value)}>
                {RESULTS.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
              </select>
              <input className="rounded-lg border border-border px-3 py-2 text-sm"
                placeholder="出处（可选）" value={form.source}
                onChange={e => update('source', e.target.value)} />
            </div>
            <textarea rows={3} className="w-full rounded-lg border border-border px-3 py-2 text-sm"
              placeholder="核心异议" value={form.objection}
              onChange={e => update('objection', e.target.value)} />
            <div className="grid gap-3 md:grid-cols-2">
              <textarea rows={3} className="rounded-lg border border-border px-3 py-2 text-sm"
                placeholder="关键动作" value={form.keyActions}
                onChange={e => update('keyActions', e.target.value)} />
              <textarea rows={3} className="rounded-lg border border-border px-3 py-2 text-sm"
                placeholder="关键话术 / 客户原话" value={form.scripts}
                onChange={e => update('scripts', e.target.value)} />
              <textarea rows={3} className="rounded-lg border border-border px-3 py-2 text-sm"
                placeholder="可复用要点" value={form.reusablePoints}
                onChange={e => update('reusablePoints', e.target.value)} />
              <textarea rows={3} className="rounded-lg border border-border px-3 py-2 text-sm"
                placeholder="失败点 / 如果重来会怎么做" value={form.failurePoints}
                onChange={e => update('failurePoints', e.target.value)} />
            </div>
            <div className="flex justify-end">
              <button type="button" onClick={submit} disabled={saving}
                className="rounded-full bg-primary px-5 py-2 text-sm text-white disabled:opacity-50">
                {saving ? '提交中…' : '提交审核'}
              </button>
            </div>
          </div>
        </section>

        <section className="rounded-xl border border-border bg-white p-5">
          <h2 className="mb-3 font-semibold text-foreground">我的提交</h2>
          {loading ? (
            <p className="py-6 text-center text-sm text-muted-foreground">加载中…</p>
          ) : rows.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">还没有提交记录。</p>
          ) : (
            <ul className="space-y-2">
              {rows.map(row => (
                <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border px-3 py-2">
                  <div className="min-w-0">
                    <p className="truncate text-sm text-foreground">{row.title}</p>
                    <p className="text-xs text-muted-foreground">
                      {row.sceneTag || '无场景'} · {row.createdAt?.slice(0, 10)}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="rounded bg-surface-2 px-2 py-0.5 text-xs text-muted-foreground">
                      {SUBMISSION_STATUS[row.status] || row.status}
                    </span>
                    {(row.status === 'pending' || row.status === 'draft') && (
                      <button type="button" onClick={() => withdraw(row.id)}
                        className="text-xs text-red-600 hover:underline">撤回</button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
          {totalPages > 1 && (
            <div className="mt-3 flex justify-center gap-2 text-sm">
              <button disabled={page <= 1} onClick={() => setPage(p => p - 1)} className="disabled:opacity-40">上一页</button>
              <span className="text-muted-foreground">{page}/{totalPages}</span>
              <button disabled={page >= totalPages} onClick={() => setPage(p => p + 1)} className="disabled:opacity-40">下一页</button>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
