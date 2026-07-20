'use client';

import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React, { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { getSession } from '@/lib/api/interview';
import { SalesInterviewChat } from './SalesInterviewChat';
import { ExpertInterviewChat } from './ExpertInterviewChat';

export default function InterviewChatPage() {
  const params = useParams();
  const sessionId = params.sessionId as string;
  const [interviewType, setInterviewType] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!sessionId) return;
    getSession(sessionId).then(s => {
      setInterviewType(s.interviewType || 'sales');
      setLoading(false);
    }).catch(() => setLoading(false));
  }, [sessionId]);

  if (loading) return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <LoadingSpinner fullScreen={false} />
    </div>
  );

  if (interviewType === 'expert') return <ExpertInterviewChat />;
  return <SalesInterviewChat />;
}
