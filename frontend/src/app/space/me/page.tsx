'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { getCurrentUser } from '@/lib/api/auth';
import { getSpaces } from '@/lib/api/spaces';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Permission } from '@/lib/permissions';

/**
 * "我的空间" — 只跳转当前登录用户自己的空间
 */
export default function MySpaceRedirect() {
  const router = useRouter();
  const [error, setError] = useState(false);

  useEffect(() => {
    getCurrentUser()
      .then(user => {
        const perms: string[] = (user as any).permissions || [];
        if (!perms.includes(Permission.SPACE_OWN)) {
          router.replace('/spaces');
          return null;
        }
        return getSpaces(undefined, undefined, 1, 1, (user as any).id);
      })
      .then((d: any) => {
        if (!d?.content?.length) { setError(true); return; }
        router.replace(`/space/${d.content[0].id}`);
      })
      .catch(() => router.replace('/login'));
  }, [router]);

  if (error) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <span className="text-5xl">🏠</span>
          <h2 className="mt-4 text-lg font-semibold text-foreground">还没有个人空间</h2>
          <p className="mt-2 text-sm text-muted-foreground">创建个人空间后即可管理分身和知识库</p>
          <button
            onClick={() => router.push('/interview/create')}
            className="mt-6 inline-block rounded-full bg-primary px-6 py-2.5 text-sm font-medium text-white"
          >
            开始创建 →
          </button>
        </div>
      </div>
    );
  }

  return <LoadingSpinner />;
}