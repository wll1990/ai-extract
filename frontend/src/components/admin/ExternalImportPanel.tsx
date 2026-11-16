'use client';

import React, { useEffect, useMemo, useState } from 'react';
import {
  getImportPrompt, importCases, importKnowledge, CATEGORY_LABELS,
  type ImportResult,
} from '@/lib/api/knowledge';

/**
 * 外部预提取导入面板。
 *
 * 用户把提示词复制到自己的大模型里，先产出结构化 JSON，再贴回这里批量导入。
 * 导入仍走站内质量门禁与查重：不达标条目给出原因，书籍/案例只落草稿。
 */
const RESULT_LABELS: Record<string, string> = { win: '赢单', loss: '丢单', delay: '搁置' };

function asText(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(v => String(v)).join('；');
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v != null && String(v).trim() !== '')
      .map(([k, v]) => `${k}：${v}`)
      .join('，');
  }
  return String(value);
}

export function ExternalImportPanel({ kind, onImported }: {
  kind: 'case' | 'knowledge';
  onImported?: () => void;
}) {
  const [prompt, setPrompt] = useState('');
  const [promptError, setPromptError] = useState<string | null>(null);
  const [sourceKind, setSourceKind] = useState<'internal' | 'book'>('internal');
  const [sourceName, setSourceName] = useState('');
  const [jsonText, setJsonText] = useState('');
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    getImportPrompt(kind)
      .then(r => setPrompt(r.prompt || ''))
      .catch(e => setPromptError(e instanceof Error ? e.message : '提示词加载失败'));
  }, [kind]);

  const items = useMemo(() => {
    if (!jsonText.trim()) return null;
    try {
      const parsed = JSON.parse(jsonText);
      if (Array.isArray(parsed)) return parsed as unknown[];
      if (parsed && Array.isArray(parsed.items)) return parsed.items as unknown[];
      return null;
    } catch {
      return null;
    }
  }, [jsonText]);

  const copyPrompt = async () => {
    try {
      await navigator.clipboard.writeText(prompt);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setError('复制失败，请手动全选复制');
    }
  };

  const downloadPrompt = () => {
    const blob = new Blob([prompt], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = kind === 'case' ? '案例预提取提示词.md' : '知识预提取提示词.md';
    a.click();
    URL.revokeObjectURL(url);
  };

  const run = async (dryRun: boolean) => {
    setError(null);
    setResult(null);
    if (!jsonText.trim()) { setError('请先粘贴 JSON'); return; }
    if (!items || items.length === 0) {
      setError('JSON 解析失败：需要一个数组，或 {"items":[...]}；请检查模型输出');
      return;
    }
    setBusy(true);
    try {
      const req = { sourceKind, sourceName: sourceName.trim() || undefined, dryRun, items };
      const r = kind === 'case' ? await importCases(req) : await importKnowledge(req);
      setResult(r);
      if (!dryRun && (r.imported > 0 || r.needsSupplement > 0)) {
        onImported?.();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '导入失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-border bg-surface-2 p-5 text-sm text-foreground">
        <p className="font-semibold">怎么用（三步）</p>
        <ol className="mt-2 list-decimal space-y-1 pl-5 text-muted-foreground">
          <li>复制下面的提示词，到你常用的模型（DeepSeek / ChatGPT 等）里，替换书本章节或资料正文。</li>
          <li>模型输出一段 JSON 数组；把它粘回下面，或上传 .json 文件。</li>
          <li>先「校验预览」看哪些能导入、哪些缺字段，再「确认导入」。导入后仍需在库内审批生效。</li>
        </ol>
      </div>

      <div className="rounded-xl border border-border bg-surface-2 p-4">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm font-semibold text-foreground">第一步：复制提示词</p>
          <div className="flex gap-2">
            <button onClick={copyPrompt} disabled={!prompt}
              className="rounded-full bg-primary px-4 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50">
              {copied ? '已复制' : '复制提示词'}
            </button>
            <button onClick={downloadPrompt} disabled={!prompt}
              className="rounded-full border border-border px-4 py-1.5 text-xs text-foreground hover:bg-surface disabled:opacity-50">
              下载 .md
            </button>
          </div>
        </div>
        {promptError && <p className="text-xs text-danger">{promptError}</p>}
        <textarea readOnly value={prompt} rows={8}
          className="w-full resize-y rounded-md border border-border bg-surface px-3 py-2 text-xs text-muted-foreground" />
        <p className="mt-1 text-xs text-muted-foreground">
          提示词里的 <code>{'{{BOOK_TITLE}}'}</code> / <code>{'{{CHAPTER}}'}</code> / <code>{'{{TEXT}}'}</code> 由你在外部替换。
        </p>
      </div>

      <div className="rounded-xl border border-border bg-surface-2 p-4">
        <p className="mb-2 text-sm font-semibold text-foreground">第二步：选择资料性质并粘贴 JSON</p>
        <div className="mb-3 grid grid-cols-2 gap-3">
          <button onClick={() => setSourceKind('internal')}
            className={`rounded-xl border p-3 text-left ${sourceKind === 'internal' ? 'border-primary bg-primary-light' : 'border-border bg-surface hover:bg-surface-2'}`}>
            <p className="text-sm font-medium text-foreground">企业自有资料</p>
            <p className="mt-1 text-xs text-muted-foreground">标准脱敏，保留客户公司/项目/金额；案例合规即进待审。</p>
          </button>
          <button onClick={() => setSourceKind('book')}
            className={`rounded-xl border p-3 text-left ${sourceKind === 'book' ? 'border-primary bg-primary-light' : 'border-border bg-surface hover:bg-surface-2'}`}>
            <p className="text-sm font-medium text-foreground">书籍 / 课程</p>
            <p className="mt-1 text-xs text-muted-foreground">强脱敏（公司/政府/项目/金额），案例一律先落草稿并标「二手」。</p>
          </button>
        </div>
        <input value={sourceName} onChange={e => setSourceName(e.target.value)}
          placeholder={sourceKind === 'book' ? '书名或课程名（会拼进每条的出处）' : '资料名称（可选）'}
          className="mb-3 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
        <textarea value={jsonText} onChange={e => setJsonText(e.target.value)} rows={10}
          placeholder='粘贴模型输出的 JSON，例如：[{"title":"...","content":"..."}]'
          className="w-full resize-y rounded-md border border-border bg-surface px-3 py-2 font-mono text-xs text-foreground" />
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <label className="cursor-pointer rounded-full border border-border px-4 py-1.5 text-xs text-foreground hover:bg-surface">
            上传 .json
            <input type="file" accept=".json,application/json" className="hidden"
              onChange={async e => {
                const f = e.target.files?.[0];
                if (!f) return;
                setJsonText(await f.text());
                setResult(null);
                setError(null);
              }} />
          </label>
          <span className="text-xs text-muted-foreground">
            {items ? `识别到 ${items.length} 条` : jsonText.trim() ? 'JSON 解析失败' : '尚未粘贴内容'}
          </span>
          <span className="ml-auto text-xs text-muted-foreground">单次最多 200 条</span>
        </div>
        {items && items.length > 0 && (
          <div className="mt-3 rounded-lg border border-border bg-surface p-3">
            <div className="mb-2 flex items-center justify-between">
              <p className="text-xs font-semibold text-foreground">导入前预览（{items.length} 条）</p>
              <span className="text-[11px] text-muted-foreground">
                先看标题、结论与出处；与库内已有内容重复要在「校验预览」时才显示
              </span>
            </div>
            <div className="max-h-72 space-y-2 overflow-y-auto pr-1">
              {items.slice(0, 20).map((it, idx) => {
                const row = (it && typeof it === 'object') ? it as Record<string, unknown> : {};
                const issues: string[] = [];
                const titleKey = asText(row.title).trim().toLowerCase();
                if (!titleKey) issues.push('缺标题');
                else if (titleKey && (items.filter(x => x && typeof x === 'object'
                  && asText((x as Record<string, unknown>).title).trim().toLowerCase() === titleKey).length > 1)) {
                  issues.push('本次内标题重复');
                }
                if (kind === 'knowledge') {
                  if (!asText(row.content).trim()) issues.push('缺正文');
                  if (!asText(row.category).trim()) issues.push('缺分类');
                } else {
                  if (!['win', 'loss', 'delay'].includes(asText(row.result))) issues.push('结果缺失或非法');
                  if (!asText(row.objection).trim()) issues.push('缺核心异议');
                }
                if (!asText(row.source).trim() && !sourceName.trim()) issues.push('缺出处');
                return (
                  <div key={idx} className="rounded-md border border-border/70 bg-surface-2 px-3 py-2 text-xs">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-muted-foreground">#{idx + 1}</span>
                      <span className="font-medium text-foreground">{asText(row.title) || '（无标题）'}</span>
                      {kind === 'knowledge' ? (
                        <span className="rounded-full bg-surface px-2 py-0.5 text-[11px] text-muted-foreground">
                          {CATEGORY_LABELS[asText(row.category)] || asText(row.category) || '未分类'}
                        </span>
                      ) : (
                        <span className="rounded-full bg-surface px-2 py-0.5 text-[11px] text-muted-foreground">
                          {RESULT_LABELS[asText(row.result)] || asText(row.result) || '结果未填'}
                          {asText(row.sceneTag) ? ` · ${asText(row.sceneTag)}` : ''}
                        </span>
                      )}
                      {issues.length > 0 && (
                        <span className="rounded-full bg-danger-bg px-2 py-0.5 text-[11px] text-danger">
                          {issues.join('、')}
                        </span>
                      )}
                    </div>
                    <p className="mt-1 line-clamp-2 text-muted-foreground">
                      {kind === 'knowledge' ? asText(row.content) : (
                        <>
                          {asText(row.objection) && <span>异议：{asText(row.objection)}　</span>}
                          {asText(row.keyActions) && <span>动作：{asText(row.keyActions)}　</span>}
                          {asText(row.dialogueQuote) && <span>原话：{asText(row.dialogueQuote)}</span>}
                        </>
                      )}
                    </p>
                    {asText(row.source) && (
                      <p className="mt-0.5 text-[11px] text-muted-foreground">出处：{asText(row.source)}</p>
                    )}
                  </div>
                );
              })}
              {items.length > 20 && (
                <p className="text-[11px] text-muted-foreground">还有 {items.length - 20} 条未展示，导入结果里会逐条列出。</p>
              )}
            </div>
          </div>
        )}

        <div className="mt-3 flex gap-2">
          <button onClick={() => run(true)} disabled={busy || !items}
            className="rounded-full border border-primary px-5 py-2 text-sm font-medium text-primary hover:bg-primary-light disabled:opacity-50">
            校验预览
          </button>
          <button onClick={() => run(false)} disabled={busy || !items}
            className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {busy ? '处理中…' : '确认导入'}
          </button>
        </div>
        {error && <p className="mt-2 text-sm text-danger">{error}</p>}
      </div>

      {result && (
        <div className="rounded-xl border border-border bg-surface-2 p-4">
          <div className="mb-2 flex flex-wrap items-center gap-3 text-sm">
            <span className="font-semibold text-foreground">{result.dryRun ? '校验结果（未落库）' : '导入结果'}</span>
            <span className="text-success">可导入/已导入 {result.imported}</span>
            {kind === 'case' && <span className="text-warning-text">需补齐草稿 {result.needsSupplement}</span>}
            <span className="text-danger">跳过 {result.skipped}</span>
            <span className="text-muted-foreground">共 {result.total} 条</span>
          </div>
          <div className="max-h-80 space-y-1 overflow-y-auto pr-1 text-xs">
            {result.items.map(it => (
              <div key={it.index} className="flex gap-2 rounded-md bg-surface px-3 py-1.5">
                <span className="w-8 shrink-0 text-muted-foreground">#{it.index + 1}</span>
                <span className={`w-24 shrink-0 ${it.result === 'skipped' ? 'text-danger' : it.result === 'needs_supplement' ? 'text-warning-text' : 'text-success'}`}>
                  {it.result === 'imported' ? '可导入' : it.result === 'needs_supplement' ? '需补齐' : '跳过'}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="text-foreground">{it.title || '（无标题）'}</span>
                  {it.reason && <span className="ml-2 text-muted-foreground">{it.reason}</span>}
                </span>
              </div>
            ))}
          </div>
          {!result.dryRun && (
            <p className="mt-2 text-xs text-muted-foreground">
              {kind === 'case'
                ? '已导入的案例进入「待审核/草稿」，需要补齐字段并审批后才会被分身检索。'
                : '已导入的知识进入「待审核」，审批生效后才会被分身检索。'}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
