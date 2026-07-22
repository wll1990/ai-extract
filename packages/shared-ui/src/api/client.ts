/**
 * 统一 API 请求客户端 — @aiextract/shared-ui
 *
 * 通过 configureApi() 在应用启动时注入配置，不依赖环境变量。
 * 前端、企业端、H5 端共享同一套 API 函数。
 */

export interface ApiConfig {
  baseUrl: string;
  getAuthHeaders: () => Record<string, string>;
  onAuthError?: (status: number) => void;
}

let _config: ApiConfig = {
  baseUrl: '/api/v1',
  getAuthHeaders: () => ({}),
};

export function configureApi(config: Partial<ApiConfig>) {
  if (config.baseUrl != null) _config.baseUrl = config.baseUrl;
  if (config.getAuthHeaders) _config.getAuthHeaders = config.getAuthHeaders;
  if (config.onAuthError) _config.onAuthError = config.onAuthError;
}

export function getApiBaseUrl(): string {
  return _config.baseUrl;
}

export async function apiClient<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  const authHeaders = _config.getAuthHeaders();
  Object.assign(headers, authHeaders);

  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  if (options.headers) {
    Object.assign(headers, options.headers as Record<string, string>);
  }

  const res = await fetch(`${_config.baseUrl}${path}`, {
    ...options,
    headers,
    credentials: 'include',
  });

  if (!res.ok) {
    if ((res.status === 401 || res.status === 403) && _config.onAuthError) {
      _config.onAuthError(res.status);
    }
    let msg = `请求失败 (${res.status})`;
    try {
      const errBody = await res.json();
      if (errBody.message) msg = errBody.message;
    } catch { /* ignore */ }
    throw new Error(msg);
  }

  const json = await res.json();
  if (json.code !== 200) {
    throw new Error(json.message || '请求失败');
  }
  return json.data as T;
}
