/**
 * SSE 流式读取工具 — 项目唯一 SSE 解析入口
 *
 * 支持两种后端 SSE 格式：
 *   Format A (JSON):  data: {"type":"chunk","content":"..."}\n\n
 *   Format B (Raw):   event: chunk\ndata: raw text\n\n
 *
 * 所有页面/组件统一通过此模块处理 SSE，禁止内联 fetch + 手写解析。
 *
 * 架构：
 *   sseFetch（纯 I/O 层，格式无关）
 *     → createJsonHandler / createRawHandler（事件解析 + 分发）
 *       → connectSse / connectRawSse（薄包装，对外 API）
 *
 * 渲染策略：每个 chunk 事件立即触发 onChunk —— 打字机效果。
 * done 事件延迟到同批所有 chunk 之后执行，彻底消除双重显示 bug。
 */
import { getToken } from './storage';

// ====== Format A: JSON SSE ======

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
  onSource?: (reportId: string, reportTitle: string, grainIds?: string, grainTags?: string, grainCount?: number, avgScore?: string, avgSimilarity?: string, sourceNames?: string) => void;
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

function buildFetchOptions(options: SseConnectOptions, controller: AbortController): RequestInit {
  const signal = options.signal || controller.signal;
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    ...options.headers,
  };
  // HttpOnly Cookie 自动携带，向后兼容仍支持 Authorization header；
  // 调用方已显式传入 Authorization（C 端分享页 Bearer）时不覆盖
  const token = getToken();
  if (token && !headers['Authorization']) headers['Authorization'] = `Bearer ${token}`;
  const fetchOptions: RequestInit = {
    method: options.method || 'POST',
    headers,
    signal,
    credentials: 'include', // 发送 HttpOnly Cookie
  };
  if (options.body) fetchOptions.body = JSON.stringify(options.body);
  return fetchOptions;
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

// ====== 核心 I/O 层（格式无关） ======

type ParseAndDispatch = (block: string) => void;
type FlushDeferred = () => void;
type OnIoError = (msg: string) => void;

/**
 * SSE 核心 I/O 引擎
 *
 * 负责 HTTP 请求、流式读取、buffer 管理、超时心跳。
 * 不关心事件格式 —— 每个 \n\n 分隔的完整 block 交由 parseAndDispatch 处理。
 *
 * @param options    连接配置（url / method / body / timeout …）
 * @param parseAndDispatch  每解析到一个完整 SSE block 时回调
 * @param onIoError  I/O 层错误回调（HTTP 状态码、网络中断、超时）
 * @param flush      每轮 reader.read() 结束后回调，用于执行延迟事件
 * @returns          AbortController，调用 .abort() 可取消连接
 */
export function sseFetch(
  options: SseConnectOptions,
  parseAndDispatch: ParseAndDispatch,
  onIoError: OnIoError,
  flush: FlushDeferred,
): AbortController {
  const controller = new AbortController();
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;

  const clearTimer = () => {
    if (timeoutId) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }
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

  // 安全取消 reader（幂等，不会抛异常）
  const cancelReader = () => {
    if (reader) {
      reader.cancel().catch(() => { /* 静默 —— 连接已断开 */ });
    }
  };

  resetTimer();

  fetch(options.url, buildFetchOptions(options, controller))
    .then(async (response) => {
      if (!response.ok) {
        clearTimer();
        onIoError(`HTTP ${response.status}`);
        return;
      }

      reader = response.body?.getReader() ?? null;
      if (!reader) {
        clearTimer();
        onIoError('不支持流式读取');
        return;
      }

      const decoder = new TextDecoder();
      let buffer = '';

      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');

          // 每个网络包重置超时计时器（心跳机制）
          resetTimer();

          // 逐个处理缓冲区中的完整事件
          let idx: number;
          while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const block = buffer.substring(0, idx);
            buffer = buffer.substring(idx + 2);

            if (block.trim()) {
              parseAndDispatch(block);
            }
          }

          // 同批 chunk 全部处理完毕 → 执行延迟事件（done）
          flush();
        }

        // 流结束后处理缓冲区残留
        if (buffer.trim()) {
          parseAndDispatch(buffer);
          flush();
        }
      } catch (err) {
        if ((err as Error).name !== 'AbortError') {
          onIoError('连接中断，请检查网络');
        }
        cancelReader();
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onIoError('网络错误，请检查连接');
      }
    })
    .finally(() => {
      clearTimer();
      cancelReader();
    });

  return controller;
}

