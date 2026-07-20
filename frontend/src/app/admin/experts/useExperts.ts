'use client';
import { useState, useEffect, useCallback, useRef } from 'react';
import { getExperts, getCompositeDetail, regenerateComposite, type ExpertSkill, type CompositeInfo } from '@/lib/api/expert';

export function useExperts() {
  const [experts, setExperts] = useState<ExpertSkill[]>([]);
  const [composite, setComposite] = useState<CompositeInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const pageRef = useRef(1);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [toast, setToast] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [showUpload, setShowUpload] = useState(false);
  const [showPreview, setShowPreview] = useState(false);
  const [compositeLoading, setCompositeLoading] = useState(false);
  const [previewData, setPreviewData] = useState<CompositeInfo | null>(null);
  const [reviewGrains, setReviewGrains] = useState<{ expertName: string; groups: any[] } | null>(null);

  const showToast = (msg: string) => { setToast(msg); setTimeout(() => setToast(null), 2000); };

  const loadData = useCallback(async (p?: number) => {
    const pg = p || pageRef.current;
    setPage(pg); pageRef.current = pg; setLoading(true);
    try {
      const [expData, compData] = await Promise.all([
        getExperts(pg, 12, keyword || undefined, statusFilter || undefined),
        getCompositeDetail(),
      ]);
      setExperts(expData.content || []); setTotalPages(expData.totalPages || 1); setComposite(compData);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, [keyword, statusFilter]);

  useEffect(() => { loadData(1); }, [loadData]);

  return { experts, composite, loading, page, totalPages, pageRef, keyword, setKeyword,
    statusFilter, setStatusFilter, toast, setToast, showToast, actionLoading, setActionLoading,
    showUpload, setShowUpload, showPreview, setShowPreview, compositeLoading, setCompositeLoading,
    previewData, setPreviewData, reviewGrains, setReviewGrains, loadData, regenerateComposite };
}
