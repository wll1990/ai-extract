/**
 * 门户埋点。
 *
 * 复用后端已有的公开接口 POST /analytics/event；失败静默，不影响页面。
 *
 * 用 sendBeacon 而不是 fetch：埋点常常在挂载瞬间或跳转前发出，普通 fetch 会被浏览器
 * 取消（实测 portal_view 在挂载后 34ms 就被 abort，事件全丢）。sendBeacon 是浏览器
 * 专门为“发完就走”设计的上报通道，不会因为页面卸载或渲染中断被取消。
 * 不支持 sendBeacon 时退回 keepalive fetch。
 */

import { API_BASE } from './api/client';

export function track(eventType: string, eventData: Record<string, unknown> = {}): void {
  if (typeof window === 'undefined') return;
  const url = `${API_BASE}/analytics/event`;
  const payload = JSON.stringify({ event_type: eventType, event_data: eventData });

  if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
    const blob = new Blob([payload], { type: 'application/json' });
    if (navigator.sendBeacon(url, blob)) return;
  }

  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    keepalive: true,
  }).catch(() => {});
}
