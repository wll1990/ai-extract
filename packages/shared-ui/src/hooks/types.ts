/**
 * ChatConfig — 跨端配置注入
 *
 * 前端/企业端/H5 在应用启动时通过此接口注入平台差异。
 * 默认值基于浏览器环境（window.confirm, Date.now, requestAnimationFrame）。
 */

export interface ChatConfig {
  /** === 平台适配 === */
  platform: {
    /** 确认对话框（默认 window.confirm） */
    confirmFn?: (message: string) => boolean;
    /** ID 生成器（默认 Date.now() + random） */
    generateId?: () => string;
    /** 帧调度器（默认 requestAnimationFrame，Node 环境用 setTimeout） */
    scheduleUpdate?: (fn: () => void) => void;
    /** 认证错误回调（401/403 时触发） */
    onAuthError?: (status: number) => void;
  };

  /** === 国际化文案 === */
  i18n?: {
    practice?: {
      championLabel?: string;       // 默认 '销冠会怎么说'
      hitsLabel?: string;           // 默认 '你说到的'
      missesLabel?: string;         // 默认 '进阶建议'
      techniqueLabel?: string;      // 默认 '技法'
      offTopicLabel?: string;       // 默认 '教练提醒'
      retryButtonLabel?: string;    // 默认 '用这个技法再试'
      advanceButtonLabel?: string;  // 默认 '继续下一轮'
      endPracticeLabel?: string;    // 默认 '结束对练 · 查看复盘'
    };
    match?: {
      exact?: string;               // 默认 '精确命中'
      semantic?: string;            // 默认 '语义相关'
      profileGuess?: string;        // 默认 '画像推断'
    };
    chat?: {
      talkGreeting?: string;        // 默认 '你好'
      advanceMessage?: string;      // 默认 '（继续下一轮）'
      aiUnavailableMsg?: string;    // 默认 'AI服务暂时不可用，请稍后重试'
      evalParseErrorMsg?: string;   // 默认 '评价解析失败，请重试'
      evalFallbackMsg?: string;     // 默认 '对练完成'
    };
    roles?: {
      customer?: string;            // 默认 '客户'
      salesperson?: string;         // 默认 '销售员'
      defaultOwner?: string;        // 默认 '销冠'
    };
  };
}

/** 合并用户配置与默认值 */
export function resolveConfig(config?: ChatConfig): Required<ChatConfig>['platform'] & { i18n: Required<Required<ChatConfig>['i18n']>; roles: Required<Required<ChatConfig>['i18n']>['roles'] } {
  const p = config?.platform || {} as any;
  const i = config?.i18n || {} as any;
  const ip = i.practice || {};
  const im = i.match || {};
  const ic = i.chat || {};
  const ir = i.roles || {};

  return {
    platform: {
      confirmFn: p.confirmFn || ((msg: string) => typeof window !== 'undefined' ? window.confirm(msg) : false),
      generateId: p.generateId || (() => `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`),
      scheduleUpdate: p.scheduleUpdate || ((fn: () => void) => typeof window !== 'undefined' ? requestAnimationFrame(fn) : setTimeout(fn, 16)),
      onAuthError: p.onAuthError || (() => {}),
    },
    i18n: {
      practice: {
        championLabel: ip.championLabel || '销冠会怎么说',
        hitsLabel: ip.hitsLabel || '你说到的',
        missesLabel: ip.missesLabel || '进阶建议',
        techniqueLabel: ip.techniqueLabel || '技法',
        offTopicLabel: ip.offTopicLabel || '教练提醒',
        retryButtonLabel: ip.retryButtonLabel || '用这个技法再试',
        advanceButtonLabel: ip.advanceButtonLabel || '继续下一轮',
        endPracticeLabel: ip.endPracticeLabel || '结束对练 · 查看复盘',
      },
      match: {
        exact: im.exact || '精确命中',
        semantic: im.semantic || '语义相关',
        profileGuess: im.profileGuess || '画像推断',
      },
      chat: {
        talkGreeting: ic.talkGreeting || '你好',
        advanceMessage: ic.advanceMessage || '（继续下一轮）',
        aiUnavailableMsg: ic.aiUnavailableMsg || 'AI服务暂时不可用，请稍后重试',
        evalParseErrorMsg: ic.evalParseErrorMsg || '评价解析失败，请重试',
        evalFallbackMsg: ic.evalFallbackMsg || '对练完成',
      },
      roles: {
        customer: ir.customer || '客户',
        salesperson: ir.salesperson || '销售员',
        defaultOwner: ir.defaultOwner || '销冠',
      },
    },
  };
}
