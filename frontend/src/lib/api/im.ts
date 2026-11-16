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
// IM 机器人（1 分身 = 每渠道 1 bot）
// ============================================================

/** 已发布机器人 */
export interface ImBot {
  id: string;
  channelType: string;
  botId: string;
  appId: string;
  skillId: string;
  skillName: string | null;
  status: string; // publishing / published / failed / disabled
  enabled: boolean;
  connected: string | null;
  /** 最近一次发布/重连失败原因 */
  lastError?: string | null;
  createdAt: string;
}

/** 发布结果：飞书返回扫码授权二维码；企微/钉钉返回 bridgeBotId */
export interface ImPublishResult {
  registerToken?: string;
  qrUrl?: string;
  expireIn?: number;
  bridgeBotId?: string;
}

/** 兼容旧命名 */
export type FeishuPublishResult = ImPublishResult;

/** 发布配置（扫码前可编辑机器人名称/描述/头像，缺省回退分身资料） */
export interface ImPublishConfig {
  name?: string;
  description?: string;
  avatar?: string;
  /** 企微/钉钉凭据（飞书忽略） */
  credentials?: Record<string, string>;
}

export type FeishuPublishConfig = ImPublishConfig;

/**
 * 发布分身到指定渠道。
 * feishu：返回 qrUrl，前端展示二维码并轮询；
 * wecom/dingtalk：后端校验凭据后立即绑定，返回 bridgeBotId。
 */
export function publishToChannel(
  channelType: 'feishu' | 'wecom' | 'dingtalk',
  skillId: string,
  config?: ImPublishConfig,
): Promise<ImPublishResult> {
  return apiClient<ImPublishResult>(`/im/channels/${channelType}/publish`, {
    method: 'POST',
    body: JSON.stringify({ skillId, ...(config || {}) }),
  });
}

/** 发起「发布到飞书」，返回扫码授权二维码 */
export function publishFeishu(skillId: string, config?: ImPublishConfig): Promise<ImPublishResult> {
  return publishToChannel('feishu', skillId, config);
}

/** 本企业已发布机器人列表 */
export function listImBots(): Promise<ImBot[]> {
  return apiClient<ImBot[]>('/im/channels/bots');
}

/** 下线机器人 */
/** 重连机器人（桥连接失败后重试） */
export function reconnectImBot(botId: string): Promise<void> {
  return apiClient<void>(`/im/channels/bots/${botId}/reconnect`, { method: 'POST' });
}

export function unpublishImBot(botId: string): Promise<void> {
  return apiClient<void>(`/im/channels/bots/${botId}`, { method: 'DELETE' });
}

// ==================== 通知绑定（我的 IM） ====================

export interface ImBinding {
  id: string;
  channelType: string;
  botId: string;
  displayName: string | null;
  chatId: string | null;
  createdAt: string;
  lastSeenAt: string | null;
}

/** 生成一次性绑定码（10 分钟有效） */
export function createImBindCode(): Promise<{
  code: string; expiresAt: string; instruction: string;
}> {
  return apiClient('/me/im/bind-code', { method: 'POST' });
}

export function listMyImBindings(): Promise<ImBinding[]> {
  return apiClient<ImBinding[]>('/me/im/bindings');
}

export function unbindIm(id: string): Promise<void> {
  return apiClient<void>(`/me/im/bindings/${id}`, { method: 'DELETE' });
}
