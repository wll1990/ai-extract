'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import {
  listRuns, compareRuns, listItems, RUN_STATUS_LABELS, PURPOSE_LABELS, VERDICT_LABELS, VERDICT_CLS,
  type EvaluationRun, type EvaluationItem, type RunCompareResult,
} from '@/lib/api/evaluation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

export default function EvaluationComparePage() {
  const router = useRouter();
  const [runs, setRuns] = useState<EvaluationRun[]>([]);
  const [left, setLeft] = useState('');
  const [right, setRight] = useState('');
  const [result, setResult] = useState<RunCompareResult | null>(null);
  const [questions, setQuestions] = useState<Record<string, EvaluationItem>>({});
  const [loading, setLoading] = useState(true);
  const [comparing, setComparing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listRuns({ page: 1, size: 100 })
      .then(d => setRuns(d.content || []))
      .catch(() => setError('加载运行列表失败'))
      .finally(() => setLoading(false));
  }, []);

  const runLabel = (r: EvaluationRun) => [
    PURPOSE_LABELS[r.purpose] || r.purpose,
    `v${r.datasetVersion}`,
    RUN_STATUS_LABELS[r.status] || r.status,
    r.createdAt ? new Date(r.createdAt).toLocaleString() : r.id.slice(0, 8),
  ].join(' · ');

  const onCompare = async () => {
    setError(null);
    setResult(null);
    if (!left || !right) { setError('请选择两个运行'); return; }
    if (left === right) { setError('不能与自身对比'); return; }
    setComparing(true);
    try {
      const data = await compareRuns(left, right);
      setResult(data);
      // 题目文本按右侧（或左侧）批次对应的题集版本加载，保证 diff 里能显示题干
      const ref = runs.find(r => r.id === right);
      if (ref) {
        const p = await listItems(ref.datasetId, ref.datasetVersion, 1, 200);
        const map: Record<string, EvaluationItem> = {};
        (p.content || []).forEach(i => { map[i.id] = i; });
        setQuestions(map);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '对比失败');
    } finally {
      setComparing(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <button onClick={() => router.push('/admin/evaluations/runs')}
          className="mb-3 text-sm text-muted-foreground hover:underline">← 返回运行列表</button>
        <h1 className="mb-1 text-[26px] font-bold text-foreground">版本对比</h1>
        <p className="mb-5 text-sm text-muted-foreground">
          同题集同版本可逐题比较；版本不同时只比较公共题目，不直接相减总分。
        </p>

        {loading ? <LoadingSpinner /> : (
          <>
            <div className="mb-4 flex flex-wrap items-end gap-3">
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">左侧运行</label>
                <select value={left} onChange={e => setLeft(e.target.value)}
                  className="w-72 rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                  <option value="">请选择</option>
                  {runs.map(r => <option key={r.id} value={r.id}>{runLabel(r)}</option>)}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">右侧运行</label>
                <select value={right} onChange={e => setRight(e.target.value)}
                  className="w-72 rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                  <option value="">请选择</option>
                  {runs.map(r => <option key={r.id} value={r.id}>{runLabel(r)}</option>)}
                </select>
              </div>
              <button onClick={onCompare} disabled={comparing}
                className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
                {comparing ? '对比中…' : '开始对比'}
              </button>
            </div>

            {error && (
              <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">{error}</div>
            )}

            {result && (
              <>
                <div className={`mb-4 rounded-xl px-4 py-3 text-sm ${result.sameCaliber ? 'bg-success-bg text-success' : 'bg-warning-bg text-warning-text'}`}>
                  {result.caliberNote}
                  {result.nonCommonItems > 0 && <span className="ml-2">（非公共题目 {result.nonCommonItems} 题未参与对比）</span>}
                </div>

                <div className="rounded-xl border border-border bg-surface-2 overflow-hidden">
                  <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border">
                    <span className="col-span-5">题目</span>
                    <span className="col-span-3">左 → 右 结论</span>
                    <span className="col-span-2 text-right">左最终分</span>
                    <span className="col-span-2 text-right">右最终分</span>
                  </div>
                  {result.diffs.length === 0 ? (
                    <div className="px-5 py-10 text-center text-sm text-muted-foreground">两个批次没有公共题目</div>
                  ) : result.diffs.map(d => (
                    <div key={d.itemId} className={`grid grid-cols-12 gap-2 px-5 py-3 border-b border-border last:border-0 items-center ${d.verdictChanged ? 'bg-warning-bg/40' : ''}`}>
                      <span className="col-span-5 truncate text-sm text-foreground">
                        {questions[d.itemId]?.question || d.itemId.slice(0, 8)}
                      </span>
                      <span className="col-span-3 text-xs">
                        <span className={`inline-flex px-2 py-0.5 rounded-full ${VERDICT_CLS[d.leftVerdict || ''] || 'bg-gray-100 text-gray-600'}`}>
                          {VERDICT_LABELS[d.leftVerdict || ''] || d.leftVerdict || '—'}
                        </span>
                        <span className="mx-1 text-muted-foreground">→</span>
                        <span className={`inline-flex px-2 py-0.5 rounded-full ${VERDICT_CLS[d.rightVerdict || ''] || 'bg-gray-100 text-gray-600'}`}>
                          {VERDICT_LABELS[d.rightVerdict || ''] || d.rightVerdict || '—'}
                        </span>
                        {d.verdictChanged && <span className="ml-1 text-xs text-warning-text">变化</span>}
                      </span>
                      <span className="col-span-2 text-right text-sm text-muted-foreground">
                        {d.leftScore ?? '—'}
                        {d.leftScoreSource === 'manual' && <span className="ml-1 text-[10px] text-primary">人工</span>}
                      </span>
                      <span className="col-span-2 text-right text-sm text-foreground">
                        {d.rightScore ?? '—'}
                        {d.rightScoreSource === 'manual' && <span className="ml-1 text-[10px] text-primary">人工</span>}
                      </span>
                    </div>
                  ))}
                </div>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
}
