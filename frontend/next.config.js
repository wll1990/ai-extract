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
   * API 代理（开发便利）
   *
   * 注意：Next 的 rewrites 在生产同样生效，并不存在「仅开发模式」的开关。
   * 生产必须由 Nginx/Ingress 在进入 Next 之前截获 /api/v1，否则请求会被代理到
   * **前端机器自己的** localhost:8080；前端机没装后端时表现为 502。
   * 详见 docs/78-多服务部署与调用.md §12-3。
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
