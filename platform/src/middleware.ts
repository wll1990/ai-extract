/**
 * 路由鉴权中间件 — 企业端专用
 *
 * / 和 /discover 公开；/chat/* 需登录。
 *
 * @since 2026-07-20
 */

import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

const PUBLIC_PATHS = ['/login', '/register'];
const PUBLIC_PREFIXES = ['/', '/discover', '/skill/', '/chat/', '/h5/', '/s/'];
const SKIP_PREFIXES = ['/_next', '/api', '/favicon.ico', '/robots.txt'];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (SKIP_PREFIXES.some(p => pathname.startsWith(p))) {
    return NextResponse.next();
  }

  // 公开页面放行
  if (PUBLIC_PATHS.includes(pathname)) {
    return NextResponse.next();
  }
  // 公开前缀放行
  if (PUBLIC_PREFIXES.some(p => {
    if (p === '/') return pathname === '/';
    return pathname === p || pathname.startsWith(p);
  })) {
    return NextResponse.next();
  }

  // /chat/* 需登录
  const token = request.cookies.get('token');
  if (!token?.value) {
    const loginUrl = new URL('/login', request.url);
    loginUrl.searchParams.set('redirect', pathname);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|robots.txt).*)'],
};
