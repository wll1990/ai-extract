'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

interface MyWorkbenchData {
  todayConversations: number;
  weekConversations: number;
  monthConversations: number;
  todayPractice: number;
  recentSkills: Array<{
    skillId: string; displayName: string; ownerName: string;
    conversations: number; lastActive: string;
  }>;
}

export default function WorkbenchPage() {
  const router = useRouter();
  const [data, setData] = useState<MyWorkbenchData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient<MyWorkbenchData>('/workbench/mine')
      .then(setData)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner />;
  if (!data) return null;

  return (
    <div className="min-h-screen bg-surface">
      <div className="mx-auto max-w-[960px] px-6 py-8 space-y-6">
        <h1 className="text-2xl font-bold text-foreground">我的工作台</h1>

        {/* KPI 卡片 */}
        <div className="grid grid-cols-4 gap-4">
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{data.todayConversations}</p>
            <p className="text-xs text-muted-foreground mt-1">今日对话</p>
          </div>
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{data.todayPractice}</p>
            <p className="text-xs text-muted-foreground mt-1">今日对练</p>
          </div>
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{data.weekConversations}</p>
            <p className="text-xs text-muted-foreground mt-1">本周对话</p>
          </div>
          <div className="rounded-xl bg-surface-2 border border-border p-5 text-center">
            <p className="text-3xl font-bold text-foreground">{data.monthConversations}</p>
            <p className="text-xs text-muted-foreground mt-1">本月对话</p>
          </div>
        </div>

        {/* 最近使用分身 */}
        <div>
          <h2 className="font-semibold text-foreground mb-3">我最近使用的分身</h2>
          {data.recentSkills.length > 0 ? (
            <div className="space-y-2">
              {data.recentSkills.map(skill => (
                <div
                  key={skill.skillId}
                  onClick={() => router.push(`/skill/${skill.skillId}`)}
                  className="flex items-center gap-4 rounded-xl bg-surface-2 border border-border p-4 cursor-pointer hover:border-primary transition-colors shadow-sm"
                >
                  <div className="w-10 h-10 rounded-full bg-gradient-to-br from-blue-500 to-purple-500 flex items-center justify-center text-white text-sm font-bold flex-shrink-0">
                    {(skill.displayName || skill.ownerName || '?').charAt(0)}
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-foreground truncate">
                      {skill.displayName || skill.ownerName || '未命名'}
                    </p>
                    <p className="text-xs text-muted-foreground mt-0.5">
                      {skill.conversations} 次对话 · 最近 {skill.lastActive}
                    </p>
                  </div>
                  <span className="text-muted-foreground text-sm">→</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="rounded-xl bg-surface-2 border border-border p-8 text-center">
              <p className="text-sm text-muted-foreground">还没有使用过分身</p>
              <button
                onClick={() => router.push('/skills')}
                className="mt-3 px-4 py-2 rounded-full bg-primary text-white text-sm font-medium"
              >
                去分身广场 →
              </button>
            </div>
          )}
        </div>

        {/* 快捷入口 */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div onClick={() => router.push('/skills')}
            className="rounded-xl bg-surface-2 border border-border p-5 cursor-pointer hover:border-primary transition-colors">
            <h3 className="font-semibold text-foreground text-sm">🤖 分身广场</h3>
            <p className="text-xs text-muted-foreground mt-1">浏览所有可用分身，随时请教学习</p>
          </div>
          <div onClick={() => router.push('/interview/create')}
            className="rounded-xl bg-surface-2 border border-border p-5 cursor-pointer hover:border-primary transition-colors">
            <h3 className="font-semibold text-foreground text-sm">💼 销冠访谈</h3>
            <p className="text-xs text-muted-foreground mt-1">通过 AI 访谈萃取你的销售经验</p>
          </div>
        </div>
      </div>
    </div>
  );
}
