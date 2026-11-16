'use client';

import React from 'react';

interface EvidenceItem {
  type?: string;
  sceneTag?: string;
  text?: string;
}

interface Props {
  evidence: string | null;
  jobId?: string | null;
  compact?: boolean;
}

/** 解析证据 JSON；历史/异常数据按原文展示，不吞掉内容 */
function parseEvidence(raw: string | null): EvidenceItem[] | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw);
    if (Array.isArray(v)) {
      return v.map(x => (typeof x === 'object' && x ? x as EvidenceItem : { text: String(x) }));
    }
  } catch { /* 非 JSON */ }
  return [{ text: raw }];
}

/**
 * AI 草稿证据面板：展示生成依据（真实缺口/失败题/对话摘录）。
 * 让审核人看到「为什么生成这条」，而不是只看正文。
 */
export function EvidencePanel({ evidence, jobId, compact }: Props) {
  const items = parseEvidence(evidence);
  if (!items || items.length === 0) {
    return <span className="text-xs text-muted-foreground">无生成证据</span>;
  }
  return (
    <div className={compact ? 'space-y-1' : 'space-y-1.5'}>
      {items.slice(0, compact ? 3 : 10).map((item, i) => (
        <div key={i} className="rounded-md border border-border bg-surface px-2.5 py-1.5 text-xs text-foreground">
          {item.sceneTag && <span className="mr-1 rounded bg-primary-light px-1.5 py-0.5 text-[10px] text-primary">{item.sceneTag}</span>}
          <span>{item.text || '（空）'}</span>
        </div>
      ))}
      {items.length > (compact ? 3 : 10) && (
        <p className="text-[10px] text-muted-foreground">还有 {items.length - (compact ? 3 : 10)} 条证据</p>
      )}
      {jobId && (
        <p className="text-[10px] text-muted-foreground">生成任务 {jobId.slice(0, 8)}</p>
      )}
    </div>
  );
}
