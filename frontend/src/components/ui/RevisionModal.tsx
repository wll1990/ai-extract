'use client';

import React, { useEffect, useState } from 'react';
import type { RevisionSuggestion } from '@/lib/api/knowledge';
import { LoadingSpinner } from './LoadingSpinner';

interface RevisionModalProps {
  open: boolean;
  /** 弹窗标题，如「AI 整理知识」 */
  title: string;
  /** 当前条目标题，让用户确认改的是哪一条 */
  entryTitle: string;
  /** 当前正文，供用户边看边写指令 */
  currentContent: string;
  /** 常用修改指令，一键填入 */
  presets: string[];
  /** 让 AI 针对当前内容给出建议 */
  onLoadSuggestions: () => Promise<RevisionSuggestion[]>;
  /** 生成新版本（指令由用户确认后提交） */
  onSubmit: (instruction: string) => Promise<void>;
  /** 改写结果如何落库：草稿直接覆盖 / 已生效生成新版本 */
  behaviorNote?: string;
  /** 提交按钮文案 */
  submitLabel?: string;
  onClose: () => void;
}

/**
 * AI 整理弹窗 —— 知识/案例共用。
 *
 * <p>解决原 `window.prompt` 的三个问题：看不到原文、不知道该写什么、AI 不给建议。</p>
 */
export function RevisionModal({
  open, title, entryTitle, currentContent, presets,
  behaviorNote, submitLabel,
  onLoadSuggestions, onSubmit, onClose,
}: RevisionModalProps) {
  const [instruction, setInstruction] = useState('');
  const [suggestions, setSuggestions] = useState<RevisionSuggestion[]>([]);
  const [loadingSuggestions, setLoadingSuggestions] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [showContent, setShowContent] = useState(true);

  useEffect(() => {
    if (open) {
      setInstruction('');
      setSuggestions([]);
      setError('');
      setSubmitting(false);
      setLoadingSuggestions(false);
      setShowContent(true);
    }
  }, [open]);

  if (!open) return null;

  const loadSuggestions = async () => {
    setLoadingSuggestions(true);
    setError('');
    try {
      setSuggestions(await onLoadSuggestions());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'AI 建议获取失败，可直接填写修改要求');
    } finally {
      setLoadingSuggestions(false);
    }
  };

  const submit = async () => {
    const text = instruction.trim();
    if (!text) {
      setError('请先填写修改要求');
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      await onSubmit(text);
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成失败，请稍后重试');
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div
        className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-surface p-6 shadow-xl"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-bold text-foreground">{title}</h2>
            <p className="mt-1 text-sm text-muted-foreground">正在改写：{entryTitle}</p>
            {behaviorNote && (
              <p className="mt-1 rounded-md bg-primary-light px-2 py-1 text-xs text-primary">{behaviorNote}</p>
            )}
          </div>
          <button onClick={onClose} className="text-sm text-muted-foreground hover:text-foreground">关闭</button>
        </div>

        <div className="mb-4 rounded-xl border border-border bg-surface-2">
          <button
            onClick={() => setShowContent(v => !v)}
            className="flex w-full items-center justify-between px-4 py-2.5 text-xs font-semibold text-muted-foreground"
          >
            <span>当前内容（{currentContent.length} 字）</span>
            <span>{showContent ? '收起' : '展开'}</span>
          </button>
          {showContent && (
            <div className="max-h-44 overflow-y-auto whitespace-pre-wrap border-t border-border px-4 py-3 text-sm leading-6 text-foreground">
              {currentContent || '（无正文）'}
            </div>
          )}
        </div>

        <div className="mb-3">
          <div className="mb-2 flex items-center justify-between">
            <label className="text-sm font-medium text-foreground">想怎么改？</label>
            <button
              onClick={loadSuggestions}
              disabled={loadingSuggestions}
              className="rounded-full border border-primary px-3 py-1 text-xs font-medium text-primary hover:bg-primary-light disabled:opacity-50"
            >
              {loadingSuggestions ? 'AI 分析中…' : '让 AI 给建议'}
            </button>
          </div>
          {loadingSuggestions && <LoadingSpinner />}
          {suggestions.length > 0 && (
            <div className="mb-2 flex flex-wrap gap-2">
              {suggestions.map(s => (
                <button
                  key={s.title}
                  onClick={() => setInstruction(s.instruction)}
                  title={s.instruction}
                  className="rounded-full bg-primary-light px-3 py-1 text-xs text-primary hover:opacity-80"
                >
                  {s.title}
                </button>
              ))}
            </div>
          )}
          <div className="mb-2 flex flex-wrap gap-2">
            {presets.map(p => (
              <button
                key={p}
                onClick={() => setInstruction(p)}
                className="rounded-full border border-border px-3 py-1 text-xs text-muted-foreground hover:bg-surface-2"
              >
                {p}
              </button>
            ))}
          </div>
          <textarea
            value={instruction}
            onChange={e => setInstruction(e.target.value)}
            rows={3}
            placeholder="例如：压缩到 300 字以内；补充适用边界；把价格条款更新为 2026 版"
            className="w-full resize-none rounded-xl border border-border bg-surface px-3 py-2 text-sm text-foreground"
          />
        </div>

        {error && <p className="mb-3 text-sm text-danger">{error}</p>}

        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="rounded-full px-4 py-2 text-sm text-muted-foreground hover:text-foreground">
            取消
          </button>
          <button
            onClick={submit}
            disabled={submitting}
            className="rounded-full bg-primary px-5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {submitting ? '生成中…' : (submitLabel || '生成改写结果')}
          </button>
        </div>
      </div>
    </div>
  );
}
