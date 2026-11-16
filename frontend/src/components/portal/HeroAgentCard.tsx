'use client';

import { Fragment, useEffect, useRef, useState } from 'react';
import { Check, Sparkle, UsersThree } from '@phosphor-icons/react';

/**
 * 首屏的「执行轨迹」卡片。
 *
 * 循环播放：理解问题 → 匹配经验 → 形成建议，停在完成态 2.6 秒，清空再从头来。
 *
 * 为什么循环而不是跑一次就停：这块是首屏唯一的动态元素，跑完就死会让人以为是截图；
 * 循环播放更像一段"正在使用"的录屏，访客随时进页面都能看到完整过程。
 *
 * 两个约束：
 * - 卡片滚出视口或标签页切走时暂停，不空耗 CPU；
 * - prefers-reduced-motion 下直接停在全完成态，不做动画。
 */

const STEPS = ['理解问题', '匹配经验', '形成建议'];
const START_DELAY_MS = 400;
const STEP_MS = 700;
const HOLD_MS = 2600;
const RESET_MS = 500;

export function HeroAgentCard() {
  const [progress, setProgress] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const reduce =
      typeof window !== 'undefined' &&
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (reduce) {
      setProgress(STEPS.length);
      return;
    }

    let stopped = false;

    const clear = () => {
      if (timer.current) {
        clearTimeout(timer.current);
        timer.current = null;
      }
    };

    const schedule = (ms: number, fn: () => void) => {
      clear();
      timer.current = setTimeout(() => {
        if (!stopped) fn();
      }, ms);
    };

    const run = (next: number) => {
      setProgress(next);
      if (next < STEPS.length) {
        schedule(STEP_MS, () => run(next + 1));
      } else {
        schedule(HOLD_MS, () => {
          setProgress(-1);
          schedule(RESET_MS, () => run(0));
        });
      }
    };

    const start = () => {
      clear();
      setProgress(-1);
      schedule(START_DELAY_MS, () => run(0));
    };

    const pause = () => clear();

    let io: IntersectionObserver | null = null;
    const el = rootRef.current;
    if (el && typeof IntersectionObserver !== 'undefined') {
      io = new IntersectionObserver(
        entries => {
          if (entries.some(e => e.isIntersecting)) start();
          else pause();
        },
        { threshold: 0.3 },
      );
      io.observe(el);
    } else {
      start();
    }

    const onVisibility = () => {
      if (document.hidden) pause();
      else start();
    };
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      stopped = true;
      clear();
      io?.disconnect();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  const done = progress >= STEPS.length;

  return (
    <div className="dark-agent-card" ref={rootRef}>
      <header>
        <span className="live-dot" />
        {done ? '回答已生成' : 'Agent 正在执行'}
      </header>
      <strong>关键人支持，但采购一直拖延。下一步应该推进谁？</strong>
      <div className={`trace-line ${progress < 0 ? 'idle' : ''}`}>
        {STEPS.map((label, i) => {
          const isDone = i < progress;
          const isRunning = i === progress;
          return (
            <Fragment key={label}>
              {i > 0 && <i />}
              <span className={isDone ? 'done' : isRunning ? 'running' : ''}>
                {isDone ? <Check /> : <Sparkle />}
                {label}
              </span>
            </Fragment>
          );
        })}
      </div>
      <small><UsersThree />已调用 3 条已确认经验</small>
    </div>
  );
}
