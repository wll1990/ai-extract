import type { Metadata } from 'next';
import { ApiProvider } from '@/components/ApiProvider';
import './globals.css';

export const metadata: Metadata = {
  title: 'MindForge — 智锻',
  description: 'Forge Expertise, Scale Minds — AI 经验萃取与专家分身平台',
  icons: {
    icon: [
      { url: '/favicon.ico', sizes: 'any' },
      { url: '/favicon-32.png', type: 'image/png', sizes: '32x32' },
      { url: '/icon-512.png', type: 'image/png', sizes: '512x512' },
    ],
    apple: '/apple-touch-icon.png',
  },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <ApiProvider>{children}</ApiProvider>
      </body>
    </html>
  );
}
