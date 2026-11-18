'use client';
import { Pagination } from '@/components/ui/Pagination';

import React, { useCallback, useEffect, useState } from 'react';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import {
  listGrainCorrections, approveGrainCorrection, rejectGrainCorrection,
  type CorrectionSuggestion,
} from '@/lib/api/grain-corrections';

const STATUS_TABS: Array<{ value: string; label: string }> = [
  { value: 'pending', label: '待审核' },
  { value: 'approved', label: '已通过' },
  { value: 'rejected', label: '已拒绝' },
  { value: '', label: '全部' },
];

/** 颗粒纠错审核 —— 员工提交的建议在这里通过/拒绝（GR-06）。 */
export default function GrainCorrectionsPage() {
  const [status, setStatus] = useState('pending');
  const [page, setPage] = useState(1);
  const [rows, setRows] = useState<CorrectionSuggestion[]>([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [acting, setActing] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError('');
    listGrainCorrections({ status: status || undefined, page, size: 20 })
      .then(d => {
        setRows(d.content || []);
        setTotal(d.total || 0);
        setTotalPages(Math.max(1, d.totalPages || 1));
      })
      .catch(e => {
        setRows([]);
        setError(e instanceof Error ? e.message : '加载失败');
      })
      .finally(() => setLoading(false));
  }, [status, page]);

  useEffect(() => { load(); }, [load]);

  const act = async (id: string, approve: boolean) => {
    const note = window.prompt(approve ? '通过备注（可留空）' : '拒绝原因（建议填写）') ?? '';
    setActing(id);
    try {
      if (approve) await approveGrainCorrection(id, note.trim() || undefined);
      else await rejectGrainCorrection(id, note.trim() || undefined);
      load();
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(null);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="text-[26px] font-bold text-foreground">颗粒纠错建议</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          一线员工提交的经验纠错，通过后会覆盖颗粒并重新生成向量；拒绝不影响颗粒。
        </p>

        <div className="my-4 flex gap-2">
          {STATUS_TABS.map(t => (
            <button key={t.value || 'all'}
              onClick={() => { setStatus(t.value); setPage(1); }}
              className={`rounded-full px-4 py-1.5 text-sm ${status === t.value ? 'bg-primary text-white' : 'bg-primary-light text-muted-foreground hover:bg-border'}`}>
              {t.label}
            </button>
          ))}
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
            {error}
            <button onClick={load} className="ml-3 underline">重试</button>
          </div>
        )}

        {loading ? <LoadingSpinner /> : rows.length === 0 ? (
          <div className="rounded-xl border border-border bg-surface-2 px-4 py-12 text-center text-sm text-muted-foreground">
            {status === 'pending' ? '没有待审核的纠错建议' : '暂无记录'}
          </div>
        ) : (
          <div className="space-y-3">
            {rows.map(row => (
              <div key={row.id} className="rounded-xl border border-border bg-surface-2 p-5">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-foreground">
                      {row.skillName || '未知分身'} · {row.grainSceneDescription || row.grainSceneTag || '未命名颗粒'}
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      提交人 {row.suggestedByName || row.suggestedBy} · {row.createdAt ? new Date(row.createdAt).toLocaleString() : ''}
                    </p>
                  </div>
                  <span className={`rounded-full px-2.5 py-0.5 text-xs ${
                    row.status === 'pending' ? 'bg-warning-bg text-warning-text'
                      : row.status === 'approved' ? 'bg-success-bg text-success'
                        : 'bg-gray-100 text-gray-500'
                  }`}>
                    {row.status === 'pending' ? '待审核' : row.status === 'approved' ? '已通过' : '已拒绝'}
                  </span>
                </div>

                <div className="mt-3 rounded-lg bg-surface px-3 py-2 text-sm text-foreground">
                  <span className="text-xs text-muted-foreground">问题原因：</span>{row.reason}
                </div>

                {(row.sceneDescription || row.expertThought || row.standardScript || row.commonMistakes) && (
                  <div className="mt-2 space-y-1 text-xs text-muted-foreground">
                    {row.sceneDescription && <p>场景描述：{row.sceneDescription}</p>}
                    {row.expertThought && <p>专家思考：{row.expertThought}</p>}
                    {row.standardScript && <p>标准话术：{row.standardScript}</p>}
                    {row.commonMistakes && <p>常见错误：{row.commonMistakes}</p>}
                  </div>
                )}

                {row.status === 'pending' && (
                  <div className="mt-3 flex justify-end gap-2">
                    <button onClick={() => act(row.id, false)} disabled={acting === row.id}
                      className="rounded-lg border border-border px-4 py-1.5 text-sm text-danger hover:bg-danger-bg disabled:opacity-50">
                      拒绝
                    </button>
                    <button onClick={() => act(row.id, true)} disabled={acting === row.id}
                      className="rounded-lg bg-primary px-4 py-1.5 text-sm text-white hover:opacity-90 disabled:opacity-50">
                      通过并写入颗粒
                    </button>
                  </div>
                )}
                {row.status !== 'pending' && row.reviewNote && (
                  <p className="mt-2 text-xs text-muted-foreground">审核备注：{row.reviewNote}</p>
                )}
              </div>
            ))}
          </div>
        )}

        <Pagination page={page} totalPages={totalPages} totalElements={total} onPageChange={setPage} loading={loading} />
      </div>
    </div>
  );
}
