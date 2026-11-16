'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  CoachSupplement,
  PreviousReview,
  ReviewDetail,
  ReviewMessage,
  ReviewReport,
  getCoachSupplement,
  getPreviousReview,
  getReport,
  getReportVersion,
  getReview,
  listMessages,
  listReports,
  precipitateReview,
  requestReport,
  updateReviewShare,
} from '@/lib/api/sales';
import { StatusBadge } from '@/components/sales/StatusBadge';
import { ReviewReportPanel } from '@/components/sales/ReviewReportPanel';
import { ReportVersionDiff } from '@/components/sales/ReportVersionDiff';
import { ReviewChat } from '@/components/sales/ReviewChat';
import { ConvertLeadModal } from '@/components/sales/ConvertLeadModal';
import { TaskDraftModal } from '@/components/sales/TaskDraftModal';
import { CoachSupplementPanel } from '@/components/sales/CoachSupplementPanel';
import { PreviousReviewPanel } from '@/components/sales/PreviousReviewPanel';
import { getUser } from '@/lib/storage';

export default function ReviewDetailPage() {
  const params = useParams<{ id: string }>();
  const reviewId = params.id;
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [report, setReport] = useState<ReviewReport | null>(null);
  const [reports, setReports] = useState<ReviewReport[]>([]);
  const [selectedReportId, setSelectedReportId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ReviewMessage[]>([]);
  const [supplement, setSupplement] = useState<CoachSupplement | null>(null);
  const [previous, setPrevious] = useState<PreviousReview | null>(null);
  const [selectedReportFull, setSelectedReportFull] = useState<ReviewReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [leadOpen, setLeadOpen] = useState(false);
  const [taskOpen, setTaskOpen] = useState(false);
  const [precipitating, setPrecipitating] = useState(false);
  const router = useRouter();

  const onPrecipitate = async (target: 'case' | 'knowledge') => {
    const label = target === 'case' ? '案例' : '知识';
    if (!window.confirm(`把本次复盘沉淀为${label}草稿？生成后可在${label}详情页继续编辑并审批。`)) return;
    setPrecipitating(true);
    setError(null);
    try {
      const res = await precipitateReview(reviewId, target);
      const role = (getUser() as { role?: string } | null)?.role;
      if (role === 'employee') {
        // 员工没有管理端权限：回到「我的提交」，而不是跳管理员详情页
        router.push(target === 'case' ? '/cases/submissions' : '/knowledge/submissions');
      } else {
        router.push(target === 'case' ? `/admin/cases/${res.id}` : `/admin/knowledge/${res.id}`);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '沉淀失败，请稍后重试');
    } finally {
      setPrecipitating(false);
    }
  };

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);
    try {
      // 核心详情必须成功；消息/教练补充/版本列表任一失败不应拖垮整页
      const d = await getReview(reviewId);
      const [msgsR, supR, listR, prevR] = await Promise.allSettled([
        listMessages(reviewId),
        getCoachSupplement(reviewId),
        listReports(reviewId),
        getPreviousReview(reviewId),
      ]);
      const list = listR.status === 'fulfilled' ? listR.value : [];
      setDetail(d);
      setReport(d.session.latestReport);
      setReports(list);
      setSelectedReportId(prev =>
        prev && list.some(r => r.id === prev) ? prev : list[0]?.id ?? null,
      );
      setMessages(msgsR.status === 'fulfilled' ? msgsR.value : []);
      setSupplement(supR.status === 'fulfilled' ? supR.value : null);
      setPrevious(prevR.status === 'fulfilled' ? prevR.value : null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      if (!silent) setLoading(false);
    }
  }, [reviewId]);

  useEffect(() => {
    void load();
  }, [load]);

  // 教练补充后销售端要立即看到，不能等用户手动刷新
  useEffect(() => {
    const onRefresh = () => { void load(true); };
    window.addEventListener('sales:refresh', onRefresh);
    return () => window.removeEventListener('sales:refresh', onRefresh);
  }, [load]);

  // 版本列表只含元数据，选中历史版本时按需拉完整 payload
  useEffect(() => {
    if (!selectedReportId) { setSelectedReportFull(null); return; }
    if (report?.id === selectedReportId && report.payload) {
      setSelectedReportFull(report);
      return;
    }
    const meta = reports.find(r => r.id === selectedReportId);
    if (meta?.payload) {
      setSelectedReportFull(meta);
      return;
    }
    let cancelled = false;
    getReportVersion(reviewId, selectedReportId)
      .then(full => { if (!cancelled) setSelectedReportFull(full); })
      .catch(() => { if (!cancelled) setSelectedReportFull(null); });
    return () => { cancelled = true; };
  }, [selectedReportId, reports, report, reviewId]);

  const reportStatus = report?.status;
  useEffect(() => {
    if (!reportStatus || !['queued', 'generating'].includes(reportStatus)) return;
    let failures = 0;
    const startedAt = Date.now();
    const MAX_POLL_MS = 10 * 60 * 1000;
    const timer = window.setInterval(async () => {
      if (Date.now() - startedAt > MAX_POLL_MS) {
        window.clearInterval(timer);
        setError('报告生成超时，请稍后重试或联系管理员');
        return;
      }
      try {
        const next = await getReport(reviewId);
        failures = 0;
        setReport(next);
        if (next.status === 'ready' || next.status === 'failed') {
          window.clearInterval(timer);
          if (next.status === 'ready') {
            setDetail(prev => {
              // converted/closed 不能被轮询改回 report_ready，否则页面状态回退
              if (!prev || !['active', 'report_ready'].includes(prev.session.status)) return prev;
              return { ...prev, session: { ...prev.session, status: 'report_ready' } };
            });
          }
          // 只刷新版本列表，不强制把用户从历史版本切走
          listReports(reviewId).then(setReports).catch(() => undefined);
        }
      } catch (e) {
        failures += 1;
        if (failures >= 3) {
          window.clearInterval(timer);
          setError(e instanceof Error ? e.message : '报告状态刷新失败，请手动重试');
        }
      }
    }, 2000);
    return () => window.clearInterval(timer);
  }, [reportStatus, reviewId]);

  const onGenerate = async () => {
    try {
      await requestReport(reviewId);
      setReport(await getReport(reviewId));
      const list = await listReports(reviewId);
      setReports(list);
      setSelectedReportId(list[0]?.id ?? null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成失败');
    }
  };

  const onToggleShare = async (share: boolean) => {
    try {
      await updateReviewShare(reviewId, share);
      setDetail(prev =>
        prev
          ? { ...prev, session: { ...prev.session, shareTranscriptWithCoach: share } }
          : prev,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '授权失败');
    }
  };

  if (loading) return <div className="p-6 text-sm text-gray-500">加载中…</div>;
  if (!detail) return <div className="p-6 text-sm text-red-600">{error || '复盘不存在'}</div>;

  const s = detail.session;
  const currentUserId = (getUser() as { id?: string } | null)?.id;
  const isOwner = Boolean(currentUserId && currentUserId === s.reviewerId);
  const latestReport = reports[0] ?? report;
  const selectedReport =
    (selectedReportFull && selectedReportFull.id === selectedReportId
      ? selectedReportFull
      : undefined)
    ?? reports.find(r => r.id === selectedReportId)
    ?? latestReport;
  const isLatest = !selectedReport || !latestReport || selectedReport.id === latestReport.id;
  const newerMeta = selectedReport
    ? reports.find(r => r.version === selectedReport.version + 1)
    : undefined;
  // 最新版本的全量 payload 已在 report 里；历史版本对比需要时由 selected 的 effect 覆盖
  const newerReport = newerMeta
    ? (newerMeta.id === report?.id && report.payload ? report : newerMeta)
    : undefined;
  const canConvert = latestReport?.status === 'ready' && !s.leadId;

  return (
    <div className="mx-auto max-w-6xl p-6">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">{s.customerName || '未命名客户'}</h1>
          <p className="mt-1 text-sm text-gray-500">
            {s.topic || '无主题'} · 教练 {s.coachName || '—'} · {s.transcriptChars} 字
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2" data-tour="review-actions">
          <StatusBadge status={s.status} />
          {isOwner && (
            <label className="flex items-center gap-1 text-xs text-gray-600">
              <input
                type="checkbox"
                checked={s.shareTranscriptWithCoach}
                onChange={e => onToggleShare(e.target.checked)}
              />
              允许教练看原文
            </label>
          )}
          {reports.length > 1 && (
            <select
              value={selectedReport?.id ?? ''}
              onChange={e => setSelectedReportId(e.target.value)}
              className="rounded border border-gray-300 px-2 py-2 text-sm"
            >
              {reports.map((r, i) => (
                <option key={r.id} value={r.id}>
                  v{r.version}
                  {i === 0 ? '（最新）' : ''}
                </option>
              ))}
            </select>
          )}
          {isLatest &&
            (!latestReport ||
              latestReport.status === 'failed' ||
              latestReport.status === 'ready') && (
              <button
                type="button"
                onClick={() => {
                  if (
                    latestReport?.status === 'ready' &&
                    !window.confirm('重新生成会新增一个版本，确认继续？')
                  ) {
                    return;
                  }
                  void onGenerate();
                }}
                className="rounded bg-blue-600 px-4 py-2 text-sm text-white"
              >
                {latestReport ? '重新生成报告' : '生成报告'}
              </button>
            )}
          {isLatest && canConvert && (
            <button
              type="button"
              onClick={() => setLeadOpen(true)}
              className="rounded border border-blue-300 px-3 py-2 text-sm text-blue-700"
            >
              转线索
            </button>
          )}
          {isLatest && latestReport?.status === 'ready' && (
            <button
              type="button"
              onClick={() => setTaskOpen(true)}
              className="rounded border border-gray-300 px-3 py-2 text-sm"
            >
              生成任务
            </button>
          )}
          {isLatest && latestReport?.status === 'ready' && (
            <>
              <button
                type="button"
                disabled={precipitating}
                onClick={() => onPrecipitate('case')}
                className="rounded border border-gray-300 px-3 py-2 text-sm disabled:opacity-50"
              >
                沉淀为案例
              </button>
              <button
                type="button"
                disabled={precipitating}
                onClick={() => onPrecipitate('knowledge')}
                className="rounded border border-gray-300 px-3 py-2 text-sm disabled:opacity-50"
              >
                沉淀为知识
              </button>
            </>
          )}
        </div>
      </div>

      {error && (
        <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      {latestReport && (latestReport.status === 'queued' || latestReport.status === 'generating') && (
        <ReportWaitBanner report={latestReport} />
      )}

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          {!isLatest && (
            <div className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
              历史版本，只读；转线索/生成任务请切回最新版。
            </div>
          )}
          {previous && (
            <PreviousReviewPanel
              previous={previous}
              currentSignals={latestReport?.payload?.customerSignals}
            />
          )}
          {selectedReport ? (
            <div data-tour="review-report">
              <ReviewReportPanel report={selectedReport} segments={detail.segments} />
            </div>
          ) : (
            <div className="rounded border border-gray-200 p-4 text-sm text-gray-500">
              还没有报告。点右上角「生成报告」。
            </div>
          )}
          {selectedReport && newerReport && !isLatest && (
            <ReportVersionDiff current={newerReport} previous={selectedReport} />
          )}
          {supplement && (
            <CoachSupplementPanel
              reviewId={reviewId}
              data={supplement}
              isOwner={isOwner}
              onChanged={async () => {
                try { setSupplement(await getCoachSupplement(reviewId)); }
                catch { /* 失败时保留原状态，不阻断操作反馈 */ }
              }}
            />
          )}
          <ReviewChat
            reviewId={reviewId}
            initial={messages}
          />
        </div>

        <aside className="space-y-4">
          <section className="rounded border border-gray-200 p-4">
            <h3 className="mb-2 text-sm font-medium text-gray-900">对话段落</h3>
            <div className="max-h-[60vh] space-y-2 overflow-auto">
              {detail.segments.map(seg => (
                <div key={seg.id} className="rounded bg-gray-50 p-2 text-xs">
                  <span className="mr-1 text-gray-400">
                    {seg.speaker === 'customer' ? '客户' : seg.speaker === 'sales' ? '销售' : '未知'}
                  </span>
                  <span className="whitespace-pre-wrap text-gray-800">{seg.text}</span>
                </div>
              ))}
            </div>
          </section>
        </aside>
      </div>

      {leadOpen && (
        <ConvertLeadModal
          reviewId={reviewId}
          draft={latestReport?.payload?.leadDraft}
          onClose={() => setLeadOpen(false)}
          onConverted={leadId => {
            setLeadOpen(false);
            setDetail(prev =>
              prev
                ? { ...prev, session: { ...prev.session, leadId, status: 'converted' } }
                : prev,
            );
          }}
        />
      )}
      {taskOpen && latestReport?.payload && (
        <TaskDraftModal
          reviewId={reviewId}
          steps={latestReport.payload.nextSteps}
          primaryAction={latestReport.payload.primaryAction}
          onClose={() => setTaskOpen(false)}
          onCreated={() => {
            setTaskOpen(false);
            void load(true);
          }}
        />
      )}
    </div>
  );
}

/** 报告等待条：显示排队/生成阶段和已等待时长，超过 3 分钟提示可先离开。 */
function ReportWaitBanner({ report }: { report: ReviewReport }) {
  const [, forceTick] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => forceTick(t => t + 1), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const since = report.startedAt || report.queuedAt;
  const elapsedSec = since ? Math.max(0, Math.floor((Date.now() - new Date(since).getTime()) / 1000)) : 0;
  const queued = report.status === 'queued';
  const longWait = elapsedSec > 180;

  return (
    <div className="mb-3 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
      {queued
        ? `排队中（已等待 ${elapsedSec} 秒），前面还有其他报告在生成`
        : `生成中（已 ${elapsedSec} 秒，通常 30–90 秒）`}
      {longWait && '：仍在生成，可以先离开，完成后报告会自动保存。'}
    </div>
  );
}
