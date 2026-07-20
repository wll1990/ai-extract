/**
 * IM 管理 API 客户端
 * @since 2026-07-01
 */

import { apiClient } from './client';

export interface ImChannel {
  id: string; channelType: string; enabled: boolean;
  config: Record<string, unknown>; linkedSkills: string[]; createdAt: string;
}

export interface ImChannelRequest {
  channelType: string; config: Record<string, unknown>; enabled?: boolean;
}

/** 获取 IM 渠道列表 */
export function getChannels(): Promise<ImChannel[]> {
  return apiClient<ImChannel[]>('/im/channels');
}

/** 新增渠道 */
export function createChannel(data: ImChannelRequest): Promise<ImChannel> {
  return apiClient<ImChannel>('/im/channels', { method: 'POST', body: JSON.stringify(data) });
}

/** 编辑渠道 */
export function updateChannel(channelId: string, data: ImChannelRequest): Promise<ImChannel> {
  return apiClient<ImChannel>(`/im/channels/${channelId}`, { method: 'PUT', body: JSON.stringify(data) });
}

/** 删除渠道 */
export function deleteChannel(channelId: string): Promise<void> {
  return apiClient<void>(`/im/channels/${channelId}`, { method: 'DELETE' });
}

/** 测试连接 */
export function testChannel(channelId: string): Promise<{ success: boolean; message: string }> {
  return apiClient<{ success: boolean; message: string }>(`/im/channels/${channelId}/test`, { method: 'POST' });
}
