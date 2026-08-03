/**
 * [平台端副本]
 * 本文件从 frontend 复制而来。
 * 设计决策：B端(8089)和平台端(8088)各自独立，互不引用对方 URL。
 * 两个 App 各自维护一份副本。
 *
 * 维护约定：
 * - 如果两端需要相同改动 → 抽到 @aiextract/shared-ui 共享库
 * - 如果各自有不同需求 → 独立演进，不改对方
 *
 * 原始文件: frontend 对应路径
 * 复制日期: 2026-08-02
 */


'use client';

import type { ShareInfo } from '@/lib/api/c';

interface Props {
  info: ShareInfo;
  starting: boolean;
  onStart: (mode: 'qa' | 'talk' | 'practice') => void;
  onLogin: () => void;
}

/** 模式入口卡配置 */
const MODE_CARDS = [
  {
    mode: 'qa' as const, title: '向他请教', desc: '销售难题直接问，答案带出处',
    cta: '选一个关心的场景，拿到真实销冠经验',
    value: '不是 AI 瞎编的',
    rec: true, iconBg: 'bg-primary-light', iconColor: 'text-primary',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
        <path d="M21 11.5a8.38 8.38 0 01-9 8.36c-1.2-.08-2.3-.4-3.3-.9L3 20l1.1-3.6A8.5 8.5 0 1121 11.5z" />
        <path d="M9.5 9.5a2.5 2.5 0 114 2c-.8.6-1.5 1-1.5 2" /><circle cx="12" cy="16.4" r="0.4" />
      </svg>
    ),
  },
  {
    mode: 'talk' as const, title: '随便聊聊', desc: '像老销售带新人一样自由对话',
    cta: '像碰到老同事一样聊，越聊越能挖出他的实战打法',
    value: '想到什么说什么',
    rec: false, iconBg: 'bg-[rgba(6,182,212,0.10)]', iconColor: 'text-[#0891b2]',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
        <path d="M8 10h8M8 14h5" /><path d="M4 6a2 2 0 012-2h12a2 2 0 012 2v9a2 2 0 01-2 2H9l-4 3.5V6z" />
      </svg>
    ),
  },
  {
    mode: 'practice' as const, title: '实战对练', desc: '他扮演客户，练完逐轮点评',
    cta: '他扮演客户来考你，每轮给你实战点评',
    value: '敢试试吗？',
    rec: false, iconBg: 'bg-[rgba(200,164,92,0.14)]', iconColor: 'text-[#8a6a2f]',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
        <circle cx="12" cy="12" r="8.5" /><circle cx="12" cy="12" r="4.5" /><circle cx="12" cy="12" r="1" />
      </svg>
    ),
  },
];

/**
 * 分享落地页 — 销冠名片 + 三模式入口（无凭证可看，点入口才建游客身份）
 */
