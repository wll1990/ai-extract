import type { Metadata } from 'next';
import './globals.css';
import AppLayout from '@/components/layout/AppLayout';
import { ThemeProvider } from '@/lib/theme';
import { TourProvider } from '@/components/tour/TourProvider';

export const metadata: Metadata = {
  title: 'AI经验萃取平台',
  description: 'AI驱动的销售经验萃取与传承平台',
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
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `
              (function() {
                try {
                  var theme = localStorage.getItem('mindsmith-theme');
                  if (theme === 'dark' || theme === 'claude') {
                    document.documentElement.classList.add('theme-' + theme);
                  }
                } catch(e) {}
              })();
            `,
          }}
        />
      </head>
      <body>
        <ThemeProvider>
          <TourProvider>
            <AppLayout>{children}</AppLayout>
          </TourProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
