/**
 * [B 端原始文件]
 * 本文件已被复制到平台端 platform/src/ 对应路径。
 *
 * 维护约定：
 * - 如果两端需要相同改动 → 通知平台端同步，或抽到 @aiextract/shared-ui 共享库
 * - 如果只有 B 端需要 → 独立改动，不影响平台端
 *
 * 平台端副本: platform/src/ 对应路径
 */


'use client';

import { useState } from 'react';

export type SheetMode = 'register' | 'login';

interface Props {
  open: boolean;
  mode: SheetMode;
  setMode: (m: SheetMode) => void;
  /** 触发原因：limit=额度用尽 / expired=凭证失效 / manual=用户主动 */
  reason: 'limit' | 'expired' | 'manual';
  submitting: boolean;
  error: string;
  onClose: () => void;
  onRegister: (form: { account: string; password: string; nickname?: string }) => void;
  onLogin: (form: { account: string; password: string }) => void;
}

/**
 * 底部注册/登录抽屉 — 不整页跳转，聊天上下文不丢
 */
export default function RegisterSheet({
  open, mode, setMode, reason, submitting, error, onClose, onRegister, onLogin,
}: Props) {
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');

  if (!open) return null;

  const isRegister = mode === 'register';
  const title = isRegister
    ? (reason === 'limit' ? '免费体验次数已用完' : '注册账号')
    : '登录';
  const sub = isRegister
    ? '注册后继续聊，这次对话和历史记录全部保留'
    : '登录后跨设备继续你的对话';

  const submit = () => {
    if (submitting) return;
    if (isRegister) onRegister({ account: account.trim(), password, nickname: nickname.trim() || undefined });
    else onLogin({ account: account.trim(), password });
  };

  return (
    <div className="fixed inset-0 z-[60]">
      <div className="absolute inset-0 bg-navy/40" onClick={onClose} />
      <div className="absolute inset-x-0 bottom-0 mx-auto max-w-[520px] rounded-t-2xl bg-bg px-6 pb-[calc(26px+env(safe-area-inset-bottom))] pt-2.5 shadow-xl">
        <div className="relative mx-auto flex items-center justify-center">
          <div className="h-1 w-9 rounded-pill bg-border-strong" />
          <button
            onClick={onClose}
            aria-label="关闭"
            className="absolute right-0 top-1/2 -translate-y-1/2 flex h-7 w-7 items-center justify-center rounded-full text-muted-foreground-2 hover:bg-surface active:bg-border"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="h-4 w-4">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="mx-auto mt-5 flex h-[52px] w-[52px] items-center justify-center rounded-full bg-[linear-gradient(135deg,#06b6d4_0%,#3b82f6_100%)] shadow-float">
          <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
            <rect x="4" y="10" width="16" height="10" rx="2" /><path d="M8 10V7a4 4 0 017.8-1.3" />
          </svg>
        </div>

        <h3 className="mt-3 text-center text-lg font-semibold text-foreground">{title}</h3>
        <p className="mt-1.5 text-center text-[13px] leading-relaxed text-muted-foreground">{sub}</p>

        <div className="mt-5 flex flex-col gap-3">
          <input
            value={account}
            onChange={e => setAccount(e.target.value)}
            placeholder="账号（4-50位）"
            autoComplete="username"
            className="h-12 rounded-lg border border-border bg-surface px-4 text-body text-foreground outline-none placeholder:text-muted-foreground-2 focus:border-primary focus:bg-bg focus:shadow-glow"
          />
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder={isRegister ? '设置密码（至少6位）' : '密码'}
            autoComplete={isRegister ? 'new-password' : 'current-password'}
            className="h-12 rounded-lg border border-border bg-surface px-4 text-body text-foreground outline-none placeholder:text-muted-foreground-2 focus:border-primary focus:bg-bg focus:shadow-glow"
          />
          {isRegister && (
            <div className="relative">
              <input
                value={nickname}
                onChange={e => setNickname(e.target.value)}
                placeholder="昵称"
                className="h-12 w-full rounded-lg border border-border bg-surface px-4 pr-14 text-body text-foreground outline-none placeholder:text-muted-foreground-2 focus:border-primary focus:bg-bg focus:shadow-glow"
              />
              <span className="absolute right-4 top-1/2 -translate-y-1/2 text-[11px] text-muted-foreground-2">选填</span>
            </div>
          )}

          {error && <div className="text-center text-xs text-danger">{error}</div>}

          <button
            onClick={submit}
            disabled={submitting || !account.trim() || password.length < (isRegister ? 6 : 1)}
            className="mt-1 h-12 rounded-pill bg-[linear-gradient(135deg,#06b6d4_0%,#3b82f6_100%)] text-[15px] font-semibold text-white shadow-float transition-transform active:scale-[0.98] disabled:opacity-50"
          >
            {submitting ? '提交中…' : isRegister ? '注册并继续聊天' : '登录'}
          </button>
        </div>

        <div className="mt-4 text-center text-[13px] text-muted-foreground">
          {isRegister ? (
            <>已有账号？<button onClick={() => setMode('login')} className="font-medium text-primary">去登录</button></>
          ) : (
            <>没有账号？<button onClick={() => setMode('register')} className="font-medium text-primary">去注册</button></>
          )}
          {reason !== 'expired' && (
            <button onClick={onClose} className="ml-4 text-muted-foreground-2">
              {reason === 'limit' ? '暂不，先看看' : '暂不'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
