'use client';
import { useParams } from 'next/navigation';
import { useEffect } from 'react';
import { API_BASE } from '@/lib/api/client';

export default function PublicReportPage() {
  const { shareCode } = useParams<{ shareCode: string }>();
  useEffect(() => {
    if (shareCode) window.location.replace(`${API_BASE}/public/reports/${shareCode}`);
  }, [shareCode]);
  return null;
}