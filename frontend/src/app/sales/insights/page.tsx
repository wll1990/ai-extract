'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { CommonIssues, createIssueKnowledge, getCommonIssues } from '@/lib/api/sales';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';

const PERIODS = [30, 90, 180];

export default function SalesInsightsPage() {
  const router = useRouter();
  const [days, setDays] = useState(90);
  const [data, setData] = useState<CommonIssues | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    getCommonIssues(days)
      .then(setData)
      .catch(e => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false));
  }, [days]);

  useEffect(() => { load(); }, [load]);

  const onCreateKnowledge = async (key: string) => {
    setCreating(key);
    setError(null);
    try {
      const res = await createIssueKnowledge(key, days);
      router.push(`/admin/knowledge/${res.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成培训主题失败');
    } finally {
      setCreating(null);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold text-foreground">团队共性问题</h1>
            <p className="mt-1 text-xs text-muted-foreground">
              把多份复盘里反复出现的不足和风险归并成问题类目，用来决定培训练什么、知识库补什么。
            </p>
          </div>
          <PageHelp
            title="团队共性问题"
            intro="按销售问题类别归并近期复盘的「不足」和「风险」，只统计已生成报告且属于本企业的复盘。"
            steps={[
              { title: '选周期', desc: '默认近 90 天；30 天看近期波动，180 天看长期共性' },
              { title: '看占比', desc: '每类显示出现在多少份复盘里，点开可回到具体复盘原话' },
              { title: '转培训', desc: '点「生成培训主题」会创建一条知识草稿，管理员确认后可用于分身检索和培训' },
            ]}
            notes={[
              '归类是确定性的：每一条不足/风险按关键词命中最多的一类归入，不会被重复计数。',
              '只统计 weaknesses / risks；报告生成失败的复盘不参与。',
              '只有企业管理员能看全公司；销售自己请用「我的销售看板」。',
            ]}
          />
        </div>

        <div className="mb-5 flex items-center gap-2">
          <span className="text-xs text-muted-foreground">统计周期</span>
          {PERIODS.map(p => (
            <button
              key={p}
              type="button"
              onClick={() => setDays(p)}
              className={`rounded-lg border px-3 py-1.5 text-xs ${
                days === p
                  ? 'border-primary bg-primary text-white'
                  : 'border-border bg-white text-foreground hover:bg-primary-light/40'
              }`}
            >
              近 {p} 天
            </button>
          ))}
          <button
            type="button"
            onClick={() => router.push('/sales/dashboard')}
            className="ml-auto text-xs text-primary hover:underline"
          >
            回到我的看板
          </button>
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
            {error}
            <button onClick={load} className="ml-3 underline">重试</button>
          </div>
        )}

        {loading && !data ? (
          <div className="py-16"><LoadingSpinner /></div>
        ) : !data || data.issues.length === 0 ? (
          <div className="rounded-xl border border-border bg-white p-10 text-center text-sm text-muted-foreground">
            近 {data?.periodDays ?? days} 天还没有足够的已完成报告，先完成几次复盘再看。
          </div>
        ) : (
          <>
            <p className="mb-3 text-xs text-muted-foreground">
              已分析 {data.reportCount} 份报告，归并出 {data.issues.length} 类问题。
            </p>
            <div className="grid gap-4 md:grid-cols-2">
              {data.issues.map(issue => (
                <section key={issue.key} className="rounded-xl border border-border bg-white p-5">
                  <div className="mb-2 flex items-start justify-between gap-2">
                    <h2 className="font-semibold text-foreground">{issue.label}</h2>
                    <span className="shrink-0 rounded bg-primary-light px-2 py-0.5 text-xs text-primary">
                      {issue.reportCount}/{data.reportCount} 份复盘
                    </span>
                  </div>
                  <p className="mb-3 text-xs text-muted-foreground">
                    共 {issue.pointCount} 条相关判断
                  </p>

                  {issue.samples.length > 0 && (
                    <ul className="mb-3 space-y-2">
                      {issue.samples.map((s, i) => (
                        <li key={i} className="rounded-lg bg-surface-2 px-3 py-2 text-xs text-foreground">
                          <p>{s.point}</p>
                          {s.suggestion && (
                            <p className="mt-1 text-muted-foreground">建议：{s.suggestion}</p>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}

                  {issue.reviews.length > 0 && (
                    <div className="mb-3 flex flex-wrap gap-1.5">
                      {issue.reviews.slice(0, 5).map(r => (
                        <button
                          key={r.sessionId}
                          type="button"
                          onClick={() => router.push(`/sales/reviews/${r.sessionId}`)}
                          className="rounded border border-border px-2 py-0.5 text-[11px] text-muted-foreground hover:bg-primary-light/40"
                        >
                          {r.customerName || '未命名客户'}
                        </button>
                      ))}
                    </div>
                  )}

                  <button
                    type="button"
                    disabled={creating === issue.key}
                    onClick={() => onCreateKnowledge(issue.key)}
                    className="rounded-lg border border-primary px-3 py-1.5 text-xs text-primary hover:bg-primary-light/40 disabled:opacity-50"
                  >
                    {creating === issue.key ? '生成中…' : '生成培训主题（知识草稿）'}
                  </button>
                </section>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
