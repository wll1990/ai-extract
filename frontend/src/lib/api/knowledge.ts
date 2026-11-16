/**
 * 知识库 + 案例库 API 客户端
 *
 * 与后端 AdminKnowledgeController / AdminCaseController 对齐。
 * 过期态由前端按 expireAt 派生（status 只存 draft/pending/active/deprecated）。
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

/** 知识条目（对应后端 toMap，不含 embedding） */
export interface KnowledgeEntry {
  id: string;
  companyId: string | null;
  /** 提交到公共库的来源企业（公共自建内容为空） */
  sourceCompanyId?: string | null;
  sourceType: string;
  category: string;
  title: string;
  content: string;
  version: number;
  /** 被本版本取代的旧版本 ID（AI 整理 / 人工修订产生） */
  supersedesId?: string | null;
  effectiveAt: string | null;
  expireAt: string | null;
  status: string; // draft / pending / active / deprecated
  /** 向量索引状态：pending / indexing / indexed / failed */
  embeddingStatus?: string | null;
  indexedAt?: string | null;
  indexError?: string | null;
  source: string | null;
  /** 来源上传任务 ID；手工录入/外部导入为空 */
  sourceTaskId?: string | null;
  /** 来源文档名或书名；手工录入为空 */
  sourceDocument?: string | null;
  /** AI 生成依据（JSON 数组字符串），人工录入为空 */
  evidence?: string | null;
  /** 生成任务 ID；非空表示 AI 草稿 */
  generationJobId?: string | null;
  /** 是否 AI 生成（后端由 generationJobId 推导） */
  generated?: boolean;
  reviewedAt?: string | null;
  reviewNote?: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  /** 超管跨企业列表时后端补的企业名（公共内容为 null） */
  companyName?: string | null;
}

/** 案例条目 */
export interface IndustryCase {
  id: string;
  companyId: string | null;
  /** 提交到公共库的来源企业（公共自建内容为空） */
  sourceCompanyId?: string | null;
  title: string;
  customerProfile: string | null;
  funnelStage: string | null;
  sceneTag: string | null;
  dialogueQuote: string | null;
  objection: string | null;
  emotionChange: string | null;
  keyActions: string | null;
  scripts: string | null;
  advanceNodes: string | null;
  result: string | null; // win / loss / delay
  reusablePoints: string | null;
  failurePoints: string | null;
  source: string | null;
  /** 来源上传任务 ID；手工录入/外部导入为空 */
  sourceTaskId?: string | null;
  /** 来源文档名或书名；手工录入为空 */
  sourceDocument?: string | null;
  contributorId: string | null;
  confidence: number | null;
  version: number;
  /** 被本版本取代的旧案例 ID（AI 整理 / 人工修订产生） */
  supersedesId?: string | null;
  effectiveAt: string | null;
  expireAt: string | null;
  status: string;
  /** 来源性质：internal=企业实战，book=书籍/课程二手案例 */
  origin?: string | null;
  /** 准入阻断问题（非空时不能审批生效） */
  qualityIssues?: string | null;
  /** 准入提醒项 */
  qualityWarnings?: string | null;
  /** 向量索引状态：pending / indexing / indexed / failed */
  embeddingStatus?: string | null;
  indexedAt?: string | null;
  indexError?: string | null;
  /** AI 生成依据（JSON 数组字符串），人工录入为空 */
  evidence?: string | null;
  /** 生成任务 ID；非空表示 AI 草稿 */
  generationJobId?: string | null;
  /** 是否 AI 生成（后端由 generationJobId 推导） */
  generated?: boolean;
  reviewedAt?: string | null;
  reviewNote?: string | null;
  createdAt: string;
  updatedAt: string;
  /** 超管跨企业列表时后端补的企业名（公共内容为 null） */
  companyName?: string | null;
}

/** 上传萃取任务 */
export interface UploadTask {
  id: string;
  companyId: string | null;
  fileName: string | null;
  status: string; // extracting / done / failed
  producedCount: number;
  totalSections: number;
  processedSections: number;
  /** 萃取失败的章节数（部分成功时任务仍 done） */
  failedSections?: number;
  /** 素材类型：internal=企业自有资料，book=外部书籍/课程 */
  sourceKind?: string | null;
  /** 被跳过的候选数（重复/超上限/噪声） */
  skippedCount?: number;
  /** 处理说明：书籍模式提示、为什么跳过 */
  guideMessage?: string | null;
  errorMessage: string | null;
  createdAt: string;
}

