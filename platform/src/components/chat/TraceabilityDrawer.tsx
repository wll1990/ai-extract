'use client';

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { apiClient } from '@/lib/api/client';

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
  avgSimilarity?: number;
}

interface TraceabilityDrawerProps {
  grainIds: string;
  /** 相似度数值（0-100），用于匹配级别标签 */
  avgSimilarity?: number | string;
  open: boolean;
  onClose: () => void;
}

function matchLevel(sim: number) {
  if (sim >= 50) return { label: '精准匹配', bg: '#dcfce7', fg: '#166534', icon: '🏅' };
  if (sim >= 30) return { label: '关联匹配', bg: '#dbeafe', fg: '#1e40af', icon: '📎' };
  return { label: '参考', bg: '#f3f4f6', fg: '#4b5563', icon: '📖' };
}

const BORDER_COLORS = ['#6366f1', '#8b5cf6', '#0ea5e9'];

export function TraceabilityDrawer({ grainIds, avgSimilarity, open, onClose }: TraceabilityDrawerProps) {
  const [data, setData] = useState<GrainTrace[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (!open || !grainIds) return;
    setLoading(true);
    apiClient<GrainTrace[]>(`/admin/grains/traceability?grainIds=${encodeURIComponent(grainIds)}`)
      .then(data => setData(data))
      .finally(() => setLoading(false));
  }, [open, grainIds]);

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopied(prev => ({ ...prev, [id]: true }));
    setTimeout(() => setCopied(prev => ({ ...prev, [id]: false })), 1500);
  };

  if (!open) return null;

  return createPortal(
    <>
      <div onClick={onClose} style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.2)',
        backdropFilter: 'blur(4px)', zIndex: 9999,
      }} />
      <div style={{
        position: 'fixed', top: 0, right: 0, bottom: 0, width: 420, maxWidth: '92vw',
        background: '#fff', zIndex: 10000,
        boxShadow: '-8px 0 30px rgba(0,0,0,0.12)',
        animation: 'slideInRight 0.3s ease-out',
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 20px', borderBottom: '1px solid #f9fafb',
          background: '#fafbfc', flexShrink: 0,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 15, fontWeight: 700, color: '#111827' }}>📋 溯源</span>
            {!loading && data.length > 0 && (
              <span style={{
                fontSize: 11, color: '#4f46e5', background: '#eef2ff',
                padding: '2px 8px', borderRadius: 10, fontWeight: 500,
              }}>{data.length} 条经验</span>
            )}
          </div>
          <button onClick={onClose} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            fontSize: 18, color: '#9ca3af', lineHeight: 1,
          }}>✕</button>
        </div>

        {/* Body */}
        <div style={{
          flex: 1, overflowY: 'auto', padding: '16px',
          scrollbarWidth: 'thin',
        }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: 48, color: '#9ca3af', fontSize: 13 }}>
              加载中…
            </div>
          ) : data.map((grain, i) => {
            const sim = typeof avgSimilarity === 'number' ? avgSimilarity
              : avgSimilarity ? Number(avgSimilarity) : 0;
            const level = matchLevel(sim);
            return (
            <div key={grain.grainId} style={{
              borderRadius: 16, overflow: 'hidden',
              border: '1px solid #f3f4f6', background: '#fff',
              boxShadow: '0 1px 2px rgba(0,0,0,.04)',
              marginBottom: 14,
              borderLeft: `3px solid ${BORDER_COLORS[i % 3]}`,
            }}>
              {/* Card Header */}
              <div style={{ padding: '14px 16px 12px', borderBottom: '1px solid #f9fafb' }}>
                <h3 style={{ fontSize: 14, fontWeight: 700, color: '#111827', lineHeight: 1.3, margin: 0 }}>
                  {grain.sceneDescription || '未命名场景'}
                </h3>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
                  {grain.qualityScore != null && (
                    <span style={{
                      fontSize: 10, fontWeight: 500, padding: '2px 8px', borderRadius: 6,
                      background: '#fef3c7', color: '#92400e',
                    }}>⭐ {grain.qualityScore}/5</span>
                  )}
                  <span style={{
                    fontSize: 10, fontWeight: 500, padding: '2px 8px', borderRadius: 6,
                    background: level.bg, color: level.fg,
                  }}>{level.icon} {level.label}</span>
                  {grain.difficultyLevel && (
                    <span style={{
                      fontSize: 10, fontWeight: 500, padding: '2px 8px', borderRadius: 6,
                      background: '#f3e8ff', color: '#6b21a8',
                    }}>
                      {grain.difficultyLevel === 'hard' ? '进阶' : grain.difficultyLevel === 'medium' ? '中级' : '基础'}
                    </span>
                  )}
                </div>
              </div>

              {/* 销冠思路 */}
              {grain.expertThought && (
                <div style={{ padding: '12px 16px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 6 }}>
                    <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#6366f1', flexShrink: 0 }} />
                    <span style={{ fontSize: 11, fontWeight: 600, color: '#4f46e5', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                      销冠怎么想
                    </span>
                  </div>
                  <p style={{ fontSize: 13, color: '#374151', lineHeight: 1.7, margin: 0 }}>
                    {grain.expertThought}
                  </p>
                </div>
              )}

              {/* 标准话术 */}
              {grain.standardScript && (
                <div style={{ padding: '12px 16px', borderTop: '1px solid #f9fafb' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 8 }}>
                    <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#f59e0b', flexShrink: 0 }} />
                    <span style={{ fontSize: 11, fontWeight: 600, color: '#d97706', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                      可以这样说
                    </span>
                  </div>
                  <div style={{
                    background: 'rgba(255,251,235,0.6)', borderRadius: 12, padding: '12px 14px',
                    position: 'relative',
                  }}>
                    <span style={{
                      position: 'absolute', left: 10, top: 6, fontSize: 18,
                      color: '#fcd34d', fontFamily: 'Georgia, serif', lineHeight: 1,
                    }}>"</span>
                    <p style={{
                      fontSize: 13, color: '#78350f', lineHeight: 1.7,
                      fontStyle: 'italic', margin: 0, paddingLeft: 10,
                    }}>
                      {grain.standardScript}
                    </p>
                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
                      <button
                        onClick={() => handleCopy(grain.standardScript!, grain.grainId)}
                        style={{
                          fontSize: 11, fontWeight: 500, padding: '4px 10px', borderRadius: 6,
                          background: copied[grain.grainId] ? '#dcfce7' : '#fef3c7',
                          color: copied[grain.grainId] ? '#166534' : '#92400e',
                          border: `1px solid ${copied[grain.grainId] ? '#bbf7d0' : '#fcd34d'}`,
                          cursor: 'pointer', transition: 'all .15s',
                        }}
                      >
                        {copied[grain.grainId] ? '✓ 已复制' : '📋 复制话术'}
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* 常见误区 */}
              {grain.commonMistakes && (
                <div style={{ padding: '12px 16px', borderTop: '1px solid #f9fafb' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 6 }}>
                    <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#ef4444', flexShrink: 0 }} />
                    <span style={{ fontSize: 11, fontWeight: 600, color: '#ef4444', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                      新手容易踩的坑
                    </span>
                  </div>
                  <div style={{ background: 'rgba(254,242,242,0.6)', borderRadius: 12, padding: '12px 14px' }}>
                    <p style={{ fontSize: 12, color: '#991b1b', lineHeight: 1.6, margin: 0 }}>
                      {grain.commonMistakes}
                    </p>
                  </div>
                </div>
              )}

              {/* 原始对话 */}
              {grain.sourceSnippet && (
                <div style={{ padding: '10px 16px', borderTop: '1px solid #f9fafb' }}>
                  <button
                    onClick={() => setExpanded(prev => ({ ...prev, [grain.grainId]: !prev[grain.grainId] }))}
                    style={{
                      fontSize: 11, color: '#6366f1', fontWeight: 500,
                      background: 'none', border: 'none', cursor: 'pointer', padding: 0,
                    }}
                  >
                    {expanded[grain.grainId] ? '▾ 收起' : '▸ 展开'}原始对话片段
                  </button>
                  {expanded[grain.grainId] && (
                    <div style={{
                      marginTop: 8, padding: 12, borderRadius: 8,
                      background: '#f9fafb', fontSize: 11, color: '#6b7280',
                      whiteSpace: 'pre-wrap', lineHeight: 1.5, border: '1px solid #f3f4f6',
                    }}>
                      {grain.sourceSnippet}
                    </div>
                  )}
                </div>
              )}

              {/* Source Footer */}
              <div style={{
                padding: '10px 16px', background: '#fafbfc',
                borderTop: '1px solid #f9fafb',
                display: 'flex', alignItems: 'center', gap: 8,
              }}>
                <span style={{ fontSize: 12 }}>📄</span>
                <span style={{ fontSize: 11, color: '#6b7280', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {grain.sourceName || '未知来源'}
                  {grain.reportTitle && <span style={{ color: '#9ca3af' }}> · {grain.reportTitle}</span>}
                </span>
                {grain.reportId && (
                  <button
                    onClick={() => window.open(`/report/${grain.reportId}`, '_blank')}
                    style={{
                      fontSize: 10, color: '#6366f1', fontWeight: 500,
                      background: 'none', border: 'none', cursor: 'pointer',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    查看报告 →
                  </button>
                )}
              </div>

            </div>
          )})}
        </div>
      </div>
      <style>{`
        @keyframes slideInRight {
          from { transform: translateX(100%); }
          to { transform: translateX(0); }
        }
      `}</style>
    </>,
    document.body
  );
}
