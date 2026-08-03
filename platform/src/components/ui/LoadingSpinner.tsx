/**
 * 通用加载微调器组件
 */
import React from 'react';

interface LoadingSpinnerProps {
  fullScreen?: boolean;
  size?: string;
}

export function LoadingSpinner({ fullScreen = true, size = 'h-10 w-10' }: LoadingSpinnerProps) {
  const spinner = (
    <div className={`${size} animate-spin rounded-full border-4 border-muted-foreground-2 border-t-navy`} />
  );

  if (!fullScreen) return spinner;

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      {spinner}
    </div>
  );
}