/** 知识创建/编辑请求 */
export interface KnowledgeEntryRequest {
  title: string;
  category: string;
  sourceType: string;
  content: string;
  source?: string;
  effectiveAt?: string;
  expireAt: string;
  status?: string;
}

/** 案例创建/编辑请求 */
export interface IndustryCaseRequest {
  title: string;
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
  source?: string;
  effectiveAt?: string;
  expireAt?: string;
  status?: string;
}

/** AI 整理建议：点击后填充为修改指令 */
export interface RevisionSuggestion {
  title: string;
  instruction: string;
}

export interface KnowledgeListParams {
  category?: string;
  sourceType?: string;
  status?: string;
  keyword?: string;
  scope?: 'private' | 'public';
  /** true=只看 AI 生成草稿 */
  aiGenerated?: boolean;
  page?: number;
  size?: number;
}

export interface CaseListParams {
  funnelStage?: string;
  sceneTag?: string;
  result?: string;
  status?: string;
  keyword?: string;
  /** public=仅公共、private=仅企业私有 */
  scope?: 'private' | 'public';
  /** true=只看 AI 生成草稿 */
  aiGenerated?: boolean;
  page?: number;
  size?: number;
}

/** 知识/案例「被引用」条目：分身 + 次数 + 最近时间 */
export interface ReferenceItem {
  skillId: string | null;
  skillName: string;
  refCount: number;
  lastReferencedAt: string | null;
}

// ==================== 展示标签 ====================

export const CATEGORY_LABELS: Record<string, string> = {
  compliance: '合规',
  price: '价格',
  product: '产品',
  policy: '政策',
  competitor: '竞品',
  terminology: '术语',
  methodology: '方法论',
  industry_intro: '行业介绍',
};

export const SOURCE_TYPE_LABELS: Record<string, string> = {
  public: '公共',
  platform: '平台',
  deidentified: '脱敏',
  tenant: '企业私有',
};

export const STATUS_LABELS: Record<string, string> = {
  draft: '草稿',
  pending: '待审核',
  active: '已生效',
  deprecated: '已下架',
  expired: '已过期',
};

/** 文档萃取任务状态（与知识条目状态是两套枚举，不能混用） */
export const UPLOAD_TASK_STATUS_LABELS: Record<string, string> = {
  pending: '等待中',
  extracting: '萃取中',
  done: '已完成',
  failed: '失败',
};

/** 派生有效状态：active 且已过失效时间 → expired（不落库） */
export function effectiveStatus(status: string, expireAt: string | null): string {
  if (status === 'active' && expireAt && new Date(expireAt) < new Date()) {
    return 'expired';
  }
  return status;
}

// ==================== 知识库 ====================

export function listKnowledge(params: KnowledgeListParams = {}): Promise<PageResult<KnowledgeEntry>> {
  const q = new URLSearchParams();
  if (params.category) q.set('category', params.category);
  if (params.sourceType) q.set('sourceType', params.sourceType);
  if (params.status) q.set('status', params.status);
  if (params.keyword) q.set('keyword', params.keyword);
  if (params.scope) q.set('scope', params.scope);
  if (params.aiGenerated) q.set('aiGenerated', 'true');
  q.set('page', String(params.page ?? 1));
  q.set('size', String(params.size ?? 20));
  return apiClient<PageResult<KnowledgeEntry>>(`/admin/knowledge?${q.toString()}`);
}

export function getKnowledge(id: string): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}`);
}

/** 溯源：来源上传文档的文件名 + 短时效下载地址 */
export interface SourceFileRef {
  fileName: string;
  url: string;
}

export function getKnowledgeSourceFile(id: string): Promise<SourceFileRef> {
  return apiClient<SourceFileRef>(`/admin/knowledge/${id}/source-file`);
}

/** 该知识派生出的新版本（AI 整理草稿），按版本倒序 */
export function getKnowledgeRevisions(id: string): Promise<KnowledgeEntry[]> {
  return apiClient<KnowledgeEntry[]>(`/admin/knowledge/${id}/revisions`);
}

/** 完整版本链（v1 → 当前），按版本号升序 */
export function getKnowledgeVersions(id: string): Promise<KnowledgeEntry[]> {
  return apiClient<KnowledgeEntry[]>(`/admin/knowledge/${id}/versions`);
}

/** 以历史版本内容生成新草稿（回滚） */
export function rollbackKnowledge(id: string, versionId: string): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}/versions/${versionId}/rollback`, { method: 'POST' });
}

/** 被引用情况（最近 N 天） */
export function getKnowledgeReferences(id: string, days = 30): Promise<ReferenceItem[]> {
  return apiClient<ReferenceItem[]>(`/admin/knowledge/${id}/references?days=${days}`);
}

