'use client';

import type { ConversationItem } from '@/lib/api/skill';

interface HistorySidebarProps {
  conversations: ConversationItem[];
  currentConvId: string | null;
  onClose: () => void;
  onSwitch: (id: string) => void;
  onDelete: (id: string) => void;
  onNew: () => void;
}

export function HistorySidebar({
  conversations, currentConvId, onClose, onSwitch, onDelete, onNew,
}: HistorySidebarProps) {
  return (
    <div style={{
      width: 280, flexShrink: 0, borderRight: '1px solid var(--border-subtle)',
      background: 'var(--s1)', display: 'flex', flexDirection: 'column',
      height: '100vh', overflow: 'hidden',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '14px 16px', borderBottom: '1px solid var(--border-subtle)',
      }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)' }}>
          对话历史
        </span>
        <button
          onClick={onClose}
          style={{
            background: 'none', border: 'none', cursor: 'pointer',
            color: 'var(--fg-low)', fontSize: 16, padding: 4,
          }}
        >
          ✕
        </button>
      </div>

      <button
        onClick={onNew}
        style={{
          margin: '10px 16px', padding: '8px 14px', borderRadius: 100,
          border: '1.5px solid var(--border-subtle)', background: 'var(--surface)',
          cursor: 'pointer', fontSize: 12, fontWeight: 600,
          color: 'var(--fg-high)', transition: 'background 0.15s',
        }}
      >
        ＋ 新建对话
      </button>

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 8px' }}>
        {conversations.length === 0 ? (
          <div style={{
            textAlign: 'center', padding: '40px 16px',
            color: 'var(--fg-dim)', fontSize: 12,
          }}>
            暂无对话记录
          </div>
        ) : (
          conversations.map(conv => (
            <div
              key={conv.id}
              onClick={() => onSwitch(conv.id)}
              style={{
                padding: '10px 12px', borderRadius: 12, cursor: 'pointer',
                marginBottom: 4, transition: 'background 0.15s',
                ...(conv.id === currentConvId
                  ? { background: 'var(--s3)' }
                  : {}),
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{
                  fontSize: 12, fontWeight: 500, color: 'var(--fg-high)',
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  maxWidth: 180,
                }}>
                  {conv.title || '新对话'}
                </span>
                <button
                  onClick={(e) => { e.stopPropagation(); onDelete(conv.id); }}
                  style={{
                    background: 'none', border: 'none', cursor: 'pointer',
                    color: 'var(--fg-dim)', fontSize: 12, padding: '2px 4px',
                    opacity: 0.6,
                  }}
                  title="删除"
                >
                  🗑
                </button>
              </div>
              <div style={{ fontSize: 10, color: 'var(--fg-dim)', marginTop: 2 }}>
                {conv.mode === 'practice' ? '对练' : conv.mode === 'talk' ? '对话' : '问答'}
                {conv.updatedAt && ` · ${new Date(conv.updatedAt).toLocaleDateString()}`}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
