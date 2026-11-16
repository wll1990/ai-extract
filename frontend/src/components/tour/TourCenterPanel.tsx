'use client';

import React from 'react';
import { getUser } from '@/lib/storage';
import { pickTrack, TRACKS, visibleChapters } from '@/lib/tour';
import { useTour } from './TourProvider';

/**
 * 导览中心内容 — 章节列表 / 继续上次 / 从头开始 / 重置进度。
 * 由「帮助与学习」弹出，不自己定位容器。
 */
export function TourCenterPanel({ onClose }: { onClose: () => void }) {
  const { start, running, chapter, chapters, hasProgress, reset, startError } = useTour();

  const user = getUser() as { permissions?: string[]; companyId?: string } | null;
  const perms = user?.permissions || [];
  const track = pickTrack(perms, user?.companyId);
  const list = visibleChapters(track, perms);
  const currentIndex = running && chapter ? chapters.findIndex(c => c.id === chapter.id) : -1;

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-semibold text-foreground">
          产品导览 · {list.length} 章
          {running && currentIndex >= 0 ? ` · 进行中 ${currentIndex + 1}` : ''}
        </span>
        <button type="button" onClick={onClose}
          className="text-xs text-muted-foreground hover:text-foreground" aria-label="关闭">✕</button>
      </div>
      {startError && (
        <p className="mb-2 rounded bg-red-50 px-2 py-1.5 text-[11px] text-red-600">
          {startError}
        </p>
      )}
      {hasProgress && (
        <button type="button" onClick={() => start(track)}
          className="mb-2 w-full rounded bg-primary-light px-2 py-1.5 text-left text-xs font-medium text-primary">
          ▶ 继续上次导览
        </button>
      )}
      <p className="mb-2 text-[11px] text-muted-foreground">
        随便挑一章看，不限次数。
      </p>
      <div className="max-h-[320px] space-y-1 overflow-y-auto">
        {list.map((c, i) => (
          <button
            key={c.id}
            type="button"
            onClick={() => start(track, c.id)}
            className="block w-full rounded px-2 py-1.5 text-left text-xs text-foreground hover:bg-primary-light"
            title={c.summary}
          >
            {String(i + 1).padStart(2, '0')} · {c.title}
          </button>
        ))}
      </div>
      <div className="mt-2 flex items-center justify-between border-t border-border pt-2">
        <button type="button" onClick={() => { reset(); void start(track, TRACKS[track][0]); }}
          className="text-xs text-primary">从头开始</button>
        <div className="flex items-center gap-2">
          <a href="/help" className="text-xs text-muted-foreground hover:text-primary">图文手册</a>
          {hasProgress && (
            <button type="button" onClick={() => { reset(); onClose(); }}
              className="text-xs text-muted-foreground hover:text-primary">重置进度</button>
          )}
        </div>
      </div>
    </div>
  );
}
