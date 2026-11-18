'use client';

import { useEffect } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';

/**
 * 旧测试对话入口已下线 —— 统一到试问台。
 *
 * 旧页面用 is_test=true 仍然写 conversation_stats / grain_retrieve_log，
 * 与试问台的「零交叉」口径不一致；保留路由只做重定向，避免旧链接 404。
 */
export default function RetiredTestConversationPage() {
  const params = useParams();
  const router = useRouter();
  const skillId = params.skillId as string;

  useEffect(() => {
    router.replace(`/my/skills/${skillId}/trial`);
  }, [router, skillId]);

  return <div className="flex h-64 items-center justify-center"><LoadingSpinner /></div>;
}
