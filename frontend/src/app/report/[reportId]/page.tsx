'use client';
import React from 'react';
import { useParams } from 'next/navigation';
import { API_BASE } from '@/lib/api/client';
import ReportViewer from '@/components/report/ReportViewer';

export default function ReportPage() {
  const { reportId } = useParams<{ reportId: string }>();

  return (
    <ReportViewer
      htmlUrl={`${API_BASE}/reports/${reportId}/html`}
      downloadUrl={`${API_BASE}/reports/${reportId}/download`}
      reportId={reportId}
      canRegenerate={false}
    />
  );
}
