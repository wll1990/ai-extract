/**
 * 超级管理员「切换企业视角」的本地状态。
 *
 * 只存 sessionStorage：按标签页隔离，多标签可以各看各的企业；
 * key 绑定 userId，换账号/登出不会继承上一个用户的视角。
 * 服务端仍是权威：JwtAuthFilter 会校验权限、UUID、企业状态。
 *
 * @since 2026-11-13
 */

import { getUser } from '@/lib/storage';
import { Permission } from '@/lib/permissions';

const ID_PREFIX = 'acting.companyId.';
const NAME_PREFIX = 'acting.companyName.';
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 切换过程中抑制 401 跳登录：探针失败应该回退视角，而不是把人踢出 */
let switching = false;

function isBrowser(): boolean {
  return typeof window !== 'undefined';
}

function currentUserId(): string | null {
  const user = getUser();
  return user && typeof user.id === 'string' && user.id ? user.id : null;
}

/** 前端纵深防御：只有具备 COMPANY_MANAGE 的账号才注入 header */
function canAct(): boolean {
  const user = getUser();
  const permissions = (user?.permissions as string[] | undefined) || [];
  return permissions.includes(Permission.COMPANY_MANAGE);
}

export function getActingCompanyId(): string | null {
  if (!isBrowser() || !canAct()) return null;
  const uid = currentUserId();
  if (!uid) return null;
  const raw = window.sessionStorage.getItem(ID_PREFIX + uid);
  if (!raw) return null;
  if (!UUID_RE.test(raw)) {
    window.sessionStorage.removeItem(ID_PREFIX + uid);
    return null;
  }
  return raw;
}

export function getActingCompanyName(): string | null {
  if (!isBrowser()) return null;
  const uid = currentUserId();
  if (!uid) return null;
  return window.sessionStorage.getItem(NAME_PREFIX + uid);
}

/** 设置视角；companyId 为空 = 平台视角（全部企业） */
export function setActingCompany(companyId: string | null, companyName?: string | null): void {
  if (!isBrowser()) return;
  const uid = currentUserId();
  if (!uid) return;
  if (!companyId) {
    window.sessionStorage.removeItem(ID_PREFIX + uid);
    window.sessionStorage.removeItem(NAME_PREFIX + uid);
    return;
  }
  if (!UUID_RE.test(companyId)) return;
  window.sessionStorage.setItem(ID_PREFIX + uid, companyId);
  if (companyName) {
    window.sessionStorage.setItem(NAME_PREFIX + uid, companyName);
  } else {
    window.sessionStorage.removeItem(NAME_PREFIX + uid);
  }
}

export function clearActingCompany(): void {
  if (!isBrowser()) return;
  const keys: string[] = [];
  for (let i = 0; i < window.sessionStorage.length; i += 1) {
    const key = window.sessionStorage.key(i);
    if (key && (key.startsWith(ID_PREFIX) || key.startsWith(NAME_PREFIX))) {
      keys.push(key);
    }
  }
  keys.forEach(key => window.sessionStorage.removeItem(key));
}

/** 请求头；无视角时返回空对象 */
export function getActingHeaders(): Record<string, string> {
  const id = getActingCompanyId();
  return id ? { 'X-Acting-Company-Id': id } : {};
}

export function beginActingSwitch(): void {
  switching = true;
}

export function endActingSwitch(): void {
  switching = false;
}

export function isActingSwitching(): boolean {
  return switching;
}
