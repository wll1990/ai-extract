import { apiClient } from './client';

export interface AuditDashboard {
  skill: {
    id: string; status: string; displayName?: string; ownerName?: string;
    ownerTitle?: string; department?: string; seniority?: string;
    tags?: string; targetScenarios?: string; limitations?: string;
    publishNotes?: string; createdAt?: string;
  };
  profile?: {
    personality?: string; speakingStyle?: string; background?: string;
    commonPhrases?: string; knowledgeDomains?: string;
    communicationPreferences?: string; weaknessNotes?: string;
    extraContext?: string;
  };
  skillsSummary: {
    totalGrains: number; activeGrains: number; deprecatedGrains: number;
    sceneTags: string[]; sceneCoverage: Record<string, number>; avgWeight: number;
    dimensionAvg?: Record<string, number>;
    qualityDistribution?: Record<string, number>;
  };
  materials: Array<{
    id: string; fileName: string; fileType: string; fileSize?: number; version: number; status: string;
    analysisNotes?: string; createdAt: string; reportVersion?: string;
    context?: any; patterns?: any; faq?: any[]; narrative?: any;
    verifiedCount?: number; rejectedCount?: number;
  }>;
  evaluations: Array<{
    id: string; mode: string; score: number; styleScore?: number; consistencyScore?: number;
    behaviorScore?: number; scriptReuseScore?: number; strengths?: string; improvements?: string;
    createdAt: string;
  }>;
  extractionResult?: {
    grainCount?: number; verifiedCount?: number; rejectedCount?: number;
    context?: any; patterns?: any; faq?: any[]; narrative?: any;
  };
  scenarioGrains?: Record<string, Array<{
    id: string; sceneTag: string; sceneDescription: string;
    expertThought: string; standardScript: string; commonMistakes: string;
    applicableCondition: string; qualityScore?: number; difficultyLevel?: string;
  }>>;
  acceptance?: { id: string; status: string; testScore: number; totalQuestions?: number; passedQuestions?: number };
}

export function getAuditDashboard(skillId: string): Promise<AuditDashboard> {
  return apiClient(`/admin/skills/${skillId}/audit-dashboard`);
}

export interface SupplementData {
  displayName?: string; ownerName?: string; ownerTitle?: string;
  department?: string; seniority?: string; tags?: string;
  targetScenarios?: string; limitations?: string; publishNotes?: string;
  communicationPreferences?: string; weaknessNotes?: string;
}

export function getSupplement(skillId: string): Promise<SupplementData> {
  return apiClient(`/admin/skills/${skillId}/supplement`);
}

export function saveSupplement(skillId: string, data: Partial<SupplementData>): Promise<void> {
  return apiClient(`/admin/skills/${skillId}/supplement`, { method: 'PUT', body: JSON.stringify(data) });
}

export function publishSkill(skillId: string, action: 'publish' | 'discard'): Promise<void> {
  return apiClient(`/admin/skills/${skillId}/publish`, { method: 'PUT', body: JSON.stringify({ action }) });
}

// ---- 发布前验证 ----

export interface GrainTrace {
  sceneTag: string;
  qualityScore?: number;
  fileName?: string;
  matchLevel?: string;
  levelLabel?: string;
}

export interface AutoDemoMessage {
  role: 'customer' | 'avatar';
  content: string;
  sceneTag?: string;
  grains?: GrainTrace[];
}

export interface EvaluateDemoRequest {
  messages: Array<{ role: string; content: string }>;
}

export interface EvaluateDemoResponse {
  totalScore: number;
  dimensions: Record<string, { score: number; comment: string }>;
  highlights: string[];
  improvements: string[];
  grainHitRate: string;
}

// ====== ProductDemoModal API 函数 ======

/** AI 生成客户开场白 */
export function fetchPracticeOpening(skillId: string, sceneTag: string): Promise<string> {
  return apiClient<string>(`/admin/skills/${skillId}/practice-opening`, {
    method: 'POST', body: JSON.stringify({ sceneTag }),
  });
}

/** 获取练习角度列表 */
export function fetchPracticeAngles(skillId: string, sceneTag: string): Promise<string[]> {
  return apiClient<string[]>(`/admin/skills/${skillId}/practice-angles?sceneTag=${encodeURIComponent(sceneTag)}`);
}

/** 对练逐轮评价 */
export function evaluatePracticeRound(skillId: string, body: {
  sceneTag: string; customerMessage: string; myResponse: string;
  previousChampionAnswer?: string; retryCount?: number;
}): Promise<any> {
  return apiClient<any>(`/admin/skills/${skillId}/practice-evaluate`, {
    method: 'POST', body: JSON.stringify(body),
  });
}

/** 匹配颗粒溯源 */
export function matchGrains(skillId: string, query: string): Promise<any> {
  return apiClient<any>(`/admin/skills/${skillId}/match-grains`, {
    method: 'POST', body: JSON.stringify({ query }),
  });
}

/** 对练评分汇总 */
export function scorePractice(skillId: string, rounds: any[]): Promise<any> {
  return apiClient<any>(`/admin/skills/${skillId}/practice-score`, {
    method: 'POST', body: JSON.stringify({ rounds }),
  });
}

/** QA 汇总报告 */
export function qaSummary(skillId: string, rounds: any[]): Promise<any> {
  return apiClient<any>(`/admin/skills/${skillId}/qa-summary`, {
    method: 'POST', body: JSON.stringify({ rounds }),
  });
}

/** Demo 评估 */
export function evaluateDemo(skillId: string, messages: Array<{ role: string; content: string }>): Promise<any> {
  return apiClient<any>(`/admin/skills/${skillId}/evaluate-demo`, {
    method: 'POST', body: JSON.stringify({ messages }),
  });
}
