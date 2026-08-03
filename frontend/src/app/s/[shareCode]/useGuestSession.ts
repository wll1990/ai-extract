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

import { useCallback, useRef, useState } from 'react';
import { createGuest, getCAuth, setCAuth, type CSession, type CSessionResponse } from '@/lib/api/c';

/**
 * C 端会话状态机 — probing → anonymous → guest → member
 *
 * <p>ensure() 是唯一入口：统一调幂等的 createGuest（后端自动判断
 * 新建游客 / 滑动续期），前端无需区分新老访客。
 * remaining 本地镜像用于顶栏 pill 即时递减，以后端 limit 事件为准。</p>
 */
export function useGuestSession(shareCode: string) {
  const [session, setSession] = useState<CSession | null>(() => getCAuth());
  const [remaining, setRemaining] = useState<number | null>(null);
  const [ensuring, setEnsuring] = useState(false);
  const ensuringRef = useRef(false);

  /** 应用后端返回的会话（发证/续期/注册/登录后统一走这里） */
  const applySession = useCallback((resp: CSessionResponse) => {
    const user = {
      userId: resp.userId,
      nickname: resp.nickname,
      status: resp.status,
      remaining: resp.remaining,
      limit: resp.limit,
    };
    const token = resp.token || getCAuth()?.token || '';
    if (token) setCAuth(token, user);
    const next = { token, user };
    setSession(next);
    setRemaining(resp.status === 'guest' ? (resp.remaining ?? null) : null);
    return next;
  }, []);

  /**
   * 确保持有可用 C 端身份：无身份 → 静默领游客证；有身份 → 滑动续期。
   * 并发去重（点击多个模式入口只发一次）。
   */
  const ensure = useCallback(async (): Promise<CSession | null> => {
    if (ensuringRef.current) return getCAuth();
    ensuringRef.current = true;
    setEnsuring(true);
    try {
      const resp = await createGuest(shareCode);
      return applySession(resp);
    } catch (e) {
      console.error('游客发证失败:', e);
      return null;
    } finally {
      ensuringRef.current = false;
      setEnsuring(false);
    }
  }, [shareCode, applySession]);

  /** 发送成功后本地递减（游客态顶栏 pill 即时反馈） */
  const decrementRemaining = useCallback(() => {
    setRemaining(prev => (prev !== null && prev > 0 ? prev - 1 : prev));
  }, []);

  return {
    session,
    remaining,
    setRemaining,
    ensuring,
    ensure,
    applySession,
    decrementRemaining,
    isGuest: session?.user.status === 'guest',
  };
}
