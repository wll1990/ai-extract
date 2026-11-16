import { apiClient } from './client';

export interface SkillOverview {
  conversations: number;
  activeUsers: number;
  satisfactionRate: number;
  totalFeedback: number;
}
export interface SceneTopItem { scene: string; count: number; }
export interface RagDistribution {
  high: number; ref: number; none: number; total: number;
  highPct: number; refPct: number; nonePct: number;
  /** 知识/案例检索命中（独立于颗粒 RAG） */
  kcKnowledge?: number; kcCase?: number;
  kcKnowledgePct?: number; kcCasePct?: number;
}
export interface GrainRankItem { id: string; description: string; sceneTag?: string; helpful: number; unhelpful: number; qualityScore?: number; }
export interface KnowledgeGapItem { id: string; query: string; sceneTag?: string; count: number; lastSeen?: string; status: string; note?: string; }
export interface SkillHealth { skillId: string; name: string; ownerTitle?: string; department?: string; conversations: number; users: number; satisfactionRate: number; hitRate: number; openGaps: number; grainCount: number; lastActive?: string; alerts: string[]; }
export interface GlobalOverview { totalConversations: number; activeUsers: number; satisfactionRate: number; hitRate: number; totalGrains: number; totalOpenGaps: number; totalSkills: number; skills: SkillHealth[]; }

// ── 自动发现 v1 ──

export interface AutoInsight {
  id: string;
  skillId: string | null;
  type: 'gap_burst' | 'satisfaction_drop' | 'hit_rate_drop' | 'new_pattern' | 'inactive';
  title: string;
  description: string;
  severity: 'critical' | 'warning' | 'info';
  evidence: string; // JSONB string
  candidateGrainId: string | null;
  status: 'active' | 'resolved' | 'ignored';
  createdAt: string;
}

export interface CandidateGrain {
  id: string;
  skillId: string | null;
  sceneTag: string;
  sceneDescription: string;
  expertThought: string;
  standardScript: string;
  commonMistakes: string;
  applicableCondition: string;
  sourceInsightId: string;
  sourceEvidence: string;
  status: 'pending_review' | 'approved' | 'rejected';
  reviewedAt: string | null;
  note: string | null;
  createdAt: string;
}

export interface DiscoveryDetail extends AutoInsight {
  candidateGrain?: CandidateGrain;
  candidateGrains: CandidateGrain[];
}

export function getSkillOverview(skillId: string) { return apiClient<SkillOverview>(`/admin/insights/${skillId}/overview`); }
export function getSceneTop(skillId: string) { return apiClient<SceneTopItem[]>(`/admin/insights/${skillId}/scene-top`); }
export function getRagDistribution(skillId: string) { return apiClient<RagDistribution>(`/admin/insights/${skillId}/rag-distribution`); }
export function getTopGrains(skillId: string, sort: 'best' | 'worst' = 'best') { return apiClient<GrainRankItem[]>(`/admin/insights/${skillId}/grains-top?sort=${sort}`); }
export function getKnowledgeGaps(skillId: string) {
  return apiClient<{ content: KnowledgeGapItem[] }>(`/admin/insights/${skillId}/knowledge-gaps?page=0&size=100`)
    .then(d => d.content || []);
}
export function getGlobalOverview() { return apiClient<GlobalOverview>('/admin/insights/overview'); }

// ── 分身主（空间属主）只读接口 — 员工没有 DASHBOARD_VIEW，走 /skills/{id}/insights ──
export function getOwnerSkillOverview(skillId: string) { return apiClient<SkillOverview>(`/skills/${skillId}/insights/overview`); }
export function getOwnerSceneTop(skillId: string) { return apiClient<SceneTopItem[]>(`/skills/${skillId}/insights/scene-top`); }
export function getOwnerRagDistribution(skillId: string) { return apiClient<RagDistribution>(`/skills/${skillId}/insights/rag-distribution`); }
export function getOwnerTopGrains(skillId: string, sort: 'best' | 'worst' = 'best') { return apiClient<GrainRankItem[]>(`/skills/${skillId}/insights/grains-top?sort=${sort}`); }
export function getOwnerKnowledgeGaps(skillId: string) {
  return apiClient<{ content: KnowledgeGapItem[] }>(`/skills/${skillId}/insights/knowledge-gaps?page=0&size=100`)
    .then(d => d.content || []);
}

// ── 自动发现 API ──

export interface PageResult<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

/** 洞察列表（支持按分身/严重程度/状态筛选；page 从 1 开始） */
export function getDiscoveries(params?: {
  skillId?: string; severity?: string; status?: string; page?: number; size?: number;
}): Promise<PageResult<AutoInsight>> {
  const sp = new URLSearchParams();
  if (params?.skillId) sp.set('skillId', params.skillId);
  if (params?.severity) sp.set('severity', params.severity);
  if (params?.status) sp.set('status', params.status);
  else sp.set('status', 'active');
  sp.set('page', String(params?.page ?? 1));
  sp.set('size', String(params?.size ?? 20));
  return apiClient<PageResult<AutoInsight>>(`/admin/insights/discoveries?${sp.toString()}`);
}

/** 批量取候选颗粒，key 为洞察 ID */
export function getDiscoveryCandidates(insightIds: string[]): Promise<Record<string, CandidateGrain[]>> {
  if (insightIds.length === 0) return Promise.resolve({});
  const sp = new URLSearchParams({ insightIds: insightIds.join(',') });
  return apiClient<Record<string, CandidateGrain[]>>(`/admin/insights/discoveries/candidates?${sp.toString()}`);
}

/** 洞察详情（含候选颗粒） */
export function getDiscoveryDetail(id: string): Promise<DiscoveryDetail> {
  return apiClient<DiscoveryDetail>(`/admin/insights/discoveries/${id}`);
}

/** 审核通过候选颗粒 */
export function approveCandidateGrain(id: string, note?: string): Promise<{ id: string; status: string; message: string }> {
  return apiClient(`/admin/insights/candidate-grains/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify(note ? { note } : {}),
  });
}

/** 拒绝候选颗粒 */
export function rejectCandidateGrain(id: string, note?: string): Promise<{ id: string; status: string }> {
  return apiClient(`/admin/insights/candidate-grains/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify(note ? { note } : {}),
  });
}

/** 处理洞察 — 标记为已处理或忽略 */
export function resolveDiscovery(id: string, status: 'resolved' | 'ignored'): Promise<{ id: string; status: string }> {
  return apiClient(`/admin/insights/discoveries/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  });
}

/** 更新知识缺口状态 — 标记为已解决或忽略 */
export function updateKnowledgeGap(id: string, status: 'resolved' | 'ignored', note?: string): Promise<void> {
  return apiClient(`/admin/insights/knowledge-gaps/${id}`, {
    method: 'PUT',
    body: JSON.stringify(note ? { status, note } : { status }),
  });
}
