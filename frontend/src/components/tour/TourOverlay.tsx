'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { usePathname } from 'next/navigation';
import { useTour } from './TourProvider';
import { TourCard } from './TourCard';

interface Rect { left: number; top: number; width: number; height: number }

const EXCLUDED = ['/login', '/register', '/portal'];
const EXCLUDED_PREFIX = ['/s/', '/h5/', '/i/', '/public/'];

const CARD_W = 360;
const CARD_H = 280;
const MARGIN = 12;

function clamp(v: number, min: number, max: number) {
  return Math.max(min, Math.min(max, v));
}

/** 只高亮视口内的交集；目标比屏幕大时也不会把洞开在屏幕外 */
function measure(el: Element): Rect {
  const r = el.getBoundingClientRect();
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const left = clamp(r.left, 8, vw - 8);
  const top = clamp(r.top, 8, vh - 8);
  const right = clamp(r.right, 8, vw - 8);
  const bottom = clamp(r.bottom, 8, vh - 8);
  return {
    left,
    top,
    width: Math.max(1, right - left),
    height: Math.max(1, bottom - top),
  };
}

function cardPosition(rect: Rect | null, placement: string | undefined) {
  const vw = window.innerWidth;
  const vh = window.innerHeight;

  // 窄屏：抽屉要避开目标。目标在下半屏就把抽屉放顶部，反之放底部。
  if (vw < 1024) {
    const targetMid = rect ? rect.top + rect.height / 2 : vh / 2;
    const base = { left: 8, width: vw - 16, maxHeight: '40vh', overflowY: 'auto' as const };
    return targetMid > vh * 0.45
      ? { ...base, top: 8 }
      : { ...base, bottom: 8 };
  }
  const safe = { maxHeight: 'calc(100vh - 24px)', overflowY: 'auto' as const };
  if (!rect || placement === 'center') {
    return { left: (vw - CARD_W) / 2, top: (vh - CARD_H) / 2, ...safe };
  }

  const spaceBelow = vh - rect.top - rect.height;
  const spaceAbove = rect.top;
  const spaceRight = vw - rect.left - rect.width;
  const spaceLeft = rect.left;

  if (placement === 'right' && spaceRight > CARD_W + MARGIN * 2) {
    return { left: rect.left + rect.width + MARGIN, top: clamp(rect.top, MARGIN, vh - CARD_H - MARGIN) };
  }
  if (placement === 'left' && spaceLeft > CARD_W + MARGIN * 2) {
    return { left: rect.left - CARD_W - MARGIN, top: clamp(rect.top, MARGIN, vh - CARD_H - MARGIN) };
  }
  if (placement === 'top' && spaceAbove > CARD_H + MARGIN * 2) {
    return {
      left: clamp(rect.left + rect.width / 2 - CARD_W / 2, MARGIN, vw - CARD_W - MARGIN),
      top: rect.top - CARD_H - MARGIN,
    };
  }
  if (spaceBelow > CARD_H + MARGIN * 2 || spaceAbove <= CARD_H + MARGIN * 2) {
    return {
      left: clamp(rect.left + rect.width / 2 - CARD_W / 2, MARGIN, vw - CARD_W - MARGIN),
      top: clamp(rect.top + rect.height + MARGIN, MARGIN, vh - CARD_H - MARGIN),
    };
  }
  return {
    left: clamp(rect.left + rect.width / 2 - CARD_W / 2, MARGIN, vw - CARD_W - MARGIN),
    top: rect.top - CARD_H - MARGIN,
  };
}

