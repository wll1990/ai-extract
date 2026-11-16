'use client';

import { useEffect, useState } from 'react';
import { ArrowUp, PlayCircle } from '@phosphor-icons/react';

/**
 * 门户右侧悬浮导航。
 *
 * 页面很长（桌面 6000px+、手机更甚），顶部导航在 ≤1100px 时是隐藏的，
 * 滚到中段以后用户既回不到演示区，也回不到顶部。这里补两个动作。
 *
 * 只在滚过首屏后出现，避免压住首屏主视觉。
 */
export function PortalQuickNav({ onDemo }: { onDemo: () => void }) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > 700);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const toTop = () => {
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
  };

  return (
    <div className={`quick-nav ${visible ? 'in' : ''}`} aria-hidden={!visible}>
      <button type="button" className="quick-nav-btn" onClick={onDemo} tabIndex={visible ? 0 : -1}>
        <PlayCircle weight="fill" />
        看演示
      </button>
      <button type="button" className="quick-nav-btn" onClick={toTop} tabIndex={visible ? 0 : -1}>
        <ArrowUp weight="bold" />
        回到顶部
      </button>
    </div>
  );
}
