'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUser } from '@/lib/storage';

export default function HomePage() {
  const router = useRouter();

  useEffect(() => {
    const user = getUser();
    if (user?.role === 'super_admin') {
      router.replace('/admin');
    } else {
      router.replace('/skills');
    }
  }, [router]);

  return null;
}
