import type { Metadata } from 'next';
import { ApiProvider } from '@/components/ApiProvider';
import './globals.css';

export const metadata: Metadata = {
  title: 'MindForge — 智锻',
  description: 'Forge Expertise, Scale Minds — AI 经验萃取与专家分身平台',
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
