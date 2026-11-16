'use client';

import { useRouter } from 'next/navigation';
import { ArrowRight, Sparkle } from '@phosphor-icons/react';

function scrollTo(id: string) {
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  document.getElementById(id)?.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
}

export function PortalHeader({ loggedIn }: { loggedIn: boolean }) {
  const router = useRouter();

  return (
    <header className="public-header">
      <button className="brand" type="button" onClick={() => router.push('/portal')}>
        <span className="brand-mark"><Sparkle weight="fill" /></span>
        <span>MindForge</span>
        <small>销冠门户</small>
      </button>

      <nav>
        <button type="button" onClick={() => scrollTo('capabilities')}>核心能力</button>
        <button type="button" onClick={() => scrollTo('how')}>如何工作</button>
        <button type="button" onClick={() => scrollTo('demo')}>现场演示</button>
        <button type="button" onClick={() => scrollTo('setup')}>怎么接入</button>
        <button type="button" onClick={() => scrollTo('contact')}>预约</button>
      </nav>

      <div>
        {loggedIn ? (
          <button className="button button-primary button-small" type="button" onClick={() => router.push('/')}>
            进入系统 <ArrowRight />
          </button>
        ) : (
          <>
            <button className="button button-ghost" type="button" onClick={() => router.push('/login?redirect=/portal')}>
              登录
            </button>
            <button
              className="button button-primary button-small"
              type="button"
              onClick={() => router.push('/login?redirect=/portal')}
            >
              进入系统 <ArrowRight />
            </button>
          </>
        )}
      </div>
    </header>
  );
}
