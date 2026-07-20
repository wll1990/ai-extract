'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';

export default function ConversationsPage() {
  const router = useRouter();
  const [convs, setConvs] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);

  useEffect(() => {
    apiClient<any>(`/admin/conversations?page=${page}&size=20`)
      .then(d => setConvs(d.content || [])).catch(() => {}).finally(() => setLoading(false));
  }, [page]);

  if (loading) return <LoadingSpinner />;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-5xl">
        <h1 className="mb-6 text-2xl font-bold text-foreground">对话历史</h1>
        {convs.length === 0 ? (
          <div className="py-16 text-center text-muted-foreground-2">
            <p className="text-4xl mb-2">💬</p>
            <p>暂无对话记录</p>
          </div>
        ) : (
          <div className="bg-surface-2 rounded-xl border border-border overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-surface border-b">
                <tr>
                  <th className="text-left p-3 font-medium text-muted-foreground">用户</th>
                  <th className="text-left p-3 font-medium text-muted-foreground">分身</th>
                  <th className="text-left p-3 font-medium text-muted-foreground">模式</th>
                  <th className="text-left p-3 font-medium text-muted-foreground">消息数</th>
                  <th className="text-left p-3 font-medium text-muted-foreground">时间</th>
                  <th className="text-right p-3 font-medium text-muted-foreground">操作</th>
                </tr>
              </thead>
              <tbody>
                {convs.map((c: any) => (
                  <tr key={c.id} className="border-b last:border-0 hover:bg-surface">
                    <td className="p-3">{c.userName || '-'}</td>
                    <td className="p-3 font-medium">{c.skillName}</td>
                    <td className="p-3"><span className="px-2 py-0.5 rounded-full text-xs bg-primary-light">{c.mode}</span></td>
                    <td className="p-3 text-muted-foreground">{c.messageCount}</td>
                    <td className="p-3 text-muted-foreground">{c.updatedAt ? new Date(c.updatedAt).toLocaleString('zh-CN') : '-'}</td>
                    <td className="p-3 text-right">
                      <button onClick={() => router.push(`/admin/conversations/${c.id}`)}
                        className="text-primary hover:underline text-xs">回放</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
