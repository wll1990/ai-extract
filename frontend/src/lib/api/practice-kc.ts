/**
 * 对练知识/案例检索开关（企业级，保存后立即生效）。
 */

import { apiClient } from './client';

export interface PracticeKcSetting {
  enabled: boolean;
  /** 未配置时的全局默认值，用于解释「为什么现在还是关的」 */
  defaultEnabled: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
}

export function getPracticeKcSetting(): Promise<PracticeKcSetting> {
  return apiClient('/admin/company/practice-kc');
}

export function savePracticeKcSetting(enabled: boolean): Promise<PracticeKcSetting> {
  return apiClient('/admin/company/practice-kc', {
    method: 'PUT',
    body: JSON.stringify({ enabled }),
  });
}
