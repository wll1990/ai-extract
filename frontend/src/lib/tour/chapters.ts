/**
 * 纯数据入口 —— 唯一给 e2e / 消费方 import 的模块。
 * 禁止在此文件引入 React / next / api / CSS。
 */
import { CORE_CHAPTERS, EMPLOYEE_CHAPTERS } from './core';
import { FULL_CHAPTERS } from './full';
import type { TourChapter } from './types';

export const CHAPTERS: TourChapter[] = [
  ...CORE_CHAPTERS,
  ...EMPLOYEE_CHAPTERS,
  ...FULL_CHAPTERS,
];

export const CHAPTER_MAP: Record<string, TourChapter> = Object.fromEntries(
  CHAPTERS.map(c => [c.id, c]),
);

export { TOUR_VERSION, DEMO_COMPANY_ID, ANCHORS } from './types';
export type { TourChapter, TourStep, TrackId, TourData } from './types';