export default function ShareLanding({ info, starting, onStart, onLogin }: Props) {
  const name = info.ownerName || '销冠';
  const isOrg = info.skillType === 'organization';
  const isCard = info.shareChannel === 'card';
  const orgMembers = info.members || [];
  // Show up to 4 member avatars for org skills
  const previewMembers = orgMembers.slice(0, 4);
  const AVATAR_COLORS = ['#6366f1', '#8b5cf6', '#a855f7', '#d946ef'];

  return (
    <div className="flex min-h-[100dvh] flex-col bg-bg">
      {/* 渐变 hero */}
      <div className={`relative pb-16 pt-12 ${isCard ? 'bg-[linear-gradient(135deg,#f59e0b_0%,#ef4444_100%)]' : isOrg ? 'bg-[linear-gradient(135deg,#6366f1_0%,#a855f7_100%)]' : 'bg-[linear-gradient(135deg,#06b6d4_0%,#3b82f6_100%)]'}`}>
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(120%_60%_at_80%_0%,rgba(255,255,255,0.22),transparent_60%)]" />
        <div className="relative px-6 text-center">
          <div className="relative mx-auto h-[88px] w-[88px]">
            {isOrg ? (
              <div className="flex items-center justify-center gap-0.5">
                {previewMembers.length > 0 ? previewMembers.map((m, i) => (
                  m.avatarUrl ? (
                    <img key={m.id} src={m.avatarUrl} alt={m.ownerName}
                      className="h-12 w-12 rounded-full border-2 border-white object-cover"
                      style={{ marginLeft: i > 0 ? -8 : 0, zIndex: 4 - i }} />
                  ) : (
                    <div key={m.id} className="h-12 w-12 rounded-full border-2 border-white flex items-center justify-center text-white text-sm font-bold"
                      style={{ background: AVATAR_COLORS[i % AVATAR_COLORS.length], marginLeft: i > 0 ? -8 : 0, zIndex: 4 - i }}>
                      {(m.ownerName || '?')[0]}
                    </div>
                  )
                )) : (
                  <span className="text-5xl">🏢</span>
                )}
                {orgMembers.length > 4 && (
                  <div className="h-12 w-12 rounded-full border-2 border-white bg-white/20 flex items-center justify-center text-white text-xs font-medium" style={{ marginLeft: -8 }}>
                    +{orgMembers.length - 4}
                  </div>
                )}
              </div>
            ) : info.avatarUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={info.avatarUrl} alt={name}
                className="h-[88px] w-[88px] rounded-full border-[3px] border-white/95 object-cover shadow-float" />
            ) : (
              <div className="flex h-[88px] w-[88px] items-center justify-center rounded-full border-[3px] border-white/95 bg-gradient-to-br from-blue-100 to-blue-300 text-3xl font-semibold text-primary shadow-float">
                {name.charAt(0)}
              </div>
            )}
            {/* 金色销冠徽标 */}
            <div className="absolute -bottom-0.5 -right-1 flex h-[30px] w-[30px] items-center justify-center rounded-full border-[2.5px] border-white bg-gold shadow-card" title="销冠">
              <svg viewBox="0 0 24 24" className="h-[15px] w-[15px] fill-white"><path d="M3 7l4.5 4L12 4l4.5 7L21 7l-1.8 11H4.8L3 7z" /></svg>
            </div>
          </div>
          <div className="mt-3.5 text-[22px] font-semibold tracking-wide text-white">{name}</div>
          {info.ownerTitle && <div className="mt-1 text-[13px] text-white/85">{info.ownerTitle}</div>}
          {info.stats && info.stats.conversationCount > 0 && (
            <div className="mt-3 flex items-center justify-center gap-3 text-white/80 text-[12px]">
              <span>💬 {info.stats.conversationCount.toLocaleString()} 次对话</span>
              {info.stats.satisfactionRate > 0 && (
                <><span className="text-white/30">·</span><span>👍 {info.stats.satisfactionRate}% 满意</span></>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 上叠白卡：简介 + 擅长 chips */}
      <div className="relative z-10 -mt-11 mx-4 rounded-2xl border border-white/60 bg-card p-5 pb-4 text-center shadow-float backdrop-blur">
        <div className="text-body leading-relaxed text-foreground">
          {info.stats?.userCount && info.stats.userCount > 0 ? (
            <>已帮助 <span className="font-semibold text-[#2563eb]">{info.stats.userCount}</span> 位销售同行解决实际问题</>
          ) : (
            <>把{name}的实战打法，浓缩成你随时可问的 AI 分身</>
          )}
        </div>
        {(info.sceneTags?.length || 0) > 0 && (
          <div className="mt-2 text-[11px] text-muted-foreground">
            {info.stats?.satisfactionRate ? <>{info.stats.satisfactionRate}% 满意 · </> : ''}
            {info.sceneTags!.length} 个场景
          </div>
        )}
        {(info.tags?.length || 0) > 0 && (
          <div className="mt-3 flex flex-wrap justify-center gap-2">
            {info.tags!.slice(0, 6).map(tag => (
              <span key={tag} className="rounded-pill bg-primary-light px-3 py-1 text-xs text-primary">{tag}</span>
            ))}
          </div>
        )}
      </div>

      {/* 信任条 */}
      <div className="mx-4 mt-4 rounded-xl px-3 py-3"
        style={{ background: 'linear-gradient(135deg, rgba(6,182,212,0.06), rgba(59,130,246,0.08))', border: '1px solid rgba(59,130,246,0.1)' }}>
        <div className="flex items-center divide-x divide-black/5">
          <div className="flex-1 flex flex-col items-center gap-0.5 px-1">
            <div className="flex h-7 w-7 items-center justify-center rounded-full"
              style={{ background: 'linear-gradient(135deg, #f59e0b, #d97706)' }}>
              <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                <path d="M12 2l3.5 7L22 9l-5.5 7.5L18 22l-6-4.5L6 22l1.5-5.5L2 9l6.5 0L12 2z" />
              </svg>
            </div>
            <span className="text-[11px] font-semibold text-foreground">{info.stats?.conversationCount ? `${info.stats.conversationCount} 次` : '实战打法'}</span>
            <span className="text-[9px] text-muted-foreground leading-tight">{info.stats?.conversationCount ? '真实对话交流' : '销冠真实案例提炼'}</span>
          </div>
          <div className="flex-1 flex flex-col items-center gap-0.5 px-1">
            <div className="flex h-7 w-7 items-center justify-center rounded-full"
              style={{ background: 'linear-gradient(135deg, #6366f1, #4f46e5)' }}>
              <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
                <path d="M14 2v6h6" />
                <circle cx="11.5" cy="14.5" r="2.5" />
                <path d="M13.5 16.5L16 19" />
              </svg>
            </div>
            <span className="text-[11px] font-semibold text-foreground">{info.stats?.satisfactionRate ? `${info.stats.satisfactionRate}% 满意` : '溯源可查'}</span>
            <span className="text-[9px] text-muted-foreground leading-tight">{info.stats?.satisfactionRate ? '回答被认可' : '每句话有据可依'}</span>
          </div>
          <div className="flex-1 flex flex-col items-center gap-0.5 px-1">
            <div className="flex h-7 w-7 items-center justify-center rounded-full"
              style={{ background: 'linear-gradient(135deg, #06b6d4, #0891b2)' }}>
              <svg viewBox="0 0 24 24" fill="white" stroke="white" strokeWidth="0.5" strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                <path d="M13 2L3 14h7l-2 8 10-12h-7z" />
              </svg>
            </div>
            <span className="text-[11px] font-semibold text-foreground">{(info.sceneTags?.length || 0) > 0 ? `${info.sceneTags!.length} 个场景` : '即问即用'}</span>
            <span className="text-[9px] text-muted-foreground leading-tight">{(info.sceneTags?.length || 0) > 0 ? '经验全面覆盖' : '30秒拿到可执行话术'}</span>
          </div>
        </div>
      </div>

      {/* 三模式入口 */}
      <div className="mt-4 flex flex-col gap-3 px-4">
        {MODE_CARDS.map(card => (
          <button
            key={card.mode}
            disabled={starting}
            onClick={() => onStart(card.mode)}
            className={`flex items-center gap-3 rounded-lg border bg-bg px-4 py-3.5 text-left shadow-card transition-transform active:scale-[0.98] disabled:opacity-60 ${
              card.rec ? 'border-primary' : 'border-border'
            }`}
          >
            <span className={`flex h-[42px] w-[42px] flex-none items-center justify-center rounded-lg ${card.iconBg} ${card.iconColor}`}>
              {card.icon}
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-2">
                <span className="text-h3 font-semibold text-foreground">{card.title}</span>
                {card.rec && <span className="rounded-pill bg-primary-light px-1.5 py-0.5 text-[10px] font-medium text-primary">推荐</span>}
              </span>
              <span className="mt-0.5 block text-[13px] text-foreground leading-snug">{card.cta}</span>
              <span className="mt-0.5 block text-[11px] text-muted-foreground">{card.value}</span>
              {card.mode === 'qa' && info.stats && info.stats.conversationCount > 0 && <span className="mt-0.5 block text-[10px] text-muted-foreground">{info.stats.conversationCount} 次对话</span>}
              {card.mode === 'talk' && info.stats && info.stats.userCount > 0 && <span className="mt-0.5 block text-[10px] text-muted-foreground">{info.stats.userCount} 人用过</span>}
              {card.mode === 'practice' && (info.sceneTags?.length || 0) > 0 && <span className="mt-0.5 block text-[10px] text-muted-foreground">{info.sceneTags!.length} 个场景</span>}
            </span>
            <svg className="h-4 w-4 flex-none text-muted-foreground-2" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        ))}
      </div>

      {/* 底部：登录入口 + 声明 */}
      <div className="mt-auto px-6 pb-[calc(24px+env(safe-area-inset-bottom))] pt-6 text-center">
        <div className="text-[13px] text-muted-foreground">
          已有账号？<button onClick={onLogin} className="font-medium text-primary">登录</button>
        </div>
        <div className="mt-2 text-[11px] text-muted-foreground-2">内容由 AI 分身生成，仅供参考</div>
      </div>
    </div>
  );
}
