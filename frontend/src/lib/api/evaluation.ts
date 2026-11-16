/**
 * 评测中心 API 客户端
 *
 * 与后端 AdminEvaluationController 对齐（前缀 /admin/evaluations）。
 * 题集三用途：regression（研发回归）/ acceptance（分身验收）/ training（培训考试）。
 *
 * @since 2026-10-27
 */

import { apiClient } from './client';

// ==================== 类型 ====================

export interface PageResult<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

/** 题集（对应后端 toDatasetMap） */
export interface EvaluationDataset {
  id: string;
  companyId: string | null;
  name: string;
  description: string | null;
  purpose: string; // regression / acceptance / training
  status: string; // draft / in_review / approved / published / retired
  currentVersion: number;
  itemCount: number;
  /** 最近一次审核说明（退回理由） */
  lastReviewReason?: string | null;
  reviewedAt?: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  createdByName?: string | null;
}

/** 题目（对应后端 toItemMap，JSONB 字段为 JSON 字符串） */
export interface EvaluationItem {
  id: string;
  datasetId: string;
  version: number;
  question: string;
  context: string | null;
  expectedPoints: string | null;
  forbiddenPoints: string | null;
  citationRequirement: string | null;
  sceneTag: string | null;
  goldBasis: string | null;
  scoringRule: string | null;
  retrievalExpected: string | null;
  orderNo: number;
  status: string; // active / deprecated
  /** AI 生成的候选题 */
  generated?: boolean;
  createdAt: string;
  updatedAt: string;
}

/** AI 生成候选任务 */
export interface GenerationJob {
  id: string;
  datasetId: string;
  sourceType: string; // knowledge / case
  status: string; // running / done / failed
  producedCount: number;
  skippedCount?: number;
  rejectedCount?: number;
  errorMessage: string | null;
  createdAt?: string;
  updatedAt?: string;
}

/** 题集创建/编辑请求 */
export interface EvaluationDatasetRequest {
  name: string;
  purpose: string;
  description?: string;
  version?: number;
}

/** 题目创建/编辑请求 */
export interface EvaluationItemRequest {
  question: string;
  context?: string;
  expectedPoints?: string;
  forbiddenPoints?: string;
  citationRequirement?: string;
  sceneTag?: string;
  goldBasis?: string;
  scoringRule?: string;
  retrievalExpected?: string;
  orderNo?: number;
}

export interface DatasetListParams {
  purpose?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

// ==================== 展示标签 ====================

export const PURPOSE_LABELS: Record<string, string> = {
  regression: '研发回归',
  acceptance: '分身验收',
  training: '培训考试',
};

export const DATASET_STATUS_LABELS: Record<string, string> = {
  draft: '草稿',
  in_review: '审核中',
  approved: '已通过',
  published: '已发布',
  retired: '已退役',
};

export const DATASET_STATUS_CLS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-600',
  in_review: 'bg-warning-bg text-warning-text',
  approved: 'bg-success-bg text-success',
  published: 'bg-primary-light text-primary',
  retired: 'bg-gray-100 text-gray-500',
};

// ==================== 题集 ====================

export function listDatasets(params: DatasetListParams = {}): Promise<PageResult<EvaluationDataset>> {
  const q = new URLSearchParams();
  if (params.purpose) q.set('purpose', params.purpose);
  if (params.status) q.set('status', params.status);
  if (params.keyword) q.set('keyword', params.keyword);
  q.set('page', String(params.page ?? 1));
  q.set('size', String(params.size ?? 20));
  return apiClient<PageResult<EvaluationDataset>>(`/admin/evaluations/datasets?${q.toString()}`);
}

export function getDataset(id: string): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>(`/admin/evaluations/datasets/${id}`);
}

export function createDataset(req: EvaluationDatasetRequest): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>('/admin/evaluations/datasets', { method: 'POST', body: JSON.stringify(req) });
}

export function updateDataset(id: string, req: EvaluationDatasetRequest): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>(`/admin/evaluations/datasets/${id}`, { method: 'PUT', body: JSON.stringify(req) });
}

