/**
 * SSE 流式读取工具 — @synced-from frontend/src/lib/sse.ts
 *
 * 完全复用，无需改动。
 *
 * @since 2026-07-20
 */

export type SseEventType = 'chunk' | 'content' | 'heartbeat' | 'phase_change' | 'collect_update' | 'done' | 'error' | 'source' | 'meta' | 'customer' | 'avatar' | 'analysis' | 'mode';

export interface SseEvent {
  type: SseEventType;
  content?: string;
  message?: string;
  phase?: string;
  module?: string;
  reportId?: string;
  reportTitle?: string;
}

export interface SseCallbacks {
  onChunk?: (content: string) => void;
  onPhaseChange?: (phase: string) => void;
  onCollectUpdate?: (module: string) => void;
  onDone?: () => void;
  onError?: (message: string) => void;
  onAbort?: () => void;
  onSource?: (reportId: string, reportTitle: string, grainIds?: string, grainTags?: string, grainCount?: number, avgScore?: string, avgSimilarity?: string) => void;
  onMeta?: (conversationId: string) => void;
  onEvent?: (type: string, data: Record<string, unknown>) => void;
}

export interface SseConnectOptions {
  url: string;
  method?: 'POST';
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  timeout?: number;
}

/** 从 localStorage 取 token（向后兼容，企业端主要用 HttpOnly Cookie） */
function getToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem('token');
}

function buildFetchOptions(options: SseConnectOptions, controller: AbortController): RequestInit {
  const signal = options.signal || controller.signal;
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    ...options.headers,
  };
  const token = getToken();
  if (token && !headers['Authorization']) headers['Authorization'] = `Bearer ${token}`;
  return {
    method: options.method || 'POST',
    headers,
    signal,
    credentials: 'include',
    ...(options.body ? { body: JSON.stringify(options.body) } : {}),
  };
}

function extractJsonFromBlock(block: string): string | null {
  const lines = block.split('\n').filter(l => l.trim());
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('data:')) {
      const afterData = trimmed.substring(5).trim();
      if (afterData.startsWith('{')) return afterData;
    } else if (trimmed.startsWith('{')) {
      return trimmed;
    }
  }
  return null;
}

type ParseAndDispatch = (block: string) => void;
type FlushDeferred = () => void;
type OnIoError = (msg: string) => void;

function sseFetch(
  options: SseConnectOptions,
  parseAndDispatch: ParseAndDispatch,
  onIoError: OnIoError,
  flush: FlushDeferred,
): AbortController {
  const controller = new AbortController();
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;

  const clearTimer = () => {
    if (timeoutId) { clearTimeout(timeoutId); timeoutId = null; }
  };
  const resetTimer = () => {
    clearTimer();
    if (options.timeout && options.timeout > 0) {
      timeoutId = setTimeout(() => {
        controller.abort();
        onIoError('请求超时，请检查网络连接');
      }, options.timeout);
    }
  };
  const cancelReader = () => {
    if (reader) { reader.cancel().catch(() => {}); }
  };

  resetTimer();

  fetch(options.url, buildFetchOptions(options, controller))
    .then(async (response) => {
      if (!response.ok) { clearTimer(); onIoError(`HTTP ${response.status}`); return; }
      reader = response.body?.getReader() ?? null;
      if (!reader) { clearTimer(); onIoError('不支持流式读取'); return; }

      const decoder = new TextDecoder();
      let buffer = '';

      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
          resetTimer();

          let idx: number;
          while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const block = buffer.substring(0, idx);
            buffer = buffer.substring(idx + 2);
            if (block.trim()) { parseAndDispatch(block); }
          }
          flush();
        }
        if (buffer.trim()) { parseAndDispatch(buffer); flush(); }
      } catch (err) {
        if ((err as Error).name !== 'AbortError') { onIoError('连接中断，请检查网络'); }
        cancelReader();
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') { onIoError('网络错误，请检查连接'); }
    })
    .finally(() => { clearTimer(); cancelReader(); });

  return controller;
}

/** 连接 JSON 格式 SSE 端点 */
export function connectSse(options: SseConnectOptions, callbacks: SseCallbacks): AbortController {
  const deferredDones: Array<() => void> = [];

  const parseAndDispatch = (block: string) => {
    const jsonStr = extractJsonFromBlock(block);
    if (!jsonStr) return;

    let event: Record<string, unknown>;
    try { event = JSON.parse(jsonStr); } catch { return; }

    const type = event.type as string | undefined;
    if (!type) return;

    switch (type) {
      case 'chunk':
      case 'content':
        callbacks.onChunk?.((event.content as string) || '');
        break;
      case 'done':
        deferredDones.push(() => callbacks.onDone?.());
        break;
      case 'phase_change':
        callbacks.onPhaseChange?.((event.content as string) || (event.phase as string) || '');
        break;
      case 'collect_update':
        callbacks.onCollectUpdate?.((event.content as string) || (event.module as string) || '');
        break;
      case 'source':
        callbacks.onSource?.(
          (event.reportId as string) || '',
          (event.reportTitle as string) || '',
          (event.grainIds as string) || '',
          (event.grainTags as string) || '',
          event.grainCount != null ? Number(event.grainCount) : 0,
          (event.avgScore as string) || '',
          (event.avgSimilarity as string) || '',
        );
        break;
      case 'meta':
        callbacks.onMeta?.((event.conversationId as string) || '');
        break;
      case 'error':
        callbacks.onError?.((event.message as string) || '');
        break;
      default:
        callbacks.onEvent?.(type, (event.data as Record<string, unknown>) || event);
    }
  };

  const flush = () => {
    while (deferredDones.length > 0) { deferredDones.shift()!(); }
  };

  return sseFetch(options, parseAndDispatch, (msg) => callbacks.onError?.(msg), flush);
}
