/**
 * [B 端原始文件]
 * 本文件已被复制到平台端 platform/src/ 对应路径。
 *
 * 维护约定：
 * - 如果两端需要相同改动 → 通知平台端同步，或抽到 @aiextract/shared-ui 共享库
 * - 如果只有 B 端需要 → 独立改动，不影响平台端
 *
 * 平台端副本: platform/src/ 对应路径
 */


'use client';

import type { ConversationItem } from '@/lib/api/skill';

interface Props {
  open: boolean;
  onClose: () => void;
  conversations: ConversationItem[];
  currentConvId: string;
  onSwitch: (convId: string) => void;
  onDelete: (convId: string) => void;
  onNew: () => void;
  nickname: string;
  isGuest: boolean;
}

/** 会话模式徽标（与 HistorySidebar 的 MODE_CONFIG 语义一致，移动端配色） */
const MODE_TAGS: Record<string, { label: string; cls: string }> = {
  qa: { label: '问答', cls: 'text-primary bg-primary-light' },
  talk: { label: '聊天', cls: 'text-[#0891b2] bg-[rgba(6,182,212,0.10)]' },
  practice: { label: '对练', cls: 'text-[#8a6a2f] bg-[rgba(200,164,92,0.14)]' },
  quick: { label: '快问', cls: 'text-muted-foreground bg-surface' },
  discuss: { label: '讨论', cls: 'text-muted-foreground bg-surface' },
};

/**
 * 左侧历史会话抽屉（☰ 触发，85% 宽滑入）
 */
export default function HistoryDrawer({
  open, onClose, conversations, currentConvId, onSwitch, onDelete, onNew, nickname, isGuest,
}: Props) {
  return (
    <>
      {/* 遮罩 */}
      <div
        className={`fixed inset-0 z-40 bg-navy/40 transition-opacity duration-200 ${open ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
        onClick={onClose}
      />
      {/* 抽屉 */}
      <div className={`fixed bottom-0 left-0 top-0 z-50 flex w-[85%] max-w-[360px] flex-col bg-bg shadow-lg transition-transform duration-200 ease-out ${open ? 'translate-x-0' : '-translate-x-[102%]'}`}>
        <div className="flex items-center justify-between border-b border-border px-4 pb-3 pt-[calc(16px+env(safe-area-inset-top))]">
          <span className="text-h3 font-semibold text-foreground">历史对话</span>
          <button
            onClick={() => { onNew(); onClose(); }}
            className="rounded-pill border border-primary px-3 py-1 text-xs font-medium text-primary"
          >
            ＋ 新对话
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-2">
          {conversations.length === 0 && (
            <div className="py-10 text-center text-xs text-muted-foreground-2">还没有历史对话</div>
          )}
          {conversations.map(c => {
            const tag = MODE_TAGS[c.mode] || MODE_TAGS.qa;
            return (
              <div
                key={c.id}
                className={`group flex cursor-pointer items-center gap-2.5 rounded-lg p-3 ${currentConvId === c.id ? 'bg-primary-light' : 'active:bg-surface'}`}
                onClick={() => { onSwitch(c.id); onClose(); }}
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-body font-medium text-foreground">{c.title || '未命名对话'}</div>
                  <div className="mt-0.5 text-[11px] text-muted-foreground-2">
                    {c.updatedAt ? c.updatedAt.replace('T', ' ').substring(5, 16) : ''}
                  </div>
                </div>
                <span className={`flex-none rounded-pill px-2 py-0.5 text-[10px] font-medium ${tag.cls}`}>{tag.label}</span>
                <button
                  onClick={e => { e.stopPropagation(); onDelete(c.id); }}
                  className="flex-none p-1 text-muted-foreground-2 active:text-danger"
                  aria-label="删除对话"
                >
                  <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13a1 1 0 001 1h8a1 1 0 001-1l1-13M9 7V5a1 1 0 011-1h4a1 1 0 011 1v2" /></svg>
                </button>
              </div>
            );
          })}
        </div>

        <div className="flex items-center gap-2 border-t border-border px-4 pb-[calc(16px+env(safe-area-inset-bottom))] pt-3 text-xs text-muted-foreground">
          <span className="flex h-6 w-6 flex-none items-center justify-center rounded-full bg-gradient-to-br from-blue-100 to-blue-300 text-[10px] font-semibold text-primary">
            {nickname.charAt(0) || '客'}
          </span>
          <span className="truncate">{nickname}{isGuest ? ' · 注册后跨设备保留记录' : ''}</span>
        </div>
      </div>
    </>
  );
}