export function submitDataset(id: string): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>(`/admin/evaluations/datasets/${id}/submit`, { method: 'POST' });
}

export function reviewDataset(id: string, action: 'approve' | 'reject', reason?: string): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>(`/admin/evaluations/datasets/${id}/review`, {
    method: 'POST',
    body: JSON.stringify({ action, reason }),
  });
}

export function publishDataset(id: string): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>(`/admin/evaluations/datasets/${id}/publish`, { method: 'POST' });
}

export interface DatasetVersion {
  version: number;
  status: string;
  itemCount: number;
  frozenAt: string | null;
}

/** 版本列表：回看历史发布版题目 */
export function listDatasetVersions(id: string): Promise<DatasetVersion[]> {
  return apiClient<DatasetVersion[]>(`/admin/evaluations/datasets/${id}/versions`);
}

export function createVersion(id: string): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>(`/admin/evaluations/datasets/${id}/versions`, { method: 'POST' });
}

export function copyDataset(id: string): Promise<EvaluationDataset> {
  return apiClient<EvaluationDataset>(`/admin/evaluations/datasets/${id}/copy`, { method: 'POST' });
}

// ==================== 题目 ====================

export function listItems(datasetId: string, version?: number, page = 1, size = 50): Promise<PageResult<EvaluationItem>> {
  const q = new URLSearchParams();
  if (version != null) q.set('version', String(version));
  q.set('page', String(page));
  q.set('size', String(size));
  return apiClient<PageResult<EvaluationItem>>(`/admin/evaluations/datasets/${datasetId}/items?${q.toString()}`);
}

