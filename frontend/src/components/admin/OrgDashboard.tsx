'use client';

import React from 'react';

interface MemberInfo {
  id: string;
  ownerName: string;
  avatarUrl?: string;
  ownerTitle?: string;
  conversationCount: number;
}

interface DashboardData {
  name: string;
  status: string;
  conversationCount: number;
  userCount: number;
  satisfactionRate: number;
  memberCount: number;
  activeMembers: MemberInfo[];
  lastActiveAt?: string;
}

interface Props {
  data: DashboardData;
  onClose: () => void;
}

export function OrgDashboard({ data, onClose }: Props) {
  return (
    <div className="fixed inset-0 bg-black/30 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-2xl max-w-lg w-full max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-gray-100">
          <div>
            <h2 className="text-lg font-bold text-gray-900">📊 {data.name}</h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              {data.status === 'published' ? '🟢 已发布' : data.status === 'draft' ? '🟡 草稿' : data.status}
            </p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-lg p-1">✕</button>
        </div>

        {/* KPI Row */}
        <div className="grid grid-cols-3 gap-4 px-6 py-5 border-b border-gray-50">
          <div className="text-center">
            <div className="text-2xl font-bold text-gray-900">{data.conversationCount.toLocaleString()}</div>
            <div className="text-[11px] text-muted-foreground mt-1">💬 次对话</div>
          </div>
          <div className="text-center">
            <div className="text-2xl font-bold text-gray-900">{data.userCount.toLocaleString()}</div>
            <div className="text-[11px] text-muted-foreground mt-1">👤 位用户</div>
          </div>
          <div className="text-center">
            <div className="text-2xl font-bold text-gray-900">{data.satisfactionRate}%</div>
            <div className="text-[11px] text-muted-foreground mt-1">👍 满意率</div>
          </div>
        </div>

        {/* Member Contributions */}
        <div className="px-6 py-5">
          <h3 className="text-sm font-semibold text-gray-900 mb-3">
            👥 成员贡献（{data.activeMembers.length}/{data.memberCount} 活跃）
          </h3>
          <div className="space-y-2">
            {data.activeMembers.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-4">暂无活跃成员</p>
            ) : (
              data.activeMembers
                .sort((a, b) => b.conversationCount - a.conversationCount)
                .map((m, i) => (
                  <div key={m.id} className="flex items-center gap-3 py-2 px-3 rounded-lg hover:bg-gray-50">
                    <span className="text-xs font-bold text-muted-foreground w-5">
                      {i === 0 ? '🥇' : i === 1 ? '🥈' : i === 2 ? '🥉' : `${i + 1}`}
                    </span>
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-indigo-500 to-purple-500 flex items-center justify-center text-white text-xs font-bold flex-shrink-0">
                      {m.ownerName?.charAt(0) || '?'}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-gray-900 truncate">{m.ownerName}</div>
                      <div className="text-[11px] text-muted-foreground truncate">{m.ownerTitle || ''}</div>
                    </div>
                    <div className="text-sm font-semibold text-indigo-600 flex-shrink-0">
                      {m.conversationCount} 次
                    </div>
                  </div>
                ))
              )}
          </div>
        </div>

        {/* Footer */}
        <div className="px-6 pb-5 text-center">
          <button onClick={onClose}
            className="w-full rounded-xl bg-gray-100 text-gray-700 py-2.5 text-sm font-medium hover:bg-gray-200 transition-colors">
            关闭
          </button>
        </div>
      </div>
    </div>
  );
}
