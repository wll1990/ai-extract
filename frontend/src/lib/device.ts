'use client';

import { useEffect, useState } from 'react';

/**
 * 设备能力检测工具。
 *
 * 用于「分享页」这类同一路径同时服务 PC 与 H5 的页面：按设备能力分流语音交互
 * （PC=点击/button，H5=长按/bar），而不是按路径判断。
 *
 * @since 2026-08-15
 */

/** 判断是否为触屏优先设备（手机/平板）。服务端无 window，返回 false。 */
export function isMobile(): boolean {
  if (typeof window === 'undefined') return false;
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia('(pointer: coarse) and (hover: none)').matches;
  }
  return 'ontouchstart' in window;
}

/**
 * SSR 安全的设备判断 hook：首次渲染返回 false（与服务端一致，避免 hydration 不一致），
 * 挂载后再按真实设备能力更新。
 */
export function useIsMobile(): boolean {
  const [mobile, setMobile] = useState(false);
  useEffect(() => {
    setMobile(isMobile());
  }, []);
  return mobile;
}
