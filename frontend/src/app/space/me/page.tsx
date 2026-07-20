'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUser } from '@/lib/storage';
import { getSpaces } from '@/lib/api/spaces';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

/**
 * "我的空间" — 自动跳转当前登录用户的空间详情页
 * 每个员工注册时自动创建空间，因此总能找到自己的空间
 */
export default function MySpaceRedirect() {
  const router = useRouter();
  const user = getUser();

  useEffect(() => {
    if (!user) { router.replace('/login'); return; }

    // 管理员 → 空间总览
    if (user.role === 'super_admin') {
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
