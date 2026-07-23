'use client';

import { useState } from 'react';
import type { Message } from '@/hooks/useChat';
import { TraceabilityDrawer } from './TraceabilityDrawer';
import { submitFeedback } from '@/lib/api/skill';
import { ChatAvatar } from '@aiextract/shared-ui';

interface MessageBubbleProps {
  message: Message;
  ownerName: string;
  skillId?: string;
}

export function MessageBubble({ message, ownerName, skillId }: MessageBubbleProps) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [feedback, setFeedback] = useState<'up' | 'down' | null>(null);
  const isUser = message.role === 'user';
  const hasTrace = !!(message.grainTags && message.grainCount);
  const sim = message.avgSimilarity ? Number(message.avgSimilarity) : 0;
  const matchLevel = sim >= 50 ? 'precise' : sim >= 30 ? 'related' : 'synthetic';

  if (isUser) {
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginBottom: 12 }}>
        <div style={{
          maxWidth: '80%', borderRadius: '20px 20px 6px 20px',
          background: 'var(--tangerine)', color: '#fff',
          padding: '10px 18px', fontSize: 13, lineHeight: 1.6,
          boxShadow: 'var(--shadow-btn)',
        }}>
          {message.content}
        </div>
        <ChatAvatar role="user" size={28} />
      </div>
    );
  }

  return (
    <div style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        {/* Avatar */}
        <ChatAvatar role="ai" size={30} />

        <div style={{ maxWidth: '80%' }}>
          {(() => {
            const sepIndex = (message.content || '').indexOf('━━━━━━');
            const mainText = sepIndex >= 0 ? (message.content || '').substring(0, sepIndex).trim() : (message.content || '');
            const sourceText = sepIndex >= 0 ? (message.content || '').substring(sepIndex + 6).trim() : '';

            return (
              <>
                {/* Bubble */}
                <div style={{
                  borderRadius: '18px 18px 18px 6px',
                  background: 'var(--surface)', border: '1px solid var(--border-subtle)',
                  padding: '10px 16px', fontSize: 13, color: 'var(--fg-high)',
                  lineHeight: 1.7, boxShadow: 'var(--shadow-sm)',
                }}>
                  {message.content ? (
                    <div>
                      {mainText && <p style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>{mainText}</p>}
                      {sourceText && (
                        <div style={{
                          marginTop: mainText ? 12 : 0,
                          borderRadius: 12,
                          background: 'linear-gradient(135deg, rgba(6,182,212,0.04), rgba(59,130,246,0.06))',
                          border: '1px solid rgba(59,130,246,0.08)',
                          overflow: 'hidden',
                        }}>
                          <div style={{
                            display: 'flex', alignItems: 'center', gap: 4,
                            padding: '8px 12px', background: 'rgba(255,255,255,0.6)',
                            borderBottom: '1px solid rgba(59,130,246,0.06)',
                          }}>
                            <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#2563EB' }} />
                            <span style={{ fontSize: 11, fontWeight: 500, color: '#475569' }}>经验溯源</span>
                          </div>
                          <div style={{ padding: '10px 12px' }}>
                            <p style={{
                              whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                              fontSize: 12, color: '#64748B', lineHeight: 1.6, margin: 0,
                            }}>
                              {sourceText}
                            </p>
                          </div>
                        </div>
                      )}
                    </div>
                  ) : (
                    <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center', height: 18 }}>
                      <span style={{
                        width: 6, height: 6, borderRadius: '50%',
                        background: 'var(--fg-dim)', animation: 'pulse-orange 1.4s infinite',
                      }} />
                      <span style={{
                        width: 6, height: 6, borderRadius: '50%',
                        background: 'var(--fg-dim)', animation: 'pulse-orange 1.4s infinite 0.2s',
                      }} />
                      <span style={{
                        width: 6, height: 6, borderRadius: '50%',
                        background: 'var(--fg-dim)', animation: 'pulse-orange 1.4s infinite 0.4s',
                      }} />
                    </span>
                  )}
                </div>

                {/* Meta row */}
                {(message.role === 'ai' || message.role === 'assistant') && message.content && (
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 12,
                    marginTop: 6, marginLeft: 4,
                  }}>
                    {matchLevel === 'precise' && (
                      <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4,
                        padding: '2px 10px', borderRadius: 6,
                        border: '1px solid #c9a44b',
                        background: 'linear-gradient(135deg, rgba(201,164,75,0.08), rgba(201,164,75,0.02))',
                        fontSize: 11, fontWeight: 500, color: '#8b6914',
                        boxShadow: '0 0 6px rgba(201,164,75,0.08)',
                      }}>
                        <span style={{ fontSize: 13 }}>🏅</span> 精准匹配
                      </span>
                    )}
                    {matchLevel === 'related' && (
                      <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4,
                        padding: '2px 10px', borderRadius: '0 6px 6px 0',
                        borderLeft: '2px solid #8b9dc3',
                        background: '#f8f9fb',
                        fontSize: 11, fontWeight: 500, color: '#5a6d8a',
                      }}>
                        <span style={{ fontSize: 13 }}>📎</span> 关联匹配
                      </span>
                    )}
                    {matchLevel === 'synthetic' && (
                      <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4,
                        fontSize: 11, fontStyle: 'italic', color: '#b0b7c3',
                      }}>
                        <span style={{ fontSize: 12 }}>✦</span> 综合画像生成
                      </span>
                    )}

                    {hasTrace && matchLevel !== 'synthetic' && (
                      <button
                        onClick={() => setDrawerOpen(true)}
                        style={{
                          display: 'inline-flex', alignItems: 'center', gap: 4,
                          fontSize: 11, color: 'var(--fg-dim)',
                          background: 'none', border: 'none', cursor: 'pointer',
                          padding: 0, fontFamily: 'inherit',
                        }}
                      >
                        溯源 · {message.grainCount} 条 →
                      </button>
                    )}

                    <div style={{ flex: 1 }} />

                    <button
                      onClick={() => {
                        if (feedback === 'up') return;
                        setFeedback('up');
                        if (skillId) submitFeedback({
                          skillId, helpful: true,
                          grainId: message.grainIds?.split(',')[0] || undefined,
                          messageId: message.id,
                          aiResponse: (message.content || '').substring(0, 500),
                        });
                      }}
                      style={{
                        background: 'none', border: 'none', cursor: 'pointer',
                        fontSize: 14, padding: '2px 4px', opacity: feedback === 'up' ? 1 : 0.4,
                        transition: 'opacity 0.2s',
                      }} title="有帮助">
                      👍
                    </button>
                    <button
                      onClick={() => {
                        if (feedback === 'down') return;
                        setFeedback('down');
                        if (skillId) submitFeedback({
                          skillId, helpful: false,
                          grainId: message.grainIds?.split(',')[0] || undefined,
                          messageId: message.id,
                          aiResponse: (message.content || '').substring(0, 500),
                        });
                      }}
                      style={{
                        background: 'none', border: 'none', cursor: 'pointer',
                        fontSize: 14, padding: '2px 4px', opacity: feedback === 'down' ? 1 : 0.4,
                        transition: 'opacity 0.2s',
                      }} title="没帮助">
                      👎
                    </button>
                  </div>
                )}
              </>
            );
          })()}

          {/* Traceability Drawer */}
          <TraceabilityDrawer
            grainIds={message.grainIds || ''}
            open={drawerOpen}
            onClose={() => setDrawerOpen(false)}
          />
        </div>
      </div>
    </div>
  );
}
