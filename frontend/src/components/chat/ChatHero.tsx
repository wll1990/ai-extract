'use client';

import React from 'react';

export interface ChatHeroProps {
  /** AI 名称 */
  name: string;
  /** AI 头像 URL 或 emoji */
  avatar?: string;
  /** 一行自我介绍 */
  intro: string;
  /** 2-4 个价值主张 {icon, title, desc} */
  traits?: { icon: string; title: string; desc: string }[];
  /** 隐私提示文案 */
  privacyNote?: string;
}

/**
 * 聊天 Hero 区 — AI 头像 + 自我介绍 + 价值主张 + 隐私提示。
 * 不挡消息区，滚动后自然消失。
 */
export const ChatHero: React.FC<ChatHeroProps> = ({ name, avatar, intro, traits, privacyNote }) => (
  <div className="mx-auto mb-6 max-w-[720px] rounded-2xl border border-border bg-surface-2/80 px-4 sm:px-6 py-5 shadow-sm">
    {/* AI 头像 + 名字 + 一句话 */}
    <div className="flex items-center gap-4 mb-4">
      {avatar ? (
        <img src={avatar} alt={name} className="h-12 w-12 rounded-full object-cover shadow-sm" />
      ) : (
        <img src="/def-avatar.png" alt={name} className="h-12 w-12 rounded-full object-cover shadow-sm" />
      )}
      <div>
        <h2 className="text-lg font-bold text-foreground">{name}</h2>
        <p className="text-sm text-muted-foreground">{intro}</p>
      </div>
    </div>

    {/* 价值主张 */}
    {traits && traits.length > 0 && (
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-3">
        {traits.map((t, i) => (
          <div key={i} className="rounded-xl bg-surface px-3 py-3 text-center">
            <div className="text-lg mb-1">{t.icon}</div>
            <div className="text-xs font-semibold text-foreground mb-0.5">{t.title}</div>
            <div className="text-[11px] text-muted-foreground leading-relaxed">{t.desc}</div>
          </div>
        ))}
      </div>
    )}

    {/* 隐私提示 */}
    {privacyNote && (
      <p className="text-[11px] text-muted-foreground-2 text-center">{privacyNote}</p>
    )}
  </div>
);
