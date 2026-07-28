'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { getUser } from '@/lib/storage';

/**
 * 客户端角色守卫 — 非授权角色自动 redirect。
 * @param allowedRoles 允许的角色列表，如 ['super_admin', 'company_admin']
 * @param redirectTo 无权限时跳转的路径，默认 '/'
 */
export function useRoleGuard(allowedRoles: string[], redirectTo = '/') {
  const router = useRouter();
  const [allowed, setAllowed] = useState(false);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    const user = getUser();
    const role = (user?.role as string) || '';
    if (allowedRoles.length === 0 || allowedRoles.includes(role)) {
      setAllowed(true);
    } else {
      router.replace(redirectTo);
    }
    setChecked(true);
  }, [allowedRoles, redirectTo, router]);

  return { allowed, checked };
}
