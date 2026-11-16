'use client';

import { useEffect, useRef, useState } from 'react';
import { ReviewMessage, streamMessage } from '@/lib/api/sales';
import { getUser } from '@/lib/storage';

function draftKeyFor(reviewId: string): string {
  const userId = (getUser() as { id?: string } | null)?.id ?? 'anonymous';
  return `sales:review:draft:${userId}:${reviewId}`;
}

export function ReviewChat({
  reviewId,
  initial,
  disabled,
}: {
  reviewId: string;
  initial: ReviewMessage[];
  disabled?: boolean;
}) {
  const [messages, setMessages] = useState<ReviewMessage[]>(initial);
  const draftKey = draftKeyFor(reviewId);
  const [input, setInput] = useState(() => {
    try {
      return window.localStorage.getItem(draftKey) ?? '';
    } catch {
      return '';
    }
  });
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  useEffect(() => {
    try {
      if (input) {
        window.localStorage.setItem(draftKey, input);
      } else {
        window.localStorage.removeItem(draftKey);
      }
    } catch {
      // localStorage 不可用时忽略
    }
  }, [input, draftKey]);

  const send = () => {
    const text = input.trim();
    if (!text || streaming || disabled) return;
    setInput('');
    setStreaming(true);
    setError(null);
    const assistantId = `tmp-${Date.now()}`;
    setMessages(prev => [
      ...prev,
      { id: `tmp-u-${Date.now()}`, role: 'user', content: text, createdAt: new Date().toISOString() },
      { id: assistantId, role: 'assistant', content: '', createdAt: new Date().toISOString() },
    ]);
    let acc = '';
    abortRef.current = streamMessage(reviewId, text, {
      onChunk: chunk => {
        acc += chunk;
        setMessages(prev => prev.map(m => (m.id === assistantId ? { ...m, content: acc } : m)));
      },
      onDone: () => setStreaming(false),
      onError: msg => {
        setStreaming(false);
        setMessages(prev => prev.filter(m => m.id !== assistantId));
        // 失败保留输入与草稿
        setInput(prev => prev || text);
        setError(msg);
      },
    });
  };

  return (
    <section className="rounded border border-gray-200 p-4">
      <h3 className="mb-3 text-sm font-medium text-gray-900">继续问教练</h3>
      <div className="mb-3 max-h-80 space-y-3 overflow-auto">
        {messages.length === 0 && (
          <p className="text-sm text-gray-500">还没有追问。可以问「这个客户最该先解决什么」。</p>
        )}
        {messages.map(m => (
          <div key={m.id} className={m.role === 'user' ? 'text-right' : 'text-left'}>
            <div
              className={`inline-block max-w-[85%] whitespace-pre-wrap rounded px-3 py-2 text-sm ${
                m.role === 'user' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-900'
              }`}
            >
              {m.content || '…'}
            </div>
          </div>
        ))}
      </div>
      {error && <p className="mb-2 text-sm text-red-600">{error}</p>}
      <div className="flex gap-2">
        <input
          className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm"
          value={input}
          disabled={disabled || streaming}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          placeholder={disabled ? '报告生成后可追问' : '输入问题，回车发送'}
        />
        <button
          type="button"
          onClick={send}
          disabled={disabled || streaming || !input.trim()}
          className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
        >
          {streaming ? '回答中…' : '发送'}
        </button>
      </div>
    </section>
  );
}
