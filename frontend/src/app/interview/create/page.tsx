'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useInterviewCreate, type ExpertOption } from './useInterviewCreate';

const PRESET_TOPICS = [
  '搞定说太贵了的客户', '如何在复杂决策链中找到关键人', '大客户破冰技巧',
  '异议处理的黄金话术', '逼单时机的判断', '信任建立的秘诀',
];

export default function CreateInterviewPage() {
  const h = useInterviewCreate();
  const [showConfirm, setShowConfirm] = useState(false);

  const handleStartClick = () => {
    if (h.isFirstInterview) {
      setShowConfirm(true);
    } else {
      h.handleStart();
    }
  };

  const iconClass = "h-8 w-8 flex-shrink-0 rounded-full bg-primary-light flex items-center justify-center text-primary text-sm font-semibold";

  return (
    <div className="min-h-screen bg-surface">
      {/* 进行中访谈提示 */}
      {h.activeSession && (
        <div className="sticky top-0 z-40 flex items-center justify-between bg-warning-bg px-4 sm:px-6 py-3">
          <div className="flex items-center gap-3 min-w-0">
            <svg className="h-5 w-5 flex-shrink-0 text-warning" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}><path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" /></svg>
            <span className="text-sm text-warning truncate">你有进行中的访谈「{h.activeSession.topic}」</span>
          </div>
          <button type="button" onClick={h.handleContinue} className="flex-shrink-0 rounded-lg bg-warning px-4 py-1.5 text-sm font-medium text-white transition-colors hover:bg-amber-700">继续</button>
        </div>
      )}

      <div className="mx-auto max-w-[640px] px-4 sm:px-6 pb-12 pt-6 sm:pt-8">
        {/* 顶部导航 */}
        <div className="mb-6 flex items-center justify-between">
          <Link href="/skill" className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors">← 返回分身广场</Link>
          <button type="button" onClick={() => window.history.back()} className="text-muted-foreground-2 hover:text-foreground text-lg transition-colors" aria-label="关闭">✕</button>
        </div>

        <h1 className="mb-8 text-[26px] sm:text-[28px] font-bold text-foreground">创建新访谈</h1>

        {/* 被访者 */}
        <div className="mb-5 rounded-xl bg-surface-2 border border-border p-4">
          <label className="block text-xs font-medium text-muted-foreground mb-2">被访者</label>
          {h.spaces.length === 0 ? (
            <p className="text-sm text-muted-foreground">加载中...</p>
          ) : h.spaces.length === 1 ? (
            <div className="flex items-center gap-3">
              <div className={iconClass}>{h.spaces[0].ownerName?.charAt(0) || '?'}</div>
              <div className="min-w-0">
                <p className="text-base font-semibold text-foreground">{h.spaces[0].ownerName}</p>
                <p className="text-xs text-muted-foreground">{h.spaces[0].title}{h.spaces[0].grainCount != null ? ` · 已萃取 ${h.spaces[0].grainCount} 条锦囊` : ''}</p>
              </div>
            </div>
          ) : (
            <select value={h.spaceId} onChange={e => h.setSpaceId(e.target.value)} className="w-full border border-border rounded-lg p-2 text-sm bg-surface-2 min-h-[44px]">
              <option value="">选择要分享经验的被访者</option>
              {h.spaces.map(s => (
                <option key={s.id} value={s.id}>
                  {s.ownerName} · {s.title}{s.grainCount != null ? ` (${s.grainCount}条锦囊)` : ''}
                </option>
              ))}
            </select>
          )}
        </div>

        {/* 主题 */}
        <div className="mb-6 rounded-xl bg-surface-2 p-4 sm:p-6 shadow-md">
          <h2 className="mb-4 text-lg font-semibold text-foreground">想聊什么主题？</h2>
          <input
            type="text"
            value={h.topicInput}
            onChange={e => h.setTopicInput(e.target.value)}
            placeholder="例如：搞定说太贵了的客户、大客户破冰技巧..."
            className="w-full border-b-2 border-border-strong bg-transparent py-3 text-base sm:text-lg text-foreground placeholder-muted-foreground-2 outline-none transition-colors focus:border-foreground"
            maxLength={200}
            autoFocus
          />
          <p className="mt-2 text-xs text-muted-foreground-2">{h.topicInput.length}/200</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <span className="text-xs text-muted-foreground self-center mr-1">💡 试试这些：</span>
            {PRESET_TOPICS.map(topic => (
              <button
                key={topic}
                type="button"
                onClick={() => h.setTopicInput(topic)}
                className="rounded-full border border-border px-3 py-1 text-xs text-foreground transition-colors hover:border-foreground hover:bg-primary-light min-h-[36px]"
              >{topic}</button>
            ))}
          </div>
        </div>

        {/* 高级设置（折叠） */}
        <div className="mb-6 rounded-xl bg-surface-2 p-4 sm:p-6 shadow-md">
          <button
            type="button"
            onClick={() => h.setShowAdvanced(!h.showAdvanced)}
            className="flex w-full items-center justify-between text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            <span className="flex items-center gap-2">⚙️ 高级设置</span>
            <svg className={`h-4 w-4 transition-transform ${h.showAdvanced ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" /></svg>
          </button>
          {h.showAdvanced && (
            <div className="mt-4 space-y-3 border-t border-border pt-4">
              <p className="text-sm font-medium text-foreground">AI 追问风格</p>
              <label className={`flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2.5 transition-all ${h.selectedExpert.type === 'composite' ? 'border-foreground bg-primary-light' : 'border-border hover:border-border-strong'}`}>
                <input type="radio" name="expert" checked={h.selectedExpert.type === 'composite'} onChange={() => { const c = h.expertOptions.find(e => e.type === 'composite'); if (c) h.setSelectedExpert(c); }} className="h-4 w-4 accent-navy" />
                <div><span className="text-sm text-foreground">综合（推荐）</span><span className="ml-2 text-xs text-muted-foreground">综合多位萃取师的经验，追问更精准</span></div>
              </label>
              {h.expertOptions.filter(e => e.type === 'single').map(expert => (
                <label key={expert.id} className={`flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2.5 transition-all ${h.selectedExpert.id === expert.id ? 'border-foreground bg-primary-light' : 'border-border hover:border-border-strong'}`}>
                  <input type="radio" name="expert" checked={h.selectedExpert.id === expert.id} onChange={() => h.setSelectedExpert(expert)} className="h-4 w-4 accent-navy" />
                  <div className="min-w-0">
                    <span className="text-sm text-foreground">{expert.name}</span>
                    {expert.styleTags && expert.styleTags.length > 0 && (
                      <span className="ml-2 text-xs text-muted-foreground">{expert.styleTags.join('、')}</span>
                    )}
                  </div>
                </label>
              ))}
              <label className={`flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2.5 transition-all ${h.selectedExpert.type === 'none' ? 'border-foreground bg-primary-light' : 'border-border hover:border-border-strong'}`}>
                <input type="radio" name="expert" checked={h.selectedExpert.type === 'none'} onChange={() => { const n = h.expertOptions.find(e => e.type === 'none'); if (n) h.setSelectedExpert(n); }} className="h-4 w-4 accent-navy" />
                <div><span className="text-sm text-foreground">基础版</span><span className="ml-2 text-xs text-muted-foreground">不使用萃取师经验，追问可能不够深入</span></div>
              </label>
            </div>
          )}
        </div>

        {/* 前置引导卡片 */}
        <div className="mb-6 rounded-xl bg-primary-light/50 border border-primary-light px-4 sm:px-5 py-4 text-sm text-muted-foreground">
          <p className="font-medium text-foreground mb-2">📋 开始前请注意</p>
          <ul className="space-y-1.5">
            <li>· 预计需要 <span className="text-foreground font-medium">40 分钟</span>，请确保时间充足</li>
            <li>· 找一个安静的环境，准备好耳机或麦克风</li>
            <li>· AI 萃取师会引导你回顾具体场景，放松像聊天一样就行</li>
            <li>· 你只需要回忆真实经历——不需要提前准备文稿</li>
          </ul>
        </div>

        {/* 底部操作区 */}
        <div className="flex items-center justify-between">
          <Link href="/skill" className="rounded-lg border border-border px-6 py-3 text-sm font-medium text-foreground transition-colors hover:bg-primary-light min-h-[44px] flex items-center">返回</Link>
          <button
            type="button"
            onClick={handleStartClick}
            disabled={!h.topicInput.trim() || !h.spaceId || h.loading}
            className="rounded-lg bg-primary px-6 sm:px-8 py-3 text-sm font-semibold text-white transition-all hover:bg-primary-hover disabled:cursor-not-allowed disabled:opacity-50 min-h-[44px]"
          >
            {h.loading ? '创建中...' : '开始访谈'}
          </button>
        </div>

        {/* 首次确认弹窗 */}
        {showConfirm && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm px-4">
            <div className="w-full max-w-[360px] rounded-2xl bg-surface-2 p-6 shadow-xl text-center">
              <p className="text-3xl mb-3">🎙️</p>
              <p className="text-lg font-semibold text-foreground mb-2">准备好了吗？</p>
              <p className="text-sm text-muted-foreground mb-6">AI 萃取师将在几秒内开始引导提问。<br />找个舒服的姿势，像跟朋友聊天一样放松。</p>
              <div className="flex gap-3">
                <button type="button" onClick={() => setShowConfirm(false)} className="flex-1 rounded-lg border border-border py-2.5 text-sm text-foreground hover:bg-primary-light transition-colors">再想想</button>
                <button type="button" onClick={() => { setShowConfirm(false); h.handleStart(); }} className="flex-1 rounded-lg bg-primary py-2.5 text-sm font-semibold text-white hover:bg-primary-hover transition-colors">开始！</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
