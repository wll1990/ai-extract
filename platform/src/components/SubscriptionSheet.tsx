'use client';

interface Props {
  open: boolean;
  used: number;
  limit: number;
  onClose: () => void;
}

/**
 * 订阅升级占位抽屉 — 注册用户（c_user）每日免费额度用尽时弹出。
 *
 * <p>H5 分享页与 PC 聊天页复用。后续订阅计划落地后，把「升级订阅」按钮
 * 接到真实支付/升级入口即可，文案与数据（used/limit）已就位。</p>
 */
export default function SubscriptionSheet({ open, used, limit, onClose }: Props) {
  if (!open) return null;

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

        <div className="mx-auto mt-5 flex h-[52px] w-[52px] items-center justify-center rounded-full bg-[linear-gradient(135deg,#f59e0b_0%,#f97316_100%)] shadow-float">
          <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
            <path d="M12 2l2.4 4.9 5.4.8-3.9 3.8.9 5.4L12 14.5 7.2 16.9l.9-5.4-3.9-3.8 5.4-.8L12 2z" />
          </svg>
        </div>

        <h3 className="mt-3 text-center text-lg font-semibold text-foreground">今日免费额度已用完</h3>
        <p className="mt-1.5 text-center text-[13px] leading-relaxed text-muted-foreground">
          已用 {used}/{limit} 条，升级订阅即可无限对话
        </p>

        <div className="mt-5 flex flex-col gap-3">
          <button
            onClick={() => alert('订阅计划即将上线，敬请期待')}
            className="h-12 rounded-pill bg-[linear-gradient(135deg,#f59e0b_0%,#f97316_100%)] text-[15px] font-semibold text-white shadow-float transition-transform active:scale-[0.98]"
          >
            升级订阅
          </button>
        </div>

        <div className="mt-4 text-center">
          <button onClick={onClose} className="text-[13px] text-muted-foreground-2">稍后再说</button>
        </div>
      </div>
    </div>
  );
}
