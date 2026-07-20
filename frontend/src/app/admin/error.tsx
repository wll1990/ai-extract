'use client';

import { useEffect } from 'react';

export default function AdminError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('管理后台加载失败:', error);
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <div className="text-center">
        <span className="text-5xl">🔧</span>
        <h2 className="mt-4 text-lg font-semibold text-foreground">页面加载异常</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          {error.message || '请稍后重试'}
        </p>
        <button
          onClick={reset}
          className="mt-6 rounded-lg bg-primary px-6 py-2 text-sm font-medium text-white hover:bg-primary-dark transition-colors"
        >
          重试
        </button>
      </div>
    </div>
  );
}
