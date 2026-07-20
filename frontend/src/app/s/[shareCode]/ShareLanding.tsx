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
    rec: false, iconBg: 'bg-[rgba(6,182,212,0.10)]', iconColor: 'text-[#0891b2]',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
        <path d="M8 10h8M8 14h5" /><path d="M4 6a2 2 0 012-2h12a2 2 0 012 2v9a2 2 0 01-2 2H9l-4 3.5V6z" />
      </svg>
    ),
  },
  {
    mode: 'practice' as const, title: '实战对练', desc: '他扮演客户，练完逐轮点评',
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

  return (
    <div className="flex min-h-[100dvh] flex-col bg-bg">
      {/* 渐变 hero */}
      <div className="relative bg-[linear-gradient(135deg,#06b6d4_0%,#3b82f6_100%)] pb-16 pt-12">
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(120%_60%_at_80%_0%,rgba(255,255,255,0.22),transparent_60%)]" />
        <div className="relative px-6 text-center">
          <div className="relative mx-auto h-[88px] w-[88px]">
            {info.avatarUrl ? (
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
        </div>
      </div>

      {/* 上叠白卡：简介 + 擅长 chips */}
      <div className="relative z-10 -mt-11 mx-4 rounded-2xl border border-white/60 bg-card p-5 pb-4 text-center shadow-float backdrop-blur">
        <div className="text-body leading-relaxed text-foreground">
          把{name}的实战打法，浓缩成你随时可问的 AI 分身
        </div>
        {(info.tags?.length || 0) > 0 && (
          <div className="mt-3 flex flex-wrap justify-center gap-2">
            {info.tags!.slice(0, 6).map(tag => (
              <span key={tag} className="rounded-pill bg-primary-light px-3 py-1 text-xs text-primary">{tag}</span>
            ))}
          </div>
        )}
      </div>

      {/* 三模式入口 */}
      <div className="mt-5 flex flex-col gap-3 px-4">
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
              <span className="flex items-center gap-2 text-h3 font-semibold text-foreground">
                {card.title}
                {card.rec && <span className="rounded-pill bg-primary-light px-1.5 py-0.5 text-[10px] font-medium text-primary">推荐</span>}
              </span>
              <span className="mt-0.5 block text-xs text-muted-foreground">{card.desc}</span>
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
