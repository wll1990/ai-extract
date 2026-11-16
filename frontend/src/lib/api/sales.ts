/**
 * 销售复盘 → 线索 → 跟进任务 API。
 *
 * 复盘追问走 SSE；URL 必须拼 API_BASE（connectSse 不会自动加前缀）。
 */

import { apiClient, API_BASE } from './client';
import { connectSse } from '@/lib/sse';
import type { PageResult } from './training';

export type ReviewStatus = 'active' | 'report_ready' | 'converted' | 'closed';

/** 与后端 app.sales.review.max-transcript-chars 默认值保持一致 */
export const MAX_TRANSCRIPT_CHARS = 30000;
export type ReportStatus = 'queued' | 'generating' | 'ready' | 'failed';

export interface ReviewPoint {
  point: string;
  evidence: Array<{ segmentId: string; quote?: string }>;
  confidence: 'high' | 'medium' | 'low';
  suggestion?: string;
}

export interface CustomerSignal {
  field: string;
  value: string | null;
  sourceType: 'customer_words' | 'sales_paraphrase' | 'ai_inference' | 'unknown';
  evidence: Array<{ segmentId: string; quote?: string }>;
  confidence: 'high' | 'medium' | 'low';
}

export interface NextStep {
  title: string;
  channel?: string;
  dueInDays?: number;
  content?: string;
  evidence?: Array<{ segmentId: string; quote?: string }>;
}

/** 下次沟通唯一主行动；旧报告没有 */
export interface PrimaryAction extends NextStep {
  channel?: string;
  dueInDays?: number;
  content?: string;
}

/** 本次没拿到、下次必须问的关键信息 */
export interface InfoGap {
  field: string;
  why?: string | null;
  question: string;
}

export interface ReviewPayload {
  summary: string;
  /** 一句话结论的原文引用；旧报告可能没有 */
  summaryEvidence?: Array<{ segmentId: string; quote?: string }>;
  strengths: ReviewPoint[];
  weaknesses: ReviewPoint[];
  risks: ReviewPoint[];
  customerSignals: CustomerSignal[];
  /** 下次沟通唯一主行动；旧报告可能没有 */
  primaryAction?: PrimaryAction | null;
  /** 本次缺失的关键信息；旧报告可能没有 */
  infoGaps?: InfoGap[];
  nextSteps: NextStep[];
  leadDraft?: { name?: string; contactName?: string; contactPhone?: string; needSummary?: string };
}

export interface ReviewReport {
  id: string;
  version: number;
  status: ReportStatus;
  summary: string | null;
  payload: ReviewPayload | null;
  errorMessage: string | null;
  modelName?: string | null;
  /** 入队时间，用于前端展示已等待时长 */
  queuedAt?: string | null;
  /** Worker 开始生成时间 */
  startedAt?: string | null;
  generatedAt: string | null;
}

export interface ReviewSession {
  id: string;
  reviewerId: string;
  reviewerName?: string | null;
  coachSkillId: string;
  coachName: string | null;
  coachTitle: string | null;
  customerName: string | null;
  topic: string | null;
  status: ReviewStatus;
  transcriptChars: number;
  shareTranscriptWithCoach: boolean;
  leadId: string | null;
  taskCount?: number | null;
  createdAt: string;
  latestReport: ReviewReport | null;
}

export interface ReviewMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

export interface ReviewSegment {
  id: string;
  seq: number;
  speaker: 'sales' | 'customer' | 'unknown';
  text: string;
}

export interface ReviewDetail {
  session: ReviewSession;
  segments: ReviewSegment[];
  lead: { id: string; name: string; status: string } | null;
}

export interface CoachOption {
  id: string;
  name: string;
  subtitle: string | null;
  status: string;
  /** 活跃经验颗粒数 */
  grainCount?: number;
  /** 覆盖场景数 */
  sceneCount?: number;
  updatedAt?: string | null;
}

export function searchCoaches(params: {
  keyword?: string;
  page?: number;
  size?: number;
} = {}): Promise<PageResult<CoachOption>> {
  const q = new URLSearchParams();
  if (params.keyword) q.set('keyword', params.keyword);
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  return apiClient<PageResult<CoachOption>>(`/sales/reviews/coaches?${q.toString()}`);
}

