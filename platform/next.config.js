/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  transpilePackages: ['@aiextract/shared-ui'],
  images: {
    remotePatterns: [{ protocol: 'https', hostname: '**' }],
  },
  /** API 代理（仅开发模式生效） */
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
