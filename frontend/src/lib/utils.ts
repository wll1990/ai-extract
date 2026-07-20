import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * 合并Tailwind CSS类名
 *
 * 使用clsx合并类名，然后通过tailwind-merge解决冲突。
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
