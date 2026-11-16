'use client';

import React from 'react';

const STYLES: Record<string, { label: string; cls: string }> = {
  indexed: { label: '已索引', cls: 'bg-success-bg text-success' },
  indexing: { label: '索引中', cls: 'bg-primary-light text-primary' },
  failed: { label: '索引失败', cls: 'bg-danger-bg text-danger' },
  pending: { label: '未索引', cls: 'bg-warning-bg text-warning-text' },
};

/**
 * 向量索引状态标签 — 与「审核状态」分开展示。
 *
 * 审核通过 ≠ 可被检索：embedding 写入失败时知识处于「已生效 · 未索引」。
 */
export function IndexStatusBadge({ status, title }: { status?: string | null; title?: string }) {
  const s = STYLES[status || 'pending'] || STYLES.pending;
  return (
    <span
      title={title || '审核状态与检索状态是两件事：已生效但未索引时，分身检索不到这条内容'}
      className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${s.cls}`}
    >
      {s.label}
    </span>
  );
}
