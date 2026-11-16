/**
 * 培训模块 API 客户端
 *
 * 与后端 AdminTraining*Controller / MeTraining*Controller 对齐：
 * - 管理侧前缀 /admin/training（TRAINING_MANAGE，复核类走 TRAINING_REVIEW）
 * - 学员侧前缀 /me/training（TRAINING_LEARN，userId 只来自 JWT）
 *
 * 约定：后端 JSONB 字段（prerequisites/passCriteria/score/answers）已解析为对象/数组下发；
 * 只有任务列表里的 prerequisites 是原始 JSON 文本，本模块提供 parsePrerequisites() 兜底。
 *
 * @since 2026-10-29
 */

import { apiClient, ApiError, API_BASE } from './client';
import { getToken } from '@/lib/storage';
import { connectSse, type SseCallbacks } from '@/lib/sse';

// ==================== 通用 ====================

export interface PageResult<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

// ==================== 管理侧：计划 ====================

export interface TrainingPlan {
  id: string;
  companyId: string | null;
  name: string;
  description: string | null;
  status: string; // draft / published / retired
  currentVersion: number;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  /** AI 生成计划的训练目标与依据 */
  goal?: string | null;
  generatedByAi?: boolean;
  generationNote?: string | null;
}

export interface TrainingPlanVersion {
  id: string;
  planId: string;
  version: number;
  status: string; // draft / published
  taskCount: number | null;
  publishedAt: string | null;
  createdAt: string;
}

export interface TrainingTask {
  id: string;
  planId: string;
  version: number;
  phaseNo: number;
  phaseName: string | null;
  type: string; // reading / practice / exam
  title: string;
  description: string | null;
  isRequired: boolean;
  orderNo: number;
  /** 前置任务 ID 的 JSON 文本（后端未解析） */
  prerequisites: string | null;
  resourceType: string | null; // knowledge / case / skill
  resourceId: string | null;
  sceneTag: string | null;
  allowHints: boolean;
  examDatasetId: string | null;
  examDatasetVersion: number | null;
  examItemCount: number | null;
  examDurationMinutes: number | null;
  passCriteria: PassCriteria | null;
  maxAttempts: number;
  deadlinePolicy: string | null; // auto_submit / timeout_fail
  createdAt: string;
  updatedAt: string;
}

export interface PassCriteria {
  minScore?: number;
  minPointCoverage?: number;
  [key: string]: unknown;
}

export interface TrainingPhase {
  phaseNo: number;
  phaseName: string | null;
  tasks: TrainingTask[];
}

export interface TrainingResourceAlert {
  id: string;
  taskId: string;
  planVersion: number;
  resourceType: 'knowledge' | 'case' | 'skill' | 'exam_dataset';
  resourceId: string;
  /** 考试题集告警时定位版本 */
  resourceVersion?: number | null;
  reason: string;
  createdAt: string | null;
}

export interface TrainingPlanDetail extends TrainingPlan {
  versions: TrainingPlanVersion[];
  latestPublishedVersion: number | null;
  tasks: TrainingTask[];
  phases: TrainingPhase[];
  /** 已发布计划引用的资源失效提醒（每 10 分钟扫描） */
  resourceAlerts?: TrainingResourceAlert[];
}

export interface TrainingPlanRequest {
  name: string;
  description?: string;
  expectedVersion?: number;
}

export interface TrainingTaskRequest {
  type: string;
  phaseNo?: number;
  phaseName?: string;
  title: string;
  description?: string;
  isRequired?: boolean;
  orderNo?: number;
  prerequisites?: string[];
  resourceType?: string;
  resourceId?: string;
  sceneTag?: string;
  allowHints?: boolean;
  examDatasetId?: string;
  examDatasetVersion?: number;
  examItemCount?: number;
  examDurationMinutes?: number;
  passCriteria?: string;
  maxAttempts?: number;
  deadlinePolicy?: string;
  /** 微练场景卡（仅 drill）：AI 生成后原样提交 */
  drillScenario?: string;
}

export interface GeneratePlanResult extends TrainingPlan {
  tasks: TrainingTask[];
  note: string | null;
  warnings: string[];
}

