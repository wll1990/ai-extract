'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { getToken } from '@/lib/storage';
import { useQaChat, type ChatMode } from '@/app/skill/[skillId]/hooks/useQaChat';
import MobileChatShell from '@/app/s/[shareCode]/MobileChatShell';

type LoginMode = 'login' | 'register';

interface InternalShareInfo {
  skillId: string;
  companyId: string | null;
  companyName: string | null;
  ownerName: string;
  avatarUrl: string | null;
  openingMessage?: string;
  ownerTitle?: string;
}

/**
 * 对内分享页 /i/[shareCode] — 公司内部访问，需登录认证。
 *
 * 与 /s/ 的区别：
 * - 无游客模式，必须登录（B端员工或平台用户）
 * - 无条数限制
 * - 无注册入口
 */
export default function InternalSharePage() {
  const params = useParams<{ shareCode: string }>();
  const shareCode = params.shareCode;
  const noopRef = useRef<() => void>(() => {});
  const noop = useCallback(() => {}, []);

  const [info, setInfo] = useState<InternalShareInfo | null>(null);
  const [loadError, setLoadError] = useState('');
  const [authChecked, setAuthChecked] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [view, setView] = useState<'landing' | 'chat'>('chat');
  const [mode, setMode] = useState<ChatMode>('qa');
  const [practiceSceneTag, setPracticeSceneTag] = useState('');
  const [practiceKey, setPracticeKey] = useState(0);

  // 登录/注册表单
  const [loginMode, setLoginMode] = useState<LoginMode>('login');
  const [bAccount, setBAccount] = useState('');
  const [bPassword, setBPassword] = useState('');
  const [regName, setRegName] = useState('');
  const [regAccount, setRegAccount] = useState('');
  const [regPassword, setRegPassword] = useState('');
  const [authLoading, setAuthLoading] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);

  // Load internal share info
  useEffect(() => {
    fetch(`/api/v1/i/${encodeURIComponent(shareCode)}/info`)
      .then((r) => { if (!r.ok) throw new Error(); return r.json(); })
      .then((d) => { if (d.code === 200) setInfo(d.data); else throw new Error(); })
      .catch(() => setLoadError('分享链接不存在或已失效'));
  }, [shareCode]);

  // Check auth
  useEffect(() => {
    fetch('/api/v1/auth/me', { headers: { Authorization: `Bearer ${getToken()}` } })
      .then((r) => { if (r.ok) setIsLoggedIn(true); })
      .catch(() => {})
      .finally(() => setAuthChecked(true));
  }, []);

  // Q&A chat hook — same pattern as /s/ page
  const qa = useQaChat({
    skillId: info?.skillId || '',
    skillInfo: { ownerName: info?.ownerName, ownerTitle: info?.ownerTitle },
    chatMode: mode,
    setChatMode: setMode,
    setModeSelected: noop,
    onResetPracticeRef: noopRef,
    authToken: undefined,
    onLimit: noop,
  });

  const handleEnterChat = useCallback((m: ChatMode = 'qa') => {
    setMode(m);
    setView('chat');
  }, []);

  // Build a ShareInfo-compatible object for MobileChatShell
  const shellInfo = info ? {
    shareCode,
    skillId: info.skillId,
    ownerName: info.ownerName || '销冠',
    ownerTitle: info.ownerTitle,
    avatarUrl: info.avatarUrl || undefined,
    openingMessage: '',
    tags: [] as string[],
    sceneTags: [] as { tag: string; count: number }[],
    guestLimit: 0,
    remaining: null as number | null,
    viewerStatus: null as string | null,
  } : null;

  if (loadError) {
    return (
      <div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center">
        <div className="text-center px-6">
          <span className="text-4xl mb-3 block">🔒</span>
          <p className="text-[#747f9e] text-sm">{loadError}</p>
        </div>
      </div>
    );
  }

  if (!info) {
    return (
      <div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center">
        <div className="text-[#747f9e] text-sm">加载中...</div>
      </div>
    );
  }

  // 登录/注册处理
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!bAccount.trim() || !bPassword.trim()) { setAuthError('请填写账号和密码'); return; }
    setAuthLoading(true); setAuthError(null);
    try {
      const r = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ account: bAccount.trim(), password: bPassword, companyId: info?.companyId }),
      });
      const d = await r.json();
      if (d.code === 200) { localStorage.setItem('token', d.data.token); setIsLoggedIn(true); }
      else setAuthError(d.message || '登录失败');
    } catch { setAuthError('网络错误'); }
    finally { setAuthLoading(false); }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!regName.trim() || !regAccount.trim() || !regPassword.trim()) {
      setAuthError('请填写所有字段'); return;
    }
    if (regPassword.length < 6) { setAuthError('密码至少 6 位'); return; }
    setAuthLoading(true); setAuthError(null);
    try {
      const r = await fetch('/api/v1/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          companyId: info?.companyId,
          name: regName.trim(),
          account: regAccount.trim(),
          password: regPassword,
          role: 'employee',
        }),
      });
      const d = await r.json();
      if (d.code === 200) { localStorage.setItem('token', d.data.token); setIsLoggedIn(true); }
      else setAuthError(d.message || '注册失败');
    } catch { setAuthError('网络错误'); }
    finally { setAuthLoading(false); }
  };

  // Not logged in
  if (authChecked && !isLoggedIn) {
    return (
      <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center px-5 py-10"
        style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
        <div className="w-full max-w-sm">
          <h1 className="text-xl font-bold text-[#10162f] mb-1">{info.ownerName || '销冠'} 的分身</h1>
          <p className="text-sm text-[#747f9e] mb-8">此分身仅限内部访问，请先登录</p>

          {loginMode === 'login' ? (
            <form onSubmit={handleLogin} className="space-y-4">
              <div>
                <label className="block text-sm text-[#747f9e] mb-1">公司</label>
                <input type="text" value={info.companyName || ''} disabled className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-gray-50 text-[#747f9e] text-sm" />
              </div>
              <div>
                <label className="block text-sm text-[#747f9e] mb-1">账号</label>
                <input type="text" value={bAccount} onChange={(e) => setBAccount(e.target.value)} placeholder="企业账号" required className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]" />
              </div>
              <div>
                <label className="block text-sm text-[#747f9e] mb-1">密码</label>
                <input type="password" value={bPassword} onChange={(e) => setBPassword(e.target.value)} placeholder="密码" required minLength={6} className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]" />
              </div>
              {authError && <div className="px-4 py-2.5 rounded-xl bg-red-50 border border-red-200 text-sm text-red-600">{authError}</div>}
              <button type="submit" disabled={authLoading} className="w-full py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40">
                {authLoading ? '登录中...' : '企业登录'}
              </button>
              <p className="text-center text-xs text-[#747f9e]">
                没有账号？<button type="button" onClick={() => { setLoginMode('register'); setAuthError(null); }} className="text-[#2147ff] font-medium">立即注册</button>
              </p>
            </form>
          ) : (
            <form onSubmit={handleRegister} className="space-y-4">
              <div>
                <label className="block text-sm text-[#747f9e] mb-1">公司</label>
                <input type="text" value={info.companyName || ''} disabled className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-gray-50 text-[#747f9e] text-sm" />
              </div>
              <div>
                <label className="block text-sm text-[#747f9e] mb-1">姓名</label>
                <input type="text" value={regName} onChange={(e) => setRegName(e.target.value)} placeholder="你的真实姓名" required className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]" />
              </div>
              <div>
                <label className="block text-sm text-[#747f9e] mb-1">账号</label>
                <input type="text" value={regAccount} onChange={(e) => setRegAccount(e.target.value)} placeholder="设置登录账号" required className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]" />
              </div>
              <div>
                <label className="block text-sm text-[#747f9e] mb-1">密码</label>
                <input type="password" value={regPassword} onChange={(e) => setRegPassword(e.target.value)} placeholder="至少 6 位密码" required minLength={6} className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff]" />
              </div>
              {authError && <div className="px-4 py-2.5 rounded-xl bg-red-50 border border-red-200 text-sm text-red-600">{authError}</div>}
              <button type="submit" disabled={authLoading} className="w-full py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40">
                {authLoading ? '注册中...' : '注册并开始'}
              </button>
              <p className="text-center text-xs text-[#747f9e]">
                已有账号？<button type="button" onClick={() => { setLoginMode('login'); setAuthError(null); }} className="text-[#2147ff] font-medium">去登录</button>
              </p>
            </form>
          )}
        </div>
      </div>
    );
  }

  // Landing
  if (view === 'landing') {
    return (
      <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center justify-center px-6 text-center"
        style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
        <div className="w-20 h-20 rounded-full bg-gradient-to-br from-[#2147ff] to-[#ff4d5f] flex items-center justify-center text-white text-2xl font-bold mb-4 mx-auto overflow-hidden">
          {info.avatarUrl ? (
            <img src={info.avatarUrl} alt={info.ownerName} className="w-full h-full object-cover" />
          ) : (
            info.ownerName?.charAt(0) || '?'
          )}
        </div>
        <h1 className="text-xl font-bold text-[#10162f] mb-1">{info.ownerName || '销冠'}</h1>
        {info.ownerTitle && <p className="text-sm text-[#747f9e] mb-6">{info.ownerTitle}</p>}
        <div className="flex gap-3">
          <button onClick={() => handleEnterChat('qa')} className="px-6 py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium">
            开始问答
          </button>
          <button onClick={() => handleEnterChat('talk')} className="px-6 py-3 rounded-full border border-[#cdd7ff] text-[#2147ff] text-sm font-medium">
            自由对话
          </button>
        </div>
      </div>
    );
  }

  // Chat
  if (!shellInfo) return null;
  return (
    <MobileChatShell
      info={shellInfo}
      mode={mode}
      onSwitchMode={setMode}
      remainingLabel={null}
      onOpenHistory={noop}
      qa={qa}
      onAfterSend={noop}
      practiceSceneTag={practiceSceneTag}
      practiceKey={practiceKey}
      onPickScene={setPracticeSceneTag}
      abortRef={{ current: null }}
      authToken={undefined}
      onPracticeLimit={noop}
      practiceHint=""
    />
  );
}
