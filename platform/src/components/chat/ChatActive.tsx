'use client';

import React, { useRef, useEffect, useState } from 'react';
import { MessageBubble } from './MessageBubble';
import { VoiceRecorder } from '@/components/voice/VoiceRecorder';
import { ThinkingCard } from '@aiextract/shared-ui';
import type { Message, ChatPhase } from '@/hooks/useChat';

interface ChatActiveProps {
  messages: Message[];
  streamText: string;
  phase: ChatPhase;
  inputValue: string;
  onInputChange: (v: string) => void;
  onSend: () => void;
  ownerName: string;
  placeholder?: string;
  mode?: string;
  skillId?: string;
  children?: React.ReactNode;
}

export function ChatActive({
  messages, streamText, phase, inputValue,
  onInputChange, onSend, ownerName, placeholder = '输入你的问题...', mode = 'qa',
  skillId, children,
}: ChatActiveProps) {
  const endRef = useRef<HTMLDivElement>(null);
  const isStreaming = phase === 'streaming';
  const [interimVoiceText, setInterimVoiceText] = useState('');

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamText]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSend();
    }
  };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {/* Messages area */}
      <div style={{
        flex: 1, overflowY: 'auto', padding: '20px 24px',
      }}>
        <div style={{ maxWidth: 720, margin: '0 auto' }}>
          {/* 入口内容（推荐问题等） */}
          {children}
          {/* 思考中 — ThinkingCard（自带进度条，首 chunk 到达前显示） */}
          {isStreaming && !streamText && (
            <ThinkingCard name={ownerName} text="正在结合经验库，为你整理答案…" />
          )}
          {messages.map((msg, i) => (
            <div key={msg.id} style={{
              animation: messages.length > 5 ? 'none' : `staggerIn 0.35s ease-out both`,
              animationDelay: messages.length > 5 ? '0ms' : `${Math.min(i * 40, 200)}ms`,
            }}>
              <MessageBubble message={msg} ownerName={ownerName} skillId={skillId} />
            </div>
          ))}

          {/* Stream text bubble */}
          {streamText && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                <div style={{
                  width: 30, height: 30, borderRadius: 10, flexShrink: 0,
                  background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: '#fff', fontSize: 13, fontWeight: 700, marginTop: 2,
                }}>
                  {ownerName[0]}
                </div>
                <div style={{
                  maxWidth: '80%', borderRadius: '18px 18px 18px 6px',
                  background: 'var(--surface)', border: '1px solid var(--border-subtle)',
                  padding: '10px 16px', fontSize: 13, color: 'var(--fg-high)',
                  lineHeight: 1.7, boxShadow: 'var(--shadow-sm)',
                }}>
                  <p style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>
                    {streamText}
                    <span style={{
                      display: 'inline-block', width: 2, height: 16,
                      background: 'var(--tangerine)', marginLeft: 2,
                      verticalAlign: 'text-bottom', animation: 'pulse-orange 1s infinite',
                    }} />
                  </p>
                </div>
              </div>
            </div>
          )}

          <div ref={endRef} />
        </div>
      </div>

      {/* Input area */}
      <div style={{
        borderTop: '1px solid var(--border-subtle)',
        background: 'var(--s1)', padding: '16px 24px',
      }}>
        <div style={{ maxWidth: 720, margin: '0 auto', display: 'flex', alignItems: 'flex-end', gap: 10 }}>
          <div style={{ position: 'relative', flex: 1 }}>
            <div style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', zIndex: 10 }}>
              <VoiceRecorder
                mode="longpress"
                onTranscription={(text) => { setInterimVoiceText(''); onInputChange(inputValue + text); }}
                onInterimText={setInterimVoiceText}
                disabled={isStreaming}
              />
            </div>
            <textarea
              value={interimVoiceText || inputValue}
              onChange={(e) => { setInterimVoiceText(''); onInputChange(e.target.value); }}
              onKeyDown={handleKeyDown}
              placeholder={interimVoiceText ? '' : placeholder}
              disabled={isStreaming}
              rows={1}
              style={{
                width: '100%', resize: 'none', borderRadius: 16,
                border: '1.5px solid var(--border-subtle)',
                background: 'var(--surface)', padding: '12px 16px',
                paddingLeft: 44, fontSize: 13, color: 'var(--fg-high)',
                outline: 'none', fontFamily: 'inherit', lineHeight: 1.5,
                minHeight: 48, maxHeight: 120,
                transition: 'border-color 0.2s',
              }}
              onFocus={(e) => { e.target.style.borderColor = 'var(--tangerine)'; }}
              onBlur={(e) => { e.target.style.borderColor = 'var(--border-subtle)'; }}
              onInput={(e) => {
                const t = e.target as HTMLTextAreaElement;
                t.style.height = 'auto';
                t.style.height = Math.min(t.scrollHeight, 120) + 'px';
              }}
            />
          </div>
          <button
            onClick={onSend}
            disabled={(!inputValue.trim() && !interimVoiceText) || isStreaming}
            style={{
              width: 48, height: 48, borderRadius: 16, flexShrink: 0,
              background: inputValue.trim() && !isStreaming
                ? 'var(--tangerine)' : 'var(--s4)',
              color: '#fff', border: 'none', cursor: 'pointer',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 20, transition: 'all 0.15s',
              transform: inputValue.trim() && !isStreaming ? 'scale(1)' : 'scale(0.95)',
            }}
          >
            {isStreaming ? (
              <div style={{
                width: 16, height: 16, borderRadius: '50%',
                border: '2px solid rgba(255,255,255,0.3)',
                borderTopColor: '#fff',
                animation: 'spin 0.6s linear infinite',
              }} />
            ) : '↑'}
          </button>
        </div>
        <div style={{ textAlign: 'center', marginTop: 8 }}>
          <span style={{ fontSize: 11, color: 'var(--fg-dim)' }}>
            按 Enter 发送，Shift+Enter 换行
          </span>
        </div>
      </div>
    </div>
  );
}
