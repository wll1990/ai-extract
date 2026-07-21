/** @type {import('next').NextConfig} */
const nextConfig = {
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
};

module.exports = nextConfig;
