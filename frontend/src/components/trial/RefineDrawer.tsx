'use client';

import React, { useMemo, useState } from 'react';
import {
  applyDraft, discardDraft, getDraft, refineGrain, revertDraft, submitSuggestion,
  type RefineEvent, type TrialGrain,
} from '@/lib/api/trial';

const FIELD_LABELS: Record<string, string> = {
  sceneDescription: '场景描述',
  expertThought: '专家思维',
  standardScript: '标准话术',
  commonMistakes: '常见错误',
  applicableCondition: '适用条件',
};

type Phase = 'editing' | 'applying' | 'ready' | 'suggested';

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

/**
 * 纠偏抽屉 —— 左边用自然语言说哪里不对，右边逐段对照修改。
 *
 * 独立抽屉，不混进试问消息流：试问是「看分身答得怎么样」，这里是「改这条经验」。
 */
export function RefineDrawer({
  skillId, grain, published, canApplyDirectly, lastQuestion, onClose, onApplied, onRetest,
}: {
  skillId: string;
  grain: TrialGrain;
  published: boolean;
  canApplyDirectly: boolean;
  lastQuestion?: string;
  onClose: () => void;
  onApplied: () => void;
  onRetest: (question: string) => void;
}) {
  const [messages, setMessages] = useState<Array<{ role: 'user' | 'assistant'; content: string }>>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [streamReply, setStreamReply] = useState('');
  const [refine, setRefine] = useState<RefineEvent | null>(null);
  const [selected, setSelected] = useState<Record<string, boolean>>({});
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [phase, setPhase] = useState<Phase>('editing');
  const [embeddingStatus, setEmbeddingStatus] = useState('');
  const [draftStatus, setDraftStatus] = useState('draft');
  const [confirmPublished, setConfirmPublished] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');

  const changed = refine?.changed ?? [];
  const selectedCount = useMemo(
    () => changed.filter(k => selected[k]).length, [changed, selected]);

  const send = () => {
    const text = input.trim();
    if (!text || sending) return;
    setMessages(prev => [...prev, { role: 'user', content: text }]);
    setInput('');
    setSending(true);
    setStreamReply('');
    setError('');
    let reply = '';
    refineGrain(skillId, grain.id, { message: text, draftId: refine?.draftId }, {
      onChunk: chunk => { reply += chunk; setStreamReply(reply); },
      onEvent: (type, data) => {
        if (type !== 'refine') return;
        const d = data as unknown as RefineEvent;
        setRefine(d);
        setSelected(Object.fromEntries(d.changed.map(k => [k, true])));
        setEdits(Object.fromEntries(
          Object.entries(d.fields).map(([k, v]) => [k, v ?? ''])));
        setMessages(prev => [...prev, { role: 'assistant', content: d.reply || reply }]);
        setStreamReply('');
      },
      onDone: () => setSending(false),
      onError: msg => { setSending(false); setStreamReply(''); setError(msg); },
    });
  };

  const apply = async () => {
    if (!refine) return;
    const fields: Record<string, string> = {};
    for (const key of changed) {
      if (selected[key]) fields[key] = edits[key] ?? '';
    }
    if (Object.keys(fields).length === 0) {
      setError('至少勾选一段修改');
      return;
    }
    setPhase('applying');
    setError('');
    try {
      await applyDraft(skillId, grain.id, refine.draftId,
        published && canApplyDirectly ? confirmPublished : false, fields);
      onApplied();
      setDraftStatus('applied');
      setToast('已写回颗粒，正在重建索引…');
      const status = await waitForEmbedding(refine.draftId);
      setPhase('ready');
      setToast(status === 'ready' ? '分身已经记住这条修改' : '索引重建失败');
    } catch (e) {
      setPhase('editing');
      setError(e instanceof Error ? e.message : '应用失败，请重试');
    }
  };

  /** 轮询草稿状态，直到向量重建完成；返回最终 embeddingStatus */
  const waitForEmbedding = async (targetDraftId: string): Promise<string> => {
    let status = 'pending';
    setEmbeddingStatus(status);
    for (let i = 0; i < 30; i++) {
      await sleep(2000);
      const state = await getDraft(skillId, grain.id, targetDraftId);
      status = state.embeddingStatus;
      setEmbeddingStatus(status);
      if (status !== 'pending') break;
    }
    return status;
  };

  const revert = async () => {
    if (!refine || phase === 'applying') return;
    setPhase('applying');
    setError('');
    try {
      await revertDraft(skillId, grain.id, refine.draftId);
      onApplied();
      setDraftStatus('reverted');
      setToast('已撤销这次修改，正在重建索引…');
      const status = await waitForEmbedding(refine.draftId);
      setPhase('ready');
      setToast(status === 'ready' ? '已恢复到修改前' : '索引重建失败');
    } catch (e) {
      setPhase('ready');
      setError(e instanceof Error ? e.message : '撤销失败，请重试');
    }
  };

  const suggest = async () => {
    if (!refine) return;
    setPhase('applying');
    setError('');
    try {
      await submitSuggestion(skillId, grain.id, refine.draftId);
      setPhase('suggested');
      onApplied();
    } catch (e) {
      setPhase('editing');
      setError(e instanceof Error ? e.message : '提交失败，请重试');
    }
  };

  const discard = async () => {
    if (refine) {
      try { await discardDraft(skillId, grain.id, refine.draftId); } catch { /* 已处理则忽略 */ }
    }
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/20">
      <div className="flex h-full w-full max-w-[900px] flex-col bg-white shadow-2xl">
        {/* 头部 */}
        <div className="flex items-start justify-between border-b border-[#EDF1F6] px-6 py-4">
          <div>
            <h2 className="text-[15px] font-semibold text-[#1E293B]">
              纠偏助手
              <span className="ml-2 text-[12px] font-normal text-[#94A3B8]">
                只改这条经验，确认后才生效
              </span>
            </h2>
            <p className="mt-1 text-[12px] text-[#64748B]">
              场景：{grain.sceneTag || '未打标签'}
              {published && !canApplyDirectly && ' · 分身已发布，修改需管理员审核'}
            </p>
          </div>
          <button onClick={onClose} className="rounded-lg px-2 py-1 text-[#94A3B8] hover:bg-[#F1F5F9]">
            ✕
          </button>
        </div>

        <div className="flex min-h-0 flex-1">
          {/* 左：对话 */}
          <div className="flex w-[360px] shrink-0 flex-col border-r border-[#EDF1F6]">
            <div className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
              {messages.length === 0 && (
                <div className="rounded-xl bg-[#F8FAFC] px-4 py-3 text-[12px] leading-relaxed text-[#64748B]">
                  例子：<br />
                  · 不是这样，我们从来先确认验收人<br />
                  · 话术太书面了，我平时不这么说<br />
                  · 常见错误写错了，新人不是不知道价格，是不敢问预算
                </div>
              )}
              {messages.map((m, i) => (
                <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                  <div className={`max-w-[92%] rounded-2xl px-3 py-2 text-[13px] leading-relaxed ${
                    m.role === 'user'
                      ? 'bg-[#2563EB] text-white'
                      : 'bg-[#F1F5F9] text-[#334155]'
                  }`}>
                    {m.content}
                  </div>
                </div>
              ))}
              {sending && streamReply && (
                <div className="flex justify-start">
                  <div className="max-w-[92%] rounded-2xl bg-[#F1F5F9] px-3 py-2 text-[13px] text-[#334155]">
                    {streamReply}
                    <span className="ml-0.5 animate-pulse">▍</span>
                  </div>
                </div>
              )}
              {sending && !streamReply && (
                <div className="text-[12px] text-[#94A3B8]">正在理解你的意思…</div>
              )}
            </div>
            <div className="border-t border-[#EDF1F6] p-3">
              <textarea
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
                }}
                rows={3}
                placeholder="说哪里不对，回车发送"
                className="w-full resize-none rounded-xl border border-[#E2E8F0] px-3 py-2 text-[13px] outline-none focus:border-[#2563EB]"
              />
              <div className="mt-2 flex justify-end">
                <button
                  onClick={send}
                  disabled={sending || !input.trim()}
                  className="rounded-lg bg-[#2563EB] px-4 py-1.5 text-[13px] font-medium text-white disabled:opacity-40"
                >
                  发送
                </button>
              </div>
            </div>
          </div>

          {/* 右：diff */}
          <div className="flex min-w-0 flex-1 flex-col">
            <div className="flex-1 overflow-y-auto px-5 py-4">
              {!refine && (
                <div className="flex h-full items-center justify-center text-center text-[13px] text-[#94A3B8]">
                  左边说一句哪里不对<br />这里会出现逐段对照的修改稿
                </div>
              )}
              {refine && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <p className="text-[12px] font-medium text-[#475569]">
                      改了 {changed.length} 段
                    </p>
                    <span className="text-[11px] text-[#94A3B8]">
                      置信度 {refine.confidence}
                    </span>
                  </div>
                  {changed.length === 0 && (
                    <div className="rounded-xl bg-[#F8FAFC] px-4 py-3 text-[13px] text-[#64748B]">
                      这一轮还没有产生修改，继续说哪里不对即可。
                    </div>
                  )}
                  {changed.map(key => {
                    const on = !!selected[key];
                    return (
                      <div key={key} className={`rounded-xl border p-3 ${
                        on ? 'border-[#BFDBFE] bg-[#F8FAFF]' : 'border-[#EDF1F6] bg-white'
                      }`}>
                        <label className="flex items-center gap-2 text-[13px] font-medium text-[#1E293B]">
                          <input
                            type="checkbox"
                            checked={on}
                            onChange={e => setSelected(prev => ({ ...prev, [key]: e.target.checked }))}
                          />
                          {FIELD_LABELS[key] || key}
                        </label>
                        <div className="mt-2 grid grid-cols-2 gap-2">
                          <div className="rounded-lg bg-[#FEF2F2] px-2.5 py-2 text-[12px] leading-relaxed text-[#7F1D1D]">
                            <div className="mb-1 text-[10px] font-medium text-[#F87171]">改前</div>
                            <div className="whitespace-pre-wrap">{refine.fields[key] == null ? '' : grainFieldText(grain, key)}</div>
                          </div>
                          <div className="rounded-lg bg-[#F0FDF4] px-2 py-1.5">
                            <div className="mb-1 text-[10px] font-medium text-[#16A34A]">改后</div>
                            <textarea
                              value={edits[key] ?? ''}
                              onChange={e => setEdits(prev => ({ ...prev, [key]: e.target.value }))}
                              rows={6}
                              className="w-full resize-y rounded-md border border-transparent bg-transparent text-[12px] leading-relaxed text-[#14532D] outline-none focus:border-[#86EFAC]"
                            />
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
              {error && (
                <div className="mt-4 rounded-lg bg-red-50 px-3 py-2 text-[12px] text-red-600">{error}</div>
              )}
              {toast && (
                <div className="mt-4 rounded-lg bg-blue-50 px-3 py-2 text-[12px] text-blue-700">{toast}</div>
              )}
            </div>

            {/* 底部动作 */}
            <div className="border-t border-[#EDF1F6] px-5 py-3">
              {phase === 'suggested' ? (
                <p className="text-[13px] text-[#16A34A]">
                  已提交修改建议，管理员审核通过后生效。
                </p>
              ) : phase === 'ready' ? (
                <div className="flex items-center justify-between">
                  <span className="text-[12px] text-[#64748B]">
                    {embeddingStatus === 'ready'
                      ? '索引已更新，可以用同一个问题再问一次'
                      : embeddingStatus === 'failed'
                        ? '索引重建失败，请稍后重试'
                        : '正在重建索引…'}
                  </span>
                  <div className="flex items-center gap-2">
                    {draftStatus === 'applied' && embeddingStatus === 'ready' && (
                      <button
                        onClick={revert}
                        className="rounded-lg border border-[#E2E8F0] px-3 py-1.5 text-[13px] text-[#64748B]"
                      >
                        撤销这次修改
                      </button>
                    )}
                    {embeddingStatus === 'ready' && lastQuestion && (
                      <button
                        onClick={() => onRetest(lastQuestion)}
                        className="rounded-lg bg-[#2563EB] px-4 py-1.5 text-[13px] font-medium text-white"
                      >
                        用这个问题再问一次
                      </button>
                    )}
                  </div>
                </div>
              ) : (
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2">
                    <button
                      onClick={discard}
                      className="rounded-lg border border-[#E2E8F0] px-3 py-1.5 text-[13px] text-[#64748B]"
                    >
                      丢弃
                    </button>
                    {published && canApplyDirectly && (
                      <label className="flex items-center gap-1.5 text-[12px] text-[#B45309]">
                        <input
                          type="checkbox"
                          checked={confirmPublished}
                          onChange={e => setConfirmPublished(e.target.checked)}
                        />
                        我确认立即对所有使用者生效
                      </label>
                    )}
                  </div>
                  {canApplyDirectly ? (
                    <button
                      onClick={apply}
                      disabled={!refine || selectedCount === 0 || phase === 'applying'
                        || (published && !confirmPublished)}
                      className="rounded-lg bg-[#2563EB] px-4 py-1.5 text-[13px] font-medium text-white disabled:opacity-40"
                    >
                      {phase === 'applying' ? '正在应用…' : '应用修改'}
                    </button>
                  ) : (
                    <button
                      onClick={suggest}
                      disabled={!refine || phase === 'applying'}
                      className="rounded-lg bg-[#2563EB] px-4 py-1.5 text-[13px] font-medium text-white disabled:opacity-40"
                    >
                      {phase === 'applying' ? '正在提交…' : '提交修改建议'}
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** 改前内容取颗粒原值（refine.fields 是改后） */
function grainFieldText(grain: TrialGrain, key: string): string {
  switch (key) {
    case 'sceneDescription': return grain.sceneDescription || '';
    case 'expertThought': return grain.expertThought || '';
    case 'standardScript': return grain.standardScript || '';
    case 'commonMistakes': return grain.commonMistakes || '';
    case 'applicableCondition': return grain.applicableCondition || '';
    default: return '';
  }
}
