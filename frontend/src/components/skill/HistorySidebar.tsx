'use client';

import React from 'react';

interface Conversation {
  id: string;
  title: string;
  mode: string;
  updatedAt: string;
}

interface Props {
  conversations: Conversation[];
  currentConvId: string;
  onClose: () => void;
  onSwitch: (id: string) => void;
  onDelete: (id: string) => void;
  onNew: () => void;
}

const MODE_CONFIG: Record<string, { icon: string; label: string; bg: string; text: string }> = {
  qa:       { icon: '💬', label: '问答',   bg: 'bg-blue-50',   text: 'text-blue-700' },
  talk:     { icon: '🗣️', label: '对话', bg: 'bg-green-50',  text: 'text-green-700' },
  practice: { icon: '🎯', label: '对练',   bg: 'bg-orange-50', text: 'text-orange-700' },
  discuss:  { icon: '💭', label: '讨论',   bg: 'bg-purple-50', text: 'text-purple-700' },
  quick:    { icon: '⚡', label: '快速',   bg: 'bg-gray-50',   text: 'text-gray-700' },
};

export default function HistorySidebar({ conversations, currentConvId, onClose, onSwitch, onDelete, onNew }: Props) {
  return (
    <>
      <div className="fixed inset-0 z-40 bg-black/30" onClick={onClose} />
      <div className="fixed right-0 top-0 bottom-0 z-50 w-[280px] bg-surface-2 border-l border-border flex flex-col shadow-xl animate-[slideInRight_0.2s_ease-out]">
        <div className="px-4 py-3 border-b border-border flex items-center justify-between flex-shrink-0">
          <span className="text-sm font-semibold text-foreground">对话历史</span>
          <button onClick={onClose} className="text-muted-foreground-2 hover:text-foreground text-sm transition-colors">✕</button>
        </div>
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          <button onClick={onNew}
            className={`w-full rounded-lg px-3 py-2 text-left text-xs transition-colors ${
              !currentConvId ? 'bg-primary-light text-foreground font-medium' : 'text-muted-foreground hover:bg-primary-light'
            }`}>
            + 新对话
          </button>
          {conversations.map(c => {
            const cfg = MODE_CONFIG[c.mode] || MODE_CONFIG.qa;
            return (
              <div key={c.id} className="group relative">
                <button onClick={() => onSwitch(c.id)}
                  className={`w-full rounded-lg px-3 py-2 text-left text-xs transition-colors ${
                    currentConvId === c.id ? 'bg-primary-light text-foreground font-medium' : 'text-muted-foreground hover:bg-primary-light'
                  }`}>
                  <div className="flex items-center gap-1.5">
                    <span className={`inline-flex items-center gap-0.5 rounded px-1.5 py-0.5 text-[10px] font-medium flex-shrink-0 ${cfg.bg} ${cfg.text}`}>
                      {cfg.icon} {cfg.label}
                    </span>
                  </div>
                  <p className="truncate mt-1">{c.title || '未命名对话'}</p>
                  <p className="text-[10px] text-muted-foreground-2 mt-0.5">{c.updatedAt ? new Date(c.updatedAt).toLocaleDateString('zh-CN') : ''}</p>
                </button>
                <button onClick={() => onDelete(c.id)}
                  className="absolute right-1 top-1/2 -translate-y-1/2 hidden group-hover:block text-danger text-xs p-1 rounded hover:bg-danger-bg">🗑</button>
              </div>
            );
          })}
          {conversations.length === 0 && (
            <p className="text-xs text-muted-foreground-2 text-center py-8">暂无历史对话</p>
          )}
        </div>
      </div>
    </>
  );
}
