/**
 * SSE 流式客户端 — @aiextract/shared-ui
 *
 * 通过 getApiBaseUrl() 获取配置的 API 地址。
 * 前后端统一使用 Format A (JSON SSE)。
 */

import { getApiBaseUrl } from './client';

export interface SseCallbacks {
  onChunk?: (content: string) => void;
  onDone?: () => void;
  onError?: (message: string) => void;
  onAbort?: () => void;
  onSource?: (reportId: string, reportTitle: string, grainIds?: string, grainTags?: string, grainCount?: number, avgScore?: string, avgSimilarity?: string, sourceNames?: string) => void;
  onMeta?: (conversationId: string) => void;
  onEvent?: (type: string, data: any) => void;
  onCollectUpdate?: (content: string) => void;
}

interface SseOptions {
  url: string;
  method?: string;
  body?: any;
  timeout?: number;
}

export function connectSse(
  options: SseOptions,
  callbacks: SseCallbacks,
): AbortController {
  const controller = new AbortController();
  const baseUrl = getApiBaseUrl();
  // 防止调用方已拼接 baseUrl 导致双重前缀（如 chat() 已拼了 /api/v1）
  const fullUrl = options.url.startsWith('http') ? options.url
    : options.url.startsWith(baseUrl) ? options.url
    : `${baseUrl}${options.url}`;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  };

  fetch(fullUrl, {
    method: options.method || 'POST',
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
    signal: controller.signal,
    credentials: 'include',
  }).then(async (response) => {
    if (!response.ok) {
      callbacks.onError?.(`请求失败 (${response.status})`);
      return;
    }
    if (!response.body) {
      callbacks.onError?.('响应体为空');
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const jsonStr = line.slice(5).trim();
            if (!jsonStr) continue;

            try {
              const event = JSON.parse(jsonStr);
              const type = event.type as string;

              switch (type) {
                case 'content':
                  callbacks.onChunk?.(event.content || '');
                  break;
                case 'done':
                  callbacks.onDone?.();
                  break;
                case 'error':
                  callbacks.onError?.(event.message || '');
                  break;
                case 'meta':
                  callbacks.onMeta?.(event.conversationId || '');
                  break;
                case 'source':
                  callbacks.onSource?.(
                    event.reportId || '', event.reportTitle || '',
                    event.grainIds, event.grainTags,
                    event.grainCount != null ? Number(event.grainCount) : 0,
                    event.avgScore, event.avgSimilarity, event.sourceNames,
                  );
                  break;
                case 'suggested':
                case 'limit':
                case 'warning':
                  callbacks.onEvent?.(type, event.data || event);
                  break;
                case 'heartbeat':
                  break;
                default:
                  if (event.content) callbacks.onChunk?.(event.content);
                  else callbacks.onEvent?.(type, event);
              }
            } catch {
              // JSON parse error on individual line, skip
            }
          }
        }
      }
    } catch (e: any) {
      if (e.name !== 'AbortError') {
        callbacks.onError?.(e.message || '连接中断');
      }
    } finally {
      reader.releaseLock();
    }
  }).catch((e) => {
    if (e.name !== 'AbortError') {
      callbacks.onError?.(e.message || '网络错误');
    }
  });

  return controller;
}
