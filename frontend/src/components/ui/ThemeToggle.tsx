'use client';

import React from 'react';
import { useTheme, type Theme } from '@/lib/theme';

const THEME_OPTIONS: { value: Theme; label: string; icon: string }[] = [
  { value: 'light', label: '浅色', icon: '☀️' },
  { value: 'dark', label: '专注', icon: '🌙' },
  { value: 'claude', label: '人文', icon: '🔥' },
];

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();

  return (
    <div className="inline-flex rounded-lg bg-primary-light p-0.5" role="radiogroup" aria-label="主题切换">
      {THEME_OPTIONS.map((opt) => (
        <button
          key={opt.value}
          type="button"
          role="radio"
          aria-checked={theme === opt.value}
          onClick={() => setTheme(opt.value)}
          className={`rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${
            theme === opt.value
              ? 'bg-surface-2 text-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          }`}
          title={opt.label}
        >
          {opt.icon}
        </button>
      ))}
    </div>
  );
}