// ====== Format A 事件处理器（JSON SSE） ======

interface SseHandler {
  parseAndDispatch: ParseAndDispatch;
  flush: FlushDeferred;
}

/**
 * 创建 JSON 格式 SSE 事件处理器
 *
 * chunk/content → 立即 onChunk（打字机效果）
 * done          → 延迟到 flush()（避免双重显示）
 * 其余事件      → 立即执行（行为不变）
 */
function createJsonHandler(callbacks: SseCallbacks): SseHandler {
  const deferredDones: Array<() => void> = [];

  const parseAndDispatch = (block: string) => {
    const jsonStr = extractJsonFromBlock(block);
    if (!jsonStr) return;

    let event: Record<string, unknown>;
    try {
      event = JSON.parse(jsonStr);
    } catch {
      console.warn('[SSE] JSON parse failed:', jsonStr);
      return;
    }

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
          (event.sourceNames as string) || '',
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
        break;
    }
  };

  const flush = () => {
    while (deferredDones.length > 0) {
      deferredDones.shift()!();
    }
  };

  return { parseAndDispatch, flush };
}

/**
 * 连接 JSON 格式 SSE 端点（Format A）
 *
 * 后端发送格式：data: {"type":"chunk","content":"..."}\n\n
 * 支持 type: chunk | content | phase_change | collect_update | source | meta | done | error
 */
export function connectSse(options: SseConnectOptions, callbacks: SseCallbacks): AbortController {
  const handler = createJsonHandler(callbacks);
  return sseFetch(options, handler.parseAndDispatch, (msg) => callbacks.onError?.(msg), handler.flush);
}

/**
 * Promise 包装版 connectSse — 适用于需要 await 语义的场景
 */
export function connectSseAsync(options: SseConnectOptions, callbacks: SseCallbacks): Promise<void> {
  return new Promise((resolve, reject) => {
    const origDone = callbacks.onDone;
    const origError = callbacks.onError;
    callbacks.onDone = () => { origDone?.(); resolve(); };
    callbacks.onError = (msg) => { origError?.(msg); reject(new Error(msg)); };
    connectSse(options, callbacks);
  });
}

// ====== Format B: Raw Text SSE ======

export interface RawSseCallbacks {
  onChunk?: (text: string) => void;
  onAnalysis?: (text: string) => void;
  onDone?: () => void;
  onError?: (msg: string) => void;
}

/**
 * 创建原始文本格式 SSE 事件处理器
 *
 * chunk    → 立即 onChunk（打字机效果）
 * analysis → 立即 onAnalysis（行为不变）
 * done     → 延迟到 flush()（避免双重显示）
 * error    → 立即 onError（行为不变）
 */
function createRawHandler(callbacks: RawSseCallbacks): SseHandler {
  const deferredDones: Array<() => void> = [];

  const parseAndDispatch = (block: string) => {
    const lines = block.split('\n');
    let eventType = '';
    let data = '';

    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed.startsWith('event:')) {
        eventType = trimmed.substring(6).trim();
      } else if (trimmed.startsWith('data:')) {
        data += trimmed.substring(5);
      }
    }

    if (!eventType) return;

    switch (eventType) {
      case 'chunk':
        callbacks.onChunk?.(data);
        break;

      case 'analysis':
        callbacks.onAnalysis?.(data);
        break;

      case 'done':
        deferredDones.push(() => callbacks.onDone?.());
        break;

      case 'error':
        callbacks.onError?.(data || '未知错误');
        break;

      default:
        // 未知事件类型，静默忽略（向前兼容）
        break;
    }
  };

  const flush = () => {
    while (deferredDones.length > 0) {
      deferredDones.shift()!();
    }
  };

  return { parseAndDispatch, flush };
}

/**
 * 连接原始文本格式 SSE 端点（Format B）
 *
 * 后端发送格式：
 *   event: chunk\ndata: some text\n\n
 *   event: analysis\ndata: analysis text\n\n
 *   event: done\ndata:\n\n
 *   event: error\ndata: error message\n\n
 */
export function connectRawSse(options: SseConnectOptions, callbacks: RawSseCallbacks): AbortController {
  const handler = createRawHandler(callbacks);
  return sseFetch(options, handler.parseAndDispatch, (msg) => callbacks.onError?.(msg), handler.flush);
}

