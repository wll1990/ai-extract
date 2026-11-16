'use client';

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { MagnifyingGlass } from '@phosphor-icons/react';
import { DOMAIN_TITLE, type NavItem } from '@/lib/nav';
import { NavIcon } from './NavIcon';

/**
 * 快捷入口（⌘K）— 只搜当前权限可见的菜单项，不发请求。
 * 回车跳转，↑↓ 选择，Esc 关闭。
 */
export function CommandPalette({
  open,
  onClose,
  items,
}: {
  open: boolean;
  onClose: () => void;
  items: NavItem[];
}) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [keyword, setKeyword] = useState('');
  const [active, setActive] = useState(0);

  useEffect(() => {
    if (!open) return;
    setKeyword('');
    setActive(0);
    const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(timer);
  }, [open]);

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return items;
    return items.filter(i =>
      i.label.toLowerCase().includes(kw)
      || i.path.toLowerCase().includes(kw)
      || DOMAIN_TITLE[i.domain].includes(kw));
  }, [keyword, items]);

  useEffect(() => { setActive(0); }, [keyword]);

  if (!open) return null;

  const go = (item: NavItem) => {
    onClose();
    router.push(item.path);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive(a => Math.min(a + 1, Math.max(0, filtered.length - 1)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive(a => Math.max(0, a - 1));
    } else if (e.key === 'Enter') {
      const target = filtered[active];
      if (target) go(target);
    } else if (e.key === 'Escape') {
      onClose();
    }
  };

  return (
    <div
      className="fixed inset-0 z-[240] flex items-start justify-center bg-slate-900/30 px-4 pt-[12vh]"
      onClick={onClose}
      role="presentation"
    >
      <div
        className="w-[520px] max-w-full overflow-hidden rounded-xl border border-border bg-surface-2 shadow-2xl"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="快捷入口"
      >
        <div className="flex items-center gap-2 border-b border-border px-4 py-3">
          <MagnifyingGlass size={16} className="text-muted-foreground" />
          <input
            ref={inputRef}
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="搜索页面、功能…"
            aria-label="搜索页面"
            className="flex-1 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
          />
          <kbd className="rounded border border-border px-1.5 py-0.5 text-[10px] text-muted-foreground">ESC</kbd>
        </div>
        <div className="max-h-[360px] overflow-y-auto p-1.5">
          {filtered.length === 0 && (
            <p className="px-3 py-6 text-center text-xs text-muted-foreground">没有匹配的页面</p>
          )}
          {filtered.map((item, i) => (
            <button
              key={item.path + item.label}
              type="button"
              onMouseEnter={() => setActive(i)}
              onClick={() => go(item)}
              className={`flex w-full items-center gap-3 rounded-md px-3 py-2 text-left text-[13px] ${
                i === active ? 'bg-primary-light text-primary' : 'text-foreground'
              }`}
            >
              <NavIcon label={item.label} />
              <span className="flex-1 truncate">{item.label}</span>
              <span className="text-[10px] text-muted-foreground">{DOMAIN_TITLE[item.domain]}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
