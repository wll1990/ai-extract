'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  getContentNeeds, getContentDashboard, getContentJobs,
  generateKnowledgeDraft, generateCaseDraft, pollContentJob,
  type ContentNeed, type ContentDashboard, type ContentJob,
} from '@/lib/api/content';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';
import EmptyState from '@/components/ui/EmptyState';

interface SectionState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

function initial<T>(): SectionState<T> {
  return { data: null, loading: true, error: null };
}

/**
 * 内容智能：真实失败信号 → AI 草稿 → 人工审核。
 *
 * 生成接口只返回 jobId，这里轮询任务状态；产出进入 AI 草稿审核台。
 */
export default function AdminContentIntelligencePage() {
  const router = useRouter();
  const [needs, setNeeds] = useState<SectionState<ContentNeed[]>>(initial());
  const [dashboard, setDashboard] = useState<SectionState<ContentDashboard>>(initial());
  const [jobs, setJobs] = useState<SectionState<ContentJob[]>>(initial());
  const [acting, setActing] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const loadNeeds = useCallback(async () => {
    setNeeds(prev => ({ ...prev, loading: true, error: null }));
    try {
      const n = await getContentNeeds(20);
      setNeeds({ data: n.needs || [], loading: false, error: null });
    } catch (e) {
      setNeeds({ data: null, loading: false, error: e instanceof Error ? e.message : '加载失败' });
    }
  }, []);

  const loadDashboard = useCallback(async () => {
    setDashboard(prev => ({ ...prev, loading: true, error: null }));
    try {
      setDashboard({ data: await getContentDashboard(), loading: false, error: null });
    } catch (e) {
      setDashboard({ data: null, loading: false, error: e instanceof Error ? e.message : '加载失败' });
    }
  }, []);

  const loadJobs = useCallback(async () => {
    setJobs(prev => ({ ...prev, loading: true, error: null }));
    try {
      setJobs({ data: await getContentJobs(20), loading: false, error: null });
    } catch (e) {
      setJobs({ data: null, loading: false, error: e instanceof Error ? e.message : '加载失败' });
    }
  }, []);

  useEffect(() => {
    loadNeeds();
    loadDashboard();
    loadJobs();
  }, [loadNeeds, loadDashboard, loadJobs]);

  const generate = async (kind: 'knowledge' | 'case', need: ContentNeed) => {
    const key = `${kind}:${need.sceneTag}`;
    setActing(key);
    try {
      const accepted = kind === 'knowledge'
        ? await generateKnowledgeDraft(need.sceneTag)
        : await generateCaseDraft(need.sceneTag);
      setToast('已提交生成任务，正在生成…');
      const job = await pollContentJob(accepted.jobId);
      if (job.status === 'failed') {
        setToast(job.errorMessage || '生成失败');
      } else {
        const ids = (job.result as { ids?: string[] } | null)?.ids ?? [];
        setToast(`生成完成：${job.producedCount} 条草稿${ids.length ? '，已进入 AI 草稿审核' : ''}`);
        router.push('/admin/content/review');
      }
      await Promise.all([loadJobs(), loadDashboard()]);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '生成失败');
    } finally {
      setActing(null);
    }
  };

  const adoption = dashboard.data?.adoption;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-5">
          <h1 className="mb-1 text-[28px] font-bold text-foreground">内容智能</h1>
          <p className="text-sm text-muted-foreground">
            用真实失败信号起草知识/案例/考题；AI 只出草稿，审核通过后才生效。
          </p>
        </div>

        {dashboard.loading && !dashboard.data ? (
          <div className="mb-6"><LoadingSpinner /></div>
        ) : dashboard.error ? (
          <div className="mb-6 rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-danger">
            看板加载失败：{dashboard.error}
            <button onClick={loadDashboard} className="ml-3 underline">重试</button>
          </div>
        ) : dashboard.data && (
          <>
            <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-4">
              {[
                ['近 30 天生成任务', dashboard.data.generationJobs, ''],
                ['产出草稿', dashboard.data.draftsProduced, ''],
                ['待审知识', dashboard.data.pendingKnowledge, 'text-amber-600'],
                ['待审案例', dashboard.data.pendingCases, 'text-amber-600'],
                ['开放知识缺口', dashboard.data.openGaps, 'text-red-600'],
                ['成功 / 失败', `${dashboard.data.generationDone}/${dashboard.data.generationFailed}`, ''],
              ].map(([label, value, cls]) => (
                <div key={String(label)} className="rounded-xl border border-border bg-surface-2 px-4 py-3">
                  <div className="text-xs text-muted-foreground">{label}</div>
                  <div className={`mt-1 text-2xl font-bold tabular-nums ${cls || 'text-foreground'}`}>{value}</div>
                </div>
              ))}
            </div>

            {adoption && (
              <div className="mb-5 rounded-xl border border-border bg-surface-2 p-4">
                <div className="mb-2 flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-foreground">AI 草稿采纳（近 {adoption.windowDays} 天）</h2>
                  <button onClick={() => router.push('/admin/content/review')}
                    className="text-xs text-primary hover:underline">去审核 →</button>
                </div>
                <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
                  {[
                    ['生成', adoption.created],
                    ['通过', adoption.approved],
                    ['丢弃', adoption.discarded],
                    ['待审', adoption.pending],
                    ['通过率', `${Math.round(adoption.approvalRate * 100)}%`],
                  ].map(([label, value]) => (
                    <div key={String(label)} className="rounded-lg bg-surface px-3 py-2">
                      <div className="text-[11px] text-muted-foreground">{label}</div>
                      <div className="mt-0.5 text-lg font-semibold tabular-nums text-foreground">{value}</div>
                    </div>
                  ))}
                </div>
                <p className="mt-2 text-xs text-muted-foreground">
                  平均审核时长 {adoption.avgReviewHours} 小时；通过率 = 通过 /（通过 + 丢弃），不含待审。
                </p>
              </div>
            )}
          </>
        )}

        <div className="mb-4 flex flex-wrap gap-2">
          <button onClick={() => router.push('/admin/content/review')}
            className="rounded-full bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90">
            审核 AI 草稿
          </button>
          <button onClick={() => router.push('/admin/knowledge?status=pending')}
            className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">
            知识待审列表
          </button>
          <button onClick={() => router.push('/admin/cases?status=pending')}
            className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">
            案例待审列表
          </button>
          <button onClick={() => { loadNeeds(); loadDashboard(); loadJobs(); }}
            className="rounded-full border border-border px-4 py-2 text-sm text-foreground hover:bg-primary-light">
            刷新
          </button>
        </div>

        <div className="mb-6">
          <h2 className="mb-2 text-sm font-semibold text-foreground">能力缺口（按场景）</h2>
          {needs.loading ? <LoadingSpinner /> : needs.error ? (
            <div className="rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-danger">
              需求加载失败：{needs.error}
              <button onClick={loadNeeds} className="ml-3 underline">重试</button>
            </div>
          ) : (needs.data?.length ?? 0) === 0 ? (
            <EmptyState icon="🌱" title="还没有可用信号"
              description="知识缺口、培训失败、评测错题会在这里汇聚" />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-border bg-surface-2">
              <table className="w-full min-w-[900px] text-sm">
                <thead className="bg-surface text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-4 py-3 font-medium">场景</th>
                    <th className="px-4 py-3 font-medium">缺口</th>
                    <th className="px-4 py-3 font-medium">实战失败</th>
                    <th className="px-4 py-3 font-medium">评测失败</th>
                    <th className="px-4 py-3 font-medium">样例</th>
                    <th className="px-4 py-3 font-medium">建议</th>
                    <th className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {(needs.data ?? []).map(n => (
                    <tr key={n.sceneTag} className="border-t border-border align-top">
                      <td className="px-4 py-3 font-medium text-foreground">{n.sceneTag}</td>
                      <td className="px-4 py-3 tabular-nums">{n.gapCount}</td>
                      <td className="px-4 py-3 tabular-nums">{n.trainingFailCount}</td>
                      <td className="px-4 py-3 tabular-nums">{n.evalFailCount}</td>
                      <td className="max-w-[260px] px-4 py-3 text-xs text-muted-foreground">
                        {n.samples.slice(0, 3).map((s, i) => <div key={i} className="line-clamp-1">· {s}</div>)}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{n.recommendation}</td>
                      <td className="px-4 py-3">
                        <div className="flex flex-col gap-1">
                          <button onClick={() => generate('knowledge', n)}
                            disabled={acting === `knowledge:${n.sceneTag}`}
                            className="rounded-full bg-primary px-3 py-1 text-xs text-white hover:opacity-90 disabled:opacity-50">
                            {acting === `knowledge:${n.sceneTag}` ? '生成中…' : '生成知识草稿'}
                          </button>
                          <button onClick={() => generate('case', n)}
                            disabled={acting === `case:${n.sceneTag}`}
                            className="rounded-full border border-border px-3 py-1 text-xs text-foreground hover:bg-primary-light disabled:opacity-50">
                            {acting === `case:${n.sceneTag}` ? '生成中…' : '生成案例草稿'}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div>
          <h2 className="mb-2 text-sm font-semibold text-foreground">最近生成记录</h2>
          {jobs.loading ? <LoadingSpinner /> : jobs.error ? (
            <div className="rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-danger">
              生成记录加载失败：{jobs.error}
              <button onClick={loadJobs} className="ml-3 underline">重试</button>
            </div>
          ) : (jobs.data?.length ?? 0) === 0 ? (
            <div className="rounded-xl border border-border bg-surface-2 p-6 text-sm text-muted-foreground">
              还没有生成记录
            </div>
          ) : (
            <div className="overflow-x-auto rounded-xl border border-border bg-surface-2">
              <table className="w-full min-w-[760px] text-sm">
                <thead className="bg-surface text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-4 py-3 font-medium">类型</th>
                    <th className="px-4 py-3 font-medium">来源</th>
                    <th className="px-4 py-3 font-medium">状态</th>
                    <th className="px-4 py-3 font-medium">产出</th>
                    <th className="px-4 py-3 font-medium">时间</th>
                    <th className="px-4 py-3 font-medium">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {(jobs.data ?? []).map(j => (
                    <tr key={j.id} className="border-t border-border">
                      <td className="px-4 py-2.5 text-foreground">{j.kind}</td>
                      <td className="px-4 py-2.5 text-xs text-muted-foreground">{j.sourceType}</td>
                      <td className="px-4 py-2.5">
                        <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${
                          j.status === 'done' ? 'border-green-200 bg-green-50 text-green-700'
                            : j.status === 'failed' ? 'border-red-200 bg-red-50 text-red-700'
                              : 'border-border text-muted-foreground'}`}>
                          {j.status}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 tabular-nums">{j.producedCount}</td>
                      <td className="px-4 py-2.5 text-xs text-muted-foreground">
                        {j.createdAt ? new Date(j.createdAt).toLocaleString() : '—'}
                      </td>
                      <td className="max-w-[260px] px-4 py-2.5 text-xs text-red-600">{j.errorMessage || ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
