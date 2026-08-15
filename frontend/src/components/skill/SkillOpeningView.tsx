'use client';

import React, { useRef, useState, useEffect, useCallback } from 'react';
import { defAvatar } from '@aiextract/shared-ui';

export interface SceneTag {
  tag: string;
  count?: number;
  description?: string;
}

export interface SkillOpeningViewProps {
  ownerName: string;
  ownerTitle?: string;
  ownerIntro?: string;
  avatarUrl?: string;
  sceneTags?: SceneTag[];
  /** 选中场景后：开始问答 */
  onQaStart?: (sceneTag: string) => void;
  /** 选中场景后：开始对练 */
  onPracticeStart?: (sceneTag: string) => void;
  /** 指定默认模式后，点场景直接触发回调，不显示模式选择按钮 */
  defaultMode?: 'qa' | 'practice';
}

const SCENE_EMOJIS: Record<string, string> = {
  '价格异议': '💰', '竞品对比': '⚔️', '破冰': '🧊', '逼单': '🎯',
  '客户维护': '🤝', '需求挖掘': '🔍', '异议处理': '🛡️', '谈判': '⚖️',
  '催单': '⏰', '跟进': '📞',
};

function getEmoji(label: string): string {
  for (const [k, e] of Object.entries(SCENE_EMOJIS)) {
    if (label.includes(k)) return e;
  }
  return '🎯';
}

/**
 * 统一入口 — 场景轮播 → 选模式（问答/对练）
 */
