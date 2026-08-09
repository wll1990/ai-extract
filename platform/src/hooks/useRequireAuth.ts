'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getToken } from '@/lib/storage';

/**
 * 客户端鉴权守卫 — 用于 middleware 无法覆盖的场景（如 /h5/ 公开前缀下的需登录子路由）。
 *
 * getToken() 同时检查 localStorage.token（B端）和 localStorage.c_auth（C端），
 * 任一存在即放行。无 token 时重定向到登录页。
 */
export function useRequireAuth(redirectTo = '/login') {
  const router = useRouter();

  useEffect(() => {
    const token = getToken();
    if (!token) {
      const currentPath = window.location.pathname + window.location.search;
      router.replace(`${redirectTo}?redirect=${encodeURIComponent(currentPath)}`);
    }
  }, [router, redirectTo]);
}
