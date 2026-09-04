'use client';

import React, { useState, useEffect } from 'react';
import { listImBots, unpublishImBot, type ImBot } from '@/lib/api/im';

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

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold text-foreground">分身接入</h1>
            <p className="text-sm text-muted-foreground mt-1">已接入到 IM 渠道的分身，员工可在飞书单聊或群聊 @</p>
          </div>
        </div>

        {loading ? (
          <p className="text-sm text-muted-foreground">加载中...</p>
        ) : bots.length === 0 ? (
          <div className="rounded-2xl bg-surface-2 p-12 text-center">
            <span className="text-4xl">🕊️</span>
            <p className="mt-4 text-muted-foreground">暂无已发布到 IM 渠道的分身</p>
            <p className="text-sm text-muted-foreground-2">去分身管理页点击「🕊️ 飞书」发布</p>
          </div>
        ) : (
          <div className="space-y-3">
            {bots.map((b) => (
              <div key={b.id} className="rounded-xl bg-surface-2 p-5 shadow-sm flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-2xl">🕊️</span>
                    <h3 className="font-semibold text-foreground">{b.skillName || '未知分身'}</h3>
                    <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${b.status === 'published' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                      {b.status === 'published' ? '已发布' : '已下线'}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground-2 mt-1">App ID: {b.appId || '—'}</p>
                </div>
                <button onClick={() => handleUnpublish(b.botId)}
                  className="rounded-lg border border-red-200 text-red-600 px-3 py-1 text-xs">下线</button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
