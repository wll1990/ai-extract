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
        {/* Header */}
        <header style={{
          display: 'flex', alignItems: 'center', gap: 12,
          padding: '10px 20px', borderBottom: '1px solid var(--border-subtle)',
          background: 'var(--s1)', flexShrink: 0,
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

          <div style={{ minWidth: 0 }}>
            <p style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)', margin: 0 }}>
              {ownerName}
            </p>
            {skill.ownerTitle && (
              <p style={{ fontSize: 11, color: 'var(--fg-low)', margin: 0 }}>
                {skill.ownerTitle}
              </p>
            )}
          </div>

          <div style={{ flex: 1 }} />

          {/* Mode tabs */}
          {mode !== 'practice' && (
            <div style={{
              display: 'flex', gap: 2, background: 'var(--s3)',
              borderRadius: 10, padding: 2,
            }}>
              {[
                { key: 'qa' as Mode, label: '请教' },
                { key: 'talk' as Mode, label: '对话' },
              ].map(m => (
                <button key={m.key} onClick={() => setMode(m.key)} style={{
                  padding: '5px 12px', borderRadius: 8, border: 'none',
                  cursor: 'pointer', fontSize: 12, fontWeight: 500,
                  fontFamily: 'inherit',
                  transition: 'all 0.15s',
                  ...(mode === m.key
                    ? { background: 'var(--surface)', color: 'var(--fg-high)', boxShadow: 'var(--shadow-sm)' }
                    : { background: 'transparent', color: 'var(--fg-low)' }),
                }}>
                  {m.label}
                </button>
              ))}
            </div>
          )}

          <button onClick={() => { setMode('practice'); setPracticeTag(undefined); }}
            style={{
              padding: '5px 12px', borderRadius: 8, border: 'none',
              cursor: 'pointer', fontSize: 12, fontWeight: 500,
              fontFamily: 'inherit', transition: 'all 0.15s',
              ...(mode === 'practice'
                ? { background: 'var(--surface)', color: 'var(--fg-high)', boxShadow: 'var(--shadow-sm)' }
                : { background: 'transparent', color: 'var(--fg-low)' }),
            }}>
            🎯 对练
          </button>

          <button onClick={toggleHistory} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            fontSize: 16, color: showHistory ? 'var(--fg-high)' : 'var(--fg-low)',
            padding: 4,
          }}>
            📋
          </button>
        </header>

        {/* Content area */}
        {mode === 'practice' ? (
          <PracticeView
            skillId={skill.id}
            ownerName={ownerName}
            initialSceneTag={practiceTag}
            onBack={() => setMode('qa')}
          />
        ) : isEntry ? (
          <ChatEntry skill={skill} onQuestionClick={handleQuestionClick} />
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
          />
        )}

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