export function SkillOpeningView({
  ownerName,
  ownerTitle,
  ownerIntro,
  avatarUrl,
  sceneTags,
  onQaStart,
  onPracticeStart,
  defaultMode,
}: SkillOpeningViewProps) {
  const sortedTags = (sceneTags
    ? [...sceneTags].sort((a, b) => (b.count || 0) - (a.count || 0))
    : []);

  const [selectedScene, setSelectedScene] = useState<string | null>(null);
  const [scenesExpanded, setScenesExpanded] = useState(false);

  // 电影卡片轮播
  const scrollRef = useRef<HTMLDivElement>(null);
  const [activeIndex, setActiveIndex] = useState(0);

  const updateCardStyles = useCallback(() => {
    const container = scrollRef.current;
    if (!container) return;
    const cards = container.querySelectorAll<HTMLElement>('.movie-card');
    const containerRect = container.getBoundingClientRect();
    const centerX = containerRect.left + containerRect.width / 2;
    let closestIdx = 0, closestDist = Infinity;
    cards.forEach((card, i) => {
      const rect = card.getBoundingClientRect();
      const dist = Math.abs(rect.left + rect.width / 2 - centerX);
      const maxDist = containerRect.width / 2 + rect.width / 2;
      const ratio = Math.min(dist / maxDist, 1);
      card.style.opacity = String(1 - ratio * 0.5);
      card.style.transform = `scale(${1 - ratio * 0.05})`;
      card.style.filter = `blur(${ratio * 1.5}px)`;
      card.style.transition = 'opacity 0.2s, transform 0.2s, filter 0.2s';
      if (dist < closestDist) { closestDist = dist; closestIdx = i; }
    });
    setActiveIndex(closestIdx);
  }, []);

  useEffect(() => {
    const c = scrollRef.current;
    if (!c || sortedTags.length === 0) return;
    requestAnimationFrame(updateCardStyles);
    c.addEventListener('scroll', updateCardStyles, { passive: true });
    window.addEventListener('resize', updateCardStyles);
    return () => { c.removeEventListener('scroll', updateCardStyles); window.removeEventListener('resize', updateCardStyles); };
  }, [sortedTags.length, updateCardStyles]);

  const handleSceneClick = (tag: string) => {
    // defaultMode 模式下：点场景直达目标模式
    if (defaultMode === 'qa') { onQaStart?.(tag); return; }
    if (defaultMode === 'practice') { onPracticeStart?.(tag); return; }
    // 无 defaultMode：保持原有选中/取消行为
    setSelectedScene(prev => prev === tag ? null : tag);
  };

  return (
    <div className="space-y-6 py-8">
      {/* 英雄区 — 仅在无默认模式时显示（初始进入分身页） */}
      {!defaultMode && (
        <div className="mx-auto rounded-2xl border border-border bg-surface-2/80 px-5 py-5 shadow-sm">
          <div className="flex items-start gap-4 mb-3">
            <div className="flex h-12 w-12 items-center justify-center rounded-full shadow-sm flex-shrink-0 overflow-hidden">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={avatarUrl || defAvatar}
                alt={ownerName || 'AI分身'}
                className="h-full w-full object-cover"
                onError={(e) => { (e.target as HTMLImageElement).src = defAvatar; }}
              />
            </div>
            <div className="flex-1 min-w-0">
              <h2 className="text-lg font-bold text-foreground">
                嗨，我是 {ownerName || 'AI分身'} <span className="inline-block">👋</span>
              </h2>
              {ownerTitle && <p className="text-sm text-muted-foreground">{ownerTitle}</p>}
              {ownerIntro && (
                <p className="mt-2 text-[13px] text-muted-foreground leading-relaxed">
                  {ownerIntro}
                </p>
              )}
            </div>
          </div>
          <div className="grid grid-cols-3 gap-2 mb-2">
            <div className="rounded-xl bg-surface px-3 py-2 text-center">
              <div className="text-sm mb-0.5">💬</div>
              <div className="text-[11px] font-semibold text-foreground">请教销冠</div>
              <div className="text-[10px] text-muted-foreground">AI 教你怎么说</div>
            </div>
            <div className="rounded-xl bg-surface px-3 py-2 text-center">
              <div className="text-sm mb-0.5">🎯</div>
              <div className="text-[11px] font-semibold text-foreground">场景对练</div>
              <div className="text-[10px] text-muted-foreground">模拟实战对话</div>
            </div>
            <div className="rounded-xl bg-surface px-3 py-2 text-center">
              <div className="text-sm mb-0.5">📋</div>
              <div className="text-[11px] font-semibold text-foreground">萃取报告</div>
              <div className="text-[10px] text-muted-foreground">查看经验成果</div>
            </div>
          </div>
          <p className="text-[11px] text-muted-foreground-2 text-center">每一段经历都值得被看见，每一个故事都能启发他人。</p>
        </div>
      )}

      {/* 场景选择 */}
      {sortedTags.length > 0 && (
        <div>
          <p className="text-xs font-medium text-muted-foreground text-center mb-2.5">
            {defaultMode === 'qa' ? '选一个场景开始，或直接跳过' : defaultMode === 'practice' ? '选一个场景开始对练' : '选择一个场景开始'}
          </p>

          {/* 场景卡片网格 — 默认4个，可展开收起 */}
          <div className={`grid grid-cols-2 gap-3 ${scenesExpanded ? 'max-h-[400px] overflow-y-auto -mx-2 px-2' : ''}`}>
            {sortedTags.slice(0, scenesExpanded ? undefined : 4).map((s) => {
              const isSelected = s.tag === selectedScene;
              return (
                <button
                  key={s.tag}
                  type="button"
                  onClick={() => handleSceneClick(s.tag)}
                  className={`rounded-xl p-4 text-left transition-all ${
                    isSelected
                      ? 'bg-primary text-white shadow-md'
                      : 'bg-surface-2 border border-border hover:shadow-md hover:border-primary/30'
                  }`}
                >
                  <span className="text-xl">{getEmoji(s.tag)}</span>
                  <h3 className={`mt-1.5 text-sm font-semibold ${isSelected ? 'text-white' : 'text-foreground'}`}>
                    {s.tag}
                  </h3>
                  {(s.count || 0) > 0 && (
                    <p className={`mt-0.5 text-[11px] ${isSelected ? 'text-white/70' : 'text-muted-foreground-2'}`}>
                      {s.count} 条锦囊
                    </p>
                  )}
                </button>
              );
            })}
          </div>

          {sortedTags.length > 4 && (
            <button
              onClick={() => setScenesExpanded(!scenesExpanded)}
              className="mt-3 w-full text-center text-xs text-muted-foreground py-2 rounded-lg hover:bg-surface transition-colors"
            >
              {scenesExpanded ? '收起 ▲' : `展开更多（还有 ${sortedTags.length - 4} 个）→`}
            </button>
          )}
        </div>
      )}

      {/* QA 模式跳过按钮 */}
      {defaultMode === 'qa' && (
        <button
          type="button"
          onClick={() => onQaStart?.('')}
          className="w-full rounded-xl border border-border bg-surface-2 px-4 py-3 text-center text-sm text-muted-foreground hover:border-primary/30 hover:text-foreground transition-all"
        >
          跳过，直接开始 →
        </button>
      )}

      {/* Practice 模式：无跳过按钮，必须选场景 */}

      {/* 无默认模式：保持原有"选场景 → 选模式"行为 */}
      {!defaultMode && selectedScene && (
        <div className="space-y-2.5 animate-[fadeIn_0.2s_ease-out]">
          <p className="text-xs font-medium text-muted-foreground text-center">
            选择「{selectedScene}」的打开方式
          </p>

          <button
            type="button"
            onClick={() => onQaStart?.(selectedScene)}
            className="w-full rounded-xl border border-border bg-surface-2 px-4 py-3.5 text-left shadow-sm hover:border-primary/30 hover:shadow-md transition-all group"
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-light text-lg">💬</span>
                <div>
                  <h3 className="font-semibold text-foreground">请教销冠</h3>
                  <p className="text-xs text-muted-foreground mt-0.5">AI 教你怎么说、为什么这么说</p>
                </div>
              </div>
              <span className="text-muted-foreground-2 group-hover:text-foreground group-hover:translate-x-0.5 transition-all">→</span>
            </div>
          </button>

          <button
            type="button"
            onClick={() => onPracticeStart?.(selectedScene)}
            className="w-full rounded-xl bg-warning-bg border border-warning/30 px-4 py-3.5 text-left hover:border-warning/50 hover:shadow-md transition-all group"
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-warning/20 text-lg">🎯</span>
                <div>
                  <h3 className="font-semibold text-warning-text">场景对练</h3>
                  <p className="text-xs text-muted-foreground mt-0.5">AI 扮演客户，模拟实战对话</p>
                </div>
              </div>
              <span className="text-warning-text/60 group-hover:text-warning-text group-hover:translate-x-0.5 transition-all">→</span>
            </div>
          </button>
        </div>
      )}
    </div>
  );
}