/** 上传复盘文件（txt/docx/pdf）→ 解析文本，仅预览用，不创建复盘 */
export function parseReviewFile(file: File): Promise<{ text: string; length: number }> {
  const fd = new FormData();
  fd.append('file', file);
  return apiClient('/sales/reviews/parse-file', { method: 'POST', body: fd });
}

export function createReview(req: {
  coachSkillId: string;
  transcript: string;
  customerName?: string;
  topic?: string;
  shareTranscriptWithCoach?: boolean;
  /** 关联已有客户（线索），同一客户多次复盘才能对比 */
  leadId?: string;
}): Promise<ReviewDetail> {
  return apiClient('/sales/reviews', { method: 'POST', body: JSON.stringify(req) });
}

/** 复盘沉淀为知识/案例草稿，返回新草稿的 type + id */
export function precipitateReview(
  id: string,
  target: 'case' | 'knowledge',
): Promise<{ type: 'case' | 'knowledge'; id: string }> {
  return apiClient(`/sales/reviews/${id}/precipitate`, {
    method: 'POST',
    body: JSON.stringify({ target }),
  });
}

export function listReviews(params: {
  page?: number;
  size?: number;
  status?: string;
  scope?: 'mine' | 'company';
} = {}): Promise<PageResult<ReviewSession>> {
  const q = new URLSearchParams();
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  if (params.status) q.set('status', params.status);
  if (params.scope) q.set('scope', params.scope);
  return apiClient(`/sales/reviews?${q.toString()}`);
}

export function getReview(id: string): Promise<ReviewDetail> {
  return apiClient(`/sales/reviews/${id}`);
}

export interface PreviousReviewTask {
  id: string;
  title: string;
  channel: string | null;
  dueAt: string | null;
  status: 'pending' | 'done' | 'cancelled';
  completedAt: string | null;
}

/** 同一客户的上一次复盘；未关联客户或没有更早复盘时为 null */
export interface PreviousReview {
  sessionId: string;
  createdAt: string;
  customerName: string | null;
  topic: string | null;
  summary: string | null;
  customerSignals: CustomerSignal[] | null;
  tasks: PreviousReviewTask[];
}

export function getPreviousReview(id: string): Promise<PreviousReview | null> {
  return apiClient(`/sales/reviews/${id}/previous`);
}

export function requestReport(id: string): Promise<{ reportId: string }> {
  return apiClient(`/sales/reviews/${id}/report`, { method: 'POST' });
}

export function getReport(id: string): Promise<ReviewReport> {
  return apiClient(`/sales/reviews/${id}/report`);
}

/** 单个历史版本的完整报告（列表只回元数据） */
export function getReportVersion(sessionId: string, reportId: string): Promise<ReviewReport> {
  return apiClient(`/sales/reviews/${sessionId}/reports/${reportId}`);
}

export function listReports(id: string): Promise<ReviewReport[]> {
  return apiClient(`/sales/reviews/${id}/reports`);
}

export function updateReviewShare(id: string, shareTranscriptWithCoach: boolean):
  Promise<{ shareTranscriptWithCoach: boolean }> {
  return apiClient(`/sales/reviews/${id}/share`, {
    method: 'PATCH',
    body: JSON.stringify({ shareTranscriptWithCoach }),
  });
}

export function listMessages(id: string): Promise<ReviewMessage[]> {
  return apiClient(`/sales/reviews/${id}/messages`);
}

export function streamMessage(
  id: string,
  content: string,
  callbacks: { onChunk: (s: string) => void; onDone: () => void; onError: (msg: string) => void },
): AbortController {
  return connectSse(
    {
      url: `${API_BASE}/sales/reviews/${id}/messages`,
      method: 'POST',
      body: { content },
    },
    callbacks,
  );
}

export interface LeadCandidate {
  id: string;
  name: string;
  ownerName: string | null;
  createdAt: string;
}