export function addItem(datasetId: string, req: EvaluationItemRequest): Promise<EvaluationItem> {
  return apiClient<EvaluationItem>(`/admin/evaluations/datasets/${datasetId}/items`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function updateItem(itemId: string, req: EvaluationItemRequest): Promise<EvaluationItem> {
  return apiClient<EvaluationItem>(`/admin/evaluations/items/${itemId}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  });
}

export function deleteItem(itemId: string): Promise<void> {
  return apiClient<void>(`/admin/evaluations/items/${itemId}`, { method: 'DELETE' });
}

// ==================== AI 生成候选 ====================

export function createGenerationJob(datasetId: string, sourceType: 'knowledge' | 'case', sourceIds: string[]): Promise<{ jobId: string }> {
  return apiClient<{ jobId: string }>(`/admin/evaluations/datasets/${datasetId}/generation-jobs`, {
    method: 'POST',
    body: JSON.stringify({ sourceType, sourceIds }),
  });
}

export function getGenerationJob(jobId: string): Promise<GenerationJob> {
  return apiClient<GenerationJob>(`/admin/evaluations/generation-jobs/${jobId}`);
}

/** 题集最近的生成任务：离开页面后回来仍能看到进度与 0 产出原因 */
export function listGenerationJobs(datasetId: string): Promise<GenerationJob[]> {
  return apiClient<GenerationJob[]>(`/admin/evaluations/datasets/${datasetId}/generation-jobs`);
}

/** 已定稿报告：刷新页面后直接加载 */
export interface EvaluationReportView {
  id: string;
  runId: string;
  version: number;
  status: string;
  summary: string | null;
  content: string | null;
  finalizedAt: string | null;
}

export function getRunReport(runId: string): Promise<EvaluationReportView> {
  return apiClient<EvaluationReportView>(`/admin/evaluations/runs/${runId}/report`);
}

// ==================== 评测运行（P1） ====================

/** 评测批次（对应后端 toRunMap） */
export interface EvaluationRun {
  id: string;
  datasetId: string;
  datasetVersion: number;
  skillId: string;
  purpose: string;
  status: string; // queued / running / completed / partial_failed / failed / cancel_requested / cancelled
  reviewStatus: string; // not_ready / pending_review / finalized
  totalItems: number;
  completedItems: number;
  errorItems: number;
  passedItems: number;
  failedItems: number;
  undeterminedItems: number;
  scoreAvg: number | null;
  /** 聚合明细（定稿时写入，含 retrieval 指标；未定稿为 null） */
  scoreDetail: string | null;
  usageTokens: number;
  usageCost: number;
  modelConfig: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdBy: string | null;
  createdAt: string;
}

/** 单题执行（对应后端 toRunItemMap，JSONB 字段为 JSON 字符串） */
export interface EvaluationRunItem {
  id: string;
  runId: string;
  itemId: string;
  itemVersion: number;
  status: string; // pending / generating / scoring / completed / error
  verdict: string | null; // pass / fail / undetermined
  aiAnswer: string | null;
  retrievalEvidence: string | null;
  ruleCheckResult: string | null;
  aiScore: string | null;
  /** 最终分（人工改分优先），由后端按最新复核记录计算 */
  finalScore?: number | null;
  scoreSource?: 'ai' | 'manual' | null;
  errorMessage: string | null;
  attemptNo: number;
  createdAt: string;
  updatedAt: string;
  reviews?: EvaluationReviewItem[];
}

/** 人工复核记录 */
export interface EvaluationReviewItem {
  id: string;
  runItemId: string;
  reviewerId: string | null;
  action: string; // adjust / undetermined / confirm
  reason: string;
  originalScore: string | null;
  adjustedScore: string | null;
  createdAt: string;
}

/** 验收报告 */
export interface EvaluationReport {
  id: string;
  runId: string;
  version: number;
  status: string; // draft / finalized
  summary: string;
  content: string | null;
  finalizedBy: string | null;
  finalizedAt: string | null;
}

/** 逐题对比条目 */
export interface RunCompareDiff {
  itemId: string;
  leftVerdict: string | null;
  rightVerdict: string | null;
  leftScore: number | null;
  rightScore: number | null;
  /** ai=AI 原分，manual=人工改分后的最终分 */
  leftScoreSource?: 'ai' | 'manual' | null;
  rightScoreSource?: 'ai' | 'manual' | null;
  verdictChanged: boolean;
}

export interface RunCompareResult {
  left: string;
  right: string;
  sameCaliber: boolean;
  caliberNote: string;
  nonCommonItems: number;
  diffs: RunCompareDiff[];
}

export interface EvaluationRunRequest {
  datasetId: string;
  datasetVersion?: number;
  skillId: string;
  purpose?: string;
  modelConfig?: string;
  idempotencyKey?: string;
}

export interface RunListParams {
  datasetId?: string;
  status?: string;
  page?: number;
  size?: number;
}

export const RUN_STATUS_LABELS: Record<string, string> = {
  queued: '排队中',
  running: '执行中',
  completed: '已完成',
  partial_failed: '部分失败',
  failed: '执行失败',
  cancel_requested: '取消中',
  cancelled: '已取消',
};

export const RUN_STATUS_CLS: Record<string, string> = {
  queued: 'bg-gray-100 text-gray-600',
  running: 'bg-primary-light text-primary',
  completed: 'bg-success-bg text-success',
  partial_failed: 'bg-warning-bg text-warning-text',
  failed: 'bg-danger-bg text-danger',
  cancel_requested: 'bg-warning-bg text-warning-text',
  cancelled: 'bg-gray-100 text-gray-500',
};

export const REVIEW_STATUS_LABELS: Record<string, string> = {
  not_ready: '未就绪',
  pending_review: '待复核',
  finalized: '已定稿',
};

export const VERDICT_LABELS: Record<string, string> = {
  pass: '通过',
  fail: '未通过',
  undetermined: '未判定',
};

export const VERDICT_CLS: Record<string, string> = {
  pass: 'bg-success-bg text-success',
  fail: 'bg-danger-bg text-danger',
  undetermined: 'bg-warning-bg text-warning-text',
};

/** 单题执行态（详情页不再直出英文） */
export const ITEM_STATUS_LABELS: Record<string, string> = {
  pending: '等待执行',
  generating: '答题中',
  scoring: '评分中',
  completed: '已完成',
  error: '执行失败',
  cancelled: '已取消',
};

/** 复核动作 */
export const REVIEW_ACTION_LABELS: Record<string, string> = {
  confirm: '确认 AI 结论',
  adjust: '改分',
  undetermined: '无法判断',
};

/** 批次是否已到终态（前端据此停止轮询） */
export function isRunTerminal(status: string): boolean {
  return ['completed', 'partial_failed', 'failed', 'cancelled'].includes(status);
}

export function listRuns(params: RunListParams = {}): Promise<PageResult<EvaluationRun>> {
  const q = new URLSearchParams();
  if (params.datasetId) q.set('datasetId', params.datasetId);
  if (params.status) q.set('status', params.status);
  q.set('page', String(params.page ?? 1));
  q.set('size', String(params.size ?? 20));
  return apiClient<PageResult<EvaluationRun>>(`/admin/evaluations/runs?${q.toString()}`);
}

export function createRun(req: EvaluationRunRequest): Promise<EvaluationRun> {
  return apiClient<EvaluationRun>('/admin/evaluations/runs', { method: 'POST', body: JSON.stringify(req) });
}

export function getRun(id: string): Promise<EvaluationRun> {
  return apiClient<EvaluationRun>(`/admin/evaluations/runs/${id}`);
}

export function listRunItems(runId: string, page = 1, size = 50): Promise<PageResult<EvaluationRunItem>> {
  return apiClient<PageResult<EvaluationRunItem>>(`/admin/evaluations/runs/${runId}/items?page=${page}&size=${size}`);
}

export function getRunItem(runId: string, itemId: string): Promise<EvaluationRunItem> {
  return apiClient<EvaluationRunItem>(`/admin/evaluations/runs/${runId}/items/${itemId}`);
}

export function cancelRun(id: string): Promise<EvaluationRun> {
  return apiClient<EvaluationRun>(`/admin/evaluations/runs/${id}/cancel`, { method: 'POST' });
}

export function retryFailedRun(id: string): Promise<{ retriedItems: number }> {
  return apiClient<{ retriedItems: number }>(`/admin/evaluations/runs/${id}/retry-failed`, { method: 'POST' });
}

export function finalizeRun(id: string): Promise<EvaluationReport> {
  return apiClient<EvaluationReport>(`/admin/evaluations/runs/${id}/finalize`, { method: 'POST' });
}

export function reviewRunItem(runItemId: string, req: { action: string; reason: string; adjustedScore?: string }): Promise<EvaluationReviewItem> {
  return apiClient<EvaluationReviewItem>(`/admin/evaluations/results/${runItemId}/reviews`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function compareRuns(left: string, right: string): Promise<RunCompareResult> {
  return apiClient<RunCompareResult>(`/admin/evaluations/runs/compare?left=${left}&right=${right}`);
}

// ==================== JSON 数组字段 ↔ 逐行文本 ====================

/** 逐行文本 → JSON 数组字符串（空行忽略），空返回 null */
export function linesToJsonArray(lines: string): string | null {
  const arr = lines.split('\n').map(s => s.trim()).filter(Boolean);
  return arr.length ? JSON.stringify(arr) : null;
}

/** JSON 数组字符串 → 逐行文本（非数组则原样返回） */
export function jsonArrayToLines(json: string | null): string {
  if (!json) return '';
  try {
    const arr = JSON.parse(json);
    return Array.isArray(arr) ? arr.join('\n') : json;
  } catch {
    return json;
  }
}

// ==================== 质量基线趋势（CH-03 / EV-02） ====================

export interface RunTrendPoint {
  runId: string;
  datasetVersion: number | null;
  finishedAt: string | null;
  scoreAvg: number | null;
  totalItems: number;
  passedItems: number;
  failedItems: number;
  errorItems: number;
  passRate: number | null;
  modelConfig: string | null;
}

export function getRunTrend(datasetId: string, limit = 10): Promise<RunTrendPoint[]> {
  return apiClient<RunTrendPoint[]>(
    `/admin/evaluations/runs/trend?datasetId=${encodeURIComponent(datasetId)}&limit=${limit}`);
}
