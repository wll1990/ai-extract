'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  CompanyAiConfigView,
  CompanyAiTestResult,
  ProviderOption,
  disableCompanyAiKey,
  getCompanyAiConfig,
  listAiProviders,
  rotateCompanyAiKey,
  saveCompanyAiConfig,
  testCompanyAiKey,
} from '@/lib/api/company-ai';

const DEFAULT_PROVIDER = 'deepseek';

export default function AiModelSettingsPage() {
  const [cfg, setCfg] = useState<CompanyAiConfigView | null>(null);
  const [providers, setProviders] = useState<ProviderOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState({
    apiKey: '', baseUrl: 'https://api.deepseek.com', fallbackEnabled: true,
    provider: DEFAULT_PROVIDER, modelName: 'deepseek-chat',
  });
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<CompanyAiTestResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [rotateOpen, setRotateOpen] = useState(false);
  const [rotateKey, setRotateKey] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [next, catalog] = await Promise.all([getCompanyAiConfig(), listAiProviders()]);
      setCfg(next);
      setProviders(catalog || []);
      const current = catalog?.find(p => p.id === next.provider);
      setForm({
        apiKey: '',
        baseUrl: next.baseUrl || current?.defaultBaseUrl || 'https://api.deepseek.com',
        fallbackEnabled: next.fallbackEnabled,
        provider: next.provider || DEFAULT_PROVIDER,
        modelName: next.model || current?.defaultModel || '',
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const currentProvider = providers.find(p => p.id === form.provider);

  /** 切厂商：自动填默认 Base URL 与模型，用户仍可改 */
  const switchProvider = (providerId: string) => {
    const p = providers.find(item => item.id === providerId);
    setForm(prev => ({
      ...prev,
      provider: providerId,
      baseUrl: p?.defaultBaseUrl || prev.baseUrl,
      modelName: p?.defaultModel || '',
    }));
  };

  useEffect(() => {
    void load();
  }, [load]);

  // 输入变化后旧的测试结果不再可信，必须清空
  useEffect(() => {
    setTestResult(null);
  }, [form.apiKey, form.baseUrl, form.provider, form.modelName]);

  const onTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      setTestResult(await testCompanyAiKey({
        apiKey: form.apiKey || undefined,
        baseUrl: form.baseUrl || undefined,
        provider: form.provider,
        modelName: form.modelName || undefined,
      }));
    } catch (e) {
      setTestResult({
        ok: false,
        latencyMs: 0,
        errorCode: 'AI_KEY_UNREACHABLE',
        message: e instanceof Error ? e.message : '测试失败',
      });
    } finally {
      setTesting(false);
    }
  };

  const onSave = async () => {
    if (form.apiKey && form.apiKey.trim().length < 8) {
      setError('Key 至少 8 位');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const next = await saveCompanyAiConfig({
        apiKey: form.apiKey || undefined,
        baseUrl: form.baseUrl,
        fallbackEnabled: form.fallbackEnabled,
        provider: form.provider,
        modelName: form.modelName,
      });
      setCfg(next);
      setForm(prev => ({ ...prev, apiKey: '' }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const onRotate = async () => {
    if (rotateKey.trim().length < 8) {
      setError('Key 至少 8 位');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await rotateCompanyAiKey(rotateKey.trim());
      setRotateOpen(false);
      setRotateKey('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '轮换失败');
    } finally {
      setSaving(false);
    }
  };

  const onDisable = async () => {
    if (!window.confirm('停用后将按「回退」开关决定是否使用平台 Key，确认停用？')) return;
    setSaving(true);
    setError(null);
    try {
      await disableCompanyAiKey();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '停用失败');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="p-6 text-sm text-gray-500">加载中…</div>;
  }
  if (!cfg) {
    return <div className="p-6 text-sm text-red-600">{error || '无权访问'}</div>;
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <h1 className="mb-1 text-xl font-semibold text-gray-900">企业 AI 模型</h1>
      <p className="mb-6 text-sm text-gray-500">
        配置企业自己的对话模型（OpenAI 兼容协议）。未配置或停用且开启回退时使用平台 Key，保证流程可用。
        向量（embedding）仍由平台 DashScope 提供，不受此处影响。
      </p>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      <section className="mb-6 rounded border border-gray-200 p-4">
        <h2 className="mb-3 text-sm font-medium text-gray-900">当前状态</h2>
        <dl className="grid grid-cols-2 gap-y-2 text-sm">
          <dt className="text-gray-500">厂商 / 模型</dt>
          <dd className="text-gray-900">{cfg.provider} · {cfg.model}</dd>
          <dt className="text-gray-500">Base URL</dt>
          <dd className="break-all text-gray-900">{cfg.baseUrl || '—'}</dd>
          <dt className="text-gray-500">状态</dt>
          <dd className={cfg.status === 'enabled' ? 'text-green-700' : 'text-gray-500'}>
            {cfg.configured ? (cfg.status === 'enabled' ? '已配置并启用' : '已停用') : '未配置'}
          </dd>
          <dt className="text-gray-500">Key</dt>
          <dd>{cfg.keyLast4 ? `••••${cfg.keyLast4}` : '—'}</dd>
          <dt className="text-gray-500">本月用量</dt>
          <dd>
            输入 {cfg.inputTokensThisMonth.toLocaleString()} / 输出{' '}
            {cfg.outputTokensThisMonth.toLocaleString()} tokens
          </dd>
        </dl>
      </section>

      <section className="mb-6 space-y-4 rounded border border-gray-200 p-4">
        <h2 className="text-sm font-medium text-gray-900">配置</h2>
        <div className="grid gap-3 md:grid-cols-2">
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">模型厂商</span>
            <select
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={form.provider}
              onChange={e => switchProvider(e.target.value)}
            >
              {providers.map(p => (
                <option key={p.id} value={p.id}>{p.label}</option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">模型名</span>
            <input
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={form.modelName}
              onChange={e => setForm({ ...form, modelName: e.target.value })}
              placeholder={currentProvider?.defaultModel || '如 deepseek-chat'}
            />
          </label>
        </div>
        <label className="block text-sm">
          <span className="mb-1 block text-gray-700">Base URL</span>
          <input
            className="w-full rounded border border-gray-300 px-3 py-2"
            value={form.baseUrl}
            onChange={e => setForm({ ...form, baseUrl: e.target.value })}
            placeholder={currentProvider?.defaultBaseUrl || 'https://api.example.com'}
          />
          {!currentProvider?.customBaseUrlAllowed && (
            <span className="mt-1 block text-xs text-gray-500">
              该厂商必须使用官方域名；如需代理/私有端点请选择「自定义」。
            </span>
          )}
          {currentProvider?.customBaseUrlAllowed && (
            <span className="mt-1 block text-xs text-amber-700">
              {form.provider === 'custom'
                ? '自定义端点会接收企业 API Key 与对话内容，请确认归属可控。'
                : '本地部署需把主机名加入服务端 AI_LOCAL_MODEL_HOSTS 白名单，否则保存会被拒绝。'}
            </span>
          )}
        </label>
        <label className="block text-sm">
          <span className="mb-1 block text-gray-700">API Key</span>
          <input
            type="password"
            autoComplete="new-password"
            className="w-full rounded border border-gray-300 px-3 py-2"
            value={form.apiKey}
            onChange={e => setForm({ ...form, apiKey: e.target.value })}
            placeholder={cfg.keyLast4 ? `已配置（末 4 位 ${cfg.keyLast4}），留空表示不修改` : '输入企业 Key'}
          />
        </label>
        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={form.fallbackEnabled}
            onChange={e => setForm({ ...form, fallbackEnabled: e.target.checked })}
          />
          停用或解密失败时回退平台 Key
        </label>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={onTest}
            disabled={testing || saving}
            className="rounded border border-gray-300 px-3 py-2 text-sm hover:bg-gray-50 disabled:opacity-50"
          >
            {testing ? '测试中…' : '测试连通'}
          </button>
          <button
            type="button"
            onClick={onSave}
            disabled={saving}
            className="rounded bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? '保存中…' : '保存'}
          </button>
          {cfg.configured && (
            <>
              <button
                type="button"
                onClick={() => setRotateOpen(true)}
                className="rounded border border-gray-300 px-3 py-2 text-sm hover:bg-gray-50"
              >
                轮换 Key
              </button>
              <button
                type="button"
                onClick={onDisable}
                className="rounded border border-red-300 px-3 py-2 text-sm text-red-700 hover:bg-red-50"
              >
                停用
              </button>
            </>
          )}
        </div>
        {testResult && (
          <div className={`text-sm ${testResult.ok ? 'text-green-700' : 'text-red-700'}`}>
            {testResult.ok
              ? `连接成功，耗时 ${testResult.latencyMs}ms`
              : `${testResult.message}（${testResult.errorCode}）`}
          </div>
        )}
      </section>

      {rotateOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="w-full max-w-md rounded bg-white p-5">
            <h3 className="mb-3 text-base font-medium">轮换 API Key</h3>
            <input
              type="password"
              autoComplete="new-password"
              className="mb-3 w-full rounded border border-gray-300 px-3 py-2"
              value={rotateKey}
              onChange={e => setRotateKey(e.target.value)}
              placeholder="输入新的 API Key"
            />
            <p className="mb-4 text-xs text-gray-500">
              旧 Key 不会保留在数据库，轮换后立即失效；如需回滚，请重新填写旧 Key。
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setRotateOpen(false)}
                className="rounded border px-3 py-2 text-sm"
              >
                取消
              </button>
              <button
                type="button"
                onClick={onRotate}
                disabled={saving || !rotateKey.trim()}
                className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
              >
                确认轮换
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
