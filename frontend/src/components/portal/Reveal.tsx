'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';

type Tag = 'div' | 'article' | 'section';

/**
 * 进入视口时淡入上移。
 *
 * 默认可见：只有「确实在视口下方」的内容才先隐藏等待动画，避免 JS/观察器异常时整块内容不可见。
 */
export function Reveal({
  children,
  delay = 0,
  className = '',
  as = 'div',
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
  /** 作为网格子项时用 article，避免多套一层 div 破坏 :nth-child 规则 */
  as?: Tag;
}) {
  const ref = useRef<HTMLElement>(null);
  const [state, setState] = useState<'visible' | 'pending'>('visible');

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;

    const rect = el.getBoundingClientRect();
    const inView = rect.top < window.innerHeight * 0.95 && rect.bottom > 0;
    if (inView) return;

    setState('pending');

    const io = new IntersectionObserver(
      entries => {
        if (entries.some(e => e.isIntersecting)) {
          setState('visible');
          io.disconnect();
        }
      },
      { rootMargin: '0px 0px -10% 0px' },
    );
    io.observe(el);

    // 兜底：观察器异常或被跳过时，最多等 2.5s 也要显示
    const fallback = window.setTimeout(() => setState('visible'), 2500);

    return () => {
      io.disconnect();
      window.clearTimeout(fallback);
    };
  }, []);

  const Tag = as as 'div';

  return (
    <Tag
      ref={ref as React.RefObject<HTMLDivElement>}
      className={`reveal ${state === 'visible' ? 'in' : ''} ${className}`.trim()}
      style={state === 'pending' ? { transitionDelay: `${delay}ms` } : undefined}
    >
      {children}
    </Tag>
  );
}
