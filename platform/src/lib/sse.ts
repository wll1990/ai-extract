/**
 * SSE 流式读取工具 — @synced-from frontend/src/lib/sse.ts
 *
 * 完全复用，无需改动。
 *
 * @since 2026-07-20
 */

import { csrfHeader } from './csrf';

export type SseEventType = 'chunk' | 'content' | 'heartbeat' | 'phase_change' | 'collect_update' | 'done' | 'error' | 'source' | 'meta' | 'customer' | 'avatar' | 'analysis' | 'mode';

export interface SseEvent {
  type: SseEventType;
  content?: string;
  message?: string;
  /** 稳定错误码（type=error）：AI_KEY_INVALID / AI_QUOTA_EXCEEDED / AI_RATE_LIMITED ... */
  code?: string;
  phase?: string;
  module?: string;
  reportId?: string;
  reportTitle?: string;
  /** 溯源类型：'knowledge' | 'case'（undefined = 经验溯源，向后兼容） */
  sourceType?: string;
  /** 知识/案例溯源标题（sourceType=knowledge/case 时有效） */
  sourceTitle?: string;
}

export interface SseCallbacks {
  onChunk?: (content: string) => void;
  onPhaseChange?: (phase: string) => void;
  onCollectUpdate?: (module: string) => void;
  onDone?: () => void;
  onError?: (message: string, code?: string) => void;
  onAbort?: () => void;
  onSource?: (reportId: string, reportTitle: string, grainIds?: string, grainTags?: string, grainCount?: number, avgScore?: string, avgSimilarity?: string, sourceNames?: string) => void;
  /** 知识库溯源（sourceType=knowledge）：标题 + 来源 + 相似度百分比 */
  onKnowledgeSource?: (title: string, source: string, similarity: string) => void;
  /** 案例库溯源（sourceType=case）：标题 + 来源 + 相似度百分比 */
  onCaseSource?: (title: string, source: string, similarity: string) => void;
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

/** 从 localStorage 取 token（B端优先，降级 C端 c_auth） */
function getToken(): string | null {
  if (typeof window === 'undefined') return null;
  const bToken = localStorage.getItem('token');
  if (bToken) return bToken;
  const cAuth = localStorage.getItem('c_auth');
  if (cAuth) {
    try { const session = JSON.parse(cAuth); return session?.token || null; }
    catch { return null; }
  }
  return null;
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
  // SSE 是 POST：Cookie 认证时后端要求 CSRF 双提交
  if (!headers['X-CSRF-Token']) Object.assign(headers, csrfHeader());
  if (!headers['X-Trace-Id']) headers['X-Trace-Id'] = `web-${crypto.randomUUID()}`;
  return {
    method: options.method || 'POST',
    headers,
    signal,
    credentials: 'same-origin',
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
      case 'source': {
        // 三类溯源按 sourceType 路由：undefined=经验（旧格式），knowledge/case 走独立回调
        const sourceType = event.sourceType as string | undefined;
        if (sourceType === 'knowledge') {
          callbacks.onKnowledgeSource?.(
            (event.sourceTitle as string) || '',
            (event.sourceNames as string) || '',
            (event.avgSimilarity as string) || '',
          );
        } else if (sourceType === 'case') {
          callbacks.onCaseSource?.(
            (event.sourceTitle as string) || '',
            (event.sourceNames as string) || '',
            (event.avgSimilarity as string) || '',
          );
        } else {
          callbacks.onSource?.(
            (event.reportId as string) || '',
            (event.reportTitle as string) || '',
            (event.grainIds as string) || '',
            (event.grainTags as string) || '',
            event.grainCount != null ? Number(event.grainCount) : 0,
            (event.avgScore as string) || '',
            (event.avgSimilarity as string) || '',
            (event.sourceNames as string) || '',
          );
        }
        break;
      }
      case 'meta':
        callbacks.onMeta?.((event.conversationId as string) || '');
        break;
      case 'error':
        callbacks.onError?.((event.message as string) || '', (event.code as string) || undefined);
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