/** AI 生成计划草稿：目标 + 候选资源 → 任务清单（含微练场景卡） */
export function generatePlan(req: { goal: string; phaseCount?: number }): Promise<GeneratePlanResult> {
  return apiClient<GeneratePlanResult>('/admin/training/plans/generate', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

/** 为单个资源生成微练场景卡（不落库） */
export function previewDrill(req: {
  resourceType: string; resourceId: string; sceneTag?: string; questionCount?: number;
}): Promise<{ scenario: string }> {
  return apiClient<{ scenario: string }>('/admin/training/plans/drill-preview', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export interface TrainingNeedDimension {
  sceneTag: string; gapCount: number; failedCount: number; weight: number;
  sampleQueries: string[]; recommendation: string;
}

export interface TrainingNeeds {
  openGaps: number; failedTasks: number; dimensions: TrainingNeedDimension[];
}

export function getTrainingNeeds(limit = 20): Promise<TrainingNeeds> {
  return apiClient<TrainingNeeds>(`/admin/training/needs?limit=${limit}`);
}

export interface TrainingEffect {
  cohortId: string; cohortName: string; totalLearners: number; certified: number;
  cancelled: number; pendingCertification: number; certificationRate: number;
  remediationRounds: number; gradedAttempts: number; needReviewAttempts: number;
  reviewRate: number;
  scenes: Array<{ sceneTag: string; avgScore: number; attempts: number }>;
  openGaps: number; summary: string;
}

export function getCohortEffect(cohortId: string): Promise<TrainingEffect> {
  return apiClient<TrainingEffect>(`/admin/training/cohorts/${cohortId}/effect`);
}

export function listPlans(params: { status?: string; keyword?: string; page?: number; size?: number } = {}): Promise<PageResult<TrainingPlan>> {
  const q = new URLSearchParams();
  if (params.status) q.set('status', params.status);
  if (params.keyword) q.set('keyword', params.keyword);
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  return apiClient<PageResult<TrainingPlan>>(`/admin/training/plans?${q.toString()}`);
}

export function createPlan(req: TrainingPlanRequest): Promise<TrainingPlan> {
  return apiClient<TrainingPlan>('/admin/training/plans', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function getPlan(id: string): Promise<TrainingPlanDetail> {
  return apiClient<TrainingPlanDetail>(`/admin/training/plans/${id}`);
}

export function updatePlan(id: string, req: TrainingPlanRequest): Promise<TrainingPlan> {
  return apiClient<TrainingPlan>(`/admin/training/plans/${id}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  });
}

export function createVersion(id: string): Promise<TrainingPlanVersion> {
  return apiClient<TrainingPlanVersion>(`/admin/training/plans/${id}/versions`, { method: 'POST' });
}

export function addTask(planId: string, req: TrainingTaskRequest): Promise<TrainingTask> {
  return apiClient<TrainingTask>(`/admin/training/plans/${planId}/tasks`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function updateTask(planId: string, taskId: string, req: TrainingTaskRequest): Promise<TrainingTask> {
  return apiClient<TrainingTask>(`/admin/training/plans/${planId}/tasks/${taskId}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  });
}

export function deleteTask(planId: string, taskId: string): Promise<void> {
  return apiClient<void>(`/admin/training/plans/${planId}/tasks/${taskId}`, { method: 'DELETE' });
}

export function reorderTasks(planId: string, taskIds: string[]): Promise<void> {
  return apiClient<void>(`/admin/training/plans/${planId}/task-order`, {
    method: 'PUT',
    body: JSON.stringify({ taskIds }),
  });
}

/** 任务绑定的资源预览（管理端；不可用时带原因而不是整页报错） */
export interface SnapshotRepairTask {
  taskId: string;
  title: string;
  status: 'repaired' | 'skipped' | 'failed';
  reason: string | null;
}

export interface SnapshotRepairResult {
  planId: string;
  version: number;
  repaired: number;
  skipped: number;
  failed: number;
  tasks: SnapshotRepairTask[];
}

export function repairPlanSnapshots(planId: string, version: number): Promise<SnapshotRepairResult> {
  return apiClient<SnapshotRepairResult>(
    `/admin/training/plans/${planId}/versions/${version}/snapshot-repair`, { method: 'POST' });
}

export interface TaskResourcePreview {
  type: string | null;
  id: string | null;
  available?: boolean;
  title?: string | null;
  content?: string | null;
  customerProfile?: string | null;
  funnelStage?: string | null;
  sceneTag?: string | null;
  dialogueQuote?: string | null;
  objection?: string | null;
  emotionChange?: string | null;
  keyActions?: string | null;
  scripts?: string | null;
  advanceNodes?: string | null;
  result?: string | null;
  reusablePoints?: string | null;
  failurePoints?: string | null;
  openingMessage?: string | null;
  /** 微练场景卡 */
  scenario?: string | null;
  clientRole?: string | null;
  questions?: Array<{ question?: string; context?: string; expectedPoints?: string[] }> | null;
  unavailableReason?: string | null;
}

export interface PreviewTask extends TrainingTask {
  resource?: TaskResourcePreview;
  prerequisiteTitles?: string[];
}

export interface PlanPreviewPhase {
  phaseNo: number;
  phaseName: string | null;
  tasks: PreviewTask[];
}

export interface PlanPreview {
  planId: string;
  name: string;
  version: number;
  latestPublishedVersion: number | null;
  /** 当前预览版本是否为已发布版本（学员真实视角） */
  published: boolean;
  phases: PlanPreviewPhase[];
}

export function previewPlan(planId: string, version?: number): Promise<PlanPreview> {
  const q = version != null ? `?version=${version}` : '';
  return apiClient(`/admin/training/plans/${planId}/preview${q}`);
}

export interface PublishBlocker {
  taskId: string | null;
  title: string | null;
  type: string | null;
  reason: string;
}

/** 发布预检：列出全部阻塞项，不执行发布 */
export function checkPublishReadiness(planId: string): Promise<{ ok: boolean; blockers: PublishBlocker[] }> {
  return apiClient(`/admin/training/plans/${planId}/readiness`);
}

/** 单个任务预览（任务配置 + 绑定资源） */
export function previewTask(planId: string, taskId: string): Promise<PreviewTask> {
  return apiClient(`/admin/training/plans/${planId}/tasks/${taskId}/preview`);
}

/** 未保存任务的资源预览 */
export function previewResource(
  planId: string, resourceType: string, resourceId: string,
): Promise<TaskResourcePreview> {
  const q = new URLSearchParams({ resourceType, resourceId });
  return apiClient(`/admin/training/plans/${planId}/resource-preview?${q.toString()}`);
}

export interface VersionFieldDiff {
  field: string;
  from: unknown;
  to: unknown;
}

export interface VersionTaskDiff {
  title: string;
  phaseNo: number | null;
  changes: VersionFieldDiff[];
}

export interface PlanVersionDiff {
  fromVersion: number;
  toVersion: number;
  added: VersionTaskDiff[];
  removed: VersionTaskDiff[];
  changed: VersionTaskDiff[];
}

/** 版本对比（TR-10）：按任务标题匹配 */
export function diffPlanVersions(
  planId: string, from: number, to: number,
): Promise<PlanVersionDiff> {
  const q = new URLSearchParams({ from: String(from), to: String(to) });
  return apiClient(`/admin/training/plans/${planId}/versions/diff?${q.toString()}`);
}

export function publishPlan(planId: string): Promise<TrainingPlan & { publishedVersion: number }> {
  return apiClient(`/admin/training/plans/${planId}/publish`, { method: 'POST' });
}

export function retirePlan(planId: string): Promise<TrainingPlan> {
  return apiClient<TrainingPlan>(`/admin/training/plans/${planId}/retire`, { method: 'POST' });
}

// ==================== 管理侧：批次 / 分配 ====================

export interface TrainingCohort {
  id: string;
  companyId: string;
  planId: string;
  planVersion: number;
  name: string;
  mentorId: string | null;
  deadline: string | null;
  status: string; // active / completed / cancelled
  createdAt: string;
  planName?: string | null;
}

export interface CertificationView {
  status: string; // pending/confirmed/remediating/revoked
  confirmedBy: string | null;
  confirmedByName: string | null;
  confirmedAt: string | null;
  note: string | null;
  revokedBy?: string | null;
  revokedByName?: string | null;
  revokedAt?: string | null;
  revokeReason?: string | null;
}

export interface CohortMember {
  id: string;
  assignmentId: string;
  cohortId: string;
  learnerId: string;
  learnerName: string | null;
  status: string;
  totalTasks: number;
  requiredTotal: number;
  completedTasks: number;
  requiredPassed: number;
  passedTasks: number;
  deadline: string | null;
  overdue: boolean;
  certification: CertificationView | null;
  createdAt: string;
}

export interface CertificationBlocker {
  taskId: string;
  title: string;
  recordStatus: string;
}

export interface LearnerProgress {
  assignmentId: string;
  learnerId: string;
  learnerName: string | null;
  status: string;
  totalTasks: number;
  requiredTotal: number;
  completedTasks: number;
  requiredPassed: number;
  passedTasks: number;
  deadline: string | null;
  overdue: boolean;
  pendingReview: boolean;
  pendingReviewOverdue: boolean;
  pendingReviewHours: number | null;
  canCertify: boolean;
  certifyBlockedReason: string | null;
  certificationBlockers: CertificationBlocker[];
  certification: CertificationView | null;
}

export interface CohortProgress extends TrainingCohort {
  summary: {
    total: number;
    notStarted: number;
    inProgress: number;
    pendingCertification: number;
    certified: number;
    cancelled: number;
    pendingReview: number;
    pendingReviewOverdue: number;
    overdue: number;
    planName?: string;
  };
  learners: LearnerProgress[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface CohortDetail extends TrainingCohort {
  planName: string | null;
  members: CohortMember[];
}

export interface TrainingCohortRequest {
  planId: string;
  planVersion: number;
  name: string;
  mentorId?: string;
  deadline?: string;
}

export function listCohorts(params: { planId?: string; status?: string; page?: number; size?: number } = {}): Promise<PageResult<TrainingCohort>> {
  const q = new URLSearchParams();
  if (params.planId) q.set('planId', params.planId);
  if (params.status) q.set('status', params.status);
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  return apiClient<PageResult<TrainingCohort>>(`/admin/training/cohorts?${q.toString()}`);
}

export function updateCohortStatus(id: string, status: 'completed' | 'cancelled' | 'archived'): Promise<void> {
  return apiClient(`/admin/training/cohorts/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  });
}

export function listMyCohorts(page = 1, size = 20): Promise<PageResult<TrainingCohort>> {
  return apiClient<PageResult<TrainingCohort>>(`/admin/training/cohorts/mine?page=${page}&size=${size}`);
}

export function createCohort(req: TrainingCohortRequest): Promise<TrainingCohort> {
  return apiClient<TrainingCohort>('/admin/training/cohorts', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function getCohort(id: string): Promise<CohortDetail> {
  return apiClient<CohortDetail>(`/admin/training/cohorts/${id}`);
}

export function getCohortProgress(
  id: string,
  params: { page?: number; size?: number; status?: string; keyword?: string } = {},
): Promise<CohortProgress> {
  const q = new URLSearchParams();
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  if (params.status) q.set('status', params.status);
  if (params.keyword) q.set('keyword', params.keyword);
  return apiClient<CohortProgress>(`/admin/training/cohorts/${id}/progress?${q.toString()}`);
}

export interface BatchAssignResult {
  cohortId: string;
  assigned: number;
  skipped: number;
  failed: number;
  results: Array<{ learnerId: string | null; name: string | null; status: string; reason: string | null }>;
}

export function batchAssign(cohortId: string, learnerIds: string[]): Promise<BatchAssignResult> {
  return apiClient<BatchAssignResult>('/admin/training/assignments/batch', {
    method: 'POST',
    body: JSON.stringify({ cohortId, learnerIds }),
  });
}

export function cancelAssignment(assignmentId: string): Promise<{ id: string; status: string }> {
  return apiClient(`/admin/training/assignments/${assignmentId}/cancel`, { method: 'POST' });
}

export function certifyAssignment(assignmentId: string, note?: string): Promise<{
  assignmentId: string;
  cohortId: string;
  learnerId: string;
  learnerName: string | null;
  status: string;
  alreadyCertified: boolean;
  certification: CertificationView | null;
}> {
  return apiClient(`/admin/training/assignments/${assignmentId}/certify`, {
    method: 'POST',
    body: JSON.stringify({ note: note || null }),
  });
}

// ==================== 管理侧：补训 / 撤销 / 延期 ====================

export function createRemediation(
  assignmentId: string,
  req: { recordId: string; reason: string; deadline?: string },
): Promise<{ assignmentId: string; recordId: string; roundNo: number; status: string; deadline: string | null }> {
  return apiClient(`/admin/training/assignments/${assignmentId}/remediation`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function reopenTask(
  assignmentId: string,
  req: { recordId: string; reason: string; deadline?: string },
): Promise<{ assignmentId: string; recordId: string; roundNo: number; roundType: string; status: string; deadline: string | null }> {
  return apiClient(`/admin/training/assignments/${assignmentId}/reopen`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function waiveTask(
  assignmentId: string,
  recordId: string,
  reason: string,
): Promise<{ assignmentId: string; recordId: string; status: string; waivedReason: string | null }> {
  return apiClient(`/admin/training/assignments/${assignmentId}/records/${recordId}/waive`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function extendCohortDeadline(
  cohortId: string,
  req: { deadline: string; reason: string },
): Promise<{
  cohortId: string; deadline: string; extended: number; skipped: number;
  items: Array<{ assignmentId: string; learnerId: string; status: string;
    oldDeadline: string | null; result: string; reason?: string; deadline?: string }>;
}> {
  return apiClient(`/admin/training/cohorts/${cohortId}/deadline`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function revokeCertification(assignmentId: string, reason: string): Promise<{
  assignmentId: string;
  status: string;
  certification: CertificationView | null;
}> {
  return apiClient(`/admin/training/assignments/${assignmentId}/certification/revoke`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function extendAssignmentDeadline(
  assignmentId: string,
  req: { deadline: string; reason: string },
): Promise<{ assignmentId: string; oldDeadline: string | null; deadline: string; updatedAttempts: number }> {
  return apiClient(`/admin/training/assignments/${assignmentId}/deadline`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

// ==================== 管理侧：导出 ====================

export interface ExportJob {
  id: string;
  sourceType: string;
  sourceId: string;
  status: string; // running/done/failed
  rowCount: number | null;
  errorMessage: string | null;
  createdBy: string | null;
  createdAt: string;
  finishedAt: string | null;
  downloadUrl?: string | null;
}

export function requestCohortExport(cohortId: string): Promise<ExportJob> {
  return apiClient(`/admin/training/cohorts/${cohortId}/export`, { method: 'POST' });
}

export function getExportJob(id: string): Promise<ExportJob> {
  return apiClient(`/admin/training/exports/${id}`);
}

/**
 * 下载导出 CSV。
 *
 * <p>不能直接用 {@code <a href>}：B 端身份在 localStorage 的 Bearer token 里，
 * 浏览器跳转不会带 Authorization（后端也不读 Cookie），必然 403。
 * OSS 模式取受限时长的预签名地址；local 模式带 header 取回 blob 再触发保存。</p>
 */
export async function downloadExport(id: string): Promise<void> {
  const token = getToken();
  const res = await fetch(`${API_BASE}/admin/training/exports/${id}/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) {
    let message = `下载失败 (${res.status})`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch { /* 非 JSON 响应保留状态码文案 */ }
    throw new ApiError(message, res.status, res.status);
  }
  // OSS 模式：后端返回 { url }，跳转即真实下载；local 模式返回文件流
  if (res.headers.get('content-type')?.includes('application/json')) {
    const body = await res.json();
    if (body?.url) {
      window.location.href = body.url;
      return;
    }
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `training-cohort-${id}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

// ==================== 管理侧：选择器 / 阶段 ====================

export interface PickerItem {
  id: string;
  name: string;
  subtitle: string | null;
  status: string;
}

export function searchPicker(
  kind: 'users' | 'knowledge' | 'cases' | 'skills' | 'datasets',
  params: {
    keyword?: string;
    status?: string;
    role?: string;
    coach?: boolean;
    page?: number;
    size?: number;
  } = {},
): Promise<PageResult<PickerItem>> {
  const q = new URLSearchParams();
  if (params.keyword) q.set('keyword', params.keyword);
  if (params.status) q.set('status', params.status);
  if (params.role) q.set('role', params.role);
  if (params.coach) q.set('coach', 'true');
  q.set('page', String(params.page || 1));
  q.set('size', String(params.size || 20));
  return apiClient<PageResult<PickerItem>>(`/admin/training/pickers/${kind}?${q.toString()}`);
}

export function updatePhases(
  planId: string,
  phases: Array<{ phaseNo: number; phaseName: string }>,
): Promise<{ tasks: TrainingTask[] }> {
  return apiClient(`/admin/training/plans/${planId}/phases`, {
    method: 'PUT',
    body: JSON.stringify({ phases }),
  });
}

// ==================== 管理侧：尝试与复核 ====================

export interface AttemptRow {
  id: string;
  attemptNo: number;
  type: string;
  status: string;
  result: string | null;
  startedAt: string | null;
  submittedAt: string | null;
  finishedAt: string | null;
  errorMessage: string | null;
  terminationReason: string | null;
  needsReview: boolean;
  totalScore: number | null;
}

export interface TaskRound {
  recordId: string;
  roundNo: number;
  status: string;
  remediationReason: string | null;
  effective: boolean;
  attempts: AttemptRow[];
}

export interface CertificationHistoryRow {
  id: string;
  status: string;
  confirmedBy: string | null;
  confirmedByName: string | null;
  confirmedAt: string | null;
  note: string | null;
  revokedBy: string | null;
  revokedByName: string | null;
  revokedAt: string | null;
  revokeReason: string | null;
  createdAt: string;
}

export interface AssignmentAttempts {
  assignmentId: string;
  learnerId: string;
  status: string;
  totalTasks: number;
  requiredTotal: number;
  completedTasks: number;
  passedTasks: number;
  requiredPassed: number;
  deadline: string | null;
  certificationHistory: CertificationHistoryRow[];
  tasks: Array<{
    recordId: string;
    recordStatus: string;
    roundNo: number | null;
    remediationReason: string | null;
    taskId: string;
    type: string | null;
    title: string | null;
    phaseNo: number | null;
    phaseName: string | null;
    orderNo: number | null;
    /** 绑定的知识/案例/分身/题集名称（学员端任务行直接展示） */
    resourceTitle: string | null;
    attempts: AttemptRow[];
    rounds: TaskRound[];
  }>;
}

export interface AdminAttemptDetail {
  attempt: {
    id: string;
    assignmentId: string;
    taskId: string;
    taskRecordId: string;
    type: string;
    attemptNo: number;
    status: string;
    result: string | null;
    startedAt: string | null;
    submittedAt: string | null;
    finishedAt: string | null;
    deadline: string | null;
    errorMessage: string | null;
    terminationReason: string | null;
    gradingAttempts: number;
    conversationId: string | null;
    questionSnapshot: { items: ExamQuestion[]; answerKey: Record<string, ExamAnswerKey> } | null;
    answers: Record<string, string> | null;
    score: ScorePayload | null;
  };
  task: {
    id: string;
    type: string;
    title: string;
    description: string | null;
    allowHints: boolean;
    maxAttempts: number;
    passCriteria: PassCriteria | null;
  };
  learner: { id: string; name: string | null };
  cohort: { id: string; name: string };
  reviews: ReviewRow[];
  messages: Array<{ id: string; role: string; roleLabel: string | null; content: string; createdAt: string }>;
}

export interface ExamQuestion {
  itemId: string;
  orderNo: number;
  question: string;
  context: string | null;
  citationRequirement: unknown;
  sceneTag: string | null;
}

export interface ExamAnswerKey {
  expectedPoints: unknown;
  forbiddenPoints: unknown;
  scoringRule: string | null;
  goldBasis: string | null;
}

export interface ScoreItem {
  itemId: string;
  orderNo: number;
  answered: boolean;
  total_score: number;
  confidence: number;
  verdict: string;
  reason?: string;
  hit_points?: string[];
  missed_points?: string[];
  forbidden_hits?: string[];
}

export interface ScorePayload {
  mode: string;
  total_score: number;
  confidence: number;
  verdict: string;
  need_review: boolean;
  critical?: boolean;
  forbidden_hit?: boolean;
  pass_threshold?: number;
  point_coverage?: number;
  min_point_coverage?: number;
  answered_items?: number;
  total_items?: number;
  items?: ScoreItem[];
  reason?: string;
  rounds?: number;
  final_result?: string | null;
  human_reviewed?: boolean;
  ai_total_score?: number;
  [key: string]: unknown;
}

export interface ReviewRow {
  id: string;
  reviewerId: string | null;
  reviewerName: string | null;
  action: string;
  reason: string;
  originalScore: unknown;
  adjustedScore: unknown;
  createdAt: string;
}

export function listAssignmentAttempts(assignmentId: string): Promise<AssignmentAttempts> {
  return apiClient<AssignmentAttempts>(`/admin/training/assignments/${assignmentId}/attempts`);
}

export function getAdminAttempt(attemptId: string): Promise<AdminAttemptDetail> {
  return apiClient<AdminAttemptDetail>(`/admin/training/attempts/${attemptId}`);
}

export function reviewAttempt(attemptId: string, action: 'pass' | 'reject', reason: string): Promise<{
  attemptId: string;
  status: string;
  result: string;
  recordStatus: string;
}> {
  return apiClient(`/admin/training/attempts/${attemptId}/reviews`, {
    method: 'POST',
    body: JSON.stringify({ action, reason }),
  });
}

export function regradeAttempt(attemptId: string): Promise<{ attemptId: string; status: string }> {
  return apiClient(`/admin/training/attempts/${attemptId}/regrade`, { method: 'POST' });
}

// ==================== 学员侧 ====================

export interface MyAssignment {
  id: string;
  cohortId: string;
  cohortName: string | null;
  planId: string | null;
  planName: string | null;
  planVersion: number | null;
  status: string;
  totalTasks: number;
  requiredTotal: number;
  completedTasks: number;
  requiredPassed: number;
  passedTasks: number;
  deadline: string | null;
  overdue: boolean;
  remainingSeconds: number | null;
  createdAt: string;
  certification: CertificationView | null;
}

export interface TaskItem {
  recordId: string;
  status: string;
  acknowledgedAt: string | null;
  roundNo?: number | null;
  remediationReason?: string | null;
  taskId: string;
  type: string;
  title: string;
  /** 绑定的知识/案例/分身名称（学员端任务行直接展示） */
  resourceTitle?: string | null;
  phaseNo: number;
  phaseName: string | null;
  orderNo: number;
  isRequired: boolean;
  blockingReasons: string[];
  /** 重考规则：最多可尝试次数、已用次数、剩余次数 */
  maxAttempts?: number;
  usedAttempts?: number;
  remainingAttempts?: number;
  passCriteria?: string | null;
  examDurationMinutes?: number | null;
  latestAttempt?: {
    id: string;
    attemptNo: number;
    status: string;
    result: string | null;
    submittedAt: string | null;
  };
}

export interface AssignmentTasks {
  assignment: {
    id: string;
    status: string;
    deadline: string | null;
    overdue: boolean;
    totalTasks: number;
    requiredTotal: number;
    completedTasks: number;
    requiredPassed: number;
    passedTasks: number;
  };
  tasks: TaskItem[];
}

export interface TaskDetail {
  record: {
    id: string;
    status: string;
    roundNo?: number | null;
    remediationReason?: string | null;
    acknowledgedAt: string | null;
    startedAt: string | null;
    finishedAt: string | null;
  };
  task: {
    id: string;
    type: string;
    title: string;
    description: string | null;
    phaseNo: number;
    phaseName: string | null;
    isRequired: boolean;
    allowHints: boolean;
    maxAttempts: number;
    passCriteria: PassCriteria | null;
    sceneTag: string | null;
    examItemCount: number | null;
    examDurationMinutes: number | null;
  };
  assignment: {
    id: string;
    status: string;
    deadline: string | null;
    totalTasks: number;
    requiredTotal: number;
    completedTasks: number;
    requiredPassed: number;
    passedTasks: number;
  };
  resource: {
    type: string | null;
    id?: string;
    title?: string;
    content?: string;
    customerProfile?: string;
    funnelStage?: string;
    sceneTag?: string;
    dialogueQuote?: string;
    objection?: string;
    emotionChange?: string;
    keyActions?: string;
    scripts?: string;
    advanceNodes?: string;
    result?: string;
    reusablePoints?: string;
    failurePoints?: string;
    openingMessage?: string;
    [key: string]: unknown;
  } | null;
  blockingReasons: string[];
  overdue: boolean;
}

export interface ExamAttemptView {
  attempt: {
    id: string;
    taskRecordId: string;
    taskId: string;
    type: string;
    attemptNo: number;
    status: string;
    result: string | null;
    errorMessage: string | null;
    startedAt: string | null;
    submittedAt: string | null;
    deadline: string | null;
    remainingSeconds: number | null;
    deadlineExceeded: boolean;
    terminationReason: string | null;
    conversationId: string | null;
  };
  task: {
    id: string;
    type: string;
    title: string;
    description: string | null;
    allowHints: boolean;
    maxAttempts: number;
    passCriteria: PassCriteria | null;
  };
  questions: ExamQuestion[];
  /** 微练场景头；非微练为 null */
  scenario?: { scenario: string; clientRole: string } | null;
  answers: Record<string, string>;
  allowedActions: string[];
}

export interface AttemptResultView {
  attempt: {
    id: string;
    assignmentId: string;
    taskRecordId: string;
    taskId: string;
    type: string;
    attemptNo: number;
    status: string;
    result: string | null;
    startedAt: string | null;
    submittedAt: string | null;
    finishedAt: string | null;
    deadline: string | null;
    terminationReason: string | null;
    errorMessage: string | null;
  };
  task: {
    id: string;
    type: string;
    title: string;
    maxAttempts: number;
    passCriteria: PassCriteria | null;
  };
  score: ScorePayload | null;
  questions: ExamQuestion[];
  answers: Record<string, string>;
  /** 微练场景（scenario/clientRole），仅 drill 有 */
  scenario?: { scenario?: string; clientRole?: string } | null;
  usedAttempts: number;
  remainingAttempts: number;
  canRetry: boolean;
  /** 不能重试的原因：pending_review / attempt_limit / deadline / assignment_closed / attempts... */
  retryBlockedReason?: string | null;
  reviews: Array<{ action: string; reason: string; createdAt: string }>;
}

export interface PracticeMessage {
  id: string;
  role: string;
  roleLabel: string | null;
  content: string;
  createdAt: string;
}

export interface RecordAttempts {
  taskId: string;
  maxAttempts: number;
  usedAttempts: number;
  remainingAttempts: number;
  attempts: Array<{
    id: string;
    attemptNo: number;
    type: string;
    status: string;
    result: string | null;
    startedAt: string | null;
    submittedAt: string | null;
    finishedAt: string | null;
    errorMessage: string | null;
    latestReview?: { action: string; reason: string; createdAt: string };
  }>;
}

export function myAssignments(page = 1, size = 20): Promise<PageResult<MyAssignment>> {
  return apiClient<PageResult<MyAssignment>>(`/me/training/assignments?page=${page}&size=${size}`);
}

export function getAssignmentTasks(assignmentId: string): Promise<AssignmentTasks> {
  return apiClient<AssignmentTasks>(`/me/training/assignments/${assignmentId}/tasks`);
}

export function getTaskDetail(recordId: string): Promise<TaskDetail> {
  return apiClient<TaskDetail>(`/me/training/task-records/${recordId}`);
}

export function startTask(recordId: string): Promise<TaskDetail> {
  return apiClient<TaskDetail>(`/me/training/task-records/${recordId}/start`, { method: 'POST' });
}

export function acknowledgeTask(recordId: string): Promise<{ id: string; status: string }> {
  return apiClient(`/me/training/task-records/${recordId}/acknowledge`, { method: 'POST' });
}

export function startAttempt(recordId: string): Promise<ExamAttemptView> {
  return apiClient<ExamAttemptView>(`/me/training/task-records/${recordId}/attempts`, { method: 'POST' });
}

export function getRecordAttempts(recordId: string): Promise<RecordAttempts> {
  return apiClient<RecordAttempts>(`/me/training/task-records/${recordId}/attempts`);
}

export function getAttempt(attemptId: string): Promise<ExamAttemptView> {
  return apiClient<ExamAttemptView>(`/me/training/attempts/${attemptId}`);
}

export function saveAnswers(attemptId: string, answers: Record<string, string>): Promise<{ id: string; saved: boolean }> {
  return apiClient(`/me/training/attempts/${attemptId}/answers`, {
    method: 'PUT',
    body: JSON.stringify({ answers }),
  });
}

export function submitAttempt(attemptId: string): Promise<ExamAttemptView> {
  return apiClient<ExamAttemptView>(`/me/training/attempts/${attemptId}/submit`, { method: 'POST' });
}

export function getAttemptResult(attemptId: string): Promise<AttemptResultView> {
  return apiClient<AttemptResultView>(`/me/training/attempts/${attemptId}/result`);
}

export function getPracticeMessages(attemptId: string): Promise<PracticeMessage[]> {
  return apiClient<PracticeMessage[]>(`/me/training/attempts/${attemptId}/messages`);
}

/** 陪练发言（SSE）。conversationId/sceneContext/sceneTag 由服务端按尝试回填，前端只传发言内容 */
export function sendPracticeMessage(
  attemptId: string,
  message: string,
  roundNumber: number,
  callbacks: SseCallbacks,
): AbortController {
  return connectSse(
    {
      url: `${API_BASE}/me/training/attempts/${attemptId}/messages`,
      method: 'POST',
      body: { message, roundNumber },
    },
    callbacks,
  );
}

/** 提示（SSE）。考试任务由服务端 403，前端只在 allowedActions 含 hint 时展示按钮 */
export function requestHint(attemptId: string, callbacks: SseCallbacks): AbortController {
  return connectSse(
    {
      url: `${API_BASE}/me/training/attempts/${attemptId}/hints`,
      method: 'POST',
      body: {},
    },
    callbacks,
  );
}

// ==================== 展示标签 ====================

export const PLAN_STATUS_LABELS: Record<string, string> = {
  draft: '草稿',
  published: '已发布',
  retired: '已退役',
};

export const COHORT_STATUS_LABELS: Record<string, string> = {
  active: '进行中',
  completed: '已完成',
  cancelled: '已取消',
  archived: '已归档',
};

export const ASSIGNMENT_STATUS_LABELS: Record<string, string> = {
  not_started: '未开始',
  in_progress: '进行中',
  pending_certification: '待认证',
  certified: '已认证',
  cancelled: '已取消',
};

export const RECORD_STATUS_LABELS: Record<string, string> = {
  locked: '未解锁',
  available: '待开始',
  in_progress: '进行中',
  pending_review: '待复核',
  passed: '已通过',
  failed: '未通过',
  waived: '免修',
};

export const ATTEMPT_STATUS_LABELS: Record<string, string> = {
  in_progress: '进行中',
  submitted: '已交卷',
  grading: '评分中',
  pending_review: '待复核',
  finalized: '已出分',
  error: '评分失败',
};

export const TASK_TYPE_LABELS: Record<string, string> = {
  reading: '阅读',
  practice: '陪练',
  exam: '考试',
  drill: '场景微练',
};

export const TERMINATION_LABELS: Record<string, string> = {
  auto_submit: '超过截止时间，系统已自动交卷',
  timeout_fail: '超过截止时间，已转主管复核',
  system_error: '系统异常，已转主管复核',
  assignment_closed: '分配已结束',
};

/** 状态 → 徽章样式（与评测中心同色系） */
export function statusCls(status: string): string {
  switch (status) {
    case 'passed':
    case 'certified':
    case 'finalized':
    case 'published':
    case 'confirmed':
      return 'bg-green-50 text-green-700 border-green-200';
    case 'failed':
    case 'error':
    case 'cancelled':
    case 'retired':
      return 'bg-red-50 text-red-700 border-red-200';
    case 'pending_review':
    case 'pending_certification':
    case 'in_review':
      return 'bg-amber-50 text-amber-700 border-amber-200';
    case 'in_progress':
    case 'active':
    case 'grading':
      return 'bg-blue-50 text-blue-700 border-blue-200';
    default:
      return 'bg-slate-50 text-slate-600 border-slate-200';
  }
}

/** 下载认证证书（HTML，浏览器可直接打印/另存为 PDF）；未认证后端返回 409 */
export async function fetchCertificateHtml(assignmentId: string): Promise<string> {
  const token = getToken();
  const res = await fetch(`${API_BASE}/me/training/assignments/${assignmentId}/certificate`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    credentials: 'include',
  });
  if (!res.ok) {
    let message = '证书下载失败';
    try {
      const body = await res.json();
      message = body.message || message;
    } catch {
      // 非 JSON 错误体，保留默认文案
    }
    throw new ApiError(message, res.status, res.status);
  }
  return res.text();
}

/** 任务列表里的 prerequisites 是 JSON 文本，损坏时返回空数组而不是抛错 */
export function parsePrerequisites(raw: string | null | undefined): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

// ==================== 导师工作台（TR-07） ====================

export interface WorkbenchPendingReview {
  attemptId: string;
  assignmentId: string;
  learnerName: string | null;
  cohortId: string | null;
  cohortName: string | null;
  taskTitle: string | null;
  type: string;
  attemptNo: number;
  submittedAt: string | null;
}

export interface WorkbenchCohort {
  cohortId: string;
  name: string;
  status: string;
  total: number;
  certified: number;
  overdue: number;
}

export interface WorkbenchOverdue {
  assignmentId: string;
  learnerName: string | null;
  cohortName: string | null;
  deadline: string;
  requiredPassed: number;
  requiredTotal: number;
}

export interface WorkbenchPendingCertification {
  assignmentId: string;
  learnerName: string | null;
  cohortId: string;
  cohortName: string | null;
  requiredPassed: number;
  requiredTotal: number;
  updatedAt: string | null;
}

export interface TrainingWorkbench {
  pendingReviews: WorkbenchPendingReview[];
  pendingCertifications?: WorkbenchPendingCertification[];
  cohorts: WorkbenchCohort[];
  overdueLearners: WorkbenchOverdue[];
  counts: { pendingReview: number; pendingCertification?: number;
    overdue: number; pendingAssignments?: number };
}

export function getTrainingWorkbench(): Promise<TrainingWorkbench> {
  return apiClient<TrainingWorkbench>('/admin/training/workbench');
}
