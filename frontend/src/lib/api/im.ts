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

// ============================================================
// 飞书机器人（1 分身 = 1 bot）
// ============================================================

/** 已发布机器人 */
export interface ImBot {
  id: string;
  channelType: string;
  botId: string;
  appId: string;
  skillId: string;
  skillName: string | null;
  status: string;
  enabled: boolean;
  connected: string | null;
  createdAt: string;
}

/** 发布扫码授权结果 */
export interface FeishuPublishResult {
  registerToken: string;
  qrUrl: string;
  expireIn: number;
}

/** 发布配置（扫码前可编辑机器人名称/描述/头像，缺省回退分身资料） */
export interface FeishuPublishConfig {
  name?: string;
  description?: string;
  avatar?: string;
}

/** 发起「发布到飞书」，返回扫码授权二维码 */
export function publishFeishu(skillId: string, config?: FeishuPublishConfig): Promise<FeishuPublishResult> {
  return apiClient<FeishuPublishResult>('/im/channels/feishu/publish', {
    method: 'POST',
    body: JSON.stringify({ skillId, ...(config || {}) }),
  });
}

/** 本企业已发布机器人列表 */
export function listImBots(): Promise<ImBot[]> {
  return apiClient<ImBot[]>('/im/channels/bots');
}

/** 下线机器人 */
export function unpublishImBot(botId: string): Promise<void> {
  return apiClient<void>(`/im/channels/bots/${botId}`, { method: 'DELETE' });
}
