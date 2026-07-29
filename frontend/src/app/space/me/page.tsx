'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUser } from '@/lib/storage';
import { getSpaces } from '@/lib/api/spaces';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Permission } from '@/lib/permissions';

/**
 * "我的空间" — 自动跳转当前登录用户的空间详情页
 * 有个人空间权限则跳转到自己的空间，有管理后台权限则跳转到空间总览
 */
export default function MySpaceRedirect() {
  const router = useRouter();
  const user = getUser() as any;
  const perms: string[] = user?.permissions || [];

  useEffect(() => {
    if (!user) { router.replace('/login'); return; }

    // 无个人空间权限 → 空间总览
    if (!perms.includes(Permission.SPACE_OWN)) {
      router.replace('/spaces');
      return;
    }

    // 员工 → 跳转到自己的空间详情
    getSpaces(undefined, undefined, 1, 1)
      .then(d => {
        if (d.content?.length > 0) {
          router.replace(`/space/${d.content[0].id}`);
        } else {
          router.replace('/interview/create');
        }
      })
      .catch(() => router.replace('/'));
  }, [router, user]);

  return <LoadingSpinner />;
}
