'use client';

import { useEffect, useState, Suspense, useCallback } from 'react';
import { useRouter, useSearchParams, useParams } from 'next/navigation';
import { getActiveSessions, restartSession } from '@/lib/api/interview';

interface InviteInfo {
  type: 'enterprise' | 'personal';
  companyId?: string;
  companyName?: string;
  inviterName?: string;
}

function H5InviteEntryContent({ inviteCode }: { inviteCode: string }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const partnerToken = searchParams.get('token');

  const [info, setInfo] = useState<InviteInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [topic, setTopic] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [toast, setToasts] = useState<string | null>(null);

  // 登录态
  const [authed, setAuthed] = useState(false);
  const [authChecked, setAuthChecked] = useState(false);
  const [cUser, setCUser] = useState<{ extractionRemaining?: number; extractionLimit?: number } | null>(null);

  // 内联登录表单
  const [showLogin, setShowLogin] = useState(false);
  const [loginTab, setLoginTab] = useState<'enterprise' | 'personal'>('enterprise');
  const [loginSub, setLoginSub] = useState<'login' | 'register'>('login');
  const [bAccount, setBAccount] = useState(''); const [bPassword, setBPassword] = useState('');
  const [bRegName, setBRegName] = useState(''); const [bRegAccount, setBRegAccount] = useState(''); const [bRegPassword, setBRegPassword] = useState('');
  const [cAccount, setCAccount] = useState(''); const [cPassword, setCPassword] = useState('');
  const [cRegNickname, setCRegNickname] = useState(''); const [cRegAccount, setCRegAccount] = useState(''); const [cRegPassword, setCRegPassword] = useState('');
  const [authLoading, setAuthLoading] = useState(false); const [authError, setAuthError] = useState<string | null>(null);

  // 活跃会话检测
  const [activeSession, setActiveSession] = useState<{ sessionId: string; topic: string } | null>(null);
  const [activeChecked, setActiveChecked] = useState(false);

  // 登录后检测活跃会话
  useEffect(() => {
    if (!authed || !authChecked) return;
    getActiveSessions().then(d => {
      if (d.hasActive && d.sessions?.length > 0) {
        setActiveSession({ sessionId: d.sessions[0].sessionId, topic: d.sessions[0].topic });
      }
    }).catch(() => {}).finally(() => setActiveChecked(true));
  }, [authed, authChecked]);
  useEffect(() => {
    fetch(`/api/v1/public/invite/${encodeURIComponent(inviteCode)}`)
      .then(r => r.json())
      .then(d => { if (d.code === 200) setInfo(d.data); else throw new Error(); })
      .catch(() => setError('邀请码无效或已过期'))
      .finally(() => setLoading(false));
  }, [inviteCode]);

  // 检查登录态
  const checkAuth = useCallback(async () => {
    // Partner token
    if (partnerToken) {
      try {
        const r = await fetch('/api/v1/c/auth/me', { headers: { Authorization: `Bearer ${partnerToken}` } });
        const d = await r.json();
        if (d.code === 200) {
          localStorage.setItem('c_auth', JSON.stringify({ token: partnerToken, user: { userId: d.data.userId, nickname: d.data.nickname, status: d.data.status } }));
          router.replace(`/h5/interview/m/${inviteCode}`);
          return true;
        }
      } catch {}
    }
    // C端 c_auth
    try {
      const stored = localStorage.getItem('c_auth');
      if (stored) {
        const s = JSON.parse(stored);
        if (s?.token) {
          const r = await fetch('/api/v1/c/auth/me', { headers: { Authorization: `Bearer ${s.token}` } });
          const d = await r.json();
          if (d.code === 200) { setCUser(d.data); return true; }
        }
      }
    } catch {}
    return false;
  }, [partnerToken, inviteCode, router]);

  useEffect(() => {
    checkAuth().then(a => { setAuthed(a); setAuthChecked(true); });
  }, [checkAuth]);

  // 开始萃取
  const handleStart = async () => {
    if (!topic.trim()) return;
    if (!authed) { setShowLogin(true); return; }
    setSubmitting(true); setToasts(null);
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (partnerToken) headers['Authorization'] = `Bearer ${partnerToken}`;
      else {
        const stored = localStorage.getItem('c_auth');
        if (stored) { const s = JSON.parse(stored); if (s?.token) headers['Authorization'] = `Bearer ${s.token}`; }
      }
      const r = await fetch('/api/v1/interviews', {
        method: 'POST', headers,
        body: JSON.stringify({ topic: topic.trim(), inviteCode }),
      });
      const d = await r.json();
      if (d.code === 200 && d.data?.sessionId) router.push(`/h5/interview/chat/${d.data.sessionId}`);
      else if (d.code === 402) setToasts('免费萃取次数已用完');
      else setToasts(d.message || '创建失败');
    } catch { setToasts('网络错误'); }
    finally { setSubmitting(false); }
  };

  // B端登录
  const doBLogin = async (e: React.FormEvent) => { e.preventDefault();
    setAuthLoading(true); setAuthError(null);
    try {
      const r = await fetch('/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ account: bAccount.trim(), password: bPassword, companyId: info?.companyId }) });
      const d = await r.json();
      if (d.code === 200) { localStorage.setItem('token', d.data.token); setAuthed(true); setShowLogin(false); }
      else setAuthError(d.message || '登录失败');
    } catch { setAuthError('网络错误'); } finally { setAuthLoading(false); }
  };
  const doBRegister = async (e: React.FormEvent) => { e.preventDefault();
    if (!bRegName.trim() || !bRegAccount.trim() || !bRegPassword.trim()) { setAuthError('请填写所有字段'); return; }
    if (bRegPassword.length < 6) { setAuthError('密码至少6位'); return; }
    setAuthLoading(true); setAuthError(null);
    try {
      const r = await fetch('/api/v1/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ companyId: info?.companyId, name: bRegName.trim(), account: bRegAccount.trim(), password: bRegPassword, role: 'employee' }) });
      const d = await r.json();
      if (d.code === 200) { localStorage.setItem('token', d.data.token); setAuthed(true); setShowLogin(false); }
      else setAuthError(d.message || '注册失败');
    } catch { setAuthError('网络错误'); } finally { setAuthLoading(false); }
  };

  // C端登录/注册
  const doCLogin = async (e: React.FormEvent) => { e.preventDefault();
    setAuthLoading(true); setAuthError(null);
    try {
      const r = await fetch('/api/v1/c/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ account: cAccount.trim(), password: cPassword }) });
      const d = await r.json();
      if (d.code === 200) { localStorage.setItem('c_auth', JSON.stringify({ token: d.data.token, user: { userId: d.data.userId, nickname: d.data.nickname, status: d.data.status } })); setAuthed(true); setShowLogin(false); }
      else setAuthError(d.message || '登录失败');
    } catch { setAuthError('网络错误'); } finally { setAuthLoading(false); }
  };
  const doCRegister = async (e: React.FormEvent) => { e.preventDefault();
    if (!cRegAccount.trim() || !cRegPassword.trim()) { setAuthError('请填写手机号/邮箱和密码'); return; }
    if (cRegPassword.length < 6) { setAuthError('密码至少6位'); return; }
    setAuthLoading(true); setAuthError(null);
    try {
      const r = await fetch('/api/v1/c/auth/register/new', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ account: cRegAccount.trim(), password: cRegPassword, nickname: cRegNickname.trim() || ('用户' + cRegAccount.trim().substring(0, 6)) }) });
      const d = await r.json();
      if (d.code === 200) { localStorage.setItem('c_auth', JSON.stringify({ token: d.data.token, user: { userId: d.data.userId, nickname: d.data.nickname, status: d.data.status } })); setAuthed(true); setShowLogin(false); }
      else setAuthError(d.message || '注册失败');
    } catch { setAuthError('网络错误'); } finally { setAuthLoading(false); }
  };

  /** 退出登录 — 清 C 端 localStorage + B 端 Cookie，重置所有状态 */
  const handleLogout = useCallback(async () => {
    try { localStorage.removeItem('c_auth'); } catch {}
    try { localStorage.removeItem('token'); await fetch('/api/v1/auth/logout', { method: 'POST' }); } catch {}
    setAuthed(false); setAuthChecked(true); setCUser(null);
    setActiveSession(null); setActiveChecked(true);
    setShowLogin(false);
  }, []);

  useEffect(() => { if (toast) { const t = setTimeout(() => setToasts(null), 2500); return () => clearTimeout(t); } }, [toast]);

  if (loading) return <div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center"><p className="text-sm text-[#747f9e]">加载中...</p></div>;
  if (error) return <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center justify-center px-6 text-center"><span className="text-4xl mb-3">🔒</span><h2 className="text-lg font-semibold text-[#10162f] mb-2">{error}</h2><p className="text-sm text-[#747f9e]">请联系企业管理员获取新的邀请码</p></div>;
  if (!info) return null;

  return (
    <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center px-5 py-10 relative" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      {authed && (
        <button onClick={handleLogout} className="absolute top-4 right-4 text-xs text-[#747f9e] hover:text-[#e03131] transition-colors">退出</button>
      )}
      <div className="w-full max-w-sm">
        {info.type === 'personal' ? (
          <>
            <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-[#2147ff] to-[#8b5cf6] flex items-center justify-center text-white text-2xl mb-4 mx-auto">💎</div>
            <h1 className="text-xl font-bold text-[#10162f] mb-1">「{info.inviterName || '平台用户'}」邀请你</h1>
            <p className="text-sm text-[#747f9e] mb-8">一起做 AI 经验萃取</p>
          </>
        ) : (
          <>
            <h1 className="text-xl font-bold text-[#10162f] mb-1">AI 经验萃取师</h1>
            <p className="text-sm text-[#747f9e] mb-2">{info.companyName}</p>
            <p className="text-xs text-[#747f9e] mb-8">发现你未被看见的价值</p>
          </>
        )}

        {/* 活跃会话提示 */}
        {activeSession && (
          <div className="mb-4 px-4 py-3 rounded-xl bg-amber-50 border border-amber-200 text-sm">
            <p className="text-amber-800 font-medium mb-2">你有进行中的访谈「{activeSession.topic}」</p>
            <div className="flex gap-2">
              <button onClick={() => router.push(`/h5/interview/chat/${activeSession.sessionId}`)}
                className="flex-1 py-2 rounded-full bg-amber-500 text-white text-xs font-medium">
                继续访谈
              </button>
              <button
                className="flex-1 py-2 rounded-full border border-amber-300 text-amber-700 text-xs font-medium"
                onClick={async () => {
                  try {
                    const r = await restartSession(activeSession.sessionId);
                    router.push(`/h5/interview/chat/${r.sessionId}`);
                  } catch { setToasts('重新开始失败，请重试'); }
                }}
              >重新开始</button>
            </div>
          </div>
        )}

        {/* 剩余次数 */}
        {authChecked && authed && cUser && cUser.extractionRemaining !== undefined && (
          <div className="mb-4 px-4 py-2.5 rounded-xl bg-white border border-[#dfe6ff] text-sm text-[#10162f]">
            剩余 <span className="font-semibold text-[#2147ff]">{cUser.extractionRemaining}</span> 次免费萃取
          </div>
        )}

        {/* 主题输入 — 活跃会话检查完成 + 无活跃会话时才显示 */}
        {activeChecked && !activeSession && (
          <>
            <div className="bg-white rounded-[26px] border border-[#e1e7ff] p-5 shadow-[0_18px_50px_rgba(42,74,177,0.10)] mb-4">
              <label className="block text-sm font-medium text-[#10162f] mb-2">这次想萃取什么经验？</label>
              <textarea value={topic} onChange={e => setTopic(e.target.value)} placeholder='比如"搞定说太贵的客户"' maxLength={100} rows={3}
                className="w-full px-4 py-3 rounded-xl border border-[#dfe6ff] bg-white text-sm focus:outline-none focus:border-[#2147ff] resize-none" />
            </div>
            <button onClick={handleStart} disabled={submitting || !topic.trim()}
              className="w-full py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium disabled:opacity-40 hover:translate-y-[-1px] transition-transform">
              {submitting ? '创建中...' : '开始萃取'}
            </button>
          </>
        )}

        {toast && <div className="mb-4 px-4 py-2.5 rounded-xl bg-red-50 border border-red-200 text-sm text-red-600">{toast}</div>}

        {/* 未登录：登录/注册入口 */}
        {authChecked && !authed && !showLogin && (
          <div className="mt-6 text-center text-sm text-[#747f9e]">
            {info.type === 'personal' ? (
              <button onClick={() => { setShowLogin(true); setLoginTab('personal'); setLoginSub('login'); setAuthError(null); }} className="text-[#2147ff] font-medium">登录 / 注册</button>
            ) : (
              <>
                <button onClick={() => { setShowLogin(true); setLoginTab('enterprise'); setLoginSub('login'); setAuthError(null); }} className="text-[#2147ff] font-medium">企业登录</button>
                <span className="mx-2 text-[#dfe6ff]">|</span>
                <button onClick={() => { setShowLogin(true); setLoginTab('personal'); setLoginSub('login'); setAuthError(null); }} className="text-[#2147ff] font-medium">个人登录</button>
              </>
            )}
          </div>
        )}

        {/* 内联登录表单 */}
        {showLogin && (
          <div className="mt-5 bg-white rounded-[20px] border border-[#e1e7ff] p-5">
            {/* Tab — personal 邀请只显示个人 */}
            {info.type !== 'personal' && (
            <div className="flex rounded-lg bg-[#f1f5f9] p-0.5 mb-4">
              {(['enterprise', 'personal'] as const).map(t => (
                <button key={t} onClick={() => { setLoginTab(t); setLoginSub('login'); setAuthError(null); }}
                  className={`flex-1 py-1.5 rounded-md text-xs font-medium ${loginTab === t ? 'bg-white text-[#10162f] shadow-sm' : 'text-[#747f9e]'}`}>
                  {t === 'enterprise' ? '企业' : '个人'}
                </button>
              ))}
            </div>
            )}
            {/* 登录/注册切换 */}
            <div className="flex gap-2 mb-3">
              {(['login', 'register'] as const).map(m => (
                <button key={m} onClick={() => { setLoginSub(m); setAuthError(null); }}
                  className={`text-xs pb-1 border-b-2 ${loginSub === m ? 'border-[#2147ff] text-[#2147ff] font-medium' : 'border-transparent text-[#747f9e]'}`}>
                  {m === 'login' ? '登录' : '注册'}
                </button>
              ))}
            </div>

            {loginTab === 'enterprise' ? (
              loginSub === 'login' ? (
                <form onSubmit={doBLogin} className="space-y-2.5">
                  <input type="text" value={info.companyName} disabled className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-gray-50 text-[#747f9e] text-xs" />
                  <input type="text" value={bAccount} onChange={e => setBAccount(e.target.value)} placeholder="企业账号" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  <input type="password" value={bPassword} onChange={e => setBPassword(e.target.value)} placeholder="密码" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  {authError && <div className="px-3 py-2 rounded-lg bg-red-50 text-xs text-red-600">{authError}</div>}
                  <button type="submit" disabled={authLoading} className="w-full py-2.5 rounded-full bg-[#2147ff] text-white text-xs font-medium disabled:opacity-40">{authLoading ? '...' : '登录'}</button>
                </form>
              ) : (
                <form onSubmit={doBRegister} className="space-y-2.5">
                  <input type="text" value={info.companyName} disabled className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-gray-50 text-[#747f9e] text-xs" />
                  <input type="text" value={bRegName} onChange={e => setBRegName(e.target.value)} placeholder="你的真实姓名" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  <input type="text" value={bRegAccount} onChange={e => setBRegAccount(e.target.value)} placeholder="设置登录账号" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  <input type="password" value={bRegPassword} onChange={e => setBRegPassword(e.target.value)} placeholder="至少6位密码" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  {authError && <div className="px-3 py-2 rounded-lg bg-red-50 text-xs text-red-600">{authError}</div>}
                  <button type="submit" disabled={authLoading} className="w-full py-2.5 rounded-full bg-[#2147ff] text-white text-xs font-medium disabled:opacity-40">{authLoading ? '...' : '注册'}</button>
                </form>
              )
            ) : (
              loginSub === 'login' ? (
                <form onSubmit={doCLogin} className="space-y-2.5">
                  <input type="text" value={cAccount} onChange={e => setCAccount(e.target.value)} placeholder="手机号或邮箱" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  <input type="password" value={cPassword} onChange={e => setCPassword(e.target.value)} placeholder="密码" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  {authError && <div className="px-3 py-2 rounded-lg bg-red-50 text-xs text-red-600">{authError}</div>}
                  <button type="submit" disabled={authLoading} className="w-full py-2.5 rounded-full bg-[#2147ff] text-white text-xs font-medium disabled:opacity-40">{authLoading ? '...' : '登录'}</button>
                </form>
              ) : (
                <form onSubmit={doCRegister} className="space-y-2.5">
                  <input type="text" value={cRegNickname} onChange={e => setCRegNickname(e.target.value)} placeholder="昵称（选填）" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  <input type="text" value={cRegAccount} onChange={e => setCRegAccount(e.target.value)} placeholder="手机号或邮箱" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  <input type="password" value={cRegPassword} onChange={e => setCRegPassword(e.target.value)} placeholder="至少6位密码" className="w-full px-3 py-2.5 rounded-xl border border-[#dfe6ff] bg-white text-xs focus:outline-none focus:border-[#2147ff]" />
                  {authError && <div className="px-3 py-2 rounded-lg bg-red-50 text-xs text-red-600">{authError}</div>}
                  <button type="submit" disabled={authLoading} className="w-full py-2.5 rounded-full bg-[#2147ff] text-white text-xs font-medium disabled:opacity-40">{authLoading ? '...' : '注册'}</button>
                </form>
              )
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export default function H5InviteEntryPage() {
  const params = useParams<{ inviteCode: string }>();
  return (
    <Suspense fallback={<div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center"><p className="text-sm text-[#747f9e]">加载中...</p></div>}>
      <H5InviteEntryContent inviteCode={params.inviteCode} />
    </Suspense>
  );
}
