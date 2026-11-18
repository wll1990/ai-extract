import { apiClient } from './client';

/** 内容需求（信号聚合） */
export interface ContentNeed {
  sceneTag: string;
  gapCount: number;
  trainingFailCount: number;
  evalFailCount: number;
  weight: number;
  samples: string[];
  recommendation: string;
}

export interface ContentNeeds {
  gaps: number;
  trainingFailures: number;
  evalFailures: number;
  /** 待审 AI 知识草稿数：大于 0 时不允许继续生成 */
  pendingAiKnowledge: number;
  /** 待审 AI 案例草稿数：大于 0 时不允许继续生成 */
  pendingAiCases: number;
  needs: ContentNeed[];
}

export interface ContentJob {
  id: string;
  kind: string;
  sourceType: string;
  status: string;
  producedCount: number;
  errorMessage: string | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface ContentJobDetail extends ContentJob {
  sourceRef: string | null;
  result: unknown;
  updatedAt: string | null;
}

export interface ContentJobAccepted {
  jobId: string;
  kind: string;
  status: string;
  message: string;
}

export interface ContentAdoptionKind {
  kind: string;
  created: number;
  approved: number;
  discarded: number;
  pending: number;
  approvalRate: number;
  avgReviewHours: number;
}

export interface ContentAdoption {
  windowDays: number;
  created: number;
  approved: number;
  discarded: number;
  pending: number;
  approvalRate: number;
  avgReviewHours: number;
  byKind: Record<string, ContentAdoptionKind>;
}

export interface ContentDashboard {
  windowDays: number;
  generationJobs: number;
  generationDone: number;
  generationFailed: number;
  draftsProduced: number;
  pendingKnowledge: number;
  pendingCases: number;
  openGaps: number;
  adoption: ContentAdoption;
  recentJobs: ContentJob[];
}

export interface ContentHealthIssue {
  code: string;
  severity: string;
  message: string;
  suggestion: string;
}

export interface ContentHealth {
  id: string;
  type: string;
  title: string | null;
  status: string;
  issues: ContentHealthIssue[];
  similar: Array<{ id: string; title: string; status: string; hint: string }>;
}

export function getContentNeeds(limit = 20): Promise<ContentNeeds> {
  return apiClient<ContentNeeds>(`/admin/content/needs?limit=${limit}`);
}

export function generateKnowledgeDraft(sceneTag: string, signals?: string[]): Promise<ContentJobAccepted> {
  return apiClient('/admin/content/generate/knowledge', {
    method: 'POST', body: JSON.stringify({ sceneTag, signals }),
  });
}

export function generateCaseDraft(sceneTag: string, signals?: string[], conversation?: string): Promise<ContentJobAccepted> {
  return apiClient('/admin/content/generate/case', {
    method: 'POST', body: JSON.stringify({ sceneTag, signals, conversation }),
  });
}

export function getContentJobs(limit = 20): Promise<ContentJob[]> {
  return apiClient<ContentJob[]>(`/admin/content/jobs?limit=${limit}`);
}

export function getContentJob(jobId: string): Promise<ContentJobDetail> {
  return apiClient<ContentJobDetail>(`/admin/content/jobs/${jobId}`);
}

/** 轮询生成任务直到 done/failed；超时抛出，避免页面无限转圈。 */
export async function pollContentJob(
  jobId: string,
  { intervalMs = 1500, timeoutMs = 120000 }: { intervalMs?: number; timeoutMs?: number } = {},
): Promise<ContentJobDetail> {
  const started = Date.now();
  for (;;) {
    const job = await getContentJob(jobId);
    if (job.status === 'done' || job.status === 'failed') return job;
    if (Date.now() - started > timeoutMs) {
      throw new Error('生成任务超时，请稍后在生成记录中查看结果');
    }
    await new Promise(resolve => setTimeout(resolve, intervalMs));
  }
}

export function getContentDashboard(): Promise<ContentDashboard> {
  return apiClient<ContentDashboard>('/admin/content/dashboard');
}

export function getContentHealth(type: 'knowledge' | 'case', id: string): Promise<ContentHealth> {
  return apiClient<ContentHealth>(`/admin/content/health/${type}/${id}`);
}

// ==================== AI 派生（原一源多用） ====================

export interface DerivedExamItem {
  question: string;
  context?: string;
  expectedPoints: string[];
  forbiddenPoints?: string[];
  scoringRule?: string;
  goldBasis?: string;
}

export interface DerivedDrillQuestion {
  question: string;
  context?: string;
  expectedPoints?: string[];
  scoringRule?: string;
}

export interface DerivedContent {
  faqs?: Array<{ question: string; answer: string }>;
  examItems?: DerivedExamItem[];
  drill?: { scenario: string; clientRole?: string; questions?: DerivedDrillQuestion[] } | null;
}

/** AI 派生：提交派生任务，返回 jobId；结果从 job.result 取（不落库） */
export function deriveContent(sourceType: 'knowledge' | 'case', sourceId: string): Promise<ContentJobAccepted> {
  return apiClient('/admin/content/derive', {
    method: 'POST', body: JSON.stringify({ sourceType, sourceId }),
  });
}

// ==================== 评测分析 ====================

export interface EvaluationItemAnalysis {
  itemId: string;
  question: string | null;
  sceneTag: string | null;
  attempts: number;
  passed: number;
  passRate: number;
  difficulty: number;
  discrimination: number;
  reviews: number;
  overturns: number;
  overturnRate: number;
  flags: string[];
}

export interface EvaluationDatasetAnalysis {
  datasetId: string;
  itemCount: number;
  analyzedItems: number;
  neverRun: number;
  tooEasy: number;
  tooHard: number;
  lowDiscrimination: number;
  disputed: number;
  items: EvaluationItemAnalysis[];
}

export function getDatasetAnalysis(datasetId: string): Promise<EvaluationDatasetAnalysis> {
  return apiClient<EvaluationDatasetAnalysis>(`/admin/content/evaluation/datasets/${datasetId}/analysis`);
}

export function generateEvalItemsFromSignals(datasetId: string, sceneTag: string, limit = 5): Promise<ContentJobAccepted> {
  return apiClient(`/admin/content/evaluation/datasets/${datasetId}/generate-from-signals`, {
    method: 'POST', body: JSON.stringify({ sceneTag, limit }),
  });
}

/** 回归评测：自动取同题集/同分身最近一次已完成批次为基线 */
export function startRegression(datasetId: string, skillId: string, baselineRunId?: string): Promise<{
  runId: string; status: string; baselineRunId: string | null; message: string;
}> {
  return apiClient('/admin/content/evaluation/regression', {
    method: 'POST', body: JSON.stringify({ datasetId, skillId, baselineRunId }),
  });
}

export type ItemDiffChange = 'same' | 'improved' | 'regressed' | 'flipped_pass' | 'flipped_fail';

export interface EvaluationItemDiff {
  itemId: string;
  question: string | null;
  baselineScore: number | null;
  currentScore: number | null;
  baselineVerdict: string | null;
  currentVerdict: string | null;
  change: ItemDiffChange;
}

export interface EvaluationRunDiff {
  baselineRunId: string;
  currentRunId: string;
  baselineAvg: number;
  currentAvg: number;
  improved: number;
  regressed: number;
  flippedPass: number;
  flippedFail: number;
  items: EvaluationItemDiff[];
}

/** 回归对比：默认以同题集/同分身最近一次已完成批次为基线 */
export function getRunDiff(runId: string, datasetId: string, baselineRunId?: string): Promise<EvaluationRunDiff> {
  const qs = new URLSearchParams({ datasetId });
  if (baselineRunId) qs.set('baselineRunId', baselineRunId);
  return apiClient<EvaluationRunDiff>(`/admin/content/evaluation/runs/${runId}/diff?${qs.toString()}`);
}

/** 错题回流：把本批次未通过的题各生成一条知识草稿（最多 3 条），异步任务 */
export function promoteRunIssues(runId: string): Promise<ContentJobAccepted> {
  return apiClient(`/admin/content/evaluation/runs/${runId}/promote-issues`, { method: 'POST' });
}

// ==================== AI 待审视图 ====================

export type ReviewKind = 'knowledge' | 'case' | 'eval_item';
export type ReviewPathKind = 'knowledge' | 'case' | 'eval-item';

export interface ReviewJobSummary {
  id: string;
  kind: string;
  status: string;
  producedCount: number;
  errorMessage: string | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface ReviewDraft {
  kind: ReviewKind;
  id: string;
  title: string;
  status: string;
  sceneTag: string | null;
  datasetId: string | null;
  evidence: string | null;
  generationJobId: string | null;
  job: ReviewJobSummary | null;
  createdAt: string;
  canApprove: boolean;
}

export interface ReviewDraftList {
  kind: ReviewKind;
  total: number;
  page: number;
  size: number;
  items: ReviewDraft[];
}

export function getReviewDrafts(
  kind: ReviewKind, page = 1, size = 20, datasetId?: string,
): Promise<ReviewDraftList> {
  const qs = new URLSearchParams({ kind, page: String(page), size: String(size) });
  if (datasetId) qs.set('datasetId', datasetId);
  return apiClient<ReviewDraftList>(`/admin/content/review?${qs.toString()}`);
}

export function approveReviewDraft(kind: ReviewPathKind, id: string, expireAt?: string): Promise<unknown> {
  return apiClient(`/admin/content/review/${kind}/${id}/approve`, {
    method: 'POST', body: JSON.stringify({ expireAt }),
  });
}

export function rejectReviewDraft(kind: ReviewPathKind, id: string, reason: string): Promise<unknown> {
  return apiClient(`/admin/content/review/${kind}/${id}/reject`, {
    method: 'POST', body: JSON.stringify({ reason }),
  });
}
