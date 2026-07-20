'use client';

import { useState, useCallback, useEffect } from 'react';
import { listConversations, getConversationMessages, deleteConversation } from '@/lib/api/skill';
import type { ConversationItem, ConversationMessage } from '@/lib/api/skill';

export interface MsgSetter {
  setMessages: (msgs: Array<{ id: string; role: string; content: string; source: string; grainId: string; reportId?: string; grainTags?: string; grainCount?: number; avgScore?: string }>) => void;
  clearMessages: () => void;
}

export function useConversations(skillId: string, mode: string, msgSetter: MsgSetter, authToken?: string) {
  const [conversations, setConversations] = useState<ConversationItem[]>([]);
  const [currentConvId, setCurrentConvId] = useState<string>('');
  const [showHistory, setShowHistory] = useState(false);

  const loadConversations = useCallback(() => {
    if (!skillId) return;
    listConversations(skillId, authToken).then(setConversations).catch(() => {});
  }, [skillId, authToken]);

  const switchConversation = useCallback(async (convId: string) => {
    setCurrentConvId(convId);
    msgSetter.clearMessages();
    try {
      const msgs = await getConversationMessages(convId, authToken);
      msgSetter.setMessages(msgs.map((m: ConversationMessage) => ({
        id: m.id, role: m.role, content: m.content,
        source: m.reportTitle || '',
        grainId: m.grainId || '',
        reportId: m.reportId || '',
        grainTags: m.grainTags || '',
        grainCount: m.grainCount || 0,
        avgScore: m.avgScore || '',
      })));
    } catch(e) { console.error(e); }
  }, [msgSetter, authToken]);

  const handleDeleteConversation = useCallback(async (convId: string) => {
    if (!confirm('确定删除这条对话记录？')) return;
    await deleteConversation(convId, authToken);
    if (currentConvId === convId) { setCurrentConvId(''); msgSetter.clearMessages(); }
    loadConversations();
  }, [currentConvId, loadConversations, msgSetter, authToken]);

  // 所有非 practice 模式都加载历史（practice 使用独立的对练流程）
  useEffect(() => { if (mode !== 'practice') loadConversations(); }, [mode, loadConversations]);

  return { conversations, currentConvId, setCurrentConvId, showHistory, setShowHistory,
    loadConversations, switchConversation, handleDeleteConversation };
}
