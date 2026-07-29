'use client';

import { useState, useCallback } from 'react';
import {
  listConversations, getConversationMessages, deleteConversation as deleteConvApi,
  type ConversationItem, type ConversationMessage,
} from '@/lib/api/skill';

export function useConversations(skillId: string) {
  const [conversations, setConversations] = useState<ConversationItem[]>([]);
  const [currentConvId, setCurrentConvId] = useState<string | null>(null);

  const loadList = useCallback(async () => {
    try {
      const list = await listConversations(skillId);
      setConversations(list);
    } catch {
      // silent — history is non-critical
    }
  }, [skillId]);

  const loadMessages = useCallback(async (conversationId: string): Promise<ConversationMessage[]> => {
    try {
      return await getConversationMessages(conversationId);
    } catch {
      return [];
    }
  }, []);

  const switchConversation = useCallback((id: string) => {
    setCurrentConvId(id);
  }, []);

  const startNew = useCallback(() => {
    setCurrentConvId(null);
  }, []);

  const deleteConversation = useCallback(async (id: string) => {
    try {
      await deleteConvApi(id);
      setConversations(prev => prev.filter(c => c.id !== id));
      if (currentConvId === id) setCurrentConvId(null);
    } catch {
      // silent
    }
  }, [currentConvId]);

  return {
    conversations, currentConvId, setCurrentConvId,
    loadList, loadMessages, switchConversation, startNew, deleteConversation,
  };
}
