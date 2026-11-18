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

import { useState, useCallback, useEffect } from 'react';
import { listConversations, getConversationMessages, deleteConversation, renameConversation } from '@/lib/api/skill';
import type { ConversationItem, ConversationMessage } from '@/lib/api/skill';

export interface MsgSetter {
  setMessages: (msgs: Array<{ id: string; role: string; content: string; source: string; grainId: string; reportId?: string; grainTags?: string; grainCount?: number; avgScore?: string }>) => void;
  clearMessages: () => void;
}

export function useConversations(skillId: string, mode: string, msgSetter: MsgSetter,
                                 authToken?: string, guestMode = false) {
  const [conversations, setConversations] = useState<ConversationItem[]>([]);
  const [currentConvId, setCurrentConvId] = useState<string>('');
  const [showHistory, setShowHistory] = useState(false);

  const loadConversations = useCallback(() => {
    // 游客分享页尚未领证时后台拉历史会 401，会误弹注册/登录抽屉；
    // 领到游客证（authToken）后再加载，历史会在进聊天时补齐。
    if (!skillId || (guestMode && !authToken)) return;
    listConversations(skillId, authToken).then(setConversations).catch(() => {});
  }, [skillId, authToken, guestMode]);

  const switchConversation = useCallback(async (convId: string) => {
    setCurrentConvId(convId);
    msgSetter.clearMessages();
    try {
      const msgs = await getConversationMessages(convId, authToken);
      msgSetter.setMessages(msgs.map((m: ConversationMessage) => ({
        id: m.id, role: m.role, content: m.content,
        source: m.reportTitle || '',
        grainId: m.grainId || '',
        grainIds: (m.grainIds || []).join(','),
        reportId: m.reportId || '',
        grainTags: m.grainTags || '',
        grainCount: m.grainCount || 0,
        avgScore: m.avgScore || '',
        avgSimilarity: m.avgSimilarity != null ? String(m.avgSimilarity) : '',
        knowledgeSources: m.knowledgeSources,
        caseSources: m.caseSources,
      })));
    } catch(e) { console.error(e); }
  }, [msgSetter, authToken]);

  const handleRenameConversation = useCallback(async (convId: string, currentTitle: string) => {
    const title = window.prompt('重命名对话', currentTitle || '');
    if (title === null || !title.trim()) return;
    try {
      await renameConversation(convId, title.trim(), authToken);
      loadConversations();
    } catch (e) {
      alert(e instanceof Error ? e.message : '重命名失败');
    }
  }, [authToken, loadConversations]);

  /** 导出会话为 txt（前端下载，不经过后端文件存储） */
  const handleExportConversation = useCallback(async (convId: string) => {
    try {
      const msgs = await getConversationMessages(convId, authToken);
      const text = msgs
        .map(m => `${m.role === 'user' ? '我' : 'AI'}：${m.content}`)
        .join('\n\n');
      const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `对话-${convId.slice(0, 8)}.txt`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      alert(e instanceof Error ? e.message : '导出失败');
    }
  }, [authToken]);

  const handleDeleteConversation = useCallback(async (convId: string) => {
    if (!confirm('确定删除这条对话记录？')) return;
    await deleteConversation(convId, authToken);
    if (currentConvId === convId) { setCurrentConvId(''); msgSetter.clearMessages(); }
    loadConversations();
  }, [currentConvId, loadConversations, msgSetter, authToken]);

  // 所有非 practice 模式都加载历史（practice 使用独立的对练流程）
  useEffect(() => { if (mode !== 'practice') loadConversations(); }, [mode, loadConversations]);

  return { conversations, currentConvId, setCurrentConvId, showHistory, setShowHistory,
    loadConversations, switchConversation, handleDeleteConversation, handleRenameConversation,
    handleExportConversation };
}
