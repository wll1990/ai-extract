'use client';

import React from 'react';

interface SceneTag {
  tag: string;
  count: number;
}

interface Props {
  sceneTags: SceneTag[];
  activeTag: string;
  chatMode: string;
  onQaTagClick: (tag: string) => void;
  onTalkTagClick: (tag: string) => void;
}

export default function SceneTagBar({ sceneTags, activeTag, chatMode, onQaTagClick, onTalkTagClick }: Props) {
  if ((chatMode !== 'qa' && chatMode !== 'talk') || sceneTags.length === 0) return null;

  return (
    <div className="overflow-x-auto scrollbar-none -mx-2 px-2">
      <div className="flex gap-1.5 pb-1">
        {sceneTags.map((s) => {
          const isActive = s.tag === activeTag;
          return (
            <button
              key={s.tag}
              type="button"
              onClick={() => {
                if (isActive) return;
                if (chatMode === 'talk') {
                  onTalkTagClick(`${s.tag}方面，你有什么经验可以分享一下？`);
                  return;
                }
                onQaTagClick(s.tag);
              }}
              className={`flex-shrink-0 rounded-full px-3 py-1 text-xs font-medium transition-colors whitespace-nowrap ${
                isActive
                  ? 'bg-primary text-white'
                  : 'bg-surface-2 border border-border text-muted-foreground hover:border-primary/30'
              }`}
            >
              {s.tag}
            </button>
          );
        })}
      </div>
    </div>
  );
}
