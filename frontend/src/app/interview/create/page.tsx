'use client';

import React, { useState, useEffect, useRef } from 'react';
import { useInterviewCreate, type ExpertOption } from './useInterviewCreate';
import { createInvite } from '@/lib/api/admin';
import QRCode from 'qrcode';

const PRESET_TOPICS = [
  '搞定说太贵了的客户', '如何在复杂决策链中找到关键人', '大客户破冰技巧',
  '异议处理的黄金话术', '逼单时机的判断', '信任建立的秘诀',
];

export default function CreateInterviewPage() {
  const h = useInterviewCreate();
  const [showConfirm, setShowConfirm] = useState(false);

  // 邀请码分享
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteData, setInviteData] = useState<{ inviteCode: string; inviteUrl: string } | null>(null);
  const [inviteQr, setInviteQr] = useState('');
  const [inviteLoading, setInviteLoading] = useState(false);
  const [inviteCopied, setInviteCopied] = useState(false);
  const inviteRef = useRef<HTMLDivElement>(null);

  const handleGenerateInvite = async () => {
    if (inviteOpen) { setInviteOpen(false); return; }
    setInviteOpen(true);
    if (inviteData) return;
    setInviteLoading(true);
    try {
      const data = await createInvite();
      setInviteData(data);
      const qr = await QRCode.toDataURL(data.inviteUrl, { width: 200, margin: 2 });
      setInviteQr(qr);
    } catch { setInviteOpen(false); }
    finally { setInviteLoading(false); }
  };

  const copyInviteUrl = async () => {
    if (!inviteData) return;
    try { await navigator.clipboard.writeText(inviteData.inviteUrl); setInviteCopied(true); setTimeout(() => setInviteCopied(false), 2000); } catch {}
  };

  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (inviteRef.current && !inviteRef.current.contains(e.target as Node)) setInviteOpen(false);
    };
    if (inviteOpen) document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [inviteOpen]);

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
          <div />
          <div ref={inviteRef} style={{ position: 'relative' }}>
            <button type="button" onClick={handleGenerateInvite}
              className="rounded-lg px-3 py-1.5 text-xs font-medium text-primary hover:bg-primary-light transition-colors">
              🔗 邀请销冠
            </button>
            {inviteOpen && (
              <div className="absolute top-full right-0 mt-2 z-50 w-[360px] rounded-xl border border-border bg-white shadow-xl p-5">
                <div className="absolute -top-1.5 right-4 w-3 h-3 rotate-45 bg-white border-l border-t border-border" />
                <h3 className="text-sm font-semibold text-foreground mb-3">邀请销冠参与访谈</h3>
                {inviteLoading && <p className="text-xs text-muted-foreground py-4 text-center">生成中...</p>}
                {inviteData && (
                  <>
                    <div className="flex justify-center mb-3">
                      {inviteQr ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={inviteQr} alt="邀请二维码" className="h-[160px] w-[160px] rounded-lg border border-border" />
                      ) : (
                        <div className="flex h-[160px] w-[160px] items-center justify-center rounded-lg border border-border text-xs text-muted-foreground-2">生成中…</div>
                      )}
                    </div>
                    <div className="flex items-center gap-2">
                      <input readOnly value={inviteData.inviteUrl} className="h-9 min-w-0 flex-1 rounded-lg border border-border bg-surface px-3 text-xs text-muted-foreground outline-none" />
                      <button onClick={copyInviteUrl} className="h-9 flex-none rounded-lg bg-primary px-3 text-xs font-semibold text-white hover:bg-primary-hover">
                        {inviteCopied ? '已复制 ✓' : '复制'}
                      </button>
                    </div>
                    <p className="mt-2 text-[11px] text-muted-foreground-2">销冠用手机扫码即可开始访谈，链接永久有效</p>
                  </>
                )}
              </div>
            )}
          </div>
        </div>

        <h1 className="mb-8 text-[26px] sm:text-[28px] font-bold text-foreground">
          {h.interviewType === 'expert' ? '萃取师访谈' : '创建新访谈'}
        </h1>
        {h.interviewType === 'expert' && (
          <p className="-mt-6 mb-6 text-sm text-muted-foreground">通过元萃取引擎，深度挖掘萃取师本人的实践智慧和判断直觉</p>
        )}

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
          {h.interviewType === 'expert' ? (
            <ul className="space-y-1.5">
              <li>· 预计需要 <span className="text-foreground font-medium">30-40 分钟</span>，请确保时间充足</li>
              <li>· 找一个安静的环境，准备好耳机或麦克风</li>
              <li>· AI 会引导你回顾自己的萃取经验和方法论</li>
              <li>· 围绕你真实的萃取案例展开——不需要提前准备文稿</li>
            </ul>
          ) : (
            <ul className="space-y-1.5">
              <li>· 预计需要 <span className="text-foreground font-medium">40 分钟</span>，请确保时间充足</li>
              <li>· 找一个安静的环境，准备好耳机或麦克风</li>
              <li>· AI 萃取师会引导你回顾具体场景，放松像聊天一样就行</li>
              <li>· 你只需要回忆真实经历——不需要提前准备文稿</li>
            </ul>
          )}
        </div>

        {/* 底部操作区 */}
        <div className="flex items-center justify-end">



          <button
            type="button"
            onClick={handleStartClick}
            disabled={!h.topicInput.trim() || !h.spaceId || h.loading}
            className="rounded-lg bg-primary px-6 sm:px-8 py-3 text-sm font-semibold text-white transition-all hover:bg-primary-hover disabled:cursor-not-allowed disabled:opacity-50 min-h-[44px]"
          >
            {h.loading ? '创建中...' : h.interviewType === 'expert' ? '开始萃取师访谈' : '开始访谈'}
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
