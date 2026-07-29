'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { getUser } from '@/lib/storage';

/**
 * 客户端权限守卫 — 缺少任一 required 权限即自动 redirect。
 * @param required 需要的权限码列表，用户拥有其中任一即可通过
 * @param redirectTo 无权限时跳转的路径，默认 '/'
 */
export function usePermissionGuard(required: string[], redirectTo = '/') {
  const router = useRouter();
  const [allowed, setAllowed] = useState(false);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    const user = getUser() as any;
    const perms: string[] = user?.permissions || [];
    if (required.length === 0 || required.some(p => perms.includes(p))) {
      setAllowed(true);
    } else {
      router.replace(redirectTo);
    }
    setChecked(true);
  }, [required, redirectTo, router]);

  return { allowed, checked };
}
