'use client';

import React from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'neutral' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md';

const BASE =
  'inline-flex items-center justify-center gap-1.5 rounded-[10px] font-medium leading-none transition-colors ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1 ' +
  'focus-visible:ring-offset-surface disabled:cursor-not-allowed disabled:opacity-45';

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-white hover:bg-primary-hover',
  // 内容工具用浅蓝底，和白色次级按钮拉开层级
  secondary: 'border border-primary bg-primary-light text-primary hover:border-primary-hover',
  neutral: 'border border-border-strong bg-surface text-foreground hover:bg-surface-2',
  ghost: 'text-muted-foreground hover:bg-surface-2 hover:text-foreground',
  danger: 'border border-danger bg-surface text-danger hover:bg-danger-bg',
};

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-[13px]',
  md: 'h-9 px-4 text-sm',
};

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

/**
 * 管理后台统一按钮。
 *
 * <p>之前各页面用 rounded-full + 不同高度/字号拼样式，同一排按钮看起来像三种控件。
 * 这里收敛为「一种形状、五个语义」：primary 只给当前页面的主操作，
 * secondary 给内容工具，neutral 给次级动作，ghost 给表格内联，danger 给不可逆动作。</p>
 */
export function Button({ variant = 'neutral', size = 'md', className = '', type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={`${BASE} ${VARIANTS[variant]} ${SIZES[size]} ${className}`}
      {...rest}
    />
  );
}
