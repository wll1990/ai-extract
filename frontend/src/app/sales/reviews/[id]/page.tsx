'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
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
import { ActionLegend } from '@/components/sales/ActionLegend';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHelp } from '@/components/ui/PageHelp';
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
  const [taskSource, setTaskSource] = useState<ReviewReport | null>(null);
  const [taskLoading, setTaskLoading] = useState(false);
  const [precipitating, setPrecipitating] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [confirmState, setConfirmState] = useState<
    { kind: 'regenerate' } | { kind: 'precipitate'; target: 'case' | 'knowledge' } | null
  >(null);
  const router = useRouter();

  const onPrecipitate = async (target: 'case' | 'knowledge') => {
    const label = target === 'case' ? '案例' : '知识';
    setPrecipitating(true);
    setError(null);
    setConfirmState(null);
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
    setGenerating(true);
    setError(null);
    setConfirmState(null);
    try {
      await requestReport(reviewId);
      setReport(await getReport(reviewId));
      const list = await listReports(reviewId);
      setReports(list);
      setSelectedReportId(list[0]?.id ?? null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成失败');
    } finally {
      setGenerating(false);
    }
  };

  const requestGenerate = () => {
    const current = reports[0] ?? report;
    if (current?.status === 'ready') {
      setConfirmState({ kind: 'regenerate' });
      return;
    }
    void onGenerate();
  };

  /**
   * 版本列表只回元数据（payload=null），直接开弹窗会因为没有 nextSteps 而永远不渲染。
   * 优先复用详情/版本缓存里的全量报告，缺失时按版本 ID 补拉一次。
   */
  const openTaskModal = async () => {
    const meta = reports[0] ?? report;
    if (!meta) {
      setError('报告不存在，请刷新后重试');
      return;
    }
    const cached = [meta, report, selectedReportFull].find(
      r => r?.id === meta.id && r.payload,
    );
    if (cached?.payload) {
      if (cached.payload.nextSteps?.length) setTaskSource(cached);
      else setError('这份报告没有可用的「下一步」动作，无法生成跟进任务');
      return;
    }
    setTaskLoading(true);
    setError(null);
    try {
      const full = await getReportVersion(reviewId, meta.id);
      if (full.payload?.nextSteps?.length) setTaskSource(full);
      else setError('这份报告没有可用的「下一步」动作，无法生成跟进任务');
    } catch (e) {
      setError(e instanceof Error ? e.message : '报告内容加载失败，请稍后重试');
    } finally {
      setTaskLoading(false);
    }
  };

  const requestPrecipitate = (target: 'case' | 'knowledge') => {
    if (precipitating) return;
    setConfirmState({ kind: 'precipitate', target });
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

  if (loading) return <div className="p-6 text-sm text-muted-foreground">加载中…</div>;
  if (!detail) return <div className="p-6 text-sm text-danger">{error || '复盘不存在'}</div>;

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
  const canCreateTasks = isLatest && latestReport?.status === 'ready';
  const statusHint = (() => {
    if (!latestReport) return '下一步：点「生成报告」，分身会基于这段对话产出复盘。';
    if (latestReport.status === 'queued' || latestReport.status === 'generating') {
      return '报告生成中，通常 30–90 秒；可以先离开，完成后会自动保存。';
    }
    if (latestReport.status === 'failed') {
      return '报告生成失败，可点「重新生成报告」再试；连续失败请联系管理员检查模型 Key。';
    }
    if (s.status === 'converted') {
      return '已转线索：去「线索」继续记录跟进，报告仍可随时回看。';
    }
    if (!s.leadId) {
      return '报告已就绪：先读结论，再决定「转线索」或把下一步「生成跟进任务」。';
    }
    return '报告已就绪：可以继续「生成跟进任务」，或把这次经验沉淀成知识/案例草稿。';
  })();

  return (
    <div className="mx-auto max-w-[1200px] px-6 py-8">
      <Link href="/sales/reviews" className="text-xs text-muted-foreground hover:text-primary">
        ← 返回销售复盘
      </Link>

      <section className="mt-2 rounded-xl border border-border bg-surface-2 p-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-[26px] font-bold leading-tight text-foreground">
                {s.customerName || '未命名客户'}
              </h1>
              <StatusBadge status={s.status} />
              {latestReport && <StatusBadge status={latestReport.status} />}
            </div>
            <p className="mt-1.5 text-sm text-muted-foreground">
              {s.topic || '无主题'} · 教练 {s.coachName || '—'} · {s.transcriptChars} 字
              {s.createdAt ? ` · ${new Date(s.createdAt).toLocaleString()}` : ''}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <PageHelp
              title="销售复盘详情使用说明"
              intro="这份页面把一次客户对话变成可执行动作：先生成报告，再决定转线索、建跟进任务，或沉淀成知识/案例草稿。"
              steps={[
                { title: '读报告', desc: '报告包含摘要、亮点、问题、客户信号和下一步动作；可切换历史版本对比。' },
                { title: '转线索', desc: '只有最新版报告已生成且尚未转过线索时才显示。' },
                { title: '建跟进任务', desc: '把报告的「下一步」变成待办，最多选 3 条，可改标题、渠道和截止时间。' },
                { title: '沉淀经验', desc: '沉淀为案例/知识会生成草稿，管理员审核通过后才生效。' },
              ]}
              actions={[
                { label: '生成报告 / 重新生成报告', desc: '调用所选教练分身复盘这段对话。已有报告时重新生成会新增版本，不覆盖旧版本。', tone: 'primary' },
                { label: '转线索', desc: '把客户和需求存进线索池，同一份复盘只能转一次；转完状态变为「已转线索」。' },
                { label: '生成跟进任务', desc: '把报告里的下一步动作变成跟进任务，可逐条改标题、渠道、截止时间和话术。' },
                { label: '沉淀为案例 / 沉淀为知识', desc: '把本次复盘整理成案例或知识草稿，进入对应库审核；不会直接生效。' },
                { label: '允许教练看原文', desc: '勾选后教练能在教练台账查看完整对话；不勾选只看到摘要，之后可随时改。' },
              ]}
              notes={[
                '历史版本只读：切换回最新版才能转线索、生成跟进任务或沉淀。',
                '报告生成失败不会扣掉已有版本，可重试；连续失败请检查模型 Key。',
              ]}
            />
            {reports.length > 1 && (
              <select
                value={selectedReport?.id ?? ''}
                onChange={e => setSelectedReportId(e.target.value)}
                className="h-9 rounded-[10px] border border-border-strong bg-surface px-2 text-sm text-foreground"
                title="切换报告版本；历史版本只读"
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
                <Button
                  variant="primary"
                  onClick={requestGenerate}
                  disabled={generating}
                  title={latestReport ? '重新生成会新增一个版本，旧版本保留' : '让分身基于这段对话生成复盘报告'}
                >
                  {generating
                    ? '提交中…'
                    : latestReport ? '重新生成报告' : '生成报告'}
                </Button>
              )}
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-border pt-4" data-tour="review-actions">
          {canConvert && (
            <Button
              variant="secondary"
              onClick={() => setLeadOpen(true)}
              title="把客户和需求存进线索池；同一份复盘只能转一次"
            >
              转线索
            </Button>
          )}
          {canCreateTasks && (
            <Button
              variant="neutral"
              onClick={() => void openTaskModal()}
              disabled={taskLoading}
              title="把报告的「下一步」变成跟进任务，最多选 3 条"
            >
              {taskLoading ? '加载报告…' : '生成跟进任务'}
            </Button>
          )}
          {canCreateTasks && (
            <>
              <Button
                variant="neutral"
                disabled={precipitating}
                onClick={() => requestPrecipitate('case')}
                title="生成案例草稿，需管理员在案例库审核后才生效"
              >
                沉淀为案例
              </Button>
              <Button
                variant="neutral"
                disabled={precipitating}
                onClick={() => requestPrecipitate('knowledge')}
                title="生成知识草稿，需管理员在知识库审核后才生效"
              >
                沉淀为知识
              </Button>
            </>
          )}
          {isOwner && (
            <label className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground">
              <input
                type="checkbox"
                checked={s.shareTranscriptWithCoach}
                onChange={e => onToggleShare(e.target.checked)}
              />
              允许教练看原文
              <span className="text-muted-foreground-2">（不勾选只看摘要）</span>
            </label>
          )}
        </div>
      </section>

      <div className="mt-3 rounded-xl border border-primary/30 bg-primary-light/40 px-4 py-2.5 text-sm text-primary">
        {statusHint}
      </div>

      <ActionLegend
        className="mt-3"
        items={[
          { label: '生成报告 / 重新生成报告', desc: '让所选分身复盘这段对话；重新生成会新增版本，旧版本仍可查看。', tone: 'primary' },
          { label: '转线索', desc: '把客户与需求存进线索池，之后在线索页记录跟进；一份复盘只能转一次。' },
          { label: '生成跟进任务', desc: '把报告里的「下一步」变成待办，最多选 3 条，可改标题、渠道和截止时间。' },
          { label: '沉淀为案例 / 知识', desc: '生成对应库的草稿，管理员审核通过后才生效，不会直接进入分身检索。' },
          { label: '允许教练看原文', desc: '控制教练台账能看到摘要还是完整对话，随时可改。' },
        ]}
      />

      {error && (
        <div className="mt-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
          {error}
        </div>
      )}

      {latestReport && (latestReport.status === 'queued' || latestReport.status === 'generating') && (
        <ReportWaitBanner report={latestReport} />
      )}

      <div className="mt-4 grid grid-cols-1 gap-5 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          {!isLatest && (
            <div className="rounded-xl border border-warning-text/30 bg-warning-bg px-4 py-2.5 text-sm text-warning-text">
              历史版本只读；转线索、生成跟进任务请切回最新版。
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
            <div className="rounded-xl border border-border bg-surface-2 p-5 text-sm text-muted-foreground">
              还没有报告。点右上角「生成报告」，分身会基于这段对话输出复盘。
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
          <section className="rounded-xl border border-border bg-surface-2 p-4">
            <div className="mb-2 flex items-center justify-between">
              <h3 className="text-sm font-semibold text-foreground">对话段落</h3>
              <span className="text-[11px] text-muted-foreground">{detail.segments.length} 段</span>
            </div>
            <div className="max-h-[60vh] space-y-2 overflow-auto">
              {detail.segments.map(seg => (
                <div key={seg.id} className="rounded-lg bg-surface p-2 text-xs">
                  <span className={`mr-1 font-medium ${
                    seg.speaker === 'customer' ? 'text-warning-text' : 'text-primary'
                  }`}>
                    {seg.speaker === 'customer' ? '客户' : seg.speaker === 'sales' ? '销售' : '未知'}
                  </span>
                  <span className="whitespace-pre-wrap text-foreground">{seg.text}</span>
                </div>
              ))}
            </div>
          </section>
        </aside>
      </div>

      <ConfirmDialog
        open={confirmState !== null}
        title={confirmState?.kind === 'regenerate' ? '重新生成报告？' : '沉淀为草稿？'}
        confirmLabel={confirmState?.kind === 'regenerate' ? '重新生成' : '生成草稿'}
        busy={generating || precipitating}
        onCancel={() => setConfirmState(null)}
        onConfirm={() => {
          if (confirmState?.kind === 'regenerate') void onGenerate();
          else if (confirmState?.kind === 'precipitate') void onPrecipitate(confirmState.target);
        }}
      >
        {confirmState?.kind === 'regenerate' ? (
          <>
            <p>重新生成会新增一个报告版本，旧版本仍保留在版本下拉里。</p>
            <p>生成需要调用模型，通常 30–90 秒；重复点击会重复消耗额度。</p>
          </>
        ) : (
          <>
            <p>
              把本次复盘整理成
              {confirmState?.kind === 'precipitate' && confirmState.target === 'case' ? '案例' : '知识'}
              草稿。
            </p>
            <p>生成后可在对应库继续编辑；管理员审核通过后才会生效，不会直接进入分身检索。</p>
          </>
        )}
      </ConfirmDialog>

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
      {taskSource?.payload && (
        <TaskDraftModal
          reviewId={reviewId}
          steps={taskSource.payload.nextSteps ?? []}
          primaryAction={taskSource.payload.primaryAction}
          onClose={() => setTaskSource(null)}
          onCreated={() => {
            setTaskSource(null);
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
    <div className="mt-3 rounded-xl border border-warning-text/30 bg-warning-bg px-4 py-2.5 text-sm text-warning-text">
      {queued
        ? `排队中（已等待 ${elapsedSec} 秒），前面还有其他报告在生成`
        : `生成中（已 ${elapsedSec} 秒，通常 30–90 秒）`}
      {longWait && '：仍在生成，可以先离开，完成后报告会自动保存。'}
    </div>
  );
}
