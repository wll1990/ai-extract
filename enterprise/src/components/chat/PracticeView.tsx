'use client';

import { useState, useCallback, useRef, useEffect } from 'react';
import { MessageBubble } from './MessageBubble';
import {
  startPractice, respondPractice, evaluatePractice, fetchPracticeScenes,
} from '@/lib/api/skill';

interface PracticeViewProps {
  skillId: string;
  ownerName: string;
  initialSceneTag?: string;
  onBack: () => void;
}

export function PracticeView({ skillId, ownerName, initialSceneTag, onBack }: PracticeViewProps) {
  const [phase, setPhase] = useState<'select' | 'active' | 'evaluating' | 'result'>('select');
  const [scenes, setScenes] = useState<{ label: string; title: string; setting: string; customerLine: string }[]>([]);
  const [selectedScene, setSelectedScene] = useState<string>(initialSceneTag || '');
  const [messages, setMessages] = useState<Array<{ role: string; content: string }>>([]);
  const [evaluation, setEvaluation] = useState('');
  const [streamText, setStreamText] = useState('');
  const [inputValue, setInputValue] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    fetchPracticeScenes(skillId).then(setScenes).catch(() => {});
  }, [skillId]);

  const handleStart = useCallback(async (sceneLabel: string) => {
    setSelectedScene(sceneLabel);
    setPhase('active');
    try {
      const data = await startPractice(skillId, sceneLabel);
      setMessages([{ role: 'assistant', content: data.scene.customerLine }]);
    } catch {
      setMessages([{ role: 'assistant', content: '你好，我听说你们的产品不错，但说实话我们已经有供应商了。' }]);
    }
  }, [skillId]);

  const handleRespond = useCallback(() => {
    const text = inputValue.trim();
    if (!text || isStreaming) return;

    const userMsg = { role: 'user', content: text };
    setMessages(prev => [...prev, userMsg]);
    setInputValue('');
    setIsStreaming(true);
    setStreamText('');

    let full = '';
    const ctrl = respondPractice(skillId, '', text, {
      onChunk: (c) => { full += c; setStreamText(full); },
      onDone: () => {
        setStreamText('');
        setMessages(prev => [...prev, { role: 'assistant', content: full }]);
        setIsStreaming(false);
      },
      onError: () => {
        setIsStreaming(false);
        setMessages(prev => [...prev, { role: 'assistant', content: '（对方暂时无法回应）' }]);
      },
    }, selectedScene);
    abortRef.current?.abort();
    abortRef.current = ctrl;
  }, [inputValue, isStreaming, skillId, selectedScene]);

  const handleEvaluate = useCallback(() => {
    setPhase('evaluating');
    const conv = messages.map(m => `${m.role === 'user' ? '你' : '客户'}：${m.content}`).join('\n');
    let full = '';
    const ctrl = evaluatePractice(skillId, conv, selectedScene, {
      onChunk: (c) => { full += c; setEvaluation(full); },
      onDone: () => { setPhase('result'); },
      onError: () => { setPhase('result'); setEvaluation('评价服务暂不可用'); },
    });
    abortRef.current?.abort();
    abortRef.current = ctrl;
  }, [skillId, selectedScene, messages]);

  // Scene selection
  if (phase === 'select') {
    return (
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: 40 }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <div style={{ fontSize: 40, marginBottom: 16 }}>🎯</div>
          <h3 style={{ fontSize: 18, fontWeight: 700, marginBottom: 8 }}>选择对练场景</h3>
          <p style={{ fontSize: 13, color: 'var(--fg-mid)' }}>AI 将扮演客户，模拟真实对话场景</p>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, maxWidth: 600, width: '100%' }}>
          {scenes.map(s => (
            <button key={s.label} onClick={() => handleStart(s.label)}
              style={{
                padding: '16px', borderRadius: 16, border: '1.5px solid var(--border-subtle)',
                background: 'var(--surface)', cursor: 'pointer', textAlign: 'left',
                fontFamily: 'inherit', transition: 'all 0.15s',
              }}>
              <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{s.label}</div>
              <div style={{ fontSize: 11, color: 'var(--fg-low)' }}>{s.title}</div>
            </button>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {/* Top bar */}
      <div style={{
        padding: '10px 20px', borderBottom: '1px solid var(--border-subtle)',
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        <button onClick={onBack} style={{
          background: 'none', border: 'none', cursor: 'pointer',
          color: 'var(--fg-low)', fontSize: 13,
        }}>← 返回</button>
        <span style={{ fontSize: 13, fontWeight: 600 }}>🎯 实战对练 — {selectedScene}</span>
        <div style={{ flex: 1 }} />
        {phase === 'active' && messages.length > 3 && (
          <button onClick={handleEvaluate} style={{
            padding: '6px 14px', borderRadius: 100, border: 'none',
            background: 'var(--tangerine)', color: '#fff',
            fontSize: 12, fontWeight: 600, cursor: 'pointer',
          }}>
            结束对练
          </button>
        )}
      </div>

      {/* Messages */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '20px 24px' }}>
        <div style={{ maxWidth: 720, margin: '0 auto' }}>
          {messages.map((msg, i) => (
            <div key={i} style={{ marginBottom: 12 }}>
              {msg.role === 'user' ? (
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <div style={{
                    maxWidth: '80%', borderRadius: '20px 20px 6px 20px',
                    background: 'var(--tangerine)', color: '#fff',
                    padding: '10px 18px', fontSize: 13, lineHeight: 1.6,
                  }}>
                    {msg.content}
                  </div>
                </div>
              ) : (
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                  <div style={{
                    width: 30, height: 30, borderRadius: 10, flexShrink: 0,
                    background: 'linear-gradient(135deg,#16a34a,#4ade80)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: '#fff', fontSize: 13, fontWeight: 700,
                  }}>
                    🎯
                  </div>
                  <div style={{
                    borderRadius: '18px 18px 18px 6px', background: 'var(--surface)',
                    border: '1px solid var(--border-subtle)', padding: '10px 16px',
                    fontSize: 13, color: 'var(--fg-high)', lineHeight: 1.7,
                    boxShadow: 'var(--shadow-sm)',
                  }}>
                    {msg.content}
                  </div>
                </div>
              )}
            </div>
          ))}

          {/* Stream text for practice */}
          {streamText && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                <div style={{
                  width: 30, height: 30, borderRadius: 10,
                  background: 'linear-gradient(135deg,#16a34a,#4ade80)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: '#fff', fontSize: 13,
                }}>🎯</div>
                <div style={{
                  borderRadius: '18px 18px 18px 6px', background: 'var(--surface)',
                  border: '1px solid var(--border-subtle)', padding: '10px 16px',
                  fontSize: 13, lineHeight: 1.7,
                }}>
                  {streamText}<span style={{
                    display: 'inline-block', width: 2, height: 14,
                    background: 'var(--tangerine)', marginLeft: 2,
                  }} />
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Evaluation result */}
      {(phase === 'evaluating' || phase === 'result') && (
        <div style={{
          margin: '0 24px 16px', maxWidth: 720, alignSelf: 'center',
          width: '100%',
        }}>
          <div style={{
            borderRadius: 20, background: 'var(--surface)',
            border: '1px solid var(--border-subtle)',
            padding: '20px 24px', boxShadow: 'var(--shadow-md)',
          }}>
            <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 12 }}>📊 对练复盘</div>
            <div style={{
              fontSize: 13, color: 'var(--fg-mid)', lineHeight: 1.7,
              whiteSpace: 'pre-wrap',
            }}>
              {evaluation || '正在生成评价...'}
            </div>
          </div>
        </div>
      )}

      {/* Input */}
      {phase === 'active' && (
        <div style={{
          borderTop: '1px solid var(--border-subtle)', padding: '12px 24px',
          background: 'var(--s1)',
        }}>
          <div style={{ maxWidth: 720, margin: '0 auto', display: 'flex', gap: 10 }}>
            <textarea
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleRespond(); } }}
              placeholder="回应客户..."
              disabled={isStreaming}
              rows={1}
              style={{
                flex: 1, resize: 'none', borderRadius: 16,
                border: '1.5px solid var(--border-subtle)', background: 'var(--surface)',
                padding: '10px 14px', fontSize: 13, outline: 'none',
                fontFamily: 'inherit', minHeight: 44,
              }}
            />
            <button onClick={handleRespond}
              disabled={!inputValue.trim() || isStreaming}
              style={{
                width: 44, height: 44, borderRadius: 14, flexShrink: 0,
                background: 'var(--tangerine)', color: '#fff', border: 'none',
                cursor: 'pointer', fontSize: 18,
              }}>
              ↑
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
