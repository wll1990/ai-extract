'use client';

import React, { useEffect, useState } from 'react';
import { apiClient } from '@/lib/api/client';

export interface SkillModeSelectorProps {
  skillId: string;
  ownerName: string;
  ownerTitle?: string;
  /** AI 生成或手动填写的开场白（一句有质感的话） */
  openingMessage?: string | null;
  /** 分身头像 URL */
  avatarUrl?: string | null;
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
  openingMessage,
  avatarUrl,
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
      <div className="flex flex-col px-4 pt-12 pb-8">
        <div className="flex items-start gap-4">
          <div className="flex h-[72px] w-[72px] items-center justify-center rounded-2xl bg-gradient-to-br from-navy to-primary text-2xl text-white font-bold shadow-lg flex-shrink-0">
            {initial}
          </div>
          <div className="flex-1 min-w-0 pt-1">
            <h2 className="text-xl font-bold text-foreground">{ownerName}</h2>
            {ownerTitle && <p className="text-sm text-muted-foreground mt-0.5">{ownerTitle}</p>}
          </div>
        </div>
        <div className="mt-8 text-sm text-muted-foreground text-center">加载中...</div>
      </div>
    );
  }

  return (
    <div className="flex flex-col px-4 pt-12 pb-8">
      {/* 分身头像 + 信息 + 开场白 — 左图右文布局 */}
      <div className="flex items-start gap-4">
        {/* 左侧大头像 */}
        {avatarUrl ? (
          <div className="flex h-[72px] w-[72px] items-center justify-center rounded-2xl overflow-hidden flex-shrink-0 shadow-lg">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={avatarUrl} alt={ownerName || 'AI分身'} className="h-full w-full object-cover" />
          </div>
        ) : (
          <div className="flex h-[72px] w-[72px] items-center justify-center rounded-2xl bg-gradient-to-br from-navy to-primary text-2xl text-white font-bold shadow-lg flex-shrink-0">
            {initial}
          </div>
        )}

        {/* 右侧信息 */}
        <div className="flex-1 min-w-0 pt-1">
          <h2 className="text-xl font-bold text-foreground">{ownerName}</h2>
          {ownerTitle && (
            <p className="text-sm text-muted-foreground mt-0.5">{ownerTitle}</p>
          )}

          {/* Hero 开场白 — 一句有质感的话 */}
          {openingMessage && (
            <p className="mt-2.5 text-sm text-foreground/80 leading-relaxed italic
              bg-gradient-to-r from-primary/5 via-primary/10 to-primary/5
              rounded-xl px-3.5 py-2">
              "{openingMessage}"
            </p>
          )}
        </div>
      </div>

      {/* 模式选择 — 标签从领域配置读取 */}
      <div className="mt-8 grid grid-cols-3 gap-3 w-full max-w-sm mx-auto">
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
