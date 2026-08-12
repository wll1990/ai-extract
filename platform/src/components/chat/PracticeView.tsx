'use client';

import { useState, useCallback, useRef, useEffect } from 'react';
import {
  startPractice, respondPractice, evaluatePractice, fetchPracticeScenes,
  evaluatePracticeRound, type RoundEval,
} from '@/lib/api/skill';
import { TrustBadge, DefaultAvatar, PortraitCard, ChatAvatar, MODE_GUIDE, TALK_NAME_CARD } from '@aiextract/shared-ui';
import { VoiceRecorder } from '@/components/voice/VoiceRecorder';
import { TraceabilityDrawer } from './TraceabilityDrawer';

interface PracticeMessage {
  role: 'user' | 'assistant';
  content: string;
  // 逐轮评价字段
  championAnswer?: string;
  comparison?: string;
  hits?: string[];
  misses?: string[];
  technique?: string;
  offTopic?: boolean;
  grains?: RoundEval['grains'];
  matchLevel?: string;
  fullAnswer?: string;
  isLastRetry?: boolean;
  retryCount?: number;
  // 溯源字段（SSE source event）
  grainIds?: string;
  grainTags?: string;
  grainCount?: number;
  avgSimilarity?: string;
}

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
  const [messages, setMessages] = useState<PracticeMessage[]>([]);
  const [evaluation, setEvaluation] = useState('');
  const [streamText, setStreamText] = useState('');
  const [inputValue, setInputValue] = useState('');
  const [interimVoiceText, setInterimVoiceText] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [practiceId, setPracticeId] = useState('');
  const [practiceConvId, setPracticeConvId] = useState<string | undefined>();
  const [angleCurrent, setAngleCurrent] = useState(1);
  const [angleTotal, setAngleTotal] = useState(3);
  const [showHint, setShowHint] = useState(false);
  const [traceGrainIds, setTraceGrainIds] = useState('');
  const [traceAvgSim, setTraceAvgSim] = useState<number>(0);
  const [scenePageSize, setScenePageSize] = useState(6);
  const SCENE_INCREMENT = 4;
  const abortRef = useRef<AbortController | null>(null);
  const retryRef = useRef(false);
  const retryCountRef = useRef(0);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    fetchPracticeScenes(skillId).then(setScenes).catch((e) => { console.error('[PracticeView] fetchPracticeScenes failed', e); });
  }, [skillId]);

  const handleStart = useCallback(async (sceneLabel: string) => {
    setSelectedScene(sceneLabel);
    setPhase('active');
    try {
      const data = await startPractice(skillId, sceneLabel);
      setPracticeId(data.practiceId);
      setPracticeConvId(data.conversationId);
      setAngleTotal(data.totalAngles || data.practiceAngles?.length || 3);
      setMessages([{ role: 'assistant', content: data.scene.customerLine }]);
    } catch {
      setMessages([{ role: 'assistant', content: '你好，我听说你们的产品不错，但说实话我们已经有供应商了。' }]);
    }
  }, [skillId]);

  const handleRespond = useCallback(async () => {
    const text = inputValue.trim();
    if (!text || isStreaming) return;

    const isRetry = retryRef.current;
    const currentRetryCount = isRetry ? retryCountRef.current + 1 : 0;
    retryRef.current = false;
    retryCountRef.current = currentRetryCount;

    const userMsg: PracticeMessage = { role: 'user', content: text, retryCount: currentRetryCount };
    setMessages(prev => [...prev, userMsg]);
    setInputValue('');
    setIsStreaming(true);
    setStreamText('');

    // 找到上一轮的销冠答案（重试时传给后端）
    let previousChampionAnswer: string | undefined;
    if (isRetry) {
      for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].role === 'user' && messages[i].championAnswer) {
          previousChampionAnswer = messages[i].championAnswer;
          break;
        }
      }
    }

    // Step 1: 逐轮评价
    try {
      const lastCustomerMsg = [...messages].reverse().find(m => m.role === 'assistant');
      const evalResult = await evaluatePracticeRound(skillId, {
        sceneTag: selectedScene,
        customerMessage: lastCustomerMsg?.content || '',
        myResponse: text,
        previousChampionAnswer,
        retryCount: currentRetryCount,
      });

      setMessages(prev => {
        const next = [...prev];
        // 找到最后一个 user 消息并更新评价字段
        for (let i = next.length - 1; i >= 0; i--) {
          if (next[i].role === 'user') {
            next[i] = {
              ...next[i],
              championAnswer: evalResult.championAnswer,
              comparison: evalResult.comparison,
              hits: evalResult.hits,
              misses: evalResult.misses,
              technique: evalResult.technique,
              offTopic: evalResult.offTopic,
              grains: evalResult.grains,
              matchLevel: evalResult.matchLevel,
              fullAnswer: evalResult.fullAnswer,
              isLastRetry: evalResult.isLastRetry,
            };
            break;
          }
        }
        return next;
      });
    } catch {
      // 评价失败不阻塞对话，继续
    }

    // Step 2: 客户回应（SSE）
    // 计算历史文本
    const historyLines: string[] = [];
    for (const m of messages) {
      if (m.role === 'user') historyLines.push(`${ownerName || '销售'}：${m.content}`);
      else historyLines.push(`客户：${m.content}`);
    }
    const history = historyLines.join('\n');

    let full = '';
    let sourceInfo: any = {};
    const ctrl = respondPractice(
      skillId, practiceId, text, {
        onChunk: (c) => { full += c; setStreamText(full); },
        onSource: (_reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity, sourceNames) => {
          sourceInfo = { grainIds, grainTags, grainCount, avgScore, avgSimilarity, reportTitle, sourceNames };
        },
        onDone: () => {
          setStreamText('');
          setMessages(prev => {
            const next = [...prev, { role: 'assistant', content: full } as PracticeMessage];
            if (Object.keys(sourceInfo).length > 0) {
              next[next.length - 1] = { ...next[next.length - 1], ...sourceInfo };
            }
            return next;
          });
          setIsStreaming(false);
          setAngleCurrent(prev => Math.min(prev + 1, angleTotal));
        },
        onError: () => {
          setIsStreaming(false);
          setMessages(prev => [...prev, { role: 'assistant', content: '（对方暂时无法回应）' }]);
        },
      },
      selectedScene,
      history,
      practiceConvId,
      selectedScene,
    );
    abortRef.current?.abort();
    abortRef.current = ctrl;
  }, [inputValue, isStreaming, skillId, selectedScene, practiceId, practiceConvId, messages, ownerName, angleTotal]);

  const handleRetry = useCallback(() => {
    retryRef.current = true;
    // 删掉最后一条客户消息，让用户重新回应
    setMessages(prev => {
      const next = [...prev];
      for (let i = next.length - 1; i >= 0; i--) {
        if (next[i].role === 'assistant') {
          next.splice(i, 1);
          break;
        }
      }
      return next;
    });
    setTimeout(() => textareaRef.current?.focus(), 100);
  }, []);

  const handleAdvance = useCallback(() => {
    retryCountRef.current = 0;
    setIsStreaming(true);
    setStreamText('');

    const historyLines: string[] = [];
    for (const m of messages) {
      if (m.role === 'user') historyLines.push(`${ownerName || '销售'}：${m.content}`);
      else historyLines.push(`客户：${m.content}`);
    }

    const nextAngle = Math.min(angleCurrent + 1, angleTotal);
    let full = '';
    let sInfo: any = {};
    const ctrl = respondPractice(
      skillId, practiceId, '（继续下一轮）', {
        onChunk: (c) => { full += c; setStreamText(full); },
        onSource: (reportId, reportTitle, grainIds, grainTags, grainCount, avgScore, avgSimilarity, sourceNames) => {
          sInfo = { grainIds, grainTags, grainCount, avgScore, avgSimilarity, reportTitle, sourceNames };
        },
        onDone: () => {
          setStreamText('');
          setMessages(prev => {
            const next = [...prev, { role: 'assistant', content: full } as PracticeMessage];
            if (Object.keys(sInfo).length > 0) {
              next[next.length - 1] = { ...next[next.length - 1], ...sInfo };
            }
            return next;
          });
          setIsStreaming(false);
          setAngleCurrent(nextAngle);
        },
        onError: () => setIsStreaming(false),
      },
      selectedScene,
      historyLines.join('\n'),
      practiceConvId,
      selectedScene,
    );
    abortRef.current?.abort();
    abortRef.current = ctrl;
  }, [skillId, selectedScene, practiceId, practiceConvId, messages, ownerName, angleCurrent, angleTotal]);

  const handleEvaluate = useCallback(() => {
    setPhase('evaluating');
    const conv = messages.map(m => `${m.role === 'user' ? (ownerName || '销售') : '客户'}：${m.content}`).join('\n');
    let full = '';
    const ctrl = evaluatePractice(skillId, conv, selectedScene, {
      onChunk: (c) => { full += c; setEvaluation(full); },
      onDone: () => { setPhase('result'); },
      onError: () => { setPhase('result'); setEvaluation('评价服务暂不可用'); },
    });
    abortRef.current?.abort();
    abortRef.current = ctrl;
  }, [skillId, selectedScene, messages, ownerName]);

  // ═══ 场景选择 ═══
  if (phase === 'select') {
    const visibleScenes = scenes.slice(0, scenePageSize);
    const hasMore = scenes.length > scenePageSize;
    const allShown = scenePageSize >= scenes.length;
    return (
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'flex-start', padding: '32px 40px 60px', overflowY: 'auto' }}>
        {/* ① 名片卡片 */}
        <div className="animate-stagger-1 rounded-3xl bg-white py-7 px-7" style={{
          maxWidth: 640, width: '88%', marginBottom: 24,
          borderRadius: 26,
          background: 'radial-gradient(circle at 18% 28%, rgba(65,91,255,.09), transparent 24%), radial-gradient(circle at 80% 10%, rgba(255,77,95,.03), transparent 20%), rgba(255,255,255,.9)',
          border: '1px solid #e1e7ff',
          boxShadow: '0 18px 50px rgba(42,74,177,.08), 0 3px 12px rgba(34,55,126,.04)',
          overflow: 'hidden',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginBottom: 24 }}>
            <div style={{ width: 180, flexShrink: 0 }}>
              <PortraitCard alt={ownerName} />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <p style={{ fontSize: 26, fontWeight: 700, color: 'var(--fg-high)', margin: '0 0 10px', lineHeight: 1.3, letterSpacing: '-1px' }}>
                {TALK_NAME_CARD.greeting}<span style={{ color: '#2563EB' }}>{ownerName}</span><span style={{ fontSize: 14 }}>&nbsp;✨</span>
              </p>
              <span style={{ display: 'inline-block', fontSize: 13, color: '#64748B', background: '#f1f5f9', borderRadius: 100, padding: '2px 12px', marginBottom: 8 }}>
                {TALK_NAME_CARD.roleTag}
              </span>
              <p style={{ fontSize: 14, color: 'var(--fg-mid)', lineHeight: 1.7, margin: 0, whiteSpace: 'pre-wrap' }}>
                {TALK_NAME_CARD.valueProp.split(TALK_NAME_CARD.valuePropHighlight).map((part, i, arr) =>
                  i < arr.length - 1
                    ? <span key={i}>{part}<span style={{ color: '#DC2626', fontWeight: 600 }}>{TALK_NAME_CARD.valuePropHighlight}</span></span>
                    : <span key={i}>{part}</span>
                )}
              </p>
            </div>
          </div>
          <TrustBadge />
        </div>

        {/* ② 引导语气泡 */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, maxWidth: 640, width: '88%', marginBottom: 24 }}>
          <ChatAvatar role="ai" size={28} />
          <div style={{
            flex: 1, padding: '12px 16px', borderRadius: '18px 18px 18px 6px',
            background: '#f0fdf4', borderLeft: '2px solid rgba(16,185,129,0.2)',
          }}>
            <p style={{ fontSize: 11, color: 'var(--fg-dim)', margin: '0 0 4px' }}>{ownerName}</p>
            <p style={{ fontSize: 14, color: 'var(--fg-mid)', lineHeight: 1.7, margin: 0, whiteSpace: 'pre-wrap' }}>
              {MODE_GUIDE.practice}
            </p>
          </div>
        </div>

        {/* 场景标题 */}
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <h3 style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>选择演练场景</h3>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, maxWidth: 640, width: '88%' }}>
          {visibleScenes.map(s => (
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
        {hasMore && (
          <button onClick={() => setScenePageSize(prev => prev + SCENE_INCREMENT)}
            style={{
              marginTop: 16, padding: '8px 24px', borderRadius: 100,
              border: '1.5px solid var(--border-subtle)', background: 'var(--surface)',
              cursor: 'pointer', fontSize: 13, color: 'var(--fg-mid)',
              fontFamily: 'inherit', fontWeight: 500,
              transition: 'all 0.15s',
              display: 'inline-flex', alignItems: 'center', gap: 6,
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.borderColor = 'var(--tangerine)';
              e.currentTarget.style.color = 'var(--tangerine)';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.borderColor = 'var(--border-subtle)';
              e.currentTarget.style.color = 'var(--fg-mid)';
            }}
          >
            展开更多 · 还剩 {scenes.length - scenePageSize} 个 ▼
          </button>
        )}
        {allShown && scenes.length > 6 && (
          <button onClick={() => setScenePageSize(6)}
            style={{
              marginTop: 12, padding: '6px 20px', borderRadius: 100,
              border: 'none', background: 'transparent',
              cursor: 'pointer', fontSize: 12, color: 'var(--fg-dim)',
              fontFamily: 'inherit',
            }}>
            收起 ▲
          </button>
        )}
      </div>
    );
  }

  // 判断最新一条有评价的用户消息
  const userRounds = messages.filter(m => m.role === 'user').length;
  const latestEvalMsg = [...messages].reverse().find(m => m.role === 'user' && m.championAnswer);

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
        <span style={{ fontSize: 13, fontWeight: 600 }}>
          🎯 实战演练 — {selectedScene}
          {angleTotal > 1 && (
            <span style={{ fontSize: 11, color: 'var(--fg-low)', marginLeft: 8, fontWeight: 400 }}>
              角度 {angleCurrent}/{angleTotal} · 第 {userRounds} 轮
            </span>
          )}
        </span>
        <div style={{ flex: 1 }} />
        {phase === 'active' && userRounds >= 1 && (
          <button onClick={handleEvaluate} style={{
            padding: '6px 14px', borderRadius: 100, border: 'none',
            background: 'var(--tangerine)', color: '#fff',
            fontSize: 12, fontWeight: 600, cursor: 'pointer',
          }}>
            结束对练 · 查看复盘
          </button>
        )}
      </div>

      {/* Messages */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '20px 24px' }}>
        <div style={{ maxWidth: 720, margin: '0 auto' }}>
          {messages.map((msg, i) => {
            const hasEval = msg.role === 'user' && !!msg.championAnswer;
            const isLatestEval = hasEval && msg === latestEvalMsg;
            const canRetry = isLatestEval && !isStreaming && !msg.isLastRetry && (msg.retryCount || 0) < 2;
            const canAdvance = isLatestEval && !isStreaming;

            return (
              <div key={i} style={{ marginBottom: 12 }}>
                {/* 用户消息 */}
                {msg.role === 'user' ? (
                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                    <div style={{
                      maxWidth: '80%', borderRadius: '20px 20px 6px 20px',
                      background: 'var(--tangerine)', color: '#fff',
                      padding: '10px 18px', fontSize: 13, lineHeight: 1.6,
                      boxShadow: 'var(--shadow-btn)',
                    }}>
                      <div style={{ fontSize: 11, marginBottom: 4, opacity: 0.7 }}>
                        {ownerName || '销售'}（你）{msg.retryCount ? `· 再试${msg.retryCount}` : ''}
                      </div>
                      <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{msg.content}</div>
                    </div>
                    <ChatAvatar role="user" size={28} />
                  </div>
                ) : (
                  /* 客户消息 */
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                    <ChatAvatar role="customer" size={30} />
                    <div style={{
                      maxWidth: '80%', borderRadius: '18px 18px 18px 6px',
                      background: 'var(--surface)', border: '1px solid var(--border-subtle)',
                      padding: '10px 16px', fontSize: 13, color: 'var(--fg-high)',
                      lineHeight: 1.7, boxShadow: 'var(--shadow-sm)',
                    }}>
                      {msg.content || (
                        <span style={{ display: 'flex', gap: 4 }}>
                          <span style={{ animation: 'cursorBlink 0.8s infinite', color: 'var(--tangerine)' }}>●</span>
                          <span style={{ animation: 'cursorBlink 0.8s infinite 0.2s', color: 'var(--tangerine)' }}>●</span>
                          <span style={{ animation: 'cursorBlink 0.8s infinite 0.4s', color: 'var(--tangerine)' }}>●</span>
                        </span>
                      )}
                    </div>
                  </div>
                )}

                {/* 客户消息溯源 */}
                {msg.role === 'assistant' && msg.content && msg.avgSimilarity && Number(msg.avgSimilarity) >= 30 && (
                  <div style={{ marginLeft: 40, marginTop: 4, display: 'flex', alignItems: 'center', gap: 8 }}>
                    {Number(msg.avgSimilarity) >= 50 ? (
                      <span style={{ fontSize: 10, fontWeight: 500, color: '#16a34a', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: 4, padding: '1px 6px' }}>
                        🏅 精准匹配
                      </span>
                    ) : (
                      <span style={{ fontSize: 10, fontWeight: 500, color: '#2563eb', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: 4, padding: '1px 6px' }}>
                        📎 关联匹配
                      </span>
                    )}
                    {msg.grainIds && msg.grainCount && (
                      <button onClick={() => { setTraceGrainIds(msg.grainIds!); setTraceAvgSim(Number(msg.avgSimilarity) || 0); }} style={{
                        background: 'none', border: 'none', cursor: 'pointer', fontSize: 10, color: 'var(--fg-dim)', fontFamily: 'inherit',
                      }}>
                        溯源 · {msg.grainCount} 条 →
                      </button>
                    )}
                  </div>
                )}

                {/* 逐轮评价卡片 */}
                {hasEval && (
                  <div style={{
                    marginLeft: 40, marginTop: 8,
                    borderRadius: 12, overflow: 'hidden',
                    border: msg.offTopic
                      ? '1px solid rgba(37,99,235,0.2)'
                      : '1px solid rgba(217,119,6,0.2)',
                    background: msg.offTopic
                      ? 'rgba(239,246,255,0.6)'
                      : 'rgba(255,251,235,0.6)',
                  }}>
                    {msg.offTopic ? (
                      /* 跑题提醒 */
                      <div style={{ padding: '10px 14px' }}>
                        <div style={{ fontSize: 12, fontWeight: 600, color: '#2563eb', marginBottom: 6 }}>💡 教练提醒</div>
                        {msg.comparison && (
                          <div style={{ fontSize: 12, color: 'var(--fg-mid)', lineHeight: 1.6 }}>{msg.comparison}</div>
                        )}
                        {msg.misses && msg.misses.length > 0 && (
                          <div style={{ marginTop: 6 }}>
                            {msg.misses.map((m, j) => (
                              <div key={j} style={{ fontSize: 11, color: '#2563eb', marginTop: 2 }}>· {m}</div>
                            ))}
                          </div>
                        )}
                      </div>
                    ) : (
                      /* 正常评价 */
                      <div style={{ padding: '10px 14px' }}>
                        <div style={{ fontSize: 12, fontWeight: 600, color: '#b45309', marginBottom: 6 }}>⭐ 销冠会怎么说</div>
                        {msg.championAnswer && (
                          <div style={{ fontSize: 12, color: 'var(--fg-high)', lineHeight: 1.7, whiteSpace: 'pre-wrap', marginBottom: 8 }}>
                            {msg.championAnswer}
                          </div>
                        )}

                        {msg.hits && msg.hits.length > 0 && (
                          <div style={{ marginBottom: 6 }}>
                            <div style={{ fontSize: 11, fontWeight: 600, color: '#16a34a', marginBottom: 2 }}>✅ 你说到的</div>
                            {msg.hits.map((h, j) => (
                              <div key={j} style={{ fontSize: 11, color: '#16a34a', marginTop: 1 }}>· {h}</div>
                            ))}
                          </div>
                        )}

                        {msg.misses && msg.misses.length > 0 && (
                          <div style={{ marginBottom: 6 }}>
                            <div style={{ fontSize: 11, fontWeight: 600, color: '#dc2626', marginBottom: 2 }}>💡 进阶建议</div>
                            {msg.misses.map((m, j) => (
                              <div key={j} style={{ fontSize: 11, color: '#dc2626', marginTop: 1 }}>· {m}</div>
                            ))}
                          </div>
                        )}

                        {msg.comparison && (
                          <div style={{
                            fontSize: 11, color: 'var(--fg-low)', paddingTop: 6,
                            borderTop: '1px solid rgba(217,119,6,0.15)',
                          }}>
                            {msg.comparison}
                          </div>
                        )}
                      </div>
                    )}

                    {/* 技法标签 */}
                    {msg.technique && (
                      <div style={{ padding: '6px 14px 0' }}>
                        <span style={{
                          display: 'inline-block', padding: '2px 10px', borderRadius: 8,
                          background: 'rgba(147,51,234,0.08)', border: '1px solid rgba(147,51,234,0.15)',
                          fontSize: 11, color: '#7e22ce', fontWeight: 500,
                        }}>
                          🏷️ 技法：{msg.technique}
                        </span>
                      </div>
                    )}

                    {/* 说法依据（grain 溯源） */}
                    {msg.grains && msg.grains.length > 0 && (
                      <details style={{ padding: '6px 14px 10px' }}>
                        <summary style={{ fontSize: 11, color: 'var(--fg-low)', cursor: 'pointer' }}>
                          📋 说法依据 · {msg.grains.length}条
                        </summary>
                        <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                          {msg.grains.slice(0, 5).map((g, j) => (
                            <div key={j} style={{
                              padding: '4px 8px', borderRadius: 8,
                              background: 'var(--surface)', border: '1px solid var(--border-subtle)',
                              fontSize: 11, color: 'var(--fg-mid)',
                            }}>
                              {g.matchLevel === 'EXACT' ? '📋 ' : g.matchLevel === 'SEMANTIC' ? '🔗 ' : '💡 '}
                              {g.sceneTag || g.fileName || '真实素材'}
                              {g.qualityScore != null && (
                                <span style={{ marginLeft: 4, color: 'var(--fg-dim)' }}>⭐{g.qualityScore.toFixed(1)}</span>
                              )}
                            </div>
                          ))}
                          {msg.grains.length > 5 && (
                            <span style={{ fontSize: 11, color: 'var(--fg-dim)' }}>...共{msg.grains.length}条</span>
                          )}
                        </div>
                      </details>
                    )}

                    {/* 完整答案（最后重试） */}
                    {isLatestEval && !isStreaming && msg.isLastRetry && msg.fullAnswer && (
                      <div style={{
                        margin: '6px 14px 10px', padding: '10px 14px', borderRadius: 10,
                        background: 'rgba(22,163,74,0.06)', border: '1px solid rgba(22,163,74,0.15)',
                      }}>
                        <div style={{ fontSize: 11, fontWeight: 600, color: '#16a34a', marginBottom: 4 }}>
                          📋 完整答案（所有技法角度）
                        </div>
                        <div style={{ fontSize: 11, color: 'var(--fg-high)', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>
                          {msg.fullAnswer}
                        </div>
                        <div style={{ fontSize: 10, color: '#16a34a', marginTop: 4 }}>
                          以上涵盖了该场景的全部技法要点，可以作为参考模板
                        </div>
                      </div>
                    )}

                    {/* 操作按钮 */}
                    {isLatestEval && !isStreaming && (canRetry || canAdvance) && (
                      <div style={{
                        padding: '0 14px 10px', display: 'flex', gap: 8, flexWrap: 'wrap',
                      }}>
                        {canRetry && (
                          <button onClick={handleRetry} style={{
                            padding: '5px 14px', borderRadius: 8,
                            background: 'rgba(147,51,234,0.08)', border: '1px solid rgba(147,51,234,0.15)',
                            color: '#7e22ce', fontSize: 11, fontWeight: 500,
                            cursor: 'pointer', fontFamily: 'inherit',
                          }}>
                            🔄 用这个技法再试
                          </button>
                        )}
                        {canAdvance && (
                          <button onClick={handleAdvance} style={{
                            padding: '5px 14px', borderRadius: 8,
                            background: 'var(--s3)', border: '1px solid var(--border-subtle)',
                            color: 'var(--fg-mid)', fontSize: 11, fontWeight: 500,
                            cursor: 'pointer', fontFamily: 'inherit',
                          }}>
                            继续下一轮 →
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}

          {/* 流式客户消息 */}
          {streamText && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                <div style={{
                  width: 30, height: 30, borderRadius: 10,
                  background: 'linear-gradient(135deg, #475569, #64748b)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: '#fff', fontSize: 13,
                }}>👤</div>
                <div style={{
                  maxWidth: '80%', borderRadius: '18px 18px 18px 6px',
                  background: 'var(--surface)', border: '1px solid var(--border-subtle)',
                  padding: '10px 16px', fontSize: 13, lineHeight: 1.7,
                }}>
                  {streamText}<span style={{
                    display: 'inline-block', width: 2, height: 14,
                    background: 'var(--tangerine)', marginLeft: 2,
                    animation: 'cursorBlink 0.8s infinite',
                  }} />
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* 分身锦囊 */}
      {phase === 'active' && (
        <div style={{ padding: '0 24px', maxWidth: 720, margin: '0 auto', width: '100%' }}>
          <button
            onClick={() => setShowHint(prev => !prev)}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: 12, color: 'var(--fg-low)', padding: '4px 0',
              fontFamily: 'inherit',
            }}
          >
            💡 分身锦囊 {showHint ? '▲' : '▼'}
          </button>
          {showHint && (
            <div style={{
              padding: '8px 12px', borderRadius: 10,
              background: 'var(--s3)', border: '1px solid var(--border-subtle)',
              fontSize: 12, color: 'var(--fg-mid)', marginBottom: 8,
            }}>
              专注倾听客户真正的顾虑，不要急着推销。用提问引导对话。
            </div>
          )}
        </div>
      )}

      {/* 评价结果 */}
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
              {evaluation || '正在生成教练复盘...'}
            </div>
          </div>
        </div>
      )}

      {/* 输入框 */}
      {phase === 'active' && (
        <div style={{
          borderTop: '1px solid var(--border-subtle)', padding: '12px 24px',
          background: 'var(--s1)',
        }}>
          <div style={{ maxWidth: 720, margin: '0 auto', display: 'flex', gap: 10 }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <div style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', zIndex: 10 }}>
                <VoiceRecorder
                  mode="longpress"
                  onTranscription={(text) => { setInterimVoiceText(''); setInputValue(prev => prev + text); }}
                  onInterimText={setInterimVoiceText}
                  disabled={isStreaming}
                />
              </div>
              <textarea
                ref={textareaRef}
                value={interimVoiceText || inputValue}
                onChange={(e) => { setInterimVoiceText(''); setInputValue(e.target.value); }}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleRespond(); } }}
                placeholder={interimVoiceText ? '' : '回应客户...'}
                disabled={isStreaming}
                rows={1}
                style={{
                  width: '100%', resize: 'none', borderRadius: 16,
                  border: '1.5px solid var(--border-subtle)', background: 'var(--surface)',
                  padding: '10px 14px', paddingLeft: 44, fontSize: 13, outline: 'none',
                  fontFamily: 'inherit', minHeight: 44,
                }}
              />
            </div>
            <button onClick={handleRespond}
              disabled={(!inputValue.trim() && !interimVoiceText) || isStreaming}
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
      <TraceabilityDrawer grainIds={traceGrainIds} avgSimilarity={traceAvgSim} open={!!traceGrainIds} onClose={() => { setTraceGrainIds(''); setTraceAvgSim(0); }} />
    </div>
  );
}
