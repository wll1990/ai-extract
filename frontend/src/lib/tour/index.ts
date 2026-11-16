import { CHAPTER_MAP } from './chapters';
import { DEMO_COMPANY_ID, type TrackId, type TourChapter, type TourData, type TourStep } from './types';

/** 轨道顺序 */
export const TRACKS: Record<TrackId, string[]> = {
  sales: ['overview', 'use', 'source', 'pipeline', 'review', 'update', 'assets', 'rollout'],
  onboarding: [
    'overview', 'source', 'pipeline', 'use', 'share',
    'review', 'coach', 'update', 'assets', 'evaluation', 'insights', 'rollout',
  ],
  employee: ['employee-use', 'source', 'review', 'employee-bridge', 'employee-training'],
};

/**
 * 已关闭的缺陷。章节的 blockedBy 必须全部在这里，才会出现在轨道中。
 * 与 docs/defects.md 的状态保持一致；未修完的章节宁可不演示。
 */
export const CLOSED_DEFECTS = new Set<string>([
  'M1', 'M2', 'M3', 'M4', 'M9', 'M10', 'M26', 'M27',
  'A1', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8', 'A9', 'A10', 'A19',
  'R1',
  'K1', 'K2', 'K3', 'K4', 'K7', 'K8', 'K10', 'K23',
]);

export function chapterReady(chapter: TourChapter): boolean {
  return (chapter.blockedBy || []).every(id => CLOSED_DEFECTS.has(id));
}

export function hasPermission(chapter: TourChapter, permissions: string[]): boolean {
  return !chapter.permission || permissions.includes(chapter.permission);
}

export function getTrackChapters(track: TrackId): TourChapter[] {
  return TRACKS[track]
    .map(id => CHAPTER_MAP[id])
    .filter((c): c is TourChapter => Boolean(c));
}

export function visibleChapters(track: TrackId, permissions: string[]): TourChapter[] {
  return getTrackChapters(track).filter(c => hasPermission(c, permissions) && chapterReady(c));
}

/** 未就绪章节（内部导览中心展示原因） */
export function blockedChapters(track: TrackId, permissions: string[]): TourChapter[] {
  return getTrackChapters(track).filter(c => hasPermission(c, permissions) && !chapterReady(c));
}

export function pickTrack(permissions: string[], companyId?: string): TrackId {
  const canAdmin = permissions.includes('SKILL_MANAGE') || permissions.includes('DASHBOARD_VIEW');
  if (!canAdmin) return 'employee';
  // 售前轨道只给演示企业；真实企业管理员走自助轨道，不能整条入口失效
  return companyId === DEMO_COMPANY_ID ? 'sales' : 'onboarding';
}

/** 用解析出的数据替换路由占位符；任一占位符缺失返回 null，避免跳到坏路由 */
export function fillRoute(route: string, data: TourData): string | null {
  let missing = false;
  const filled = route.replace(/\{(\w+)\}/g, (_, key: string) => {
    const v = (data as Record<string, unknown>)[key];
    if (typeof v !== 'string' || !v) { missing = true; return ''; }
    return encodeURIComponent(v);
  });
  return missing ? null : filled;
}

export function stepNeedsData(step: TourStep, data: TourData): boolean {
  if (!step.requires) return true;
  return Boolean((data as Record<string, unknown>)[step.requires]);
}

/** 去掉当前演示数据不支持的步骤；整章为空则不展示 */
export function chaptersForData(chapters: TourChapter[], data: TourData): TourChapter[] {
  return chapters
    .map(c => ({
      ...c,
      steps: c.steps
        .filter(s => stepNeedsData(s, data))
        .filter(s => !s.route || fillRoute(s.route, data) !== null),
    }))
    .filter(c => c.steps.length > 0);
}

export type { TrackId, TourChapter, TourData, TourStep };
