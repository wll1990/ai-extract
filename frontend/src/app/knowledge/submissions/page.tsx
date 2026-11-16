'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  MySubmission,
  SUBMISSION_STATUS,
  listMyKnowledgeSubmissions,
  submitKnowledge,
  withdrawKnowledge,
} from '@/lib/api/submissions';
import { PageHelp } from '@/components/ui/PageHelp';

const CATEGORIES = [
  { value: 'methodology', label: '方法论' },
  { value: 'price', label: '价格' },
  { value: 'product', label: '产品' },
  { value: 'competitor', label: '竞品' },
  { value: 'policy', label: '政策' },
  { value: 'compliance', label: '合规' },
  { value: 'terminology', label: '术语' },
  { value: 'industry_intro', label: '行业介绍' },
];

export default function KnowledgeSubmissionsPage() {
  const [rows, setRows] = useState<MySubmission[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [title, setTitle] = useState('');
  const [category, setCategory] = useState('methodology');
  const [content, setContent] = useState('');
  const [source, setSource] = useState('');
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listMyKnowledgeSubmissions(page, 20)
      .then(res => {
        setRows(res.content || []);
        setTotalPages(Math.max(1, res.totalPages || 1));
      })
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false));
  }, [page]);

  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!title.trim() || !content.trim()) {
      setError('标题和正文不能为空');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await submitKnowledge({
        title: title.trim(),
        category,
        content: content.trim(),
        source: source.trim() || undefined,
      });
      setTitle('');
      setContent('');
      setSource('');
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
      await withdrawKnowledge(id);
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
            <h1 className="text-2xl font-bold text-foreground">提交知识</h1>
            <p className="mt-1 text-xs text-muted-foreground">
              把客户问到、但你找不到答案的事实/规则提交给知识运营；审核通过后才会被分身引用。
            </p>
          </div>
          <PageHelp
            title="提交知识"
            intro="一线销售可以提交知识草稿，管理员审核通过后生效；提交内容只在本企业可见。"
            steps={[
              { title: '写清楚事实', desc: '标题写具体问题或结论，正文写可核验的事实、数字、边界条件。' },
              { title: '提交审核', desc: '提交后状态为「待审核」，管理员会补充或审批。' },
              { title: '撤回', desc: '未审核前可以撤回，撤回后不再出现在审核队列。' },
            ]}
            notes={['只提交客户真实问到、能核验的内容；不要粘贴整份合同或产品手册。']}
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
            <input
              className="w-full rounded-lg border border-border px-3 py-2 text-sm"
              placeholder="标题：例如「客户问三年总拥有成本怎么算」"
              value={title}
              onChange={e => setTitle(e.target.value)}
            />
            <div className="grid gap-3 md:grid-cols-2">
              <select
                className="rounded-lg border border-border px-3 py-2 text-sm"
                value={category}
                onChange={e => setCategory(e.target.value)}
              >
                {CATEGORIES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
              <input
                className="rounded-lg border border-border px-3 py-2 text-sm"
                placeholder="出处（可选）：客户会话/内部文档"
                value={source}
                onChange={e => setSource(e.target.value)}
              />
            </div>
            <textarea
              rows={5}
              className="w-full rounded-lg border border-border px-3 py-2 text-sm"
              placeholder="正文：可独立陈述的事实、规则、数字与适用边界"
              value={content}
              onChange={e => setContent(e.target.value)}
            />
            <div className="flex justify-end">
              <button
                type="button"
                onClick={submit}
                disabled={saving}
                className="rounded-full bg-primary px-5 py-2 text-sm text-white disabled:opacity-50"
              >
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
                      {row.category || '未分类'} · {row.createdAt?.slice(0, 10)}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="rounded bg-surface-2 px-2 py-0.5 text-xs text-muted-foreground">
                      {SUBMISSION_STATUS[row.status] || row.status}
                    </span>
                    {(row.status === 'pending' || row.status === 'draft') && (
                      <button
                        type="button"
                        onClick={() => withdraw(row.id)}
                        className="text-xs text-red-600 hover:underline"
                      >
                        撤回
                      </button>
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
