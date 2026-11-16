import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import '@/styles/mindmaster/styles.css';
import '@/styles/mindmaster/overrides.css';

/**
 * 销冠门户外壳。
 *
 * MindSmith 的样式表里有 :root / html / body / 元素级选择器，已在复制时收进
 * .mm-root 作用域，避免 Next.js 全局 CSS 常驻后污染后台路由。
 *
 * metadata 决定这个链接被贴到微信、飞书、邮件里时显示成什么样：
 * 没有它，分享出去只有一行网址，没有标题、没有摘要、没有缩略图。
 */
export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? 'http://localhost:3000'),
  title: 'MindForge 销冠门户 | 把销冠的经验变成团队当天就能用的回答',
  description:
    '问销冠的分身、和 AI 客户对练、复盘一次沟通并直接转成线索和任务。每条回答能回到原话，超出范围会明说不知道，关键动作由你确认。',
  keywords: ['销冠经验', '销售复盘', 'AI 分身', '销售对练', 'B2B 大客户销售'],
  openGraph: {
    type: 'website',
    locale: 'zh_CN',
    siteName: 'MindForge',
    title: 'MindForge 销冠门户',
    description:
      '把销冠的经验变成团队当天就能用的回答：能问、能练、能复盘、能沉淀。',
    images: [{ url: '/assets/og-portal.jpg', width: 1200, height: 630, alt: 'MindForge 销冠门户' }],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'MindForge 销冠门户',
    description: '把销冠的经验变成团队当天就能用的回答。',
    images: ['/assets/og-portal.jpg'],
  },
};

export default function PortalLayout({ children }: { children: ReactNode }) {
  return <div className="mm-root">{children}</div>;
}
