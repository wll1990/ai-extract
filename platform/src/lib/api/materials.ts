/**
 * 素材 API 客户端 — C 端上传/列表/删除。
 *
 * 上传使用 XMLHttpRequest 实现进度回调（fetch 不支持 upload progress）。
 * 列表和删除通过统一 apiClient，自动携带 credentials。
 *
 * @since 2026-07-29
 */

import { apiClient, API_BASE } from './client';
import { getToken } from '@/lib/storage';

// ---- Types ----

export interface MaterialItem {
  id: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface MaterialListResponse {
  content: MaterialItem[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface UploadResult {
  materialId: string;
  fileName: string;
  status: string;
}

// ---- C-end 上传（XMLHttpRequest → 进度回调）----

export function uploadMaterial(
  skillId: string,
  file: File,
  materialType?: string,
  onProgress?: (pct: number) => void,
): Promise<UploadResult> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const fd = new FormData();
    fd.append('file', file);
    if (materialType) fd.append('materialType', materialType);

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    });

    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const j = JSON.parse(xhr.responseText);
          if (j.code === 200) {
            resolve(j.data as UploadResult);
          } else {
            reject(new Error(j.message || '上传失败'));
          }
        } catch {
          reject(new Error('解析响应失败'));
        }
      } else if (xhr.status === 401 || xhr.status === 403) {
        window.location.href = '/login';
        reject(new Error('登录已过期'));
      } else {
        reject(new Error(`上传失败 (${xhr.status})`));
      }
    });

    xhr.addEventListener('error', () => reject(new Error('网络错误，请检查连接')));
    xhr.addEventListener('abort', () => reject(new Error('上传已取消')));

    xhr.open('POST', `${API_BASE}/skills/${skillId}/materials/upload`);
    const token = getToken();
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    xhr.send(fd);
  });
}

// ---- C-end 素材列表 ----

export function listSkillMaterials(
  skillId: string,
  page = 1,
  size = 20,
): Promise<MaterialListResponse> {
  return apiClient<MaterialListResponse>(
    `/skills/${skillId}/materials?page=${page}&size=${size}`,
  );
}

// ---- C-end 删除素材 ----

export function deleteSkillMaterial(
  skillId: string,
  materialId: string,
): Promise<void> {
  return apiClient<void>(`/skills/${skillId}/materials/${materialId}`, {
    method: 'DELETE',
  });
}
