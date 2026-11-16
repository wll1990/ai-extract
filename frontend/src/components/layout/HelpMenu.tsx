'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { BookOpen, CaretDown, Command, Compass, Question } from '@phosphor-icons/react';
import { TourCenterPanel } from '@/components/tour/TourCenterPanel';
import { useTour } from '@/components/tour/TourProvider';

const rowClass = 'flex h-9 w-full items-center gap-2.5 rounded-md px-2.5 text-left text-[12.5px] text-muted-foreground hover:bg-primary-light/60';

/**
 * 侧栏底部「帮助与学习」。
 *
 * 导览 / 手册 / FAQ / 快捷入口都是「辅助」入口，收进一个 popover，
 * 避免底部栏变成第二个业务菜单。导览被启动后自动收起。
 */
export function HelpMenu({ onOpenPalette }: { onOpenPalette: () => void }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [tourOpen, setTourOpen] = useState(false);
  const { running } = useTour();

  useEffect(() => {
    if (!running) return;
    setOpen(false);
    setTourOpen(false);
  }, [running]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { setOpen(false); setTourOpen(false); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const close = () => { setOpen(false); setTourOpen(false); };

  return (
    <div className="relative border-t border-border p-2.5">
      {open && (
        <>
          <div className="fixed inset-0 z-[90]" onClick={close} />
          <div
            className="absolute bottom-full left-2.5 z-[100] mb-2 w-[260px] rounded-xl border border-border bg-surface-2 p-2 shadow-xl"
            role="menu"
          >
            {tourOpen ? (
              <>
                <button
                  type="button"
                  onClick={() => setTourOpen(false)}
                  className="mb-1 text-[11px] text-muted-foreground hover:text-foreground"
                >
                  ← 返回
                </button>
                <TourCenterPanel onClose={close} />
              </>
            ) : (
              <>
                <button role="menuitem" onClick={() => setTourOpen(true)} className={rowClass}>
                  <Compass size={15} className="text-muted-foreground" />
                  <span className="flex-1">产品导览</span>
                  <span className="text-[10px] text-muted-foreground">重看 / 续播</span>
                </button>
                <button
                  role="menuitem"
                  onClick={() => { close(); router.push('/help?doc=manual'); }}
                  className={rowClass}
                >
                  <BookOpen size={15} className="text-muted-foreground" />
                  <span className="flex-1">操作手册</span>
                </button>
                <button
                  role="menuitem"
                  onClick={() => { close(); router.push('/help#faq'); }}
                  className={rowClass}
                >
                  <Question size={15} className="text-muted-foreground" />
                  <span className="flex-1">常见问题</span>
                </button>
                <button
                  role="menuitem"
                  onClick={() => { close(); onOpenPalette(); }}
                  className={rowClass}
                >
                  <Command size={15} className="text-muted-foreground" />
                  <span className="flex-1">快捷入口</span>
                  <kbd className="rounded border border-border px-1 text-[10px] leading-4 text-muted-foreground">⌘K</kbd>
                </button>
              </>
            )}
          </div>
        </>
      )}

      <button
        type="button"
        onClick={() => setOpen(v => { const next = !v; if (!next) setTourOpen(false); return next; })}
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex h-8 w-full items-center gap-2 rounded-md px-2.5 text-[12.5px] text-muted-foreground transition-colors hover:bg-primary-light/60"
      >
        <Question size={15} />
        <span className="flex-1 text-left">帮助与学习</span>
        <CaretDown size={12} />
      </button>
    </div>
  );
}
