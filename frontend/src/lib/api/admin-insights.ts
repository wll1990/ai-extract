import { apiClient } from './client';

export interface SkillOverview {
  conversations: number;
  activeUsers: number;
  satisfactionRate: number;
  totalFeedback: number;
}
export interface SceneTopItem { scene: string; count: number; }
export interface RagDistribution { high: number; ref: number; none: number; total: number; highPct: number; refPct: number; nonePct: number; }
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
export function getKnowledgeGaps(skillId: string) { return apiClient<KnowledgeGapItem[]>(`/admin/insights/${skillId}/knowledge-gaps`); }
export function getGlobalOverview() { return apiClient<GlobalOverview>('/admin/insights/overview'); }

// ── 自动发现 API ──

/** 洞察列表（支持按分身/严重程度/状态筛选） */
export function getDiscoveries(params?: {
  skillId?: string; severity?: string; status?: string; page?: number; size?: number;
}): Promise<AutoInsight[]> {
  const sp = new URLSearchParams();
  if (params?.skillId) sp.set('skillId', params.skillId);
  if (params?.severity) sp.set('severity', params.severity);
  if (params?.status) sp.set('status', params.status);
  else sp.set('status', 'active');
  if (params?.page != null) sp.set('page', String(params.page));
  if (params?.size != null) sp.set('size', String(params.size));
  return apiClient<AutoInsight[]>(`/admin/insights/discoveries?${sp.toString()}`);
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