export function convertLead(id: string, body: {
  name: string;
  contactName?: string;
  contactPhone?: string;
  needSummary?: string;
  force?: boolean;
}): Promise<{
  created: boolean;
  lead: LeadSummary | null;
  duplicateCandidates: LeadCandidate[];
}> {
  return apiClient(`/sales/reviews/${id}/leads`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function createTasks(id: string, drafts: Array<{
  title: string;
  channel?: string;
  dueAt?: string;
  content?: string;
}>): Promise<{ created: number; skipped?: string[] }> {
  return apiClient(`/sales/reviews/${id}/tasks`, {
    method: 'POST',
    body: JSON.stringify({ drafts }),
  });
}

export interface CoachLedgerRow {
  sessionId: string;
  reviewerName: string | null;
  customerName: string | null;
  topic: string | null;
  summary: string | null;
  reportStatus: string | null;
  reportError: string | null;
  status: ReviewStatus;
  transcriptShared: boolean;
  createdAt: string;
}

export function getCoachLedger(page = 1, size = 20): Promise<PageResult<CoachLedgerRow>> {
  return apiClient(`/sales/coach/ledger?page=${page}&size=${size}`);
}

export function getCoachTranscript(sessionId: string): Promise<{
  segments: Array<{ seq: number; speaker: string; text: string }>;
}> {
  return apiClient(`/sales/coach/ledger/${sessionId}/transcript`);
}

// ====== 线索 ======
export interface LeadSummary {
  id: string;
  name: string;
  ownerId: string;
  ownerName: string | null;
  status: 'new' | 'following' | 'converted' | 'invalid';
  source: string;
  sourceReviewId: string | null;
  needSummary: string | null;
  nextFollowUpAt: string | null;
  lastActivityAt: string | null;
  updatedAt: string;
}

export function listLeads(params: { page?: number; size?: number; status?: string } = {}) {
  const q = new URLSearchParams();
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  if (params.status) q.set('status', params.status);
  return apiClient<PageResult<LeadSummary>>(`/sales/leads?${q.toString()}`);
}

export function updateLeadStatus(id: string, status: string): Promise<LeadSummary> {
  return apiClient(`/sales/leads/${id}/status`, {
    method: 'POST',
    body: JSON.stringify({ status }),
  });
}

/** 分配线索负责人（管理员/教练） */
export function assignLead(id: string, ownerId: string): Promise<LeadSummary> {
  return apiClient(`/sales/leads/${id}/owner`, {
    method: 'PUT',
    body: JSON.stringify({ ownerId }),
  });
}

export type LeadContactRole =
  | 'decision_maker'
  | 'influencer'
  | 'user'
  | 'gatekeeper'
  | 'other';

export interface LeadContact {
  id: string;
  name: string;
  phone: string | null;
  role: LeadContactRole | null;
  primary: boolean;
  note: string | null;
  createdAt: string;
}

export interface LeadActivity {
  id: string;
  type: string;
  content: string | null;
  actorName: string | null;
  sourceType: string | null;
  sourceId: string | null;
  occurredAt: string;
}

export interface LeadSourceReview {
  id: string;
  customerName: string | null;
  topic: string | null;
  coachName: string | null;
  createdAt: string;
}

export interface LeadDetail {
  summary: LeadSummary;
  needSummary: string | null;
  nextFollowUpAt: string | null;
  lastActivityAt: string | null;
  contacts: LeadContact[];
  activities: LeadActivity[];
  tasks: TaskView[];
  sourceReview: LeadSourceReview | null;
}

export function getLead(id: string): Promise<LeadDetail> {
  return apiClient(`/sales/leads/${id}`);
}

export function updateLead(
  id: string,
  body: { name: string; needSummary?: string; nextFollowUpAt?: string | null },
): Promise<LeadSummary> {
  return apiClient(`/sales/leads/${id}`, { method: 'PUT', body: JSON.stringify(body) });
}

export function addLeadContact(
  id: string,
  body: {
    name: string;
    phone?: string;
    role?: LeadContactRole;
    primary?: boolean;
    note?: string;
  },
): Promise<LeadContact> {
  return apiClient(`/sales/leads/${id}/contacts`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateLeadContact(
  id: string,
  contactId: string,
  body: {
    name: string;
    phone?: string;
    role?: LeadContactRole;
    primary?: boolean;
    note?: string;
  },
): Promise<LeadContact> {
  return apiClient(`/sales/leads/${id}/contacts/${contactId}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function deleteLeadContact(id: string, contactId: string): Promise<void> {
  return apiClient(`/sales/leads/${id}/contacts/${contactId}`, { method: 'DELETE' });
}

export function addLeadNote(id: string, content: string): Promise<LeadActivity> {
  return apiClient(`/sales/leads/${id}/notes`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  });
}

export function listLeadActivities(
  id: string,
  params: { page?: number; size?: number } = {},
): Promise<PageResult<LeadActivity>> {
  const q = new URLSearchParams();
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  return apiClient(`/sales/leads/${id}/activities?${q.toString()}`);
}

// ====== 任务 ======
export interface TaskView {
  id: string;
  leadId: string | null;
  reviewId: string | null;
  ownerId: string;
  ownerName: string | null;
  title: string;
  channel: string | null;
  content: string | null;
  dueAt: string | null;
  status: 'pending' | 'done' | 'cancelled';
  completedAt: string | null;
  escalatedAt: string | null;
}

export function listTasks(params: { page?: number; size?: number; status?: string } = {}) {
  const q = new URLSearchParams();
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  if (params.status) q.set('status', params.status);
  return apiClient<PageResult<TaskView>>(`/sales/tasks?${q.toString()}`);
}

export function updateTaskStatus(id: string, status: string): Promise<TaskView> {
  return apiClient(`/sales/tasks/${id}/status`, {
    method: 'POST',
    body: JSON.stringify({ status }),
  });
}

export function updateTask(
  id: string,
  body: { title: string; channel?: string; dueAt?: string | null; content?: string },
): Promise<TaskView> {
  return apiClient(`/sales/tasks/${id}`, { method: 'PUT', body: JSON.stringify(body) });
}

export function transferTask(id: string, ownerId: string): Promise<TaskView> {
  return apiClient(`/sales/tasks/${id}/transfer`, {
    method: 'POST',
    body: JSON.stringify({ ownerId }),
  });
}

export function escalateTask(id: string, reason?: string): Promise<TaskView> {
  return apiClient(`/sales/tasks/${id}/escalate`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export interface TaskOwnerOption {
  id: string;
  name: string;
  role: string;
}

export function listTaskOwners(
  keyword?: string,
  page = 1,
  size = 20,
): Promise<PageResult<TaskOwnerOption>> {
  const q = new URLSearchParams();
  q.set('page', String(page));
  q.set('size', String(size));
  if (keyword) q.set('keyword', keyword);
  return apiClient(`/sales/tasks/owners?${q.toString()}`);
}

// ====== 教练补充 ======
export interface CoachNote {
  id: string;
  coachName: string;
  content: string;
  createdAt: string;
}

export interface CoachSupplement {
  status: 'pending' | 'done' | 'cancelled' | null;
  message: string | null;
  shareTranscript: boolean;
  /** 当前用户就是该分身的教练所有者，无需发请求 */
  selfCoach: boolean;
  notes: CoachNote[];
}

export interface CoachRequestRow {
  id: string;
  sessionId: string;
  reviewerName: string | null;
  coachName: string | null;
  customerName: string | null;
  topic: string | null;
  message: string | null;
  status: 'pending' | 'done' | 'cancelled';
  requestedAt: string;
  completedAt: string | null;
  readAt: string | null;
  repliedAt: string | null;
  replyContent: string | null;
}

export interface CoachWorkspace {
  sessionId: string;
  reviewerName: string | null;
  customerName: string | null;
  topic: string | null;
  transcriptShared: boolean;
  report: ReviewReport | null;
  segments: ReviewSegment[];
  notes: CoachNote[];
  requestStatus: 'pending' | 'done' | 'cancelled' | null;
  requestMessage: string | null;
}

export function requestCoachSupplement(id: string, body: {
  message?: string;
  shareTranscript?: boolean;
}): Promise<CoachSupplement> {
  return apiClient(`/sales/reviews/${id}/coach-request`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function cancelCoachSupplement(id: string): Promise<void> {
  return apiClient(`/sales/reviews/${id}/coach-request/cancel`, { method: 'POST' });
}

export function getCoachSupplement(id: string): Promise<CoachSupplement> {
  return apiClient(`/sales/reviews/${id}/coach-supplement`);
}

export function listCoachRequests(params: {
  page?: number;
  size?: number;
  status?: string;
  mine?: boolean;
} = {}): Promise<PageResult<CoachRequestRow>> {
  const q = new URLSearchParams();
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  if (params.status) q.set('status', params.status);
  if (params.mine === false) q.set('mine', 'false');
  return apiClient(`/sales/coach/requests?${q.toString()}`);
}

/** SA-08 标记已读（批量） */
export function markCoachRequestsRead(ids: string[]): Promise<{ updated: number }> {
  return apiClient('/sales/coach/requests/mark-read', { method: 'POST', body: JSON.stringify({ ids }) });
}

/** SA-08 回复单条 */
export function replyCoachRequest(id: string, content: string): Promise<CoachRequestRow> {
  return apiClient(`/sales/coach/requests/${id}/reply`, { method: 'POST', body: JSON.stringify({ content }) });
}

/** SA-08 批量回复（同一内容） */
export function replyCoachRequestsBatch(ids: string[], content: string): Promise<{ success: number; failed: number }> {
  return apiClient('/sales/coach/requests/reply-batch', { method: 'POST', body: JSON.stringify({ ids, content }) });
}

/** SA-08 转交 */
export function transferCoachRequest(id: string, coachId: string): Promise<CoachRequestRow> {
  return apiClient(`/sales/coach/requests/${id}/transfer`, { method: 'POST', body: JSON.stringify({ coachId }) });
}

/** SA-08 可转交的同事 */
export function listCoachCandidates(): Promise<Array<{ id: string; name: string; role: string }>> {
  return apiClient('/sales/coach/coaches');
}

export function getCoachWorkspace(sessionId: string): Promise<CoachWorkspace> {
  return apiClient(`/sales/coach/requests/${sessionId}`);
}

export function addCoachNote(sessionId: string, content: string): Promise<CoachNote> {
  return apiClient(`/sales/coach/requests/${sessionId}/notes`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  });
}

// ==================== 销售个人看板（SA-07） ====================

export interface MySalesDashboard {
  range: { today: string; weekStart: string; monthStart: string };
  reviews: {
    thisWeek: number;
    thisMonth: number;
    total: number;
    reportReady: number;
    converted: number;
  };
  weekly: Array<{ week: string; count: number }>;
  leads: {
    total: number;
    new: number;
    following: number;
    converted: number;
    invalid: number;
    newThisWeek: number;
    convertedThisMonth: number;
    needFollowUp: number;
    overdueFollowUp: number;
  };
  tasks: {
    pending: number;
    overdue: number;
    dueToday: number;
    doneThisWeek: number;
  };
  recentReviews: Array<{
    id: string;
    customerName: string | null;
    topic: string | null;
    status: string;
    coachName: string | null;
    createdAt: string | null;
  }>;
}

/** 只看当前登录人自己的复盘/线索/跟进任务 */
export function getMySalesDashboard(): Promise<MySalesDashboard> {
  return apiClient('/sales/dashboard/mine');
}

// ==================== 团队共性问题 ====================

export interface CommonIssueSample {
  sessionId: string;
  customerName: string | null;
  point: string;
  suggestion: string | null;
}

export interface CommonIssueReviewRef {
  sessionId: string;
  customerName: string | null;
  createdAt: string | null;
}

export interface CommonIssue {
  key: string;
  label: string;
  reportCount: number;
  pointCount: number;
  samples: CommonIssueSample[];
  reviews: CommonIssueReviewRef[];
}

export interface CommonIssues {
  periodDays: number;
  reportCount: number;
  issues: CommonIssue[];
}

/** 近 N 天已生成报告的复盘，按销售问题类别归并（仅企业管理员） */
export function getCommonIssues(days = 90): Promise<CommonIssues> {
  return apiClient(`/sales/insights/common-issues?days=${days}`);
}

/** 把一类共性问题沉淀为知识草稿，返回草稿 id */
export function createIssueKnowledge(
  key: string,
  days = 90,
): Promise<{ id: string; title: string }> {
  return apiClient(`/sales/insights/common-issues/${key}/knowledge?days=${days}`, {
    method: 'POST',
  });
}
