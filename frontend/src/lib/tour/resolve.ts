import { listSkills } from '@/lib/api/skill';
import { listReviews } from '@/lib/api/sales';
import type { TourData } from './types';

/**
 * 解析导览需要的动态 ID。
 * 任何一步失败都返回部分数据，不抛异常；章节层再决定跳过还是降级。
 */
export async function resolveTourData(permissions: string[] = []): Promise<TourData> {
  const data: TourData = {};
  const canReadGaps = permissions.includes('DASHBOARD_VIEW') || permissions.includes('GRAIN_MANAGE');

  try {
    const page = await listSkills(1, 50);
    const skills = page?.content || [];
    const published = skills.find(s => s.status === 'published');
    const reviewing = skills.find(s => s.status === 'reviewing' || s.status === 'generating');
    data.publishedSkillId = published?.id;
    data.reviewingSkillId = reviewing?.id || published?.id;
    data.skillWithMaterialsId = reviewing?.id || published?.id;
    const forGaps = data.reviewingSkillId || data.publishedSkillId;
    // 员工没有看板权限，请求缺口只会留下一条静默 403
    if (forGaps && canReadGaps) {
      data.openGapSkillId = forGaps;
      try {
        const gaps = await fetchGaps(forGaps);
        const open = gaps.find(g => g.status === 'open');
        if (open) { data.openGapId = open.id; data.openGap = true; }
      } catch { /* 没缺口不影响其他章节 */ }
    }
  } catch {
    /* 没权限或接口失败：保留空数据，章节自行降级 */
  }

  try {
    const reviews = await listReviews({ page: 1, size: 20 });
    const ready = (reviews?.content || []).find(r => r.latestReport?.status === 'ready');
    if (ready) { data.reportReadyReviewId = ready.id; data.reportReadyReview = true; }
  } catch {
    /* 复盘权限不足时跳过该章 */
  }

  return data;
}

interface GapRow { id: string; status: string }

async function fetchGaps(skillId: string): Promise<GapRow[]> {
  const { apiClient } = await import('@/lib/api/client');
  const raw = await apiClient<unknown>(`/admin/insights/${skillId}/knowledge-gaps`);
  if (Array.isArray(raw)) return raw as GapRow[];
  const obj = raw as { content?: GapRow[]; gaps?: GapRow[] };
  return obj?.content || obj?.gaps || [];
}