export function createKnowledge(req: KnowledgeEntryRequest): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>('/admin/knowledge', { method: 'POST', body: JSON.stringify(req) });
}

export function updateKnowledge(id: string, req: KnowledgeEntryRequest): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}`, { method: 'PUT', body: JSON.stringify(req) });
}

export function deprecateKnowledge(id: string): Promise<void> {
  return apiClient<void>(`/admin/knowledge/${id}/deprecate`, { method: 'POST' });
}

/** 彻底删除：仅草稿/待审/已下架，且无派生版本、无培训引用 */
export function deleteKnowledge(id: string): Promise<void> {
  return apiClient<void>(`/admin/knowledge/${id}`, { method: 'DELETE' });
}

export function approveKnowledge(id: string, expireAt?: string): Promise<KnowledgeEntry> {
  const q = expireAt ? `?expireAt=${encodeURIComponent(expireAt)}` : '';
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}/approve${q}`, { method: 'POST' });
}

export function copyKnowledge(id: string): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}/copy`, { method: 'POST' });
}

/** 重建向量索引（异步，稍后刷新查看 embeddingStatus） */
export function reindexKnowledge(id: string): Promise<void> {
  return apiClient<void>(`/admin/knowledge/${id}/reindex`, { method: 'POST' });
}

/** KN-08 批量操作：逐条执行，失败项返回原因 */
export interface BatchResult {
  success: number;
  failed: number;
  failures: Array<{ id: string; message: string }>;
}
export function batchKnowledge(action: 'approve' | 'deprecate' | 'reindex' | 'category', ids: string[], category?: string): Promise<BatchResult> {
  return apiClient<BatchResult>('/admin/knowledge/batch', {
    method: 'POST',
    body: JSON.stringify({ action, ids, category }),
  });
}

/** 检索测试：输入问题，看知识/案例实际命中与相似度（只读，不写引用日志） */
export interface RetrievalTestHit {
  id: string;
  title: string;
  similarity: number;
  category?: string;
  source?: string | null;
  content?: string;
  sceneTag?: string | null;
  objection?: string | null;
}
export interface RetrievalTestResult {
  query: string;
  topK: number;
  knowledge: RetrievalTestHit[];
  cases: RetrievalTestHit[];
}

export function retrievalTest(query: string, topK = 5): Promise<RetrievalTestResult> {
  return apiClient<RetrievalTestResult>('/admin/knowledge/retrieval-test', {
    method: 'POST', body: JSON.stringify({ query, topK }),
  });
}

/** 提交到公共库：生成公共待审候选，来源企业只做溯源 */
export function submitKnowledgeToPublic(id: string): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}/submit-public`, { method: 'POST' });
}

