'use client';

import { useState, useCallback } from 'react';
import { ChatEntry } from './ChatEntry';
import { ChatActive } from './ChatActive';
import { PracticeView } from './PracticeView';
import { HistorySidebar } from './HistorySidebar';
import { useChat } from '@/hooks/useChat';
import { useConversations } from '@/hooks/useConversations';
import type { SkillDetail } from '@/lib/api/skill';

type Mode = 'qa' | 'talk' | 'practice';

const MODE_ITEMS: { key: Mode; label: string; icon: string; color: string; desc: string }[] = [
  { key: 'qa', label: '请教', icon: '🔍', color: '#f59e0b', desc: '向专家提问，获取实战经验' },
  { key: 'talk', label: '对话', icon: '💬', color: '#6366f1', desc: '与专家自由交流' },
  { key: 'practice', label: '对练', icon: '🎯', color: '#10b981', desc: '模拟场景，打磨技巧' },
];

interface ChatViewProps {
  skill: SkillDetail;
}

export function ChatView({ skill }: ChatViewProps) {
  const [mode, setMode] = useState<Mode>('qa');
  const [showHistory, setShowHistory] = useState(false);
  const [practiceTag, setPracticeTag] = useState<string | undefined>();

  const ownerName = skill.displayName || skill.ownerName || '专家';
  const chat = useChat({ skillId: skill.id, ownerName });
  const convs = useConversations(skill.id);

  // Talk 配置
  const talkConfig = (() => { try { return JSON.parse((skill as any).talkConfig || '{}'); } catch { return {}; } })();
  const showQuestions = mode === 'qa' || (mode === 'talk' && talkConfig.showRecommendedQuestions !== false);
  const showSceneTags = mode === 'qa' || (mode === 'talk' && talkConfig.showSceneTags !== false);

  const [inputValue, setInputValue] = useState('');

  const handleSend = useCallback(() => {
    const text = inputValue.trim();
    if (!text) return;
    chat.sendMessage(text, mode);
    setInputValue('');
  }, [inputValue, chat, mode]);

  const handleQuestionClick = useCallback((q: string) => {
    chat.sendMessage(q, mode);
  }, [chat, mode]);

  const handleSwitchConversation = useCallback(async (id: string) => {
    convs.switchConversation(id);
    const msgs = await convs.loadMessages(id);
    chat.loadHistory(msgs, id);
    setShowHistory(false);
  }, [convs, chat]);

  const handleNewChat = useCallback(() => {
    convs.startNew();
    chat.reset();
    setShowHistory(false);
  }, [convs, chat]);

  const handleDeleteConversation = useCallback(async (id: string) => {
    await convs.deleteConversation(id);
    if (id === convs.currentConvId) {
      chat.reset();
    }
  }, [convs, chat]);

  const toggleHistory = useCallback(() => {
    const next = !showHistory;
    setShowHistory(next);
    if (next) convs.loadList();
  }, [showHistory, convs]);

  const isEntry = chat.phase === 'entry' && chat.messages.length === 0;

  const activeIndex = MODE_ITEMS.findIndex(m => m.key === mode);
  const activeColor = MODE_ITEMS[activeIndex].color;

  return (
    <div style={{ display: 'flex', height: '100vh', background: 'var(--s1)' }}>
      {/* History Sidebar */}
      {showHistory && (
        <HistorySidebar
          conversations={convs.conversations}
          currentConvId={convs.currentConvId}
          onClose={() => setShowHistory(false)}
          onSwitch={handleSwitchConversation}
          onDelete={handleDeleteConversation}
          onNew={handleNewChat}
        />
      )}

      {/* Main area */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        {/* Header — 模式感知 */}
        <header className="flex items-center gap-3 px-5 py-2.5 border-b flex-shrink-0"
          style={{
            background: 'var(--s1)',
            borderColor: mode === 'qa' ? 'rgba(245,158,11,0.15)' :
                         mode === 'talk' ? 'rgba(99,102,241,0.15)' :
                         'rgba(16,185,129,0.15)',
            transition: 'border-color 0.4s ease',
          }}>
          {/* Avatar */}
          {skill.avatarUrl ? (
            <img src={skill.avatarUrl} alt={ownerName} style={{
              width: 32, height: 32, borderRadius: 10, objectFit: 'cover',
            }} />
          ) : (
            <div style={{
              width: 32, height: 32, borderRadius: 10, flexShrink: 0,
              background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: '#fff', fontSize: 14, fontWeight: 700,
            }}>
              {ownerName[0]}
            </div>
          )}

          {/* 名字 + 模式描述 */}
          <div style={{ minWidth: 0 }}>
            <p style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)', margin: 0 }}>
              {ownerName}
            </p>
            <p className="text-[11px] leading-tight m-0"
              style={{
                color: activeColor,
                transition: 'color 0.4s ease',
              }}>
              {MODE_ITEMS[activeIndex].desc}
            </p>
          </div>

          <div style={{ flex: 1 }} />

          {/* === 三合一模式药丸 === */}
          <div className="relative flex items-center rounded-xl p-0.5"
            style={{
              background: 'var(--s3)',
              boxShadow: 'inset 0 1px 2px rgba(0,0,0,0.04)',
            }}>
            {/* 滑动指示器 — 绝对定位彩色胶囊 */}
            <div className="absolute top-0.5 bottom-0.5 rounded-lg"
              style={{
                width: 'calc((100% - 4px) / 3)',
                left: `calc(2px + ${activeIndex * 100 / 3}%)`,
                background: activeColor,
                opacity: 0.12,
                boxShadow: `0 0 8px ${activeColor}33`,
                transition: 'left 0.35s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.3s ease',
              }}
            />
            {MODE_ITEMS.map(m => (
              <button key={m.key} onClick={() => {
                if (mode !== m.key) { chat.reset(); convs.startNew(); }
                setMode(m.key);
              }} className="relative z-10 flex items-center gap-1.5 px-3 py-1.5 rounded-lg border-none cursor-pointer text-xs font-medium font-[inherit] transition-colors duration-200"
                style={{
                  color: mode === m.key ? m.color : 'var(--fg-low)',
                }}>
                <span style={{ fontSize: 14 }}>{m.icon}</span>
                <span>{m.label}</span>
              </button>
            ))}
          </div>

          {/* 历史按钮 */}
          <button onClick={toggleHistory} className="bg-transparent border-none cursor-pointer text-base p-1"
            style={{ color: showHistory ? 'var(--fg-high)' : 'var(--fg-low)' }}>
            📋
          </button>
        </header>

        {/* Content area — key={mode} 触发重新挂载 + 交错动画 */}
        <div key={mode} className="flex-1 flex flex-col min-h-0">
          {mode === 'practice' ? (
            <PracticeView
              skillId={skill.id}
              ownerName={ownerName}
              initialSceneTag={practiceTag}
              onBack={() => {
                chat.reset();
                convs.startNew();
                setMode('qa');
              }}
            />
          ) : isEntry ? (
            <ChatEntry skill={skill} onQuestionClick={handleQuestionClick}
              showQuestions={showQuestions} showSceneTags={showSceneTags} mode={mode} />
          ) : (
            <ChatActive
              messages={chat.messages}
              streamText={chat.streamText}
              phase={chat.phase}
              inputValue={inputValue}
              onInputChange={setInputValue}
              onSend={handleSend}
              ownerName={ownerName}
              placeholder={mode === 'talk' ? '聊聊你的想法...' : '请教专家任何问题...'}
              mode={mode}
            />
          )}
        </div>

        {/* Warning toast — 分身画像不完整等非致命提醒 */}
        {chat.warning && (
          <div style={{
            position: 'fixed', bottom: 80, left: '50%', transform: 'translateX(-50%)',
            padding: '10px 20px', borderRadius: 100, background: '#f59e0b',
            color: '#fff', fontSize: 13, fontWeight: 500, zIndex: 200,
            cursor: 'pointer', boxShadow: '0 4px 12px rgba(245,158,11,0.3)',
          }} onClick={chat.dismissWarning}>
            💡 {chat.warning}（点击关闭）
          </div>
        )}

        {/* Error toast */}
        {chat.error && (
          <div style={{
            position: 'fixed', bottom: 20, left: '50%', transform: 'translateX(-50%)',
            padding: '10px 20px', borderRadius: 100, background: '#dc2626',
            color: '#fff', fontSize: 13, fontWeight: 500, zIndex: 200,
            cursor: 'pointer',
          }} onClick={chat.clearError}>
            ⚠️ {chat.error}（点击关闭）
          </div>
        )}
      </div>
    </div>
  );
}
