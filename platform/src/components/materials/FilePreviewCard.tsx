'use client';

interface Props {
  fileName: string;
  fileSize: number;
  progress: number;
  status: 'pending' | 'uploading' | 'done' | 'error';
  error?: string;
  materialType: string;
  onRemove: () => void;
}

const TYPE_LABELS: Record<string, string> = {
  dialogue: '对话记录',
  monologue: '经验独白',
  interview: '访谈实录',
};

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

function fileIcon(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  if (['mp3', 'm4a', 'wav'].includes(ext)) return '🎵';
  if (['jpg', 'jpeg', 'png'].includes(ext)) return '🖼️';
  if (['pdf'].includes(ext)) return '📕';
  if (['doc', 'docx'].includes(ext)) return '📄';
  return '📎';
}

export function FilePreviewCard({ fileName, fileSize, progress, status, error, materialType, onRemove }: Props) {
  const bg =
    status === 'error' ? '#fef2f2' :
    status === 'done' ? '#f0fdf4' :
    '#fff';

  const border =
    status === 'error' ? '#fecaca' :
    status === 'done' ? '#bbf7d0' :
    '#e1e7ff';

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px',
      borderRadius: 14, border: `1px solid ${border}`, background: bg,
      marginBottom: 8, animation: 'fadeSlideIn 0.3s ease-out',
    }}>
      <span style={{ fontSize: 24, flexShrink: 0 }}>{fileIcon(fileName)}</span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <p style={{ fontSize: 13, fontWeight: 600, color: '#10162f', margin: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {fileName}
        </p>
        <p style={{ fontSize: 11, color: '#747f9e', margin: '2px 0' }}>
          {formatSize(fileSize)} · {TYPE_LABELS[materialType] || materialType}
        </p>
        {(status === 'uploading' || status === 'done') && (
          <div style={{ marginTop: 4, height: 4, borderRadius: 2, background: '#e5e7eb', overflow: 'hidden' }}>
            <div style={{
              height: '100%', borderRadius: 2,
              background: status === 'done' ? '#10b981' : '#2147ff',
              width: `${progress}%`,
              transition: 'width 0.3s',
            }} />
          </div>
        )}
        {status === 'error' && error && (
          <p style={{ fontSize: 11, color: '#dc2626', margin: '2px 0 0' }}>{error}</p>
        )}
      </div>
      {status === 'done' && <span style={{ fontSize: 16, color: '#10b981' }}>✓</span>}
      {status === 'error' && <span style={{ fontSize: 16, color: '#dc2626' }}>✕</span>}
      <button
        onClick={onRemove}
        style={{
          background: 'none', border: 'none', cursor: 'pointer',
          fontSize: 16, color: '#a0aec0', padding: 4, fontFamily: 'inherit', lineHeight: 1,
        }}
        title="移除"
      >
        ✕
      </button>
    </div>
  );
}
