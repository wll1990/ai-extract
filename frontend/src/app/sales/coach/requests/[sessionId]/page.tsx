'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { CoachWorkspace, addCoachNote, getCoachWorkspace } from '@/lib/api/sales';
import { ReviewReportPanel } from '@/components/sales/ReviewReportPanel';
import { Button } from '@/components/ui/Button';
import { PageHelp } from '@/components/ui/PageHelp';

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

  if (loading) return <div className="p-6 text-sm text-muted-foreground">加载中…</div>;
  if (!data) return <div className="p-6 text-sm text-danger">{error || '无权查看'}</div>;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1200px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[26px] font-bold leading-tight text-foreground">
              {data.customerName || '未命名客户'} · 教练补充
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              发起人：{data.reviewerName || '—'} · {data.topic || '无主题'}
              {data.requestMessage && ` · 他的说明：${data.requestMessage}`}
            </p>
          </div>
          <PageHelp
            title="教练补充使用说明"
            intro="同事请你在这份复盘上补充专业判断：哪句是关键、下一步怎么做、AI 报告哪里不准。"
            steps={[
              { title: '看报告', desc: '先读 AI 报告和原文（若发起人授权），定位需要补充的判断。' },
              { title: '写补充', desc: '在「我的补充」写你的结论和建议；提交后会通知发起人。' },
              { title: '授权原文', desc: '没看到原文说明发起人未授权；可请对方在复盘详情勾选后再来看。' },
            ]}
            actions={[
              { label: '提交补充', desc: '把补充追加到这条复盘，发起人会收到通知；不会修改 AI 报告原文。', tone: 'primary' },
              { label: '原文', desc: '只有发起人授权时才显示完整对话；未授权只显示报告和摘要。' },
            ]}
            notes={['补充一经提交会留档，不能撤回；如写错请再提交一条更正说明。']}
          />
        </div>

        {error && (
          <div className="mb-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

        <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
          <div className="space-y-4 lg:col-span-2">
            {data.report ? (
              <ReviewReportPanel report={data.report} segments={data.segments} />
            ) : (
              <div className="rounded-xl border border-border bg-surface-2 p-5 text-sm text-muted-foreground">
                AI 报告还没生成。可以先根据原文补充你的判断，报告生成后发起人也能看到。
              </div>
            )}

            <section className="rounded-xl border border-border bg-surface-2 p-4">
              <h3 className="mb-2 text-sm font-semibold text-foreground">我的补充</h3>
              {data.notes.length === 0 && <p className="text-sm text-muted-foreground">还没有补充。</p>}
              <ul className="mb-3 space-y-2">
                {data.notes.map(n => (
                  <li key={n.id} className="rounded-lg bg-surface p-3 text-sm">
                    <div className="mb-1 text-xs text-muted-foreground">
                      {new Date(n.createdAt).toLocaleString()}
                    </div>
                    <p className="whitespace-pre-wrap text-foreground">{n.content}</p>
                  </li>
                ))}
              </ul>
              <textarea
                rows={4}
                value={content}
                maxLength={2000}
                onChange={e => setContent(e.target.value)}
                className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-foreground"
                placeholder="补充你的判断：哪句是关键、下一步建议、AI 报告哪里不准…"
              />
              <div className="mt-2 flex items-center justify-between">
                <span className="text-[11px] text-muted-foreground">提交后通知发起人，不能撤回；写错可再补一条更正</span>
                <Button variant="primary" onClick={submit} disabled={saving || !content.trim()}>
                  {saving ? '提交中…' : '提交补充'}
                </Button>
              </div>
            </section>
          </div>

          <aside className="space-y-4">
            <section className="rounded-xl border border-border bg-surface-2 p-4">
              <h3 className="mb-2 text-sm font-semibold text-foreground">原文</h3>
              {!data.transcriptShared && (
                <p className="text-sm text-muted-foreground">发起人未授权查看完整原文。</p>
              )}
              {data.transcriptShared && (
                <div className="max-h-[60vh] space-y-2 overflow-auto">
                  {data.segments.map(s => (
                    <div key={s.id} className="rounded-lg bg-surface p-2 text-xs">
                      <span className={`mr-1 font-medium ${
                        s.speaker === 'customer' ? 'text-warning-text' : 'text-primary'
                      }`}>
                        {s.speaker === 'customer' ? '客户' : s.speaker === 'sales' ? '销售' : '未知'}
                      </span>
                      <span className="whitespace-pre-wrap text-foreground">{s.text}</span>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </aside>
        </div>
      </div>
    </div>
  );
}
