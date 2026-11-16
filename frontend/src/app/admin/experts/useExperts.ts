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
  const [industryFilter, setIndustryFilter] = useState('');
  const [error, setError] = useState<string | null>(null);
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
    setPage(pg); pageRef.current = pg; setLoading(true); setError(null);
    try {
      const [expData, compData] = await Promise.all([
        getExperts(pg, 12, keyword || undefined, statusFilter || undefined),
        getCompositeDetail().catch(() => null),
      ]);
      setExperts(expData.content || []); setTotalPages(expData.totalPages || 1); setComposite(compData);
    } catch (e) {
      console.error(e);
      setExperts([]);
      setError(e instanceof Error ? e.message : '萃取师列表加载失败');
    }
    finally { setLoading(false); }
  }, [keyword, statusFilter]);

  useEffect(() => { loadData(1); }, [loadData]);

  // 分析中的萃取师自动轮询，避免用户手动刷新
  useEffect(() => {
    const busy = experts.some(e => e.status === 'pending' || e.status === 'analyzing');
    if (!busy) return;
    const timer = window.setInterval(() => { void loadData(pageRef.current); }, 10_000);
    return () => window.clearInterval(timer);
  }, [experts, loadData]);

  /** 行业筛选在客户端做（后端列表未支持行业条件），选项来自当前页标签 */
  const industries = Array.from(new Set(
    experts.flatMap(e => e.industryTags || []).filter(Boolean)))
    .sort((a, b) => a.localeCompare(b, 'zh-CN'));
  const filteredExperts = industryFilter
    ? experts.filter(e => (e.industryTags || []).includes(industryFilter))
    : experts;

  return { experts, filteredExperts, industries, industryFilter, setIndustryFilter,
    composite, loading, error, page, totalPages, pageRef, keyword, setKeyword,
    statusFilter, setStatusFilter, toast, setToast, showToast, actionLoading, setActionLoading,
    showUpload, setShowUpload, showPreview, setShowPreview, compositeLoading, setCompositeLoading,
    previewData, setPreviewData, reviewGrains, setReviewGrains, loadData, regenerateComposite };
}
