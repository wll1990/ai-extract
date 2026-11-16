/**
 * 站内信 API —— 认证/复核/补训/延期/导出事件通知。
 *
 * @since 2026-10-29
 */
import { apiClient } from './client';
import type { PageResult } from './training';

export interface NotificationItem {
  id: string;
  type: string;
  title: string;
  content: string | null;
  link: string | null;
  bizType: string | null;
  bizId: string | null;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export function listNotifications(
  page = 1,
  size = 20,
  unreadOnly = false,
): Promise<PageResult<NotificationItem>> {
  return apiClient(`/me/notifications?page=${page}&size=${size}&unreadOnly=${unreadOnly}`);
}

export function unreadNotificationCount(): Promise<{ count: number }> {
  return apiClient('/me/notifications/unread-count');
}

export function markNotificationRead(id: string): Promise<{ id: string; read: boolean }> {
  return apiClient(`/me/notifications/${id}/read`, { method: 'POST' });
}

export function markAllNotificationsRead(): Promise<{ updated: number }> {
  return apiClient('/me/notifications/read-all', { method: 'POST' });
}
