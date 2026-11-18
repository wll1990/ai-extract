'use client';

import React, { useEffect, useRef, useState } from 'react';
import { Check, Copy, Warning } from '@phosphor-icons/react';
import { Button, type ButtonProps } from '@/components/ui/Button';
import { copyToClipboard } from '@/lib/clipboard';

export interface CopyButtonProps extends Omit<ButtonProps, 'onClick' | 'children'> {
  text: string;
  label?: string;
  copiedLabel?: string;
  /** 复制结果回调：true=成功，false=失败（用于父级 Toast/日志） */
  onResult?: (ok: boolean) => void;
}

/**
 * 带反馈的复制按钮：成功显示「已复制 ✓」，失败提示手动复制。
 * 内部使用 copyToClipboard，HTTPS 与 HTTP 部署都有兜底。
 */
export function CopyButton({
  text,
  label = '复制',
  copiedLabel = '已复制',
  onResult,
  variant = 'secondary',
  size = 'sm',
  ...rest
}: CopyButtonProps) {
  const [state, setState] = useState<'idle' | 'ok' | 'fail'>('idle');
  const timer = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
  }, []);

  const onClick = async () => {
    const ok = await copyToClipboard(text);
    setState(ok ? 'ok' : 'fail');
    onResult?.(ok);
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setState('idle'), 1600);
  };

  return (
    <Button variant={variant} size={size} onClick={onClick} aria-live="polite" {...rest}>
      {state === 'ok' ? <Check size={14} weight="bold" /> : state === 'fail' ? <Warning size={14} /> : <Copy size={14} />}
      {state === 'ok' ? copiedLabel : state === 'fail' ? '复制失败' : label}
    </Button>
  );
}
