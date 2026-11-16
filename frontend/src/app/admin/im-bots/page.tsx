'use client';

import React, { useState, useEffect } from 'react';
import { listImBots, unpublishImBot, reconnectImBot, type ImBot } from '@/lib/api/im';

/**
 * 分身接入（公司级） — 展示本企业已接入到 IM 渠道的分身，支持下线。
 */
export default function AdminImBotsPage() {
  const [bots, setBots] = useState<ImBot[]>([]);
  const [loading, setLoading] = useState(true);

  const load = () => {
    setLoading(true);
    listImBots().then(setBots).catch(() => {}).finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);

  const handleUnpublish = async (botId: string) => {
    if (!confirm('确定下线这个分身？')) return;
    try { await unpublishImBot(botId); load(); }
    catch (e) { console.error('下线失败', e); alert('下线失败，请重试'); }
  };

  const handleReconnect = async (botId: string) => {
    try { await reconnectImBot(botId); load(); }
    catch (e) { alert(e instanceof Error ? e.message : '重连失败，请检查桥服务'); }
  };

  const CHANNEL_META: Record<string, { label: string; icon: string }> = {
    feishu: { label: '飞书', icon: '🕊️' },
    wecom: { label: '企业微信', icon: '💼' },
    dingtalk: { label: '钉钉', icon: '📌' },
  };

  const STATUS_LABELS: Record<string, string> = {
    published: '已发布',
    publishing: '连接中',
    failed: '连接失败',
    disabled: '已下线',
  };
  const STATUS_CLS: Record<string, string> = {
    published: 'bg-green-100 text-green-700',
    publishing: 'bg-blue-100 text-blue-700',
    failed: 'bg-red-100 text-red-700',
    disabled: 'bg-gray-100 text-gray-600',
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]" data-tour="im-bots">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold text-foreground">分身接入</h1>
            <p className="text-sm text-muted-foreground mt-1">已接入到 IM 渠道的分身，员工可在飞书/企微/钉钉直接提问</p>
          </div>
        </div>

        {loading ? (
          <p className="text-sm text-muted-foreground">加载中...</p>
        ) : bots.length === 0 ? (
          <div className="rounded-2xl bg-surface-2 p-12 text-center">
            <span className="text-4xl">📡</span>
            <p className="mt-4 text-muted-foreground">暂无已发布到 IM 渠道的分身</p>
            <p className="text-sm text-muted-foreground-2">去分身管理页点击「发布」，可选飞书 / 企业微信 / 钉钉</p>
          </div>
        ) : (
          <div className="space-y-3">
            {bots.map((b) => (
              <div key={b.id} className="rounded-xl bg-surface-2 p-5 shadow-sm flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-2xl">{CHANNEL_META[b.channelType]?.icon || '📡'}</span>
                    <h3 className="font-semibold text-foreground">{b.skillName || '未知分身'}</h3>
                    <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-surface text-muted-foreground">
                      {CHANNEL_META[b.channelType]?.label || b.channelType}
                    </span>
                    <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${STATUS_CLS[b.status] || STATUS_CLS.disabled}`}>
                      {STATUS_LABELS[b.status] || b.status}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground-2 mt-1">App ID: {b.appId || '—'}</p>
                  {b.status === 'failed' && b.lastError && (
                    <p className="mt-1 max-w-[520px] truncate text-xs text-danger" title={b.lastError}>{b.lastError}</p>
                  )}
                </div>
                <div className="flex shrink-0 gap-2">
                  {b.status === 'failed' && (
                    <button onClick={() => handleReconnect(b.botId)}
                      className="rounded-lg border border-primary text-primary px-3 py-1 text-xs">重连</button>
                  )}
                  <button onClick={() => handleUnpublish(b.botId)}
                    className="rounded-lg border border-red-200 text-red-600 px-3 py-1 text-xs">下线</button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
