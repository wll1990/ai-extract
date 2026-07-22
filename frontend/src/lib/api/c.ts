/**
 * C 端 API 客户端 — 与 B 端 client.ts 完全独立的凭证体系
 *
 * B 端：HttpOnly Cookie；C 端：localStorage `c_auth` + 显式 Bearer 头。
 * JwtAuthFilter 取凭证 Bearer 优先于 Cookie —— 员工浏览器同时存在后台
 * Cookie 时，分享页请求也不会串到 B 端身份。
 * 401/403 派发 `guest:auth-required` 事件，由分享页弹注册/登录抽屉，
 * 绝不跳转 B 端 /login、不触碰 B 端 storage。
 *
 * @since 2026-07-19
 */

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';

const C_AUTH_KEY = 'c_auth';

export interface CUser {
  userId: string;
  nickname: string;
  status: 'guest' | 'registered';
  remaining?: number | null;
  limit?: number | null;
}

export interface CSession {
  token: string;
  user: CUser;
}

export interface ShareInfo {
  skillId: string;
  shareCode: string;
  ownerName?: string;
  ownerTitle?: string;
  avatarUrl?: string;
  openingMessage?: string;
  tags?: string[];
  sceneTags?: Array<{ tag: string; count?: number }>;
  guestLimit: number;
  remaining?: number | null;
  viewerStatus?: string | null;
}

/** 后端 GuestSessionResponse 的前端映射 */
export interface CSessionResponse {
  token: string | null;
  userId: string;
  nickname: string;
  status: 'guest' | 'registered';
  remaining?: number | null;
  limit?: number | null;
}

// ---- 本地凭证 ----

export function getCAuth(): CSession | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = localStorage.getItem(C_AUTH_KEY);
    return raw ? (JSON.parse(raw) as CSession) : null;
  } catch {
    return null;
  }
}

export function setCAuth(token: string, user: CUser): void {
  localStorage.setItem(C_AUTH_KEY, JSON.stringify({ token, user } satisfies CSession));
}

export function clearCAuth(): void {
  localStorage.removeItem(C_AUTH_KEY);
}

export function setLastShareCode(code: string): void {
  try { localStorage.setItem('lastShareCode', code); } catch { /* ignore */ }
}

// ---- 请求层 ----

async function cFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const auth = getCAuth();
  if (auth?.token) headers['Authorization'] = `Bearer ${auth.token}`;
  if (options.headers) Object.assign(headers, options.headers as Record<string, string>);

  const res = await fetch(`${API_BASE}${path}`, { ...options, headers });

  if (!res.ok) {
    if ((res.status === 401 || res.status === 403) && typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('guest:auth-required'));
      throw new Error('AUTH_REQUIRED');
    }
    let msg = `请求失败 (${res.status})`;
    try {
      const errBody = await res.json();
      if (errBody.message) msg = errBody.message;
    } catch { /* ignore */ }
    throw new Error(msg);
  }

  const json = await res.json();
  if (json.code !== 200) throw new Error(json.message || '请求失败');
  return json.data as T;
}

// ---- 接口 ----

/** 分享落地页信息（无凭证可访问；有凭证时返回 remaining/viewerStatus） */
export function getShareInfo(shareCode: string): Promise<ShareInfo> {
  return cFetch<ShareInfo>(`/public/share/${encodeURIComponent(shareCode)}`);
}

/** 游客发证（幂等：无身份新建；已有 C 端身份滑动续期重签） */
export function createGuest(shareCode: string): Promise<CSessionResponse> {
  return cFetch<CSessionResponse>(`/public/share/${encodeURIComponent(shareCode)}/guest`, { method: 'POST' });
}

/** C 端登录（平台级账号密码，无企业 ID） */
export function cLogin(account: string, password: string): Promise<CSessionResponse> {
  return cFetch<CSessionResponse>('/c/auth/login', { method: 'POST', body: JSON.stringify({ account, password }) });
}

/** 游客升级注册（userId 不变，历史自动继承） */
export function cRegister(body: { account: string; password: string; nickname?: string }): Promise<CSessionResponse> {
  return cFetch<CSessionResponse>('/c/auth/register', { method: 'POST', body: JSON.stringify(body) });
}

/** 当前 C 端身份探测（B 端 token 会 404 → 按无 C 端身份处理） */
export function cMe(): Promise<CSessionResponse> {
  return cFetch<CSessionResponse>('/c/auth/me');
}
