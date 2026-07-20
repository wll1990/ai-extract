/**
 * 通用加载微调器组件
 *
 * 替代以前12个文件中重复的spinner div。
 *
 * @since 2026-07-01
 */
import React from 'react';

interface LoadingSpinnerProps {
  /** 是否全屏居中，默认 true */
  fullScreen?: boolean;
  /** 自定义尺寸，默认 h-10 w-10 */
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
