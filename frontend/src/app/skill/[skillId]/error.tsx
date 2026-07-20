'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

export default function SkillChatError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const router = useRouter();

  useEffect(() => {
    console.error('分身加载失败:', error);
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <div className="text-center">
        <span className="text-5xl">🤖</span>
        <h2 className="mt-4 text-lg font-semibold text-foreground">分身加载失败</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          {error.message || '可能不存在或已被移除'}
        </p>
        <div className="mt-6 flex justify-center gap-3">
          <button
            onClick={reset}
            className="rounded-lg bg-primary px-6 py-2 text-sm font-medium text-white hover:bg-primary-dark transition-colors"
          >
            重试
          </button>
          <button
            onClick={() => router.push('/skills')}
            className="rounded-lg border border-border px-6 py-2 text-sm font-medium text-foreground hover:bg-surface-2 transition-colors"
          >
            返回广场
          </button>
        </div>
      </div>
    </div>
  );
}
