'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  CompanyAiConfigHistory,
  CompanyAiConfigView,
  CompanyAiTestResult,
  ProviderOption,
  disableCompanyAiKey,
  enableCompanyAiKey,
  getCompanyAiConfig,
  getCompanyAiHistory,
  listAiProviders,
  rotateCompanyAiKey,
  saveCompanyAiConfig,
  testCompanyAiKey,
} from '@/lib/api/company-ai';
import {
  PracticeKcSetting,
  getPracticeKcSetting,
  savePracticeKcSetting,
} from '@/lib/api/practice-kc';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHelp } from '@/components/ui/PageHelp';
import { Toast } from '@/components/ui/Toast';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

const DEFAULT_PROVIDER = 'deepseek';

const ACTION_LABEL: Record<CompanyAiConfigHistory['action'], string> = {
  created: '创建配置',
  updated: '修改配置',
  rotated: '轮换 Key',
  enabled: '启用',
  disabled: '停用',
};

const ACTION_TONE: Record<CompanyAiConfigHistory['action'], string> = {
  created: 'bg-primary-light text-primary',
  updated: 'bg-surface text-muted-foreground',
  rotated: 'bg-warning-bg text-warning-text',
  enabled: 'bg-success-bg text-success',
  disabled: 'bg-danger-bg text-danger',
};

