'use client';

import { useEffect, useState } from 'react';
import { API_BASE } from '@/lib/api/client';

interface GrainTrace {
  grainId: string;
  sceneDescription?: string;
  expertThought?: string;
  standardScript?: string;
  commonMistakes?: string;
  qualityScore?: number;
  difficultyLevel?: string;
  reportTitle?: string;
  reportId?: string;
  sourceName?: string;
  sourceType?: string;
  sourceSnippet?: string;
}

interface TraceabilityDrawerProps {
  grainIds: string;
  open: boolean;
  onClose: () => void;
}

export function TraceabilityDrawer({ grainIds, open, onClose }: TraceabilityDrawerProps) {
  const [data, setData] = useState<GrainTrace[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (!open || !grainIds) return;
    setLoading(true);
    fetch(`${API_BASE}/admin/grains/traceability?grainIds=${encodeURIComponent(grainIds)}`)
      .then(r => r.json())
      .then(r => setData(r.data || []))
      .finally(() => setLoading(false));
  }, [open, grainIds]);

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopied(prev => ({ ...prev, [id]: true }));
    setTimeout(() => setCopied(prev => ({ ...prev, [id]: false })), 1500);
  };

  if (!open) return null;

  return (
    <>
      <div onClick={onClose} style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.2)',
        backdropFilter: 'blur(4px)', zIndex: 100,
      }} />
      <div style={{
        position: 'fixed', top: 0, right: 0, bottom: 0, width: 400, maxWidth: '90vw',
        background: 'var(--s1)', zIndex: 101,
        boxShadow: '-8px 0 30px rgba(0,0,0,0.12)',
        animation: 'slideInRight 0.3s ease-out',
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 20px', borderBottom: '1px solid var(--border-subtle)',
          flexShrink: 0,
        }}>
          <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--fg-high)' }}>
            📋 溯源 · {data.length} 条销冠锦囊
          </span>
          <button onClick={onClose} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            fontSize: 18, color: 'var(--fg-dim)',
          }}>✕</button>
        </div>

        <div style={{
          flex: 1, overflowY: 'auto', padding: '16px 20px',
          scrollbarWidth: 'thin',
        }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: 40, color: 'var(--fg-dim)' }}>
              加载中…
            </div>
          ) : data.map(grain => (
            <div key={grain.grainId} style={{
              background: 'var(--surface)', borderRadius: 12, padding: 16, marginBottom: 12,
              border: '1px solid var(--border-subtle)',
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
                <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)' }}>
                  🎯 {grain.sceneDescription || '未命名场景'}
                </span>
                {grain.qualityScore != null && (
                  <span style={{ fontSize: 11, color: 'var(--fg-dim)' }}>
                    ⭐ {grain.qualityScore}/5
                  </span>
                )}
              </div>

              {grain.expertThought && (
                <div style={{ fontSize: 12, color: 'var(--fg-mid)', lineHeight: 1.6, marginBottom: 8 }}>
                  <strong style={{ color: 'var(--fg-high)' }}>销冠思路：</strong>
                  {grain.expertThought}
                </div>
              )}

              {grain.standardScript && (
                <div style={{
                  borderLeft: '2px solid var(--tangerine)',
                  paddingLeft: 10, marginBottom: 8,
                  fontSize: 12, color: 'var(--fg-mid)', fontStyle: 'italic',
                  position: 'relative',
                }}>
                  &ldquo;{grain.standardScript}&rdquo;
                  <button onClick={() => handleCopy(grain.standardScript!, grain.grainId)}
                    style={{
                      position: 'absolute', right: 0, top: 0,
                      background: 'none', border: 'none', cursor: 'pointer',
                      fontSize: 14, color: copied[grain.grainId] ? '#16a34a' : 'var(--fg-dim)',
                      transition: 'color 0.2s',
                    }}>{copied[grain.grainId] ? '✓' : '📋'}</button>
                </div>
              )}

              {grain.commonMistakes && (
                <div style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 8 }}>
                  ⚠️ 常见误区：{grain.commonMistakes}
                </div>
              )}

              {grain.sourceSnippet && (
                <div style={{ marginTop: 8 }}>
                  <button onClick={() => setExpanded(prev => ({
                    ...prev, [grain.grainId]: !prev[grain.grainId]
                  }))} style={{
                    background: 'none', border: 'none', cursor: 'pointer',
                    fontSize: 11, color: 'var(--tangerine)', padding: 0,
                  }}>
                    {expanded[grain.grainId] ? '收起' : '展开'}原始对话
                  </button>
                  {expanded[grain.grainId] && (
                    <div style={{
                      marginTop: 6, padding: 10, borderRadius: 8,
                      background: 'var(--s2)', fontSize: 11, color: 'var(--fg-mid)',
                      whiteSpace: 'pre-wrap', lineHeight: 1.5,
                    }}>
                      {grain.sourceSnippet}
                    </div>
                  )}
                </div>
              )}

              <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 8 }}>
                📄 {grain.sourceName || '未知来源'}
                {grain.reportTitle && ` · ${grain.reportTitle}`}
              </div>
            </div>
          ))}
        </div>
      </div>
      <style>{`
        @keyframes slideInRight {
          from { transform: translateX(100%); }
          to { transform: translateX(0); }
        }
      `}</style>
    </>
  );
}