export function TourOverlay() {
  const {
    running, minimized, paused, step, chapter, chapters, stepIndex,
    retryNonce, next, prev, close, minimize, resume, retry, finished, replay,
  } = useTour();
  const pathname = usePathname();
  const [rect, setRect] = useState<Rect | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const cardRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const lastFocusRef = useRef<HTMLElement | null>(null);
  const prevRunningRef = useRef(false);

  // 关闭导览后把焦点还给触发入口，避免键盘用户掉到 body
  useEffect(() => {
    if (running && !prevRunningRef.current) {
      lastFocusRef.current = document.activeElement as HTMLElement | null;
    }
    if (!running && prevRunningRef.current && !finished) {
      lastFocusRef.current?.focus?.();
    }
    prevRunningRef.current = running;
  }, [running, finished]);

  const chapterIndex = chapter ? chapters.findIndex(c => c.id === chapter.id) : 0;
  const isLast = chapterIndex === chapters.length - 1
    && !!chapter && stepIndex === chapter.steps.length - 1;

  const measureTarget = useCallback(() => {
    if (!step?.target) { setRect(null); setNotFound(false); return; }
    const el = document.querySelector(`[data-tour="${step.target}"]`);
    if (!el) return false;
    const r0 = el.getBoundingClientRect();
    // 元素存在但不可见（display:none / 0×0）等同于没找到
    if (r0.width < 2 && r0.height < 2) return false;
    el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' });
    setNotFound(false);
    requestAnimationFrame(() => {
      document.fonts?.ready.then(() => {
        const node = document.querySelector(`[data-tour="${step.target}"]`);
        if (node) setRect(measure(node));
      }).catch(() => {});
      setRect(measure(el));
    });
    return true;
  }, [step?.target]);

  // 等元素：路由 + 属性变化 + 10 秒超时
  useEffect(() => {
    if (!running || minimized) return;
    if (!step?.target) { setRect(null); setNotFound(false); return; }

    let done = false;
    let raf = 0;
    let timer = 0;
    const root = rootRef.current;

    const attempt = () => {
      if (done) return;
      if (measureTarget()) {
        done = true;
        observer.disconnect();
        clearTimeout(timer);
      }
    };

    const observer = new MutationObserver(records => {
      // 忽略导览自身节点变化，避免测量→改样式→再测量的循环
      if (root && records.every(r => root.contains(r.target as Node))) return;
      if (raf) return;
      raf = requestAnimationFrame(() => { raf = 0; attempt(); });
    });

    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['data-tour', 'data-tour-state'],
    });
    timer = window.setTimeout(() => {
      if (!done) { setNotFound(true); observer.disconnect(); }
    }, 10_000);

    attempt();

    return () => {
      done = true;
      observer.disconnect();
      cancelAnimationFrame(raf);
      clearTimeout(timer);
    };
  }, [running, minimized, pathname, step?.id, step?.target, retryNonce, measureTarget]);

  // 滚动/尺寸变化时跟随
  useEffect(() => {
    if (!running || minimized || !step?.target) return;
    let raf = 0;
    const onMove = () => {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        const el = document.querySelector(`[data-tour="${step.target}"]`);
        if (el) setRect(measure(el));
      });
    };
    window.addEventListener('scroll', onMove, true);
    window.addEventListener('resize', onMove);
    return () => {
      window.removeEventListener('scroll', onMove, true);
      window.removeEventListener('resize', onMove);
      cancelAnimationFrame(raf);
    };
  }, [running, minimized, step?.target]);

  // 只要视觉上锁屏（概念卡/暂停/未找到），就必须同时锁住背景交互
  useEffect(() => {
    if (!running || minimized) return;
    const shell = document.getElementById('app-shell');
    if (!shell) return;
    const modal = step?.interaction === 'modal' || Boolean(paused) || notFound || finished;
    if (modal) shell.setAttribute('inert', '');
    else shell.removeAttribute('inert');
    return () => shell.removeAttribute('inert');
  }, [running, minimized, step?.id, step?.interaction, paused, notFound, finished]);

  // 进入步骤前执行声明式动作：切视图、点安全目标
  useEffect(() => {
    if (!running || minimized || !step?.actions?.length) return;
    const timers: number[] = [];
    step.actions.forEach((action, i) => {
      timers.push(window.setTimeout(() => {
        if (action.type === 'set-view') {
          window.dispatchEvent(new CustomEvent('tour:set-view', {
            detail: { view: action.view, sub: action.sub },
          }));
          return;
        }
        const el = document.querySelector<HTMLElement>(`[data-tour="${action.target}"]`);
        if (!el) return;
        const isWrite = el.hasAttribute('data-tour-write')
          || el.getAttribute('type') === 'submit'
          || Boolean(el.closest('form'));
        if (!el.hasAttribute('data-tour-safe') || isWrite) {
          // 导览绝不自动触发写操作
          console.warn('[tour] 跳过非安全点击', action.target);
          return;
        }
        el.click();
      }, 150 + i * 150));
    });
    return () => timers.forEach(clearTimeout);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running, minimized, step?.id]);

  // 键盘：仅焦点在导览内生效
  useEffect(() => {
    if (!running || minimized) return;
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const inTour = rootRef.current?.contains(target as Node);
      if (!inTour) return;
      const editable = target?.matches('input, textarea, select, [contenteditable="true"], [role="combobox"], [role="listbox"], [role="menu"], [role="slider"], [role="radiogroup"], [role="tablist"]');
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopImmediatePropagation();
        close();
        return;
      }
      if (editable || e.isComposing) return;
      if (e.key === 'ArrowRight') { e.preventDefault(); next(); }
      if (e.key === 'ArrowLeft') { e.preventDefault(); prev(); }
    };
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [running, minimized, close, next, prev]);

  // 步骤切换后把焦点移到卡片
  useEffect(() => {
    if (!running || minimized) return;
    const t = window.setTimeout(() => cardRef.current?.focus(), 60);
    return () => clearTimeout(t);
  }, [running, minimized, step?.id]);

  if (!mounted) return null;
  const isPublic = EXCLUDED.some(p => pathname === p || pathname.startsWith(p + '/'))
    || EXCLUDED_PREFIX.some(p => pathname.startsWith(p));
  if (isPublic) return null;

  if (finished) {
    return createPortal(
      <div className="fixed inset-0 z-[201] flex items-center justify-center p-4"
        style={{ backgroundColor: 'var(--tour-scrim, rgba(15,12,30,.62))' }}>
        <div role="dialog" aria-modal="true" aria-labelledby="tour-finished-title" tabIndex={-1}
          className="w-[360px] max-w-full rounded-xl border border-border bg-surface-2 p-5 shadow-2xl">
          <h3 id="tour-finished-title" className="mb-1.5 text-sm font-semibold text-foreground">导览完成</h3>
          <p className="text-[13px] leading-relaxed text-muted-foreground">
            可以再看一遍，也可以从侧栏「帮助与学习 → 产品导览」挑任意一章重看，不限次数。
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <button type="button" onClick={close}
              className="rounded border border-border px-3 py-1.5 text-xs text-muted-foreground">
              关闭
            </button>
            <button type="button" onClick={replay} autoFocus
              className="rounded bg-primary px-3 py-1.5 text-xs font-medium text-white">
              再看一遍
            </button>
          </div>
        </div>
      </div>,
      document.body,
    );
  }

  if (!running || !chapter || !step) return null;

  if (minimized) {
    return (
      <button
        type="button"
        onClick={resume}
        className="fixed bottom-4 right-4 z-[201] rounded-full bg-primary px-4 py-2 text-xs font-medium text-white shadow-lg"
      >
        ▶ 继续导览 {chapterIndex + 1}/{chapters.length}
      </button>
    );
  }

  const isModal = step.interaction === 'modal';
  const blocking = isModal || Boolean(paused) || notFound;
  const pos = cardPosition(rect, step.placement);

  return createPortal(
    <div ref={rootRef} className="fixed inset-0 z-[200] pointer-events-none" data-tour-root>
      {blocking && (
        <div className="absolute inset-0 pointer-events-auto" aria-hidden="true"
          style={{ backgroundColor: 'var(--tour-scrim, rgba(15,12,30,.62))' }} />
      )}

      {!blocking && rect && (
        <div
          aria-hidden="true"
          data-tour-spotlight
          className="absolute rounded-lg pointer-events-none transition-all duration-150"
          style={{
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height,
            boxShadow: '0 0 0 9999px var(--tour-scrim, rgba(15,12,30,.62))',
            outline: '2px solid var(--tour-ring, #3b82f6)',
            outlineOffset: '1px',
          }}
        />
      )}

      <TourCard
        cardRef={cardRef}
        chapter={chapter}
        step={step}
        stepIndex={stepIndex}
        chapterIndex={chapterIndex}
        chapterCount={chapters.length}
        style={pos}
        missing={notFound}
        paused={paused}
        modal={blocking}
        onNext={next}
        onPrev={prev}
        onClose={close}
        onMinimize={minimize}
        onRetry={retry}
        onResume={resume}
        isLast={isLast}
      />
    </div>,
    document.body,
  );
}
