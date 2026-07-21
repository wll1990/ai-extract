'use client';

import { useState } from 'react';
import type { Message } from '@/hooks/useChat';

interface MessageBubbleProps {
  message: Message;
  ownerName: string;
}

export function MessageBubble({ message, ownerName }: MessageBubbleProps) {
  const [traceOpen, setTraceOpen] = useState(false);
  const isUser = message.role === 'user';
  const hasTrace = !!(message.grainTags && message.grainCount);
  const sim = message.avgSimilarity ? Number(message.avgSimilarity) : 0;
  const matchLevel = sim >= 50 ? 'precise' : sim >= 30 ? 'related' : 'synthetic';

  if (isUser) {
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
        <div style={{
          maxWidth: '80%', borderRadius: '20px 20px 6px 20px',
          background: 'var(--tangerine)', color: '#fff',
          padding: '10px 18px', fontSize: 13, lineHeight: 1.6,
          boxShadow: 'var(--shadow-btn)',
        }}>
          {message.content}
        </div>
      </div>
    );
  }

  return (
    <div style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        {/* Avatar */}
        <div style={{
          width: 30, height: 30, borderRadius: 10, flexShrink: 0,
          background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: '#fff', fontSize: 13, fontWeight: 700, marginTop: 2,
        }}>
          {ownerName[0]}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          {/* Bubble */}
          <div style={{
            borderRadius: '18px 18px 18px 6px',
            background: 'var(--surface)', border: '1px solid var(--border-subtle)',
            padding: '10px 16px', fontSize: 13, color: 'var(--fg-high)',
            lineHeight: 1.7, boxShadow: 'var(--shadow-sm)',
          }}>
            {message.content ? (
              <p style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>
                {message.content}
              </p>
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
          {message.role === 'ai' && message.content && (
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
                  onClick={() => setTraceOpen(!traceOpen)}
                  style={{
                    display: 'inline-flex', alignItems: 'center', gap: 4,
                    fontSize: 11, color: 'var(--fg-dim)',
                    background: 'none', border: 'none', cursor: 'pointer',
                    padding: 0, fontFamily: 'inherit',
                  }}
                >
                  <span style={{ transform: traceOpen ? 'rotate(90deg)' : '', transition: 'transform 0.15s' }}>
                    ›
                  </span>
                  溯源 · {message.grainCount} 条
                </button>
              )}
            </div>
          )}

          {/* Trace detail */}
          {hasTrace && traceOpen && (
            <div style={{
              marginTop: 6, marginLeft: 4, borderRadius: 10,
              background: 'var(--s3)', padding: '8px 12px',
            }}>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                {message.grainTags?.split(',').filter(Boolean).map((tag, i) => (
                  <span key={i} style={{
                    padding: '2px 10px', borderRadius: 100,
                    background: 'rgba(255,92,0,0.08)', fontSize: 11,
                    color: 'var(--tangerine)',
                  }}>
                    {tag.trim()}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
