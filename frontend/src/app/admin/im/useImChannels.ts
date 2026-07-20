'use client';

import { useState, useEffect, useCallback } from 'react';
import { getChannels, createChannel, updateChannel, deleteChannel, testChannel, type ImChannel } from '@/lib/api/im';

interface ImForm {
  type: string; enabled: boolean; appId: string; appSecret: string; webhookUrl: string; skills: string;
}

interface ImConfig {
  appId?: string; appSecret?: string; webhookUrl?: string;
}

const EMPTY_FORM: ImForm = { type: 'feishu', enabled: true, appId: '', appSecret: '', webhookUrl: '', skills: '' };

function parseConfig(ch: ImChannel): ImConfig {
  try { return typeof ch.config === 'string' ? JSON.parse(ch.config) : (ch.config || {}); }
  catch { return {}; }
}

export function useImChannels() {
  const [channels, setChannels] = useState<ImChannel[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [form, setForm] = useState<ImForm>(EMPTY_FORM);

  const loadChannels = useCallback(() => {
    getChannels().then(setChannels).catch(e => console.error('加载IM渠道失败', e));
  }, []);

  useEffect(() => { loadChannels(); }, [loadChannels]);

  const openCreate = useCallback(() => {
    setEditingId(null); setForm(EMPTY_FORM); setShowForm(true);
  }, []);

  const openEdit = useCallback((ch: ImChannel) => {
    setEditingId(ch.id);
    const cfg = parseConfig(ch);
    setForm({
      type: ch.channelType, enabled: ch.enabled,
      appId: cfg.appId || '', appSecret: cfg.appSecret || '',
      webhookUrl: cfg.webhookUrl || '', skills: ch.linkedSkills.join(', '),
    });
    setShowForm(true);
  }, []);

  const handleSave = useCallback(async () => {
    try {
      const body = {
        channelType: form.type, enabled: form.enabled,
        config: { appId: form.appId, appSecret: form.appSecret, webhookUrl: form.webhookUrl },
        linkedSkills: form.skills.split(',').map((s: string) => s.trim()).filter(Boolean),
      };
      if (editingId) await updateChannel(editingId, body);
      else await createChannel(body);
      loadChannels(); setShowForm(false);
    } catch (e) {
      console.error('保存IM渠道失败', e);
      alert('保存失败，请重试');
    }
  }, [editingId, form, loadChannels]);

  const handleDelete = useCallback(async (id: string) => {
    if (!confirm('确定要删除这个IM渠道？')) return;
    try {
      await deleteChannel(id);
      loadChannels();
    } catch (e) {
      console.error('删除IM渠道失败', e);
      alert('删除失败，请重试');
    }
  }, [loadChannels]);

  const handleTest = useCallback(async (id: string) => {
    setTestingId(id);
    const result = await testChannel(id).catch(() => null);
    alert(result?.success ? '测试成功！请检查IM平台' : '测试失败：' + (result?.message || '未知错误'));
    setTestingId(null);
  }, []);

  const toggleEnabled = useCallback(async (id: string, currentEnabled: boolean, currentConfig: any, currentType: string) => {
    const newEnabled = !currentEnabled;
    setChannels(prev => prev.map(c => c.id === id ? { ...c, enabled: newEnabled } : c));
    try {
      await updateChannel(id, { channelType: currentType, enabled: newEnabled, config: currentConfig });
    } catch (e) {
      console.error('切换渠道状态失败', e);
      setChannels(prev => prev.map(c => c.id === id ? { ...c, enabled: currentEnabled } : c));
    }
  }, []);

  return { channels, showForm, setShowForm, editingId, testingId, form, setForm,
    openCreate, openEdit, handleSave, handleDelete, handleTest, toggleEnabled };
}
