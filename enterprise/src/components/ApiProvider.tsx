'use client';

import { useEffect } from 'react';
import { configureApi } from '@aiextract/shared-ui';

export function ApiProvider({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    configureApi({
      baseUrl: process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1',
    });
  }, []);

  return <>{children}</>;
}
