'use client';

import type { MaterialItem } from '@/lib/api/materials';
import EmptyState from '@/components/ui/EmptyState';

interface Props {
  materials: MaterialItem[];
  loading: boolean;
  error: string | null;
  onDelete: (id: string) => void;
  onRetry: () => void;
}

const STATUS_MAP: Record<string, { label: string; color: string; bg: string; pulse?: boolean }> = {
  uploaded:  { label: '待处理', color: '#2563eb', bg: '#dbeafe' },
  cleaning:  { label: '清洗中', color: '#2563eb', bg: '#dbeafe', pulse: true },
  parsing:   { label: '解析中', color: '#2563eb', bg: '#dbeafe', pulse: true },
  analyzing: { label: '分析中', color: '#d97706', bg: '#fef3c7', pulse: true },
  analyzed:  { label: '已分析', color: '#059669', bg: '#d1fae5' },
  extracted: { label: '已萃取', color: '#059669', bg: '#d1fae5' },
  rejected:  { label: '已拒绝', color: '#dc2626', bg: '#fef2f2' },
  discarded: { label: '已丢弃', color: '#6b7280', bg: '#f3f4f6' },
};

function formatDate(s: string): string {
  try {
    const d = new Date(s);
    const now = new Date();
    const diff = now.getTime() - d.getTime();
    if (diff < 60_000) return '刚刚';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
    if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)} 天前`;
    return d.toLocaleDateString('zh-CN');
  } catch { return s; }
}

function formatSize(bytes: number): string {
  if (!bytes) return '';
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

function fileIcon(type: string): string {
  if (!type) return '📎';
  if (type === 'audio') return '🎵';
  if (type === 'image') return '🖼️';
  if (type === 'pdf') return '📕';
  if (type === 'doc' || type === 'docx') return '📄';
  if (type === 'txt') return '📝';
  return '📎';
}

export function MaterialList({ materials, loading, error, onDelete, onRetry }: Props) {
  return (
    <div style={{ marginTop: 8 }}>
      <p style={{ fontSize: 13, fontWeight: 600, color: '#10162f', marginBottom: 10 }}>
        已上传素材 ({materials.length})
      </p>

      {loading && (
        <div style={{ textAlign: 'center', padding: 40, color: '#747f9e', fontSize: 13 }}>加载中...</div>
      )}

      {error && (
        <div style={{ padding: 20, textAlign: 'center' }}>
          <p style={{ fontSize: 13, color: '#dc2626', marginBottom: 8 }}>{error}</p>
          <button onClick={onRetry} style={{ fontSize: 12, color: '#2147ff', background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit' }}>重试</button>
        </div>
      )}

      {!loading && !error && materials.length === 0 && (
        <EmptyState
          icon="📎"
          title="暂无素材"
          description="上传对话记录、经验心得或访谈实录，让分身更懂你"
        />
      )}

      {!loading && !error && materials.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {materials.map((m) => {
            const st = STATUS_MAP[m.status] || { label: m.status, color: '#6b7280', bg: '#f3f4f6' };
            return (
              <div
                key={m.id}
                style={{
                  display: 'flex', alignItems: 'center', gap: 12, padding: '14px 16px',
                  borderRadius: 14, border: '1px solid #e1e7ff', background: '#fff',
                  boxShadow: '0 2px 8px rgba(0,0,0,0.03)',
                }}
              >
                <span style={{ fontSize: 24, flexShrink: 0 }}>{fileIcon(m.fileType)}</span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <p style={{ fontSize: 13, fontWeight: 600, color: '#10162f', margin: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {m.fileName}
                  </p>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 3 }}>
                    <span style={{ fontSize: 11, color: '#747f9e' }}>{formatSize(m.fileSize)}</span>
                    <span style={{ fontSize: 11, color: '#a0aec0' }}>·</span>
                    <span style={{ fontSize: 11, color: '#747f9e' }}>{formatDate(m.createdAt)}</span>
                  </div>
                </div>
                <span style={{
                  display: 'inline-flex', alignItems: 'center', gap: 4,
                  padding: '3px 10px', borderRadius: 100,
                  fontSize: 11, fontWeight: 600, color: st.color, background: st.bg,
                  ...(st.pulse ? { animation: 'pulse 2s ease-in-out infinite' } : {}),
                }}>
                  {st.pulse && <span style={{ width: 6, height: 6, borderRadius: 3, background: st.color, display: 'inline-block' }} />}
                  {st.label}
                </span>
                <button
                  onClick={() => onDelete(m.id)}
                  style={{
                    background: 'none', border: 'none', cursor: 'pointer', fontSize: 11, color: '#a0aec0', fontFamily: 'inherit', padding: 4,
                  }}
                  title="删除"
                >
                  删除
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
