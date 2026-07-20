'use client';

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

export type Theme = 'light' | 'dark' | 'claude';

interface ThemeContextValue {
  theme: Theme;
  setTheme: (t: Theme) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'light',
  setTheme: () => {},
});

export function useTheme() {
  return useContext(ThemeContext);
}

const STORAGE_KEY = 'mindsmith-theme';
const VALID_THEMES: Theme[] = ['light', 'dark', 'claude'];

function isValidTheme(t: string | null): t is Theme {
  return t !== null && VALID_THEMES.includes(t as Theme);
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>('light');

  // 初始化时从 localStorage 读取
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const stored = localStorage.getItem(STORAGE_KEY);
    if (isValidTheme(stored)) {
      setThemeState(stored);
    }
  }, []);

  // 主题变更时同步 class 和 localStorage
  const setTheme = (t: Theme) => {
    setThemeState(t);
    document.documentElement.classList.remove('theme-dark', 'theme-claude');
    if (t !== 'light') {
      document.documentElement.classList.add(`theme-${t}`);
    }
    localStorage.setItem(STORAGE_KEY, t);
  };

  // 初始同步 DOM class（避免首次渲染闪烁）
  useEffect(() => {
    if (typeof window === 'undefined') return;
    document.documentElement.classList.remove('theme-dark', 'theme-claude');
    if (theme !== 'light') {
      document.documentElement.classList.add(`theme-${theme}`);
    }
  }, [theme]);

  return (
    <ThemeContext.Provider value={{ theme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}
