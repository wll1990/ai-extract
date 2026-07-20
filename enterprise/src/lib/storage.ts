/**
 * SSR-safe 浏览器存储工具 — @synced-from frontend/src/lib/storage.ts
 *
 * 完全复用，无需改动。
 *
 * @since 2026-07-20
 */

const KEYS = {
  TOKEN: 'token',
  USER: 'user',
} as const;

function isBrowser(): boolean { return typeof window !== 'undefined'; }

export function getToken(): string | null {
  if (!isBrowser()) return null;
  return localStorage.getItem(KEYS.TOKEN);
}

export function getUser(): { name: string; role: string; [key: string]: unknown } | null {
  if (!isBrowser()) return null;
  const raw = localStorage.getItem(KEYS.USER);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return null; }
}

export function setAuth(token: string, user: Record<string, unknown>): void {
  if (!isBrowser()) return;
  localStorage.setItem(KEYS.TOKEN, token);
  localStorage.setItem(KEYS.USER, JSON.stringify(user));
}

export function clearAuth(): void {
  if (!isBrowser()) return;
  localStorage.removeItem(KEYS.TOKEN);
  localStorage.removeItem(KEYS.USER);
}
