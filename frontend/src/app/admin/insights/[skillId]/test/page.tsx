'use client';

import React, { useState, useRef, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_BASE } from '@/lib/api/client';
import { connectSse } from '@/lib/sse';

interface Message { id: string; role: 'user' | 'ai'; content: string; source?: string; grainTags?: string; grainCount?: number; avgSimilarity?: string; }
interface SourceInfo { grainTags?: string; grainCount?: number; avgSimilarity?: string; }

/**
 * Admin 测试对话面板 —— 管理员改完颗粒后立即验证效果。
 *
 * 对话标记 is_test=true，不会污染 conversation_stats 统计数据。
 * 复用现有的 SSE 连接和消息展示模式。
 */
export default function TestConversationPage() {
  const params = useParams(); const router = useRouter();
  const skillId = params.skillId as string;
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState('');
  const abortRef = useRef<AbortController | null>(null);

  const handleSend = useCallback(() => {
    const text = input.trim();
    if (!text || streaming) return;

    setMessages(prev => [...prev, { id: `u-${Date.now()}`, role: 'user', content: text }]);
    setInput(''); setStreaming(true); setStreamText('');

    const aiMsgId = `a-${Date.now()}`;
    let fullContent = '';
    let sourceInfo: SourceInfo = {};

    const ctrl = connectSse({
      url: `${API_BASE}/skills/${skillId}/chat`, method: 'POST',
      body: { message: text, mode: 'qa', isTest: true },
    }, {
      onChunk: c => { fullContent += c; setStreamText(fullContent); },
      onSource: (_1, _2, _3, tags, count, _4, sim) => {
        sourceInfo = { grainTags: tags, grainCount: count, avgSimilarity: sim };
      },
      onDone: () => {
        setStreamText('');
        setMessages(prev => [...prev, { id: aiMsgId, role: 'ai', content: fullContent, ...sourceInfo }]);
        setStreaming(false);
      },
      onError: () => {
        setStreaming(false);
        setMessages(prev => [...prev, { id: `err-${Date.now()}`, role: 'ai', content: '⚠️ 服务异常' }]);
      },
    });
    abortRef.current = ctrl;
  }, [input, streaming, skillId]);

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <button onClick={() => router.back()} className="text-sm text-primary hover:underline">← 返回</button>
        <span className="text-xs bg-yellow-100 text-yellow-700 rounded-full px-2 py-0.5">⚠️ 测试模式，不计入统计数据</span>
      </div>
      <h2 className="text-lg font-bold mb-4">🧪 测试对话</h2>

      <div className="rounded-xl bg-surface-2 border border-border p-6 mb-4 min-h-[300px]">
        {messages.map(msg => (
          <div key={msg.id} className={`mb-3 ${msg.role === 'user' ? 'text-right' : 'text-left'}`}>
            <div className={`inline-block max-w-[80%] rounded-xl px-4 py-2 text-sm ${msg.role === 'user' ? 'bg-primary text-white' : 'bg-surface text-foreground'}`}>
              <p className="whitespace-pre-wrap">{msg.content}</p>
              {msg.grainTags && (
                <p className="text-[10px] mt-1 opacity-60">
                  🟢 匹配 {msg.avgSimilarity}% · {msg.grainCount}条 · {msg.grainTags}
                </p>
              )}
            </div>
          </div>
        ))}
        {streamText && (
          <div className="text-left mb-3">
            <div className="inline-block max-w-[80%] rounded-xl bg-surface px-4 py-2 text-sm text-foreground">
              <p className="whitespace-pre-wrap">{streamText}</p>
            </div>
          </div>
        )}
      </div>

      <div className="flex gap-2">
        <input value={input} onChange={e => setInput(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') handleSend(); }}
          placeholder="输入测试问题..." disabled={streaming}
          className="flex-1 rounded-lg border px-4 py-2 text-sm outline-none focus:border-primary" />
        <button onClick={handleSend} disabled={!input.trim() || streaming}
          className="rounded-lg bg-primary text-white px-6 py-2 text-sm disabled:opacity-40">
          {streaming ? '...' : '发送'}
        </button>
      </div>
    </div>
  );
}
