'use client';

import React, {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { getUser } from '@/lib/storage';
import {
  chaptersForData, fillRoute, pickTrack, visibleChapters,
} from '@/lib/tour';
import { CHAPTER_MAP } from '@/lib/tour/chapters';
import { resolveTourData } from '@/lib/tour/resolve';
import {
  DEMO_COMPANY_ID, TOUR_VERSION,
  type TourChapter, type TourData, type TourStep, type TrackId,
} from '@/lib/tour/types';
import { TourOverlay } from './TourOverlay';

interface Progress {
  version: number;
  track: TrackId;
  chapterId: string;
  stepIndex: number;
}

interface TourContextValue {
  ready: boolean;
  running: boolean;
  minimized: boolean;
  paused: string | null;
  track: TrackId;
  chapter: TourChapter | null;
  step: TourStep | null;
  stepIndex: number;
  chapters: TourChapter[];
  data: TourData;
  missing: boolean;
  retryNonce: number;
  startError: string | null;
  finished: boolean;
  replay: () => void;
  start: (track?: TrackId, chapterId?: string) => void;
  startChapter: (chapterId: string) => void;
  next: () => void;
  prev: () => void;
  close: () => void;
  minimize: () => void;
  resume: () => void;
  retry: () => void;
  reset: () => void;
  hasProgress: boolean;
}

const TourContext = createContext<TourContextValue | null>(null);

const EXCLUDED = ['/login', '/register', '/portal'];
const EXCLUDED_PREFIX = ['/s/', '/h5/', '/i/', '/public/'];

function progressKey(userId: string) {
  return `tour.progress.${userId}`;
}

function readProgress(userId: string | undefined): Progress | null {
  if (!userId || typeof window === 'undefined') return null;
  try {
    const raw = localStorage.getItem(progressKey(userId));
    if (!raw) return null;
    const p = JSON.parse(raw) as Partial<Progress>;
    if (p?.version !== TOUR_VERSION) return null;
    if (!p.track || !p.chapterId || typeof p.stepIndex !== 'number'
      || !Number.isInteger(p.stepIndex) || p.stepIndex < 0) return null;
    return p as Progress;
  } catch { return null; }
}

export function TourProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [mounted, setMounted] = useState(false);
  const [running, setRunning] = useState(false);
  const [minimized, setMinimized] = useState(false);
  const [paused, setPaused] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);
  const [finished, setFinished] = useState(false);
  const [retryNonce, setRetryNonce] = useState(0);
  const [track, setTrack] = useState<TrackId>('sales');
  const [chapters, setChapters] = useState<TourChapter[]>([]);
  const [chapterId, setChapterId] = useState<string | null>(null);
  const [stepIndex, setStepIndex] = useState(0);
  const [data, setData] = useState<TourData>({});
  const [progress, setProgress] = useState<Progress | null>(null);
  const [permissions, setPermissions] = useState<string[]>([]);
  const navigatingRef = useRef(false);
  const expectedPathRef = useRef<string | null>(null);

  const isTourable = useMemo(() => {
    if (EXCLUDED.some(p => pathname === p || pathname.startsWith(p + '/'))) return false;
    return !EXCLUDED_PREFIX.some(p => pathname.startsWith(p));
  }, [pathname]);

  const hydrateAuth = useCallback(() => {
    const user = getUser() as { id?: string; permissions?: string[]; companyId?: string } | null;
    setPermissions(user?.permissions || []);
    setProgress(readProgress(user?.id));
    setTrack(pickTrack(user?.permissions || [], user?.companyId));
  }, []);

  useEffect(() => {
    setMounted(true);
    hydrateAuth();
  }, [hydrateAuth]);

  // 登录页 → 可导览页（客户端跳转，Provider 不重挂）时重新读取身份
  useEffect(() => {
    if (isTourable) hydrateAuth();
  }, [isTourable, hydrateAuth]);

  // 换账号 / 登出：清空内存态，避免导览跟着出现在登录页
  useEffect(() => {
    const onLogout = () => {
      setRunning(false);
      setMinimized(false);
      setPaused(null);
      setFinished(false);
      setChapterId(null);
      setStepIndex(0);
      setChapters([]);
      setData({});
      setProgress(null);
      setMissing(false);
      setStartError(null);
      expectedPathRef.current = null;
      navigatingRef.current = false;
    };
    window.addEventListener('auth:logout', onLogout);
    return () => window.removeEventListener('auth:logout', onLogout);
  }, []);

  const chapter = useMemo(
    () => chapters.find(c => c.id === chapterId) || null,
    [chapters, chapterId],
  );
  const step = chapter?.steps[stepIndex] || null;

  const persist = useCallback((p: Progress | null) => {
    setProgress(p);
    const user = getUser() as { id?: string } | null;
    if (!user?.id) return;
    try {
      if (p) localStorage.setItem(progressKey(user.id), JSON.stringify(p));
      else localStorage.removeItem(progressKey(user.id));
    } catch { /* 隐私模式忽略 */ }
  }, []);

  const start = useCallback(async (nextTrack?: TrackId, requestedChapterId?: string) => {
    if (!isTourable) return;
    const user = getUser() as { permissions?: string[]; companyId?: string } | null;
    const perms = user?.permissions || [];
    const t = nextTrack || pickTrack(perms, user?.companyId);
    // 售前投屏只允许在演示企业：真实企业数据（客户名/注册码/通知）不能出现在屏幕上
    if (t === 'sales' && user?.companyId !== DEMO_COMPANY_ID) {
      setStartError('当前是企业真实数据，禁止投屏演示。请登录演示企业账号后再使用售前轨道。');
      return;
    }
    setStartError(null);
    const resolved = await resolveTourData(perms);
    const list = chaptersForData(visibleChapters(t, perms), resolved);
    if (list.length === 0) return;
    const saved = readProgress((user as { id?: string } | null)?.id);
    const requestedIndex = requestedChapterId ? list.findIndex(c => c.id === requestedChapterId) : -1;
    if (requestedChapterId && requestedIndex < 0) {
      setStartError('该章节当前不可用（缺少演示数据或权限不足）');
      return;
    }
    const savedIndex = saved ? list.findIndex(c => c.id === saved.chapterId) : -1;
    const idx = requestedIndex >= 0 ? requestedIndex : savedIndex >= 0 ? savedIndex : 0;
    const maxStep = list[idx].steps.length - 1;
    const targetStep = requestedIndex >= 0 ? 0
      : saved && savedIndex >= 0 ? Math.max(0, Math.min(saved.stepIndex, maxStep)) : 0;
    expectedPathRef.current = null;
    navigatingRef.current = false;
    setData(resolved);
    setTrack(t);
    setChapters(list);
    setChapterId(list[idx].id);
    setStepIndex(targetStep);
    setRunning(true);
    setFinished(false);
    setMinimized(false);
    setPaused(null);
    setMissing(false);
  }, [isTourable]);

  const startChapter = useCallback((id: string) => {
    const idx = chapters.findIndex(c => c.id === id);
    if (idx < 0) return;
    expectedPathRef.current = null;
    navigatingRef.current = false;
    setChapterId(id);
    setStepIndex(0);
    setRunning(true);
    setFinished(false);
    setMinimized(false);
    setPaused(null);
    setMissing(false);
  }, [chapters]);

  // 深链：?tour=<chapterId>，读后从地址栏移除，避免刷新重放
  useEffect(() => {
    if (!mounted || !isTourable) return;
    const params = new URLSearchParams(window.location.search);
    const id = params.get('tour');
    if (!id || !CHAPTER_MAP[id]) return;
    params.delete('tour');
    const qs = params.toString();
    router.replace(pathname + (qs ? `?${qs}` : ''));
    void start(undefined, id);
  }, [mounted, isTourable, start, router, pathname]);

  const next = useCallback(() => {
    if (!chapter) return;
    setPaused(null);
    if (stepIndex < chapter.steps.length - 1) {
      setStepIndex(i => i + 1);
      return;
    }
    const chapterIndex = chapters.findIndex(c => c.id === chapter.id);
    if (chapterIndex >= 0 && chapterIndex < chapters.length - 1) {
      const nextChapter = chapters[chapterIndex + 1];
      setChapterId(nextChapter.id);
      setStepIndex(0);
      return;
    }
    setRunning(false);
    setFinished(true);
    persist(null);
  }, [chapter, chapters, stepIndex, persist]);

  const prev = useCallback(() => {
    setPaused(null);
    if (stepIndex > 0) { setStepIndex(i => i - 1); return; }
    const chapterIndex = chapters.findIndex(c => c.id === chapterId);
    if (chapterIndex > 0) {
      const prevChapter = chapters[chapterIndex - 1];
      setChapterId(prevChapter.id);
      setStepIndex(Math.max(0, prevChapter.steps.length - 1));
    }
  }, [chapters, chapterId, stepIndex]);

  const close = useCallback(() => {
    setRunning(false);
    setFinished(false);
    setMinimized(false);
    setPaused(null);
    expectedPathRef.current = null;
    navigatingRef.current = false;
  }, []);

  const minimize = useCallback(() => {
    setMinimized(true);
    setPaused(null);
  }, []);

  const resume = useCallback(() => {
    setMinimized(false);
    setPaused(null);
    // 用户手动离开路线后点「继续」，要真的回到当前步骤
    const expected = expectedPathRef.current;
    if (paused && expected && pathname !== expected) {
      navigatingRef.current = true;
      router.push(expected);
    }
  }, [paused, pathname, router]);

  const replay = useCallback(() => {
    setFinished(false);
    void start(track);
  }, [start, track]);

  const retry = useCallback(() => {
    setMissing(false);
    setRetryNonce(n => n + 1);
  }, []);

  const reset = useCallback(() => {
    persist(null);
    setRunning(false);
    setFinished(false);
    setMinimized(false);
    setPaused(null);
    setChapterId(null);
    setStepIndex(0);
    setChapters([]);
    setData({});
    setMissing(false);
    expectedPathRef.current = null;
    navigatingRef.current = false;
  }, [persist]);

  // 持久化进度
  useEffect(() => {
    if (!running || !chapterId) return;
    persist({ version: TOUR_VERSION, track, chapterId, stepIndex });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running, chapterId, stepIndex, track]);

  // 路由跳转（导览发起）
  useEffect(() => {
    if (!running || minimized || !chapter || !step) return;
    const raw = step.route;
    if (!raw) return;
    const target = fillRoute(raw, data);
    if (!target) { setMissing(true); return; }
    const targetPath = target.split('?')[0];
    expectedPathRef.current = targetPath;
    if (pathname !== targetPath) {
      navigatingRef.current = true;
      router.push(target);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running, minimized, chapterId, stepIndex]);

  // 用户手动离开路线 → 暂停
  useEffect(() => {
    if (!running || minimized) return;
    const expected = expectedPathRef.current;
    if (!expected) return;
    if (pathname === expected) {
      navigatingRef.current = false;
      return;
    }
    // 导览自己发起的跳转尚未落地时不要误判
    if (!navigatingRef.current) setPaused('你已离开导览路线');
  }, [pathname, running, minimized]);

  const value: TourContextValue = {
    ready: mounted,
    running,
    minimized,
    paused,
    track,
    chapter,
    step,
    stepIndex,
    chapters,
    data,
    missing,
    retryNonce,
    startError,
    finished,
    replay,
    start,
    startChapter,
    next,
    prev,
    close,
    minimize,
    resume,
    retry,
    reset,
    hasProgress: Boolean(progress),
  };

  return (
    <TourContext.Provider value={value}>
      {children}
      <TourOverlay />
    </TourContext.Provider>
  );
}

export function useTour() {
  const ctx = useContext(TourContext);
  if (!ctx) throw new Error('useTour 必须在 TourProvider 内使用');
  return ctx;
}
