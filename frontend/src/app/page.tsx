'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUser } from '@/lib/storage';
import { Permission } from '@/lib/permissions';

export default function HomePage() {
  const router = useRouter();

  useEffect(() => {
    const user = getUser() as any;
    const perms: string[] = user?.permissions || [];
    if (perms.includes(Permission.DASHBOARD_VIEW)) {
      router.replace('/admin');
    } else {
      router.replace('/skills');
    }
  }, [router]);

  return null;
}
