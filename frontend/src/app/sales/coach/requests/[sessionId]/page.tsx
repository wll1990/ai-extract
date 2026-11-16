'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { CoachWorkspace, addCoachNote, getCoachWorkspace } from '@/lib/api/sales';
import { ReviewReportPanel } from '@/components/sales/ReviewReportPanel';

export default function CoachWorkspacePage() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
  const [data, setData] = useState<CoachWorkspace | null>(null);
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await getCoachWorkspace(sessionId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [sessionId]);

  useEffect(() => {
    void load();
  }, [load]);

  const submit = async () => {
    if (!content.trim()) return;
    setSaving(true);
    setError(null);
    try {
      await addCoachNote(sessionId, content.trim());
      setContent('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交失败');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div className="p-6 text-sm text-gray-500">加载中…</div>;
  if (!data) return <div className="p-6 text-sm text-red-600">{error || '无权查看'}</div>;

  return (
    <div className="mx-auto max-w-5xl p-6">
      <h1 className="text-xl font-semibold text-gray-900">
        {data.customerName || '未命名客户'} · 教练补充
      </h1>
      <p className="mb-4 text-sm text-gray-500">
        发起人：{data.reviewerName || '—'} · {data.topic || '无主题'}
        {data.requestMessage && ` · 他的说明：${data.requestMessage}`}
      </p>
      {error && (
        <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          {data.report ? (
            <ReviewReportPanel report={data.report} segments={data.segments} />
          ) : (
            <div className="rounded border p-4 text-sm text-gray-500">AI 报告还没生成。</div>
          )}

          <section className="rounded border border-gray-200 p-4">
            <h3 className="mb-2 text-sm font-medium">我的补充</h3>
            {data.notes.length === 0 && <p className="text-sm text-gray-500">还没有补充。</p>}
            <ul className="mb-3 space-y-2">
              {data.notes.map(n => (
                <li key={n.id} className="rounded bg-gray-50 p-3 text-sm">
                  <div className="mb-1 text-xs text-gray-500">
                    {new Date(n.createdAt).toLocaleString()}
                  </div>
                  <p className="whitespace-pre-wrap">{n.content}</p>
                </li>
              ))}
            </ul>
            <textarea
              rows={4}
              value={content}
              maxLength={2000}
              onChange={e => setContent(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
              placeholder="补充你的判断：哪句是关键、下一步建议、AI 报告哪里不准…"
            />
            <div className="mt-2 flex justify-end">
              <button
                type="button"
                onClick={submit}
                disabled={saving || !content.trim()}
                className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
              >
                {saving ? '提交中…' : '提交补充'}
              </button>
            </div>
          </section>
        </div>

        <aside className="space-y-4">
          <section className="rounded border p-4">
            <h3 className="mb-2 text-sm font-medium">原文</h3>
            {!data.transcriptShared && (
              <p className="text-sm text-gray-500">发起人未授权查看完整原文。</p>
            )}
            {data.transcriptShared && (
              <div className="max-h-[60vh] space-y-2 overflow-auto">
                {data.segments.map(s => (
                  <div key={s.id} className="rounded bg-gray-50 p-2 text-xs">
                    <span className="mr-1 text-gray-400">
                      {s.speaker === 'customer' ? '客户' : s.speaker === 'sales' ? '销售' : '未知'}
                    </span>
                    <span className="whitespace-pre-wrap">{s.text}</span>
                  </div>
                ))}
              </div>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}
