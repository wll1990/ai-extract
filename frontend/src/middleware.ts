import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

/**
 * 路由鉴权中间件
 *
 * <p>检查 token cookie，未登录用户重定向到 /login。
 * 不检查角色（角色数据在 localStorage，middleware 无法读取），
 * admin 路由的角色校验由 AppLayout 和 API 层双重保障。</p>
 */

// 无需登录即可访问的路径
const PUBLIC_PATHS = ['/login', '/register'];

// 公开路径前缀（C 端分享页，游客无 Cookie 直接访问）
const PUBLIC_PREFIXES = ['/s/'];

// 静态资源和 API 路由不拦截
const SKIP_PREFIXES = ['/_next', '/api', '/favicon.ico', '/robots.txt'];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // 静态资源放行
  if (SKIP_PREFIXES.some(p => pathname.startsWith(p))) {
    return NextResponse.next();
  }

  // 公开页面放行
  if (PUBLIC_PATHS.some(p => pathname === p) || PUBLIC_PREFIXES.some(p => pathname.startsWith(p))) {
    // 登录页直接放行（"已登录跳过登录页"由客户端 AppLayout 处理，不在这里做）
    return NextResponse.next();
  }

  // 根路径放行（page.tsx 自行按角色重定向）
  if (pathname === '/') {
    return NextResponse.next();
  }

  // 其他所有路径：检查 token cookie
  const token = request.cookies.get('token');
  if (!token?.value) {
    const loginUrl = new URL('/login', request.url);
    loginUrl.searchParams.set('redirect', pathname);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  // 匹配所有路径，排除静态文件
  matcher: ['/((?!_next/static|_next/image|favicon.ico|robots.txt).*)'],
};
