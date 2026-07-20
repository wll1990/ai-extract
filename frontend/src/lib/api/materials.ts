import { apiClient } from './client';

export interface MaterialItem {
  id: string; fileName: string; fileType: string; fileSize: number;
  version: number; status: string; createdAt: string;
  reportVersion?: string; analysisNotes?: string;
  context?: any; patterns?: any; faq?: any[]; narrative?: any;
  verifiedCount?: number; rejectedCount?: number;
  grains?: GrainItem[];
}

export interface GrainItem {
  id: string; sceneTag: string; sceneDescription: string;
  expertThought: string; standardScript: string;
  commonMistakes: string; applicableCondition: string;
  qualityScore?: number; difficultyLevel?: string;
}

export function listMaterials(skillId: string, page = 1, size = 20): Promise<{ content: MaterialItem[]; total: number; totalPages: number }> {
  return apiClient(`/admin/skills/${skillId}/materials?page=${page}&size=${size}`);
}

export function deleteMaterial(skillId: string, materialId: string): Promise<void> {
  return apiClient(`/admin/skills/${skillId}/materials/${materialId}`, { method: 'DELETE' });
}

/** 手动补录文字 — 针对图片/音频等无法自动解析的素材 */
export function submitManualText(materialId: string, text: string): Promise<void> {
  return apiClient(`/admin/materials/${materialId}/manual-text`, {
    method: 'PUT',
    body: JSON.stringify({ text }),
  });
}
