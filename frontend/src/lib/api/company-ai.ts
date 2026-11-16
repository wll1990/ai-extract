/**
 * 企业 AI Key 配置 API。
 *
 * 页面永远拿不到明文 Key，只有末 4 位与状态。
 */

import { apiClient } from './client';

export interface CompanyAiConfigView {
  configured: boolean;
  provider: string;
  model: string;
  baseUrl: string | null;
  keyLast4: string | null;
  status: 'enabled' | 'disabled';
  fallbackEnabled: boolean;
  platformFallbackAvailable: boolean;
  inputTokensThisMonth: number;
  outputTokensThisMonth: number;
}

export interface CompanyAiTestResult {
  ok: boolean;
  latencyMs: number;
  errorCode: string | null;
  message: string;
}

/** 后端 ProviderCatalog 下发的厂商目录 */
export interface ProviderOption {
  id: string;
  label: string;
  defaultBaseUrl: string | null;
  defaultModel: string | null;
  completionsPath: string;
  maxOutputTokens: number;
  contextWindow: number;
  /** 允许改 Base URL（自定义端点 / 本地部署） */
  customBaseUrlAllowed: boolean;
}

export function getCompanyAiConfig(): Promise<CompanyAiConfigView> {
  return apiClient('/admin/company/ai-model');
}

export function listAiProviders(): Promise<ProviderOption[]> {
  return apiClient('/admin/company/ai-model/providers');
}

export function saveCompanyAiConfig(body: {
  apiKey?: string;
  baseUrl: string;
  fallbackEnabled: boolean;
  provider: string;
  modelName: string;
}): Promise<CompanyAiConfigView> {
  return apiClient('/admin/company/ai-model', {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function testCompanyAiKey(body: {
  apiKey?: string;
  baseUrl?: string;
  provider?: string;
  modelName?: string;
}): Promise<CompanyAiTestResult> {
  return apiClient('/admin/company/ai-model/test', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function rotateCompanyAiKey(apiKey: string): Promise<void> {
  return apiClient('/admin/company/ai-model/rotate', {
    method: 'POST',
    body: JSON.stringify({ apiKey }),
  });
}

export function disableCompanyAiKey(): Promise<void> {
  return apiClient('/admin/company/ai-model/disable', { method: 'POST' });
}