export default function AiModelSettingsPage() {
  const [cfg, setCfg] = useState<CompanyAiConfigView | null>(null);
  const [providers, setProviders] = useState<ProviderOption[]>([]);
  const [history, setHistory] = useState<CompanyAiConfigHistory[]>([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState({
    apiKey: '', baseUrl: 'https://api.deepseek.com', fallbackEnabled: true,
    provider: DEFAULT_PROVIDER, modelName: 'deepseek-chat',
  });
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<CompanyAiTestResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [rotateOpen, setRotateOpen] = useState(false);
  const [rotateKey, setRotateKey] = useState('');
  const [disableOpen, setDisableOpen] = useState(false);
  const [practiceKc, setPracticeKc] = useState<PracticeKcSetting | null>(null);
  const [kcEnabled, setKcEnabled] = useState(false);
  const [kcSaving, setKcSaving] = useState(false);
  const [kcMessage, setKcMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [next, catalog, historyRows] = await Promise.all([
        getCompanyAiConfig(),
        listAiProviders(),
        getCompanyAiHistory().catch(() => []),
      ]);
      setCfg(next);
      setProviders(catalog || []);
      setHistory(historyRows || []);
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

  const loadPracticeKc = useCallback(async () => {
    try {
      const next = await getPracticeKcSetting();
      setPracticeKc(next);
      setKcEnabled(next.enabled);
    } catch {
      // 开关不可用时不影响 AI Key 配置页
      setPracticeKc(null);
    }
  }, []);

  const onSavePracticeKc = async () => {
    setKcSaving(true);
    setKcMessage(null);
    try {
      const next = await savePracticeKcSetting(kcEnabled);
      setPracticeKc(next);
      setKcEnabled(next.enabled);
      setKcMessage('已保存，下一轮对练立即生效');
    } catch (e) {
      setKcMessage(e instanceof Error ? e.message : '保存失败');
    } finally {
      setKcSaving(false);
    }
  };

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
    void loadPracticeKc();
  }, [load, loadPracticeKc]);

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
      setHistory(await getCompanyAiHistory().catch(() => history));
      setToast(next.status === 'enabled'
        ? '已保存并启用，下一次对话请求立即使用新配置'
        : '已保存');
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
      setToast('Key 已轮换，旧 Key 立即失效');
    } catch (e) {
      setError(e instanceof Error ? e.message : '轮换失败');
    } finally {
      setSaving(false);
    }
  };

  const onEnable = async () => {
    setSaving(true);
    setError(null);
    try {
      await enableCompanyAiKey();
      await load();
      setToast('企业 Key 已启用，下一次对话请求走企业 Key');
    } catch (e) {
      setError(e instanceof Error ? e.message : '启用失败');
    } finally {
      setSaving(false);
    }
  };

  const onDisable = async () => {
    setSaving(true);
    setError(null);
    try {
      await disableCompanyAiKey();
      setDisableOpen(false);
      await load();
      setToast(cfg?.fallbackEnabled
        ? '企业 Key 已停用，当前回退到平台 Key'
        : '企业 Key 已停用，且未开启回退，AI 对话会失败');
    } catch (e) {
      setError(e instanceof Error ? e.message : '停用失败');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="p-8"><LoadingSpinner /></div>;
  }
  if (!cfg) {
    return <div className="p-6 text-sm text-danger">{error || '无权访问'}</div>;
  }

  const enabled = cfg.configured && cfg.status === 'enabled';
  const effectiveLabel = cfg.effectiveMode === 'company_key'
    ? '企业 Key'
    : cfg.effectiveMode === 'platform_key' ? '平台 Key（回退）' : '无可用 Key';
  const effectiveTone = cfg.effectiveMode === 'company_key'
    ? 'border-success/30 bg-success-bg text-success'
    : cfg.effectiveMode === 'platform_key'
      ? 'border-warning-text/30 bg-warning-bg text-warning-text'
      : 'border-danger/30 bg-danger-bg text-danger';
  const statusTitle = enabled
    ? '企业 Key 生效中'
    : cfg.configured ? '企业 Key 已停用' : '未配置企业 Key';
  const statusDesc = enabled
    ? '所有对话模型请求都使用企业自己的 Key，用量记在企业账上。'
    : cfg.configured
      ? (cfg.fallbackEnabled
        ? '企业 Key 保留在系统中但当前不启用；对话自动回退到平台 Key。'
        : '企业 Key 已停用且关闭了回退，AI 调用会直接失败。')
      : '尚未配置企业 Key，当前使用平台 Key；保存后即可切换为企业 Key。';
  const saveLabel = enabled ? '保存修改' : '保存并启用';

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <Toast message={toast} onDone={() => setToast(null)} />
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[28px] font-bold leading-tight text-foreground">AI 模型与 Key</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              配置企业自己的对话模型（OpenAI 兼容协议）。未配置或停用时可回退平台 Key，
              向量（embedding）始终由平台 DashScope 提供。
            </p>
          </div>
          <div className="flex items-center gap-2">
            <PageHelp
              title="AI 模型与 Key 使用说明"
              intro="这里决定企业对话实际用哪把 Key、哪个模型。Key 只保存密文和末 4 位，页面永远拿不到明文。"
              steps={[
                { title: '看当前状态', desc: '状态卡直接告诉你「现在实际用企业 Key 还是平台 Key」，以及最近一次修改。' },
                { title: '修改配置', desc: 'Key 留空表示只改厂商/模型/回退开关；填了新 Key 会立即替换旧 Key。' },
                { title: '测试连通', desc: '用当前表单值发一次最小请求，不落库；失败会显示错误码。' },
                { title: '启用 / 停用', desc: '停用不会删除 Key，可随时重新启用；是否回退平台 Key 由回退开关决定。' },
                { title: '看历史', desc: '最近 20 次变更都留痕：谁在什么时候改了什么，避免重复配置后不知道效果。' },
              ]}
              actions={[
                { label: '保存修改 / 保存并启用', desc: '保存同一份配置并立即生效；没有填 Key 时保留当前 Key，不会新建第二条配置。', tone: 'primary' },
                { label: '测试连通', desc: '用当前表单里的 Key/Base URL/模型发一次最小请求；不保存、不消耗对话额度。' },
                { label: '轮换 Key', desc: '输入新 Key 后替换旧 Key；旧 Key 不保留，轮换后立即失效。' },
                { label: '停用企业 Key', desc: '保留配置但停止使用；开启回退时自动走平台 Key，关闭回退时对话会失败。', tone: 'danger' },
                { label: '启用企业 Key', desc: '重新启用已停用的 Key，不需要重新填写；下一次对话请求生效。' },
                { label: '刷新', desc: '重新读取当前配置与历史，不会修改任何内容。' },
              ]}
              notes={[
                '页面只显示 Key 末 4 位；明文只在保存/测试请求中提交一次。',
                '历史记录从本版本上线后开始记录，之前的配置不会补录。',
                '运行中如果企业 Key 返回 401/402/429，不会静默回退平台 Key，会直接报错以便定位。',
              ]}
            />
            <Button variant="neutral" onClick={() => { void load(); }} disabled={loading}>
              刷新
            </Button>
          </div>
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

        <section className="mb-4 rounded-xl border border-border bg-surface-2 p-5">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-lg font-bold text-foreground">{statusTitle}</h2>
                <span className={`inline-flex rounded-full border px-2.5 py-0.5 text-xs font-medium ${effectiveTone}`}>
                  当前实际使用：{effectiveLabel}
                </span>
              </div>
              <p className="mt-1.5 max-w-2xl text-sm leading-6 text-muted-foreground">{statusDesc}</p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {cfg.configured && !enabled && (
                <Button variant="primary" onClick={onEnable} disabled={saving}
                  title="重新启用已保存的企业 Key，不需要重新填写">
                  启用企业 Key
                </Button>
              )}
              {cfg.configured && (
                <Button variant="neutral" onClick={() => setRotateOpen(true)} disabled={saving}
                  title="输入新 Key 替换旧 Key；旧 Key 不保留">
                  轮换 Key
                </Button>
              )}
              {cfg.configured && enabled && (
                <Button variant="danger" onClick={() => setDisableOpen(true)} disabled={saving}
                  title="保留配置但停止使用；按回退开关决定是否使用平台 Key">
                  停用企业 Key
                </Button>
              )}
            </div>
          </div>

          <dl className="mt-4 grid gap-3 border-t border-border pt-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="text-[11px] text-muted-foreground">实际使用模型</dt>
              <dd className="mt-0.5 text-sm font-medium text-foreground">{cfg.effectiveModel}</dd>
            </div>
            <div>
              <dt className="text-[11px] text-muted-foreground">企业 Key</dt>
              <dd className="mt-0.5 text-sm font-medium text-foreground">
                {cfg.keyLast4 ? `••••${cfg.keyLast4}` : '未配置'}
              </dd>
            </div>
            <div>
              <dt className="text-[11px] text-muted-foreground">厂商 / 配置模型</dt>
              <dd className="mt-0.5 text-sm font-medium text-foreground">
                {cfg.configured ? `${cfg.provider} · ${cfg.model}` : '—'}
              </dd>
            </div>
            <div>
              <dt className="text-[11px] text-muted-foreground">最近修改</dt>
              <dd className="mt-0.5 text-sm font-medium text-foreground">
                {cfg.updatedAt ? new Date(cfg.updatedAt).toLocaleString() : '—'}
                {cfg.updatedByName ? <span className="font-normal text-muted-foreground"> · {cfg.updatedByName}</span> : null}
              </dd>
            </div>
          </dl>
          <p className="mt-3 break-all rounded-lg bg-surface px-3 py-2 text-[11px] text-muted-foreground">
            实际 Base URL：{cfg.effectiveBaseUrl}
          </p>
        </section>

        <section className="mb-4 rounded-xl border border-border bg-surface-2 p-5">
          <div className="mb-3">
            <h2 className="text-sm font-semibold text-foreground">修改配置</h2>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">
              保存只会更新同一条企业配置，不会每次新建一条。Key 留空表示不修改当前 Key；
              填了新 Key 会立即替换，旧 Key 不保留。
            </p>
          </div>
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-2">
              <label className="block text-sm">
                <span className="mb-1 block font-medium text-foreground">模型厂商</span>
                <select
                  className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground"
                  value={form.provider}
                  onChange={e => switchProvider(e.target.value)}
                >
                  {providers.map(p => (
                    <option key={p.id} value={p.id}>{p.label}</option>
                  ))}
                </select>
              </label>
              <label className="block text-sm">
                <span className="mb-1 block font-medium text-foreground">模型名</span>
                <input
                  className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground"
                  value={form.modelName}
                  onChange={e => setForm({ ...form, modelName: e.target.value })}
                  placeholder={currentProvider?.defaultModel || '如 deepseek-chat'}
                />
              </label>
            </div>
            <label className="block text-sm">
              <span className="mb-1 block font-medium text-foreground">Base URL</span>
              <input
                className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground"
                value={form.baseUrl}
                onChange={e => setForm({ ...form, baseUrl: e.target.value })}
                placeholder={currentProvider?.defaultBaseUrl || 'https://api.example.com'}
              />
              {!currentProvider?.customBaseUrlAllowed && (
                <span className="mt-1 block text-xs text-muted-foreground">
                  该厂商必须使用官方域名；如需代理/私有端点请选择「自定义」。
                </span>
              )}
              {currentProvider?.customBaseUrlAllowed && (
                <span className="mt-1 block text-xs text-warning-text">
                  {form.provider === 'custom'
                    ? '自定义端点会接收企业 API Key 与对话内容，请确认归属可控。'
                    : '本地部署需把主机名加入服务端 AI_LOCAL_MODEL_HOSTS 白名单，否则保存会被拒绝。'}
                </span>
              )}
            </label>
            <label className="block text-sm">
              <span className="mb-1 block font-medium text-foreground">API Key</span>
              <input
                type="password"
                autoComplete="new-password"
                className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground"
                value={form.apiKey}
                onChange={e => setForm({ ...form, apiKey: e.target.value })}
                placeholder={cfg.keyLast4 ? `已配置（末 4 位 ${cfg.keyLast4}），留空表示不修改` : '输入企业 Key'}
              />
              <span className="mt-1 block text-xs text-muted-foreground">
                留空：只更新厂商/模型/Base URL/回退开关，不动现有 Key。填写：立即替换现有 Key。
              </span>
            </label>
            <label className="flex items-start gap-2 text-sm text-foreground">
              <input
                type="checkbox"
                className="mt-0.5"
                checked={form.fallbackEnabled}
                onChange={e => setForm({ ...form, fallbackEnabled: e.target.checked })}
              />
              <span>
                停用或解密失败时回退平台 Key
                <span className="block text-xs text-muted-foreground">
                  开启：停用企业 Key 后仍能正常对话，用量记在平台账上。
                  关闭：停用后 AI 调用直接失败，适合要求「只用企业 Key」的场景。
                </span>
              </span>
            </label>
            <div className="flex flex-wrap items-center gap-2 border-t border-border pt-4">
              <Button variant="primary" onClick={onSave} disabled={saving}
                title="保存同一份配置并立即生效；不会新建多条配置">
                {saving ? '保存中…' : saveLabel}
              </Button>
              <Button variant="neutral" onClick={onTest} disabled={testing || saving}
                title="用当前表单值发一次最小请求；不保存、不消耗对话额度">
                {testing ? '测试中…' : '测试连通'}
              </Button>
              {testResult && (
                <span className={`text-sm ${testResult.ok ? 'text-success' : 'text-danger'}`}>
                  {testResult.ok
                    ? `连接成功，耗时 ${testResult.latencyMs}ms`
                    : `${testResult.message}（${testResult.errorCode}）`}
                </span>
              )}
            </div>
          </div>
        </section>

        <section className="mb-4 rounded-xl border border-border bg-surface-2 p-5">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-foreground">配置历史（最近 20 条）</h2>
              <p className="mt-1 text-xs text-muted-foreground">
                每次保存、轮换、启用、停用都会留痕；只记录元数据，不记录 Key 明文。
              </p>
            </div>
            <span className="text-xs text-muted-foreground">{history.length} 条</span>
          </div>
          {history.length === 0 ? (
            <p className="rounded-lg bg-surface px-3 py-5 text-sm text-muted-foreground">
              还没有变更记录。本版本上线后的第一次保存/启用/停用会开始记录；之前的历史不会补录。
            </p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full min-w-[760px] text-left text-sm">
                <thead className="bg-surface text-xs text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2.5 font-medium">时间</th>
                    <th className="px-3 py-2.5 font-medium">操作</th>
                    <th className="px-3 py-2.5 font-medium">厂商 / 模型</th>
                    <th className="px-3 py-2.5 font-medium">Key</th>
                    <th className="px-3 py-2.5 font-medium">回退</th>
                    <th className="px-3 py-2.5 font-medium">操作人</th>
                  </tr>
                </thead>
                <tbody>
                  {history.map(row => (
                    <tr key={row.id} className="border-t border-border">
                      <td className="px-3 py-2.5 text-xs text-muted-foreground">
                        {new Date(row.createdAt).toLocaleString()}
                      </td>
                      <td className="px-3 py-2.5">
                        <span className={`inline-flex rounded-full px-2 py-0.5 text-xs ${ACTION_TONE[row.action] || 'bg-surface text-muted-foreground'}`}>
                          {ACTION_LABEL[row.action] || row.action}
                        </span>
                      </td>
                      <td className="px-3 py-2.5 text-xs text-foreground">
                        {row.provider ? `${row.provider} · ${row.model || '—'}` : '—'}
                      </td>
                      <td className="px-3 py-2.5 text-xs tabular-nums text-muted-foreground">
                        {row.keyLast4 ? `••••${row.keyLast4}` : '—'}
                      </td>
                      <td className="px-3 py-2.5 text-xs text-muted-foreground">
                        {row.fallbackEnabled ? '开启' : '关闭'}
                      </td>
                      <td className="px-3 py-2.5 text-xs text-muted-foreground">
                        {row.actorName || '系统'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {practiceKc && (
          <section className="rounded-xl border border-border bg-surface-2 p-5">
            <h2 className="text-sm font-semibold text-foreground">对练知识 / 案例检索</h2>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">
              开启后，对练会检索本企业知识库/案例库（含公共库）：扮演客户时只使用案例的客户侧信息
              （画像、情绪、核心顾虑），逐轮评价时参考知识事实与案例打法。关闭时不发起任何检索。
              保存后立即生效，无需重启。
            </p>
            <label className="mt-3 flex items-center gap-2 text-sm text-foreground">
              <input
                type="checkbox"
                checked={kcEnabled}
                onChange={e => setKcEnabled(e.target.checked)}
              />
              在实战对练中检索知识 / 案例
            </label>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <Button variant="primary" onClick={onSavePracticeKc} disabled={kcSaving}>
                {kcSaving ? '保存中…' : '保存开关'}
              </Button>
              {kcMessage && (
                <span className={`text-sm ${kcMessage.startsWith('已保存') ? 'text-success' : 'text-danger'}`}>
                  {kcMessage}
                </span>
              )}
            </div>
            <p className="mt-2 text-xs text-muted-foreground">
              当前配置：{practiceKc.enabled ? '已开启' : '已关闭'}
              {practiceKc.updatedAt ? `（${new Date(practiceKc.updatedAt).toLocaleString()} 更新）` : '（尚未单独配置，使用全局默认）'}
            </p>
          </section>
        )}
      </div>

      <ConfirmDialog
        open={rotateOpen}
        title="轮换 API Key"
        confirmLabel="确认轮换"
        busy={saving}
        onCancel={() => { if (!saving) { setRotateOpen(false); setRotateKey(''); } }}
        onConfirm={onRotate}
      >
        <p>输入新的企业 API Key。保存后旧 Key 立即失效，系统不会保留旧 Key。</p>
        <input
          type="password"
          autoComplete="new-password"
          className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-foreground"
          value={rotateKey}
          onChange={e => setRotateKey(e.target.value)}
          placeholder="输入新的 API Key"
        />
        <p className="text-xs text-muted-foreground">轮换不会改变厂商标识、模型或 Base URL，只替换 Key。</p>
      </ConfirmDialog>

      <ConfirmDialog
        open={disableOpen}
        title="停用企业 AI Key？"
        confirmLabel="停用"
        busy={saving}
        onCancel={() => { if (!saving) setDisableOpen(false); }}
        onConfirm={onDisable}
      >
        <p>停用不会删除配置，企业 Key 仍保留在系统中，可随时重新启用。</p>
        <p>
          {cfg.fallbackEnabled
            ? '当前已开启回退：停用后对话会自动改用平台 Key，用量记在平台账上。'
            : '当前关闭了回退：停用后 AI 对话会直接失败，直到重新启用或打开回退。'}
        </p>
      </ConfirmDialog>
    </div>
  );
}
