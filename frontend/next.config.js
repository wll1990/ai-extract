/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  transpilePackages: ['@aiextract/shared-ui'],
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: '**',
      },
    ],
  },
  /**
   * API 代理（仅开发模式生效）
   *
   * 生产环境跨域部署时此配置不生效，需通过以下两种方式之一：
   * 1. 设置 NEXT_PUBLIC_API_BASE_URL 为后端完整 URL（后端需配 CORS）
   * 2. 用 Nginx 反向代理 /api/ 到后端服务器
   */
  async rewrites() {
    return [
      {
        source: '/api/v1/:path*',
        destination: 'http://localhost:8080/api/v1/:path*',
      },
    ];
  },
  /**
   * 安全响应头
   *
   * CSP 保留 'unsafe-inline'/'unsafe-eval'：Next.js 14 运行时注入内联脚本，
   * 未接 nonce 前收紧会直接白屏。这里先关掉 object/base-uri/frame 三类高危面。
   */
  async headers() {
    const csp = [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https:",
      "font-src 'self' data:",
      "connect-src 'self' https: wss:",
      "media-src 'self' blob: https:",
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'self'",
      "frame-ancestors 'none'",
    ].join('; ');
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Content-Security-Policy', value: csp },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'X-Frame-Options', value: 'DENY' },
        ],
      },
    ];
  },
};

module.exports = nextConfig;