/** 恢复已废弃知识（30 天内） */
export function restoreKnowledge(id: string): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}/restore`, { method: 'POST' });
}

export function suggestKnowledgeRevision(id: string, instruction: string): Promise<KnowledgeEntry> {
  return apiClient<KnowledgeEntry>(`/admin/knowledge/${id}/suggest-revision`, {
    method: 'POST', body: JSON.stringify({ instruction }),
  });
}

/** AI 针对当前知识给出修改建议（只读） */
export function getKnowledgeImprovements(id: string): Promise<RevisionSuggestion[]> {
  return apiClient<RevisionSuggestion[]>(`/admin/knowledge/${id}/improvement-suggestions`, { method: 'POST' });
}

export function uploadKnowledge(file: File, sourceKind: 'internal' | 'book' = 'internal'): Promise<{ taskId: string }> {
  const form = new FormData();
  form.append('file', file);
  return apiClient<{ taskId: string }>(`/admin/knowledge/upload?sourceKind=${sourceKind}`, { method: 'POST', body: form });
}

/** 最近上传任务：离开页面后回来还能找到进度与草稿 */
export function listKnowledgeUploadTasks(): Promise<{ content: UploadTask[]; total: number }> {
  return apiClient<{ content: UploadTask[]; total: number }>('/admin/knowledge/upload/tasks?page=1&size=10');
}

export function getKnowledgeUploadStatus(taskId: string): Promise<UploadTask> {
  return apiClient<UploadTask>(`/admin/knowledge/upload/${taskId}`);
}

/** KN-06 上传萃取草稿（先预览，再批量采纳/拒绝） */
export interface KnowledgeDraft {
  id: string;
  sectionNo: number;
  title: string;
  content: string;
  category: string;
  source: string | null;
  status: 'pending' | 'adopted' | 'rejected';
  /** 质量分 0-100 */
  qualityScore?: number | null;
  /** 阻断问题：非空时不能直接采纳，必须先补齐 */
  qualityIssues?: string | null;
  /** 提醒项 */
  qualityWarnings?: string | null;
}
export function listKnowledgeDrafts(taskId: string): Promise<KnowledgeDraft[]> {
  return apiClient<KnowledgeDraft[]>(`/admin/knowledge/upload/${taskId}/drafts`);
}
/** 补齐未达标的草稿：保存后后端重算质量分 */
export function updateKnowledgeDraft(
  taskId: string,
  draftId: string,
  body: { title: string; content: string; category: string; source?: string },
): Promise<KnowledgeDraft> {
  return apiClient<KnowledgeDraft>(`/admin/knowledge/upload/${taskId}/drafts/${draftId}`, {
    method: 'PUT', body: JSON.stringify(body),
  });
}

export function adoptKnowledgeDrafts(taskId: string, ids: string[]): Promise<{
  adopted: number; skipped: number;
  skippedItems: Array<{ draftId: string; title: string; reason: string }>;
}> {
  return apiClient<{
    adopted: number; skipped: number;
    skippedItems: Array<{ draftId: string; title: string; reason: string }>;
  }>(`/admin/knowledge/upload/${taskId}/adopt`, {
    method: 'POST', body: JSON.stringify({ ids }),
  });
}
export function rejectKnowledgeDrafts(taskId: string, ids: string[]): Promise<{ rejected: number }> {
  return apiClient<{ rejected: number }>(`/admin/knowledge/upload/${taskId}/reject`, {
    method: 'POST', body: JSON.stringify({ ids }),
  });
}

/** 续传上传任务（从已处理章节检查点继续） */
export function retryKnowledgeUpload(taskId: string): Promise<UploadTask> {
  return apiClient<UploadTask>(`/admin/knowledge/upload/${taskId}/retry`, { method: 'POST' });
}

// ==================== 案例库 ====================

export function listCases(params: CaseListParams = {}): Promise<PageResult<IndustryCase>> {
  const q = new URLSearchParams();
  if (params.funnelStage) q.set('funnelStage', params.funnelStage);
  if (params.sceneTag) q.set('sceneTag', params.sceneTag);
  if (params.result) q.set('result', params.result);
  if (params.status) q.set('status', params.status);
  if (params.keyword) q.set('keyword', params.keyword);
  if (params.scope) q.set('scope', params.scope);
  if (params.aiGenerated) q.set('aiGenerated', 'true');
  q.set('page', String(params.page ?? 1));
  q.set('size', String(params.size ?? 20));
  return apiClient<PageResult<IndustryCase>>(`/admin/cases?${q.toString()}`);
}

export function getCase(id: string): Promise<IndustryCase> {
  return apiClient<IndustryCase>(`/admin/cases/${id}`);
}

export function getCaseSourceFile(id: string): Promise<SourceFileRef> {
  return apiClient<SourceFileRef>(`/admin/cases/${id}/source-file`);
}

/** 该案例派生出的新版本（AI 整理草稿），按版本倒序 */
export function getCaseRevisions(id: string): Promise<IndustryCase[]> {
  return apiClient<IndustryCase[]>(`/admin/cases/${id}/revisions`);
}

/** 完整版本链（v1 → 当前），按版本号升序 */
export function getCaseVersions(id: string): Promise<IndustryCase[]> {
  return apiClient<IndustryCase[]>(`/admin/cases/${id}/versions`);
}

/** 以历史案例版本内容生成新草稿（回滚） */
export function rollbackCase(id: string, versionId: string): Promise<IndustryCase> {
  return apiClient<IndustryCase>(`/admin/cases/${id}/versions/${versionId}/rollback`, { method: 'POST' });
}

/** 被引用情况（最近 N 天） */
export function getCaseReferences(id: string, days = 30): Promise<ReferenceItem[]> {
  return apiClient<ReferenceItem[]>(`/admin/cases/${id}/references?days=${days}`);
}

/** AI 整理案例：基于当前版本生成新草稿 */
export function suggestCaseRevision(id: string, instruction: string): Promise<IndustryCase> {
  return apiClient<IndustryCase>(`/admin/cases/${id}/suggest-revision`, {
    method: 'POST', body: JSON.stringify({ instruction }),
  });
}

export function createCase(req: IndustryCaseRequest): Promise<IndustryCase> {
  return apiClient<IndustryCase>('/admin/cases', { method: 'POST', body: JSON.stringify(req) });
}

export function updateCase(id: string, req: IndustryCaseRequest): Promise<IndustryCase> {
  return apiClient<IndustryCase>(`/admin/cases/${id}`, { method: 'PUT', body: JSON.stringify(req) });
}

export function deprecateCase(id: string): Promise<void> {
  return apiClient<void>(`/admin/cases/${id}/deprecate`, { method: 'POST' });
}

/** 彻底删除：仅草稿/待审/已下架，且无派生版本、无培训引用 */
export function deleteCase(id: string): Promise<void> {
  return apiClient<void>(`/admin/cases/${id}`, { method: 'DELETE' });
}

export function approveCase(id: string, expireAt?: string): Promise<IndustryCase> {
  const q = expireAt ? `?expireAt=${encodeURIComponent(expireAt)}` : '';
  return apiClient<IndustryCase>(`/admin/cases/${id}/approve${q}`, { method: 'POST' });
}

export function copyCase(id: string): Promise<IndustryCase> {
  return apiClient<IndustryCase>(`/admin/cases/${id}/copy`, { method: 'POST' });
}

/** 重建向量索引（异步，稍后刷新查看 embeddingStatus） */
export function reindexCase(id: string): Promise<void> {
  return apiClient<void>(`/admin/cases/${id}/reindex`, { method: 'POST' });
}

/** 提交到公共库：生成公共待审候选，来源企业只做溯源 */
export function submitCaseToPublic(id: string): Promise<IndustryCase> {
  return apiClient<IndustryCase>(`/admin/cases/${id}/submit-public`, { method: 'POST' });
}

/** 恢复已废弃案例（30 天内） */
export function restoreCase(id: string): Promise<IndustryCase> {
  return apiClient<IndustryCase>(`/admin/cases/${id}/restore`, { method: 'POST' });
}

/** AI 针对当前案例给出修改建议（只读） */
export function getCaseImprovements(id: string): Promise<RevisionSuggestion[]> {
  return apiClient<RevisionSuggestion[]>(`/admin/cases/${id}/improvement-suggestions`, { method: 'POST' });
}

export function uploadCase(file: File, sourceKind: 'internal' | 'book' = 'internal'): Promise<{ taskId: string }> {
  const form = new FormData();
  form.append('file', file);
  return apiClient<{ taskId: string }>(`/admin/cases/upload?sourceKind=${sourceKind}`, { method: 'POST', body: form });
}

export function getCaseUploadStatus(taskId: string): Promise<UploadTask> {
  return apiClient<UploadTask>(`/admin/cases/upload/${taskId}`);
}

/** 续传案例上传任务（从已处理章节检查点继续） */
export function retryCaseUpload(taskId: string): Promise<UploadTask> {
  return apiClient<UploadTask>(`/admin/cases/upload/${taskId}/retry`, { method: 'POST' });
}

/** 最近案例上传任务：离开页面后回来还能找到进度与产出 */
export function listCaseUploadTasks(): Promise<{ content: UploadTask[]; total: number }> {
  return apiClient<{ content: UploadTask[]; total: number }>('/admin/cases/upload/tasks?page=1&size=10');
}

// ==================== 外部预提取导入 ====================

export interface ImportItemResult {
  index: number;
  title: string | null;
  result: 'imported' | 'needs_supplement' | 'skipped';
  reason?: string;
}

export interface ImportResult {
  dryRun: boolean;
  total: number;
  imported: number;
  needsSupplement: number;
  skipped: number;
  items: ImportItemResult[];
}

export interface ImportRequest {
  /** internal=企业自有资料，book=书籍/课程（会做强脱敏，案例一律草稿） */
  sourceKind: 'internal' | 'book';
  /** 书名/资料名，会拼进每条的出处 */
  sourceName?: string;
  /** true=只校验不落库 */
  dryRun: boolean;
  items: unknown[];
}

/** 外部预提取提示词（复制给用户自己的模型用） */
export function getImportPrompt(kind: 'case' | 'knowledge'): Promise<{ prompt: string }> {
  const path = kind === 'case' ? '/admin/cases/import-prompt' : '/admin/knowledge/import-prompt';
  return apiClient<{ prompt: string }>(path);
}

export function importCases(req: ImportRequest): Promise<ImportResult> {
  return apiClient<ImportResult>('/admin/cases/import', { method: 'POST', body: JSON.stringify(req) });
}

export function importKnowledge(req: ImportRequest): Promise<ImportResult> {
  return apiClient<ImportResult>('/admin/knowledge/import', { method: 'POST', body: JSON.stringify(req) });
}

/** 场景标签候选值（当前可见范围内去重），供列表筛选下拉 */
export function listCaseSceneTags(): Promise<string[]> {
  return apiClient<string[]>('/admin/cases/scene-tags');
}
