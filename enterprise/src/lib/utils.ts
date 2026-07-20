/**
 * 工具函数 — @synced-from frontend/src/lib/utils.ts
 *
 * 完全复用，无需改动。
 *
 * @since 2026-07-20
 */

import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** 合并 Tailwind CSS class name */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
