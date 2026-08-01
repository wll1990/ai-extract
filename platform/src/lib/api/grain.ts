/**
 * 颗粒 CRUD API 客户端 — C 端审核页编辑/废弃颗粒。
 *
 * @since 2026-08-01
 */

import { apiClient } from './client';

// ---- Types ----

export interface GrainUpdateBody {
  status?: 'active' | 'deprecated';
  expertThought?: string;
  standardScript?: string;
  sceneTag?: string;
  sceneDescription?: string;
  commonMistakes?: string;
}

export interface GrainUpdateResult {
  id: string;
  status: string;
  sceneTag: string;
  expertThought: string;
  standardScript: string;
}

// ---- Update grain ----

export function updateGrain(
  grainId: string,
  body: GrainUpdateBody,
): Promise<GrainUpdateResult> {
  return apiClient<GrainUpdateResult>(`/grains/${grainId}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

// ---- Convenience ----

export function deprecateGrain(grainId: string): Promise<GrainUpdateResult> {
  return updateGrain(grainId, { status: 'deprecated' });
}

export function restoreGrain(grainId: string): Promise<GrainUpdateResult> {
  return updateGrain(grainId, { status: 'active' });
}
