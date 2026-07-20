'use client';

import React, { useEffect, useState } from 'react';
import { apiClient } from '@/lib/api/client';

export interface SkillModeSelectorProps {
  skillId: string;
  ownerName: string;
  ownerTitle?: string;
  onTalkStart: () => void;
  onQaStart: () => void;
  onPracticeStart: () => void;
}

interface ModeConfig {
  label: string;
  description?: string;
  icon?: string;
}

/**
 * 三模式入口 — 标签从领域配置 API 动态读取。
 *
 * 自由对话：直接进入聊天，AI 发送开场白
 * QA：选场景 → 问答
 * 对练：选场景 → 角色扮演
 */
export function SkillModeSelector({
  skillId,
  ownerName,
  ownerTitle,
  onTalkStart,
  onQaStart,
  onPracticeStart,
}: SkillModeSelectorProps) {
  const initial = (ownerName || '?').charAt(0);

  const [modes, setModes] = useState<Record<string, ModeConfig>>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!skillId) return;
    apiClient<{ modes: Record<string, ModeConfig> }>(`/api/skills/${skillId}/domain-config`)
      .then(d => { setModes(d.modes || {}); setLoading(false); })
      .catch(() => setLoading(false));
  }, [skillId]);

  const qa = modes?.qa;
  const talk = modes?.talk;
  const practice = modes?.practice;

  if (loading) {
    return (
      <div className="flex flex-col items-center px-4 pt-12 pb-8">
        <div className="flex h-16 w-16 items-center justify-center rounded-full bg-gradient-to-br from-navy to-primary text-xl text-white font-bold shadow-lg mb-4">
          {initial}
        </div>
        <h2 className="text-xl font-bold text-foreground">{ownerName}</h2>
        {ownerTitle && <p className="text-sm text-muted-foreground mt-1">{ownerTitle}</p>}
        <div className="mt-8 text-sm text-muted-foreground">加载中...</div>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center px-4 pt-12 pb-8">
      {/* 分身头像 + 信息 */}
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-gradient-to-br from-navy to-primary text-xl text-white font-bold shadow-lg mb-4">
        {initial}
      </div>
      <h2 className="text-xl font-bold text-foreground">{ownerName}</h2>
      {ownerTitle && (
        <p className="text-sm text-muted-foreground mt-1">{ownerTitle}</p>
      )}

      {/* 模式选择 — 标签从领域配置读取 */}
      <div className="mt-8 grid grid-cols-3 gap-3 w-full max-w-sm">
        <button
          type="button"
          onClick={onTalkStart}
          className="flex flex-col items-center gap-2 rounded-2xl border border-border bg-surface-2 p-5 shadow-sm hover:border-primary/30 hover:shadow-md transition-all group"
        >
          <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary-light text-2xl group-hover:scale-110 transition-transform">
            {talk?.icon || '☕'}
          </span>
          <span className="text-sm font-semibold text-foreground">{talk?.label || '自由对话'}</span>
          <span className="text-[11px] text-muted-foreground-2 leading-tight text-center">
            {talk?.description || '和专家聊聊'}
          </span>
        </button>

        <button
          type="button"
          onClick={onQaStart}
          className="flex flex-col items-center gap-2 rounded-2xl border border-border bg-surface-2 p-5 shadow-sm hover:border-primary/30 hover:shadow-md transition-all group"
        >
          <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary-light text-2xl group-hover:scale-110 transition-transform">
            {qa?.icon || '💬'}
          </span>
          <span className="text-sm font-semibold text-foreground">{qa?.label || '请教专家'}</span>
          <span className="text-[11px] text-muted-foreground-2 leading-tight text-center">
            {qa?.description || '有问题直接问'}
          </span>
        </button>

        <button
          type="button"
          onClick={onPracticeStart}
          className="flex flex-col items-center gap-2 rounded-2xl bg-warning-bg border border-warning/30 p-5 shadow-sm hover:border-warning/50 hover:shadow-md transition-all group"
        >
          <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-warning/20 text-2xl group-hover:scale-110 transition-transform">
            {practice?.icon || '🎯'}
          </span>
          <span className="text-sm font-semibold text-warning-text">{practice?.label || '场景演练'}</span>
          <span className="text-[11px] text-warning-text/60 leading-tight text-center">
            {practice?.description || '角色扮演训练'}
          </span>
        </button>
      </div>
    </div>
  );
}
