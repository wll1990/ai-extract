'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useState, useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';

export default function ConversationReplayPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [messages, setMessages] = useState<any[]>([]);
  const [conv, setConv] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      apiClient<any>(`/admin/conversations?size=100`).then(d => {
        const found = (d.content || []).find((c: any) => c.id === id);
        if (found) setConv(found);
      }),
      apiClient<any[]>(`/admin/conversations/${id}/messages`)
        .then(d => setMessages(d || [])),
    ]).catch(() => {}).finally(() => setLoading(false));
  }, [id]);

  if (loading) return <LoadingSpinner />;

  const modeLabel = conv?.mode === 'practice' ? '对练' : conv?.mode === 'quick' ? '问答' : conv?.mode || '问答';

  return (
    <div className="max-w-3xl mx-auto p-6">
      <button onClick={() => router.back()} className="text-sm text-muted-foreground hover:text-foreground mb-4">← 返回</button>
      <h1 className="text-xl font-bold mb-1">对话回放</h1>
      {conv && (
        <p className="text-sm text-muted-foreground mb-6">
          {conv.userName || '用户'} · {conv.skillName || 'AI分身'} · {modeLabel}
        </p>
      )}
      {messages.length === 0 ? (
        <p className="text-muted-foreground-2 text-center py-10">无消息记录</p>
      ) : (
        <div className="space-y-4">
          {messages.map((m: any) => {
            const isUser = m.role === 'user';
            const displayName = m.roleLabel || (isUser ? '我' : 'AI分身');
            return (
            <div key={m.id} className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-[80%] rounded-xl px-4 py-3 ${
                isUser ? 'bg-primary text-white' :
                m.role === 'system' ? 'bg-primary-light text-muted-foreground text-xs' : 'bg-primary-light text-gray-800'
              }`}>
                <p className="text-sm whitespace-pre-wrap">{m.content}</p>
                <span className="text-[10px] opacity-50 mt-1 block">
                  {displayName} · {m.createdAt ? new Date(m.createdAt).toLocaleTimeString('zh-CN') : ''}
                </span>
              </div>
            </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
