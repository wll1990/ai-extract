'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';
import { PageHelp } from '@/components/ui/PageHelp';
import { ApiError } from '@/lib/api/client';
import { getCurrentUser } from '@/lib/api/auth';
import {
  AgentSkillDetail, AgentSkillListItem, ResourceInput, VersionItem,
  archiveAgentSkill, createAgentSkill, deleteAgentSkillDraft, getAgentSkill,
  getAgentSkillResource, getAgentSkillSetting, listAgentSkillVersions,
  listAgentSkills, newAgentSkillVersion, previewAgentSkill, publishAgentSkill,
  updateAgentSkill, updateAgentSkillSetting,
} from '@/lib/api/agent-skills';
import { agentSkillSourceLabel, agentSkillStatusClass, agentSkillStatusLabel } from '@/lib/agent-skill-status';

type Scope = 'company' | 'platform';

interface FormState {
  skillKey: string;
  name: string;
  description: string;
  bodyMd: string;
}

const EMPTY_FORM: FormState = { skillKey: '', name: '', description: '', bodyMd: '' };

export default function AgentSkillsPage() {
  const [scope, setScope] = useState<Scope>('company');
  const [platformAdmin, setPlatformAdmin] = useState(false);
  const [items, setItems] = useState<AgentSkillListItem[]>([]);
  const [keyword, setKeyword] = useState('');
  const [source, setSource] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [settings, setSettings] = useState<{ enabled: boolean } | null>(null);

  const [editing, setEditing] = useState<AgentSkillDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [resources, setResources] = useState<ResourceInput[]>([]);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [versions, setVersions] = useState<VersionItem[] | null>(null);
  const [preview, setPreview] = useState<{ catalogBlock: string; bodyPreview: string; chars: number; tokenEstimate: number } | null>(null);
  const [confirm, setConfirm] = useState<{ title: string; body: React.ReactNode; label: string; run: () => void } | null>(null);

  const seq = useRef(0);

  const load = useCallback(async () => {
    const current = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const rows = await listAgentSkills({ scope, keyword: keyword.trim() || undefined, source: source || undefined });
      if (current === seq.current) setItems(rows);
    } catch (e) {
      if (current === seq.current) setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      if (current === seq.current) setLoading(false);
    }
  }, [scope, keyword, source]);

  useEffect(() => {
    getCurrentUser()
      .then((u) => setPlatformAdmin(u.permissions?.includes('COMPANY_MANAGE') ?? false))
      .catch(() => setPlatformAdmin(false));
  }, []);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (scope !== 'company') { setSettings(null); return; }
    getAgentSkillSetting().then(setSettings).catch(() => setSettings(null));
  }, [scope]);

  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);

  const closeEditor = useCallback(() => {
    if (dirty) {
      setConfirm({
        title: '放弃未保存的修改？',
        body: <p>当前修改尚未保存，关闭后将丢失。</p>,
        label: '放弃修改',
        run: () => { setEditing(null); setCreating(false); setDirty(false); setVersions(null); },
      });
      return;
    }
    setEditing(null);
    setCreating(false);
    setVersions(null);
  }, [dirty]);

  const openDetail = useCallback(async (id: string) => {
    if (dirty) { closeEditor(); return; }
    setBusy(true);
    try {
      const detail = await getAgentSkill(id);
      const contents: ResourceInput[] = [];
      for (const meta of detail.resources) {
        const r = await getAgentSkillResource(id, meta.id);
        contents.push({ path: r.path, content: r.content });
      }
      setEditing(detail);
      setCreating(false);
      setForm({ skillKey: detail.skillKey, name: detail.name, description: detail.description, bodyMd: detail.bodyMd });
      setResources(contents);
      setDirty(false);
      setVersions(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载详情失败');
    } finally {
      setBusy(false);
    }
  }, [dirty, closeEditor]);

  const startCreate = () => {
    if (dirty) { closeEditor(); return; }
    setCreating(true);
    setEditing(null);
    setForm(EMPTY_FORM);
    setResources([]);
    setDirty(false);
    setVersions(null);
  };

  const readonly = Boolean(editing && (editing.readOnly || editing.status !== 'draft'));

  const save = async () => {
    setBusy(true);
    try {
      if (creating) {
        const created = await createAgentSkill({ scope, ...form, resources });
        setToast('草稿已保存');
        setCreating(false);
        setEditing(created);
        setDirty(false);
      } else if (editing) {
        const updated = await updateAgentSkill(editing.id, { ...form, resources, lockVersion: editing.lockVersion });
        setEditing(updated);
        setDirty(false);
        setToast('已保存');
      }
      await load();
    } catch (e) {
      handleApiError(e);
    } finally {
      setBusy(false);
    }
  };

  const handleApiError = (e: unknown) => {
    if (e instanceof ApiError) {
      if (e.subCode === 'AGENT_SKILL_VERSION_CONFLICT') {
        setToast('内容已被他人修改，请关闭后重新打开');
      } else if (e.subCode === 'AGENT_SKILL_DUPLICATE_KEY') {
        setToast('该 key 已存在，请打开已有技能新建版本');
      } else if (e.subCode === 'AGENT_SKILL_DRAFT_EXISTS') {
        setToast('该技能已有编辑中的草稿');
      } else {
        setToast(e.message);
      }
      return;
    }
    setToast(e instanceof Error ? e.message : '操作失败');
  };

  const runAction = (title: string, body: React.ReactNode, label: string, run: () => void) =>
    setConfirm({ title, body, label, run });

  const publish = () => runAction('发布技能？', <p>发布后进行中的对话下一轮立即生效；同 key 旧版本会自动归档。</p>, '发布',
    async () => {
      if (!editing) return;
      setBusy(true);
      try {
        const updated = await publishAgentSkill(editing.id);
        setEditing(updated);
        setToast('已发布，下一轮对话生效');
        await load();
      } catch (e) { handleApiError(e); } finally { setBusy(false); }
    });

  const archive = () => runAction('下架技能？', <p>下一轮对话立即不再使用该技能，可通过版本历史恢复。</p>, '下架',
    async () => {
      if (!editing) return;
      setBusy(true);
      try {
        await archiveAgentSkill(editing.id);
        const detail = await getAgentSkill(editing.id);
        setEditing(detail);
        setToast('已下架');
        await load();
      } catch (e) { handleApiError(e); } finally { setBusy(false); }
    });

  const newVersion = async () => {
    if (!editing) return;
    setBusy(true);
    try {
      const draft = await newAgentSkillVersion(editing.id);
      setEditing(draft);
      setForm({ skillKey: draft.skillKey, name: draft.name, description: draft.description, bodyMd: draft.bodyMd });
      const contents: ResourceInput[] = [];
      for (const meta of draft.resources) {
        const r = await getAgentSkillResource(draft.id, meta.id);
        contents.push({ path: r.path, content: r.content });
      }
      setResources(contents);
      setDirty(false);
      setToast(`已创建 v${draft.version} 草稿`);
      await load();
    } catch (e) { handleApiError(e); } finally { setBusy(false); }
  };

  const removeDraft = () => runAction('删除草稿？', <p>删除后不可恢复。</p>, '删除',
    async () => {
      if (!editing) return;
      setBusy(true);
      try {
        await deleteAgentSkillDraft(editing.id);
        setEditing(null);
        setToast('草稿已删除');
        await load();
      } catch (e) { handleApiError(e); } finally { setBusy(false); }
    });

  const showPreview = async () => {
    setBusy(true);
    try {
      const result = await previewAgentSkill({ scope, ...form, resources });
      setPreview(result);
    } catch (e) { handleApiError(e); } finally { setBusy(false); }
  };

  const showVersions = async () => {
    if (!editing) return;
    try {
      setVersions(await listAgentSkillVersions(editing.id));
    } catch (e) { handleApiError(e); }
  };

  const toggleSetting = async () => {
    if (!settings) return;
    const next = !settings.enabled;
    setSettings({ enabled: next });
    try {
      await updateAgentSkillSetting(next);
      setToast(next ? '已启用技能' : '已停用技能');
    } catch (e) {
      setSettings({ enabled: !next });
      handleApiError(e);
    }
  };

  const sourceOptions = useMemo(() => (
    scope === 'platform'
      ? [['', '全部来源'], ['builtin', '内置'], ['platform', '平台'], ['mcp', 'MCP']]
      : [['', '全部来源'], ['tenant', '企业'], ['builtin', '内置'], ['platform', '平台']]
  ), [scope]);

  return (
    <div className="mx-auto max-w-[1180px] px-6 py-6">
      <div className="mb-5 flex items-start justify-between gap-4">
        <div>
          <h1 className="text-[28px] font-bold leading-tight text-foreground">模型技能</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            给模型按需加载的方法论与话术，不是「分身」。技能正文只在被调用时进入上下文。
          </p>
        </div>
        <PageHelp
          title="模型技能使用说明"
          intro="给模型按需加载的方法论与话术，不是「分身」。正文只在被调用时进入上下文。"
          steps={[
            { title: '新建草稿', desc: '填写 Key、名称、描述与正文，保存为草稿。' },
            { title: '预览模型视角', desc: '查看模型实际看到的目录片段，以及调用 use_skill 后的正文。' },
            { title: '发布', desc: '发布后下一轮对话立即生效；同 key 旧版本自动归档。' },
          ]}
          actions={[
            { label: '保存草稿', desc: '只更新草稿，不影响线上版本。', tone: 'primary' },
            { label: '发布', desc: '先保存再发布；有未保存修改时按钮不可用。', tone: 'primary' },
            { label: '新建版本', desc: '复制当前版本内容生成新草稿，旧版本保留在版本历史。' },
            { label: '下架', desc: '下一轮对话立即不再使用，可通过版本历史恢复。', tone: 'danger' },
          ]}
          notes={[
            '企业级技能只在本企业对话中生效；平台级技能所有企业可见，企业管理员只读。',
            '目录缓存最多 5 分钟；正文不缓存，发布即生效。',
            '技能只提供文本，不授予任何业务数据访问。',
          ]}
        />
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="flex rounded-[10px] border border-border bg-surface p-1">
          <button
            className={`rounded-[8px] px-3 py-1.5 text-sm ${scope === 'company' ? 'bg-primary text-white' : 'text-muted-foreground'}`}
            onClick={() => { if (dirty) { closeEditor(); return; } setScope('company'); }}
          >本企业技能</button>
          {platformAdmin && (
            <button
              className={`rounded-[8px] px-3 py-1.5 text-sm ${scope === 'platform' ? 'bg-primary text-white' : 'text-muted-foreground'}`}
              onClick={() => { if (dirty) { closeEditor(); return; } setScope('platform'); }}
            >平台技能</button>
          )}
        </div>

        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder="搜索 key 或名称"
          className="h-9 w-56 rounded-[10px] border border-border bg-surface px-3 text-sm"
        />
        <select
          value={source}
          onChange={(e) => setSource(e.target.value)}
          className="h-9 rounded-[10px] border border-border bg-surface px-3 text-sm"
        >
          {sourceOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>

        <div className="ml-auto flex items-center gap-3">
          {scope === 'company' && settings && (
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <input type="checkbox" checked={settings.enabled} onChange={toggleSetting} />
              本企业启用技能
            </label>
          )}
          <Button onClick={startCreate}>+ 新建</Button>
        </div>
      </div>

      {scope === 'company' && settings && !settings.enabled && (
        <div className="mb-3 rounded-[10px] border border-warning bg-warning-bg px-4 py-2 text-sm text-warning-text">
          本企业技能已停用：对话不会注入目录，工具也不可用。
        </div>
      )}

      {error && (
        <div className="mb-3 flex items-center justify-between rounded-[10px] border border-danger bg-danger-bg px-4 py-2 text-sm text-danger">
          <span>{error}</span>
          <Button variant="ghost" size="sm" onClick={() => void load()}>重试</Button>
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-border bg-surface-2">
        {loading ? (
          <div className="flex justify-center py-16"><LoadingSpinner /></div>
        ) : items.length === 0 ? (
          <div className="py-16 text-center text-sm text-muted-foreground">
            还没有技能。点击「新建」创建第一个，或到平台技能中查看内置示例。
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">名称</th>
                <th className="px-4 py-3 font-medium">Key</th>
                <th className="px-4 py-3 font-medium">来源</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium">版本</th>
                <th className="px-4 py-3 font-medium">更新时间</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={`${item.companyId ?? 'platform'}-${item.skillKey}`} className="border-b border-border last:border-0">
                  <td className="px-4 py-3 font-medium text-foreground">{item.name}</td>
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{item.skillKey}</td>
                  <td className="px-4 py-3 text-muted-foreground">{agentSkillSourceLabel(item.source)}</td>
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2 py-0.5 text-xs ${agentSkillStatusClass(item.effectiveStatus)}`}>
                      {item.effectiveStatus === 'published' ? `已发布 v${item.liveVersion}` : agentSkillStatusLabel(item.effectiveStatus)}
                    </span>
                    {item.draftVersion != null && (
                      <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
                        草稿 v{item.draftVersion}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{item.liveVersion ?? '—'}</td>
                  <td className="px-4 py-3 text-muted-foreground">{new Date(item.updatedAt).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right">
                    <Button size="sm" variant="ghost" onClick={() => void openDetail(item.liveId ?? item.draftId ?? '')}>
                      查看
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {(editing || creating) && (
        <div className="fixed inset-0 z-50 flex justify-end bg-black/30">
          <div className="h-full w-[min(100vw,860px)] overflow-y-auto border-l border-border bg-surface">
            <div className="flex items-center justify-between border-b border-border px-6 py-4">
              <div>
                <h2 className="text-lg font-semibold">{creating ? '新建技能' : form.name || '编辑技能'}</h2>
                <p className="text-xs text-muted-foreground">
                  {creating ? `范围：${scope === 'platform' ? '平台级' : '本企业'}` : `Key：${form.skillKey}`}
                  {editing && ` · v${editing.version} · ${agentSkillStatusLabel(editing.status)}`}
                </p>
              </div>
              <Button variant="ghost" onClick={closeEditor}>关闭</Button>
            </div>

            <div className="space-y-4 px-6 py-5">
              {creating && (
                <label className="block">
                  <span className="text-sm font-medium">Key</span>
                  <input
                    value={form.skillKey}
                    onChange={(e) => { setForm({ ...form, skillKey: e.target.value }); setDirty(true); }}
                    placeholder="price-objection-handling"
                    className="mt-1 h-9 w-full rounded-[10px] border border-border bg-surface px-3 font-mono text-sm"
                  />
                  <span className="text-xs text-muted-foreground">小写字母/数字/连字符，创建后不可修改</span>
                </label>
              )}
              <label className="block">
                <span className="text-sm font-medium">名称</span>
                <input
                  value={form.name}
                  disabled={readonly}
                  onChange={(e) => { setForm({ ...form, name: e.target.value }); setDirty(true); }}
                  className="mt-1 h-9 w-full rounded-[10px] border border-border bg-surface px-3 text-sm disabled:opacity-60"
                />
              </label>
              <label className="block">
                <span className="text-sm font-medium">描述</span>
                <textarea
                  value={form.description}
                  disabled={readonly}
                  rows={2}
                  maxLength={1024}
                  onChange={(e) => { setForm({ ...form, description: e.target.value }); setDirty(true); }}
                  className="mt-1 w-full rounded-[10px] border border-border bg-surface px-3 py-2 text-sm disabled:opacity-60"
                />
                <span className="text-xs text-muted-foreground">模型只会看到这段描述，决定是否加载正文</span>
              </label>
              <label className="block">
                <span className="text-sm font-medium">正文（Markdown / 纯文本）</span>
                <textarea
                  value={form.bodyMd}
                  disabled={readonly}
                  rows={14}
                  onChange={(e) => { setForm({ ...form, bodyMd: e.target.value }); setDirty(true); }}
                  className="mt-1 w-full rounded-[10px] border border-border bg-surface px-3 py-2 font-mono text-sm disabled:opacity-60"
                />
              </label>

              <div>
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">参考资料（references/）</span>
                  {!readonly && (
                    <Button size="sm" variant="ghost" onClick={() => {
                      setResources([...resources, { path: `references/${resources.length + 1}.md`, content: '' }]);
                      setDirty(true);
                    }}>+ 添加</Button>
                  )}
                </div>
                {resources.length === 0 && <p className="mt-1 text-xs text-muted-foreground">没有参考资料</p>}
                {resources.map((r, index) => (
                  <div key={index} className="mt-2 rounded-[10px] border border-border p-3">
                    <div className="flex items-center gap-2">
                      <input
                        value={r.path}
                        disabled={readonly}
                        onChange={(e) => {
                          const next = [...resources]; next[index] = { ...r, path: e.target.value };
                          setResources(next); setDirty(true);
                        }}
                        className="h-8 flex-1 rounded-[8px] border border-border bg-surface px-2 font-mono text-xs"
                      />
                      {!readonly && (
                        <Button size="sm" variant="ghost" onClick={() => {
                          setResources(resources.filter((_, i) => i !== index)); setDirty(true);
                        }}>移除</Button>
                      )}
                    </div>
                    <textarea
                      value={r.content}
                      disabled={readonly}
                      rows={4}
                      onChange={(e) => {
                        const next = [...resources]; next[index] = { ...r, content: e.target.value };
                        setResources(next); setDirty(true);
                      }}
                      className="mt-2 w-full rounded-[8px] border border-border bg-surface px-2 py-1 font-mono text-xs"
                    />
                  </div>
                ))}
              </div>

              {versions && (
                <div className="rounded-[10px] border border-border p-3">
                  <p className="mb-2 text-sm font-medium">版本历史</p>
                  <table className="w-full text-xs">
                    <tbody>
                      {versions.map((v) => (
                        <tr key={v.id} className="border-b border-border last:border-0">
                          <td className="py-1.5">v{v.version}</td>
                          <td className="py-1.5">{agentSkillStatusLabel(v.status)}</td>
                          <td className="py-1.5 text-muted-foreground">{new Date(v.updatedAt).toLocaleString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="sticky bottom-0 flex flex-wrap items-center gap-2 border-t border-border bg-surface px-6 py-4">
              {(creating || (editing && editing.status === 'draft')) && (
                <>
                  <Button onClick={save} disabled={busy}>{creating ? '保存草稿' : '保存'}</Button>
                  <Button variant="secondary" onClick={publish} disabled={busy || creating || dirty}>发布</Button>
                </>
              )}
              {editing && editing.status === 'published' && (
                <>
                  <Button variant="secondary" onClick={newVersion} disabled={busy}>新建版本</Button>
                  <Button variant="danger" onClick={archive} disabled={busy}>下架</Button>
                </>
              )}
              {editing && editing.status === 'archived' && (
                <Button variant="secondary" onClick={newVersion} disabled={busy}>新建版本</Button>
              )}
              {editing && editing.status === 'draft' && (
                <Button variant="danger" onClick={removeDraft} disabled={busy}>删除草稿</Button>
              )}
              <Button variant="ghost" onClick={showPreview} disabled={busy}>预览模型视角</Button>
              {editing && <Button variant="ghost" onClick={showVersions} disabled={busy}>版本历史</Button>}
              {dirty && <span className="ml-auto text-xs text-warning-text">有未保存的修改</span>}
            </div>
          </div>
        </div>
      )}

      {preview && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4" role="dialog">
          <div className="max-h-[85vh] w-[min(100vw,760px)] overflow-y-auto rounded-xl bg-surface p-6">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-lg font-semibold">模型视角预览</h3>
              <Button variant="ghost" onClick={() => setPreview(null)}>关闭</Button>
            </div>
            <p className="text-xs text-muted-foreground">
              正文 {preview.chars} 字符，约 {preview.tokenEstimate} token；正文不会常驻上下文。
            </p>
            <p className="mt-4 text-sm font-medium">目录片段</p>
            <pre className="mt-1 whitespace-pre-wrap rounded-[10px] bg-surface-2 p-3 text-xs">{preview.catalogBlock}</pre>
            <p className="mt-4 text-sm font-medium">调用 use_skill 后</p>
            <pre className="mt-1 max-h-[40vh] overflow-y-auto whitespace-pre-wrap rounded-[10px] bg-surface-2 p-3 text-xs">{preview.bodyPreview}</pre>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={Boolean(confirm)}
        title={confirm?.title ?? ''}
        confirmLabel={confirm?.label ?? '确认'}
        busy={busy}
        onCancel={() => setConfirm(null)}
        onConfirm={() => { const run = confirm?.run; setConfirm(null); run?.(); }}
      >
        {confirm?.body}
      </ConfirmDialog>

      <Toast message={toast} onDone={() => setToast(null)} />
    </div>
  );
}
