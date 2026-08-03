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

import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import {
  getShareInfo, cRegister, cLogin, setLastShareCode,
  type ShareInfo,
} from '@/lib/api/c';
import { useQaChat, type ChatMode } from '@/app/skill/[skillId]/hooks/useQaChat';
import { useGuestSession } from './useGuestSession';
import ShareLanding from './ShareLanding';
import MobileChatShell from './MobileChatShell';
import HistoryDrawer from './HistoryDrawer';
import RegisterSheet, { type SheetMode } from './RegisterSheet';

/**
 * 分身分享页 /s/[shareCode] — C 端唯一入口（mobile-first，PC 居中即网页版）
 *
 * 流程：落地页（无凭证）→ 点模式入口静默领游客证 → 聊天 →
 * 聊满 N 条后端下发 limit 事件 → 底部注册抽屉 → 原地升级 → 自动重发被拦消息。
 */
export default function SharePage() {
  const params = useParams<{ shareCode: string }>();
  const shareCode = params.shareCode;

  // ---- 落地信息 ----
  const [info, setInfo] = useState<ShareInfo | null>(null);
  const [loadError, setLoadError] = useState('');

  // ---- 会话身份 ----
  const guest = useGuestSession(shareCode);
  const authToken = guest.session?.token || undefined;

  // ---- 视图状态 ----
  const [view, setView] = useState<'landing' | 'chat'>('chat');
  const [mode, setMode] = useState<ChatMode>('talk');
  const [practiceSceneTag, setPracticeSceneTag] = useState('');
  const [practiceKey, setPracticeKey] = useState(0);
  const [practiceHint, setPracticeHint] = useState('');

  // ---- 注册/登录抽屉 ----
  const [sheetOpen, setSheetOpen] = useState(false);
  const [sheetMode, setSheetMode] = useState<SheetMode>('register');
  const [sheetReason, setSheetReason] = useState<'limit' | 'expired' | 'manual'>('manual');
  const [sheetError, setSheetError] = useState('');
  const [sheetSubmitting, setSheetSubmitting] = useState(false);
  const pendingResendRef = useRef('');

  const abortRef = useRef<AbortController | null>(null);
  const noopRef = useRef<() => void>(() => {});
  const noop = useCallback(() => {}, []);

  /** 重新打开注册抽屉（额度用完后从输入条点击"注册解锁"） */
  const handleOpenRegisterSheet = useCallback(() => {
    setSheetMode('register');
    setSheetReason('limit');
    setSheetError('');
    setSheetOpen(true);
  }, []);

  /** 游客额度用尽 → 弹注册抽屉（qa/talk 记录待重发消息） */
  const handleLimit = useCallback((limitInfo: { used: number; limit: number; pendingText: string }) => {
    guest.setRemaining(0);
    pendingResendRef.current = limitInfo.pendingText;
    setSheetMode('register');
    setSheetReason('limit');
    setSheetError('');
    setSheetOpen(true);
  }, [guest]);

  /** 对练额度用尽：不自动重发（避免重复评价轮），注册后提示点"下一轮"继续 */
  const handlePracticeLimit = useCallback((limitInfo: { used: number; limit: number; pendingText: string }) => {
    guest.setRemaining(0);
    pendingResendRef.current = '';
    setSheetMode('register');
    setSheetReason('limit');
    setSheetError('');
    setSheetOpen(true);
  }, [guest]);

  // ---- 聊天 hook（qa/talk 共用；skillId 就绪前内部自动空转） ----
  const qa = useQaChat({
    skillId: info?.skillId || '',
    skillInfo: { ownerName: info?.ownerName, ownerTitle: info?.ownerTitle },
    chatMode: mode,
    setChatMode: setMode,
    setModeSelected: noop,
    onResetPracticeRef: noopRef,
    authToken,
    onLimit: handleLimit,
  });

  // ---- 初始化：拉落地信息 ----
  useEffect(() => {
    if (!shareCode) return;
    setLastShareCode(shareCode);
    getShareInfo(shareCode)
      .then(setInfo)
      .catch(e => setLoadError(e?.message === 'AUTH_REQUIRED' ? '分享链接已失效' : (e?.message || '加载失败')));
  }, [shareCode]);

  // ---- 直接进聊天时自动初始化游客身份 ----
  useEffect(() => {
    if (view === 'chat' && info && !guest.session) {
      guest.ensure().catch(() => {});
    }
  }, [view, info, guest]);

  // ---- 凭证失效（401/403）→ 弹抽屉 ----
  useEffect(() => {
    const onAuthRequired = () => {
      setSheetMode(guest.session?.user.status === 'registered' ? 'login' : 'register');
      setSheetReason('expired');
      setSheetError('');
      setSheetOpen(true);
    };
    window.addEventListener('guest:auth-required', onAuthRequired);
    return () => window.removeEventListener('guest:auth-required', onAuthRequired);
  }, [guest.session?.user.status]);

  // ---- 注册/登录成功且 token 更新后：自动重发被拦消息（qa/talk） ----
  useEffect(() => {
    if (!sheetOpen && pendingResendRef.current && authToken && guest.session?.user.status === 'registered' && mode !== 'practice') {
      const text = pendingResendRef.current;
      pendingResendRef.current = '';
      qa.sendMessageImmediate(text);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sheetOpen, authToken, guest.session?.user.status]);

  /** 落地页点模式入口：静默领证 → 进聊天 */
  const handleStart = useCallback(async (m: ChatMode) => {
    const s = await guest.ensure();
    if (!s) { setLoadError('网络异常，请稍后重试'); return; }
    setMode(m);
    setView('chat');
  }, [guest]);

  /** 抽屉提交：注册（游客原地升级，userId 不变，历史继承） */
  const handleRegister = useCallback(async (form: { account: string; password: string; nickname?: string }) => {
    setSheetSubmitting(true);
    setSheetError('');
    try {
      const resp = await cRegister(form);
      guest.applySession(resp);
      setSheetOpen(false);
      if (mode === 'practice') setPracticeHint('注册成功！点击"下一轮"继续对练，记录已全部保留');
    } catch (e) {
      setSheetError((e as Error)?.message === 'AUTH_REQUIRED' ? '身份已失效，请刷新页面重试' : ((e as Error)?.message || '注册失败'));
    } finally {
      setSheetSubmitting(false);
    }
  }, [guest, mode]);

  /** 抽屉提交：登录（切换身份 → 清空当前对话状态，历史列表自动按新身份刷新） */
  const handleLogin = useCallback(async (form: { account: string; password: string }) => {
    setSheetSubmitting(true);
    setSheetError('');
    try {
      const resp = await cLogin(form.account, form.password);
      guest.applySession(resp);
      qa.setMessages([]);
      qa.setCurrentConvId('');
      pendingResendRef.current = '';
      setSheetOpen(false);
    } catch (e) {
      setSheetError((e as Error)?.message || '登录失败');
    } finally {
      setSheetSubmitting(false);
    }
  }, [guest, qa]);

  // ---- 渲染 ----

  if (loadError) {
    return (
      <div className="flex h-[100dvh] flex-col items-center justify-center gap-3 bg-surface px-8 text-center">
        <div className="text-4xl">🔗</div>
        <div className="text-h3 font-semibold text-foreground">{loadError}</div>
        <div className="text-xs text-muted-foreground">请联系分享者获取新的链接</div>
      </div>
    );
  }

  if (!info) {
    return (
      <div className="flex h-[100dvh] items-center justify-center bg-surface">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      </div>
    );
  }

  const remainingLabel = guest.isGuest && guest.remaining !== null ? `剩 ${guest.remaining} 条` : null;

  return (
    <div className="mx-auto h-[100dvh] w-full max-w-[520px] bg-surface">
      {view === 'landing' ? (
        <ShareLanding
          info={info}
          starting={guest.ensuring}
          onStart={handleStart}
          onLogin={() => { setSheetMode('login'); setSheetReason('manual'); setSheetError(''); setSheetOpen(true); }}
        />
      ) : (
        <MobileChatShell
          info={info}
          mode={mode}
          onSwitchMode={m => {
            if (m !== mode) {
              qa.setMessages([]);
              qa.setCurrentConvId('');
              setPracticeSceneTag('');
              setPracticeHint('');
            }
            setMode(m);
          }}
          remainingLabel={remainingLabel}
          onOpenHistory={() => { qa.loadConversations(); qa.setShowHistory(true); }}
          qa={qa}
          onAfterSend={guest.decrementRemaining}
          practiceSceneTag={practiceSceneTag}
          practiceKey={practiceKey}
          onPickScene={tag => { setPracticeSceneTag(tag); setPracticeKey(k => k + 1); }}
          abortRef={abortRef}
          authToken={authToken}
          onPracticeLimit={handlePracticeLimit}
          practiceHint={practiceHint}
          isLimitReached={guest.isGuest && guest.remaining === 0}
          onRegisterPrompt={handleOpenRegisterSheet}
        />
      )}

      <HistoryDrawer
        open={qa.showHistory}
        onClose={() => qa.setShowHistory(false)}
        conversations={qa.conversations}
        currentConvId={qa.currentConvId}
        onSwitch={qa.switchConversation}
        onDelete={qa.handleDeleteConversation}
        onNew={() => { qa.setCurrentConvId(''); qa.setMessages([]); }}
        nickname={guest.session?.user.nickname || '访客'}
        isGuest={guest.isGuest}
      />

      <RegisterSheet
        open={sheetOpen}
        mode={sheetMode}
        setMode={m => { setSheetMode(m); setSheetError(''); }}
        reason={sheetReason}
        submitting={sheetSubmitting}
        error={sheetError}
        onClose={() => setSheetOpen(false)}
        onRegister={handleRegister}
        onLogin={handleLogin}
      />
    </div>
  );
}
