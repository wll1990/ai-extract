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

export function listMaterials(
  skillId: string, page = 1, size = 20, status?: string,
): Promise<{ content: MaterialItem[]; total: number; totalPages: number }> {
  const q = new URLSearchParams({ page: String(page), size: String(size) });
  if (status && status !== 'all') q.set('status', status);
  return apiClient(`/admin/skills/${skillId}/materials?${q.toString()}`);
}

/** 管理员手动重试失败素材 */
export function retryMaterial(materialId: string): Promise<void> {
  return apiClient(`/admin/materials/${materialId}/retry`, { method: 'PUT' });
}

/** 放弃素材 / 恢复已放弃素材（status: discarded | uploaded） */
export function updateMaterialStatus(skillId: string, materialId: string, status: 'discarded' | 'uploaded'): Promise<void> {
  return apiClient(`/admin/skills/${skillId}/materials/${materialId}`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  });
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

/** 文本素材上传 — 直接粘贴文本作为素材来源 */
export function uploadMaterialText(params: {
  text: string;
  spaceId?: string;
  skillId?: string;
  skillName?: string;
  domain?: string;
  title?: string;
}): Promise<{ uploaded: number; results: any[] }> {
  return apiClient('/admin/materials/text', {
    method: 'POST',
    body: JSON.stringify(params),
  });
}
