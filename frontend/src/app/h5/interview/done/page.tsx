'use client';

import { Suspense, useCallback, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';

function DoneContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const sessionId = searchParams.get('sessionId');
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText('platform.mindforce.com');
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // fallback for older browsers
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }, []);

  return (
    <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center justify-center px-6 text-center" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <span className="text-5xl mb-5">🎉</span>
      <h1 className="text-xl font-bold text-[#10162f] mb-3">访谈完成！</h1>
      <p className="text-sm text-[#747f9e] mb-8 max-w-xs">
        你的经验已被 AI 完整记录，预计 2-3 分钟生成萃取报告。
      </p>

      <div className="flex flex-col gap-3 w-full max-w-xs mb-8">
        {sessionId && (
          <button
            onClick={() => router.push(`/h5/report/${sessionId}`)}
            className="w-full py-3 rounded-full bg-[#2147ff] text-white text-sm font-medium hover:translate-y-[-1px] transition-transform"
          >
            查看萃取报告
          </button>
        )}
      </div>

      {/* 下一步引导 + 可复制链接 */}
      <div className="bg-white rounded-[20px] border border-[#e1e7ff] px-5 py-4 max-w-xs w-full text-left shadow-[0_18px_50px_rgba(42,74,177,0.08)]">
        <p className="text-xs font-medium text-[#10162f] mb-2">💡 查看完整报告与审核进度</p>
        <p className="text-xs text-[#747f9e] leading-relaxed mb-3">
          请使用 PC 浏览器访问以下地址，登录后查看完整萃取报告和审核进度。
        </p>
        <div className="flex items-center gap-2 rounded-xl bg-[#f7f9ff] border border-[#dfe6ff] px-3 py-2.5">
          <span className="text-sm font-medium text-[#2147ff] select-all flex-1">platform.mindforce.com</span>
          <button
            onClick={handleCopy}
            className="flex-shrink-0 rounded-lg bg-[#2147ff] px-3 py-1.5 text-xs text-white font-medium hover:bg-[#1a38cc] transition-colors"
          >
            {copied ? '已复制' : '复制'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default function H5InterviewDonePage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center"><p className="text-sm text-[#747f9e]">加载中...</p></div>}>
      <DoneContent />
    </Suspense>
  );
}
