'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { getReport, updateReport, downloadReport, syncChecklist, type ReportDetail, type ChapterUpdate } from '@/lib/api/report';
import { getChecklist, setChecklist } from '@/lib/storage';

export interface StepItem { order: number; name: string; action: string; script: string; mistake: string; }
export interface DecisionItem { title: string; options: string[]; chosen: string; reason: string; }
export interface PitfallItem { title: string; solution: string; }
export interface ChecklistItem { step: number; action: string; }
export interface PracticeScene { title: string; setting: string; customerLine: string; }

export interface Chapter {
  order: number; title: string; content?: string;
  steps?: StepItem[]; decisions?: DecisionItem[]; quotes?: string[]; oneliner?: string; metaphor?: string;
  pitfalls?: PitfallItem[]; checklist?: ChecklistItem[]; practiceScene?: PracticeScene;
}

export function useReport(reportId: string) {
  const [report, setReport] = useState<ReportDetail | null>(null);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [editChapter, setEditChapter] = useState<Chapter | null>(null);
  const [editContent, setEditContent] = useState('');
  const [showSaveModal, setShowSaveModal] = useState(false);
  const [readProgress, setReadProgress] = useState(0);
  const [activeChapter, setActiveChapter] = useState(1);
  const [checklistItems, setChecklistItems] = useState<Record<string, boolean>>({});
  const [practiceAnswer, setPracticeAnswer] = useState('');
  const [showAnswer, setShowAnswer] = useState(false);
  const [userRating, setUserRating] = useState(0);
  const contentRef = useRef<HTMLDivElement>(null);

  // Load report
  useEffect(() => {
    if (!reportId) return;
    getReport(reportId).then(data => {
      setReport(data);
      try {
        const parsed = typeof data.contentJson === 'string' ? JSON.parse(data.contentJson) : data.contentJson;
        setChapters((parsed as { chapters: Chapter[] }).chapters || []);
      } catch { setChapters([]); }
    }).catch(err => { console.error('加载报告失败', err); setChapters([]); }).finally(() => setLoading(false));
  }, [reportId]);

  // Load checklist from localStorage
  useEffect(() => {
    const saved = getChecklist(reportId);
    if (saved) setChecklistItems(saved as Record<string, boolean>);
  }, [reportId]);

  // Scroll tracking with IntersectionObserver (avoids querySelectorAll on every scroll)
  useEffect(() => {
    const handleScroll = () => {
      const { scrollTop, scrollHeight, clientHeight } = document.documentElement;
      setReadProgress(Math.round(Math.min((scrollTop / (scrollHeight - clientHeight)) * 100, 100)));
    };
    window.addEventListener('scroll', handleScroll, { passive: true });

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const chapter = Number((entry.target as HTMLElement).dataset.chapter);
            if (chapter) setActiveChapter(chapter);
          }
        }
      },
      { rootMargin: '-120px 0px 0px 0px' }
    );
    const els = document.querySelectorAll('[data-chapter]');
    els.forEach(el => observer.observe(el));

    return () => {
      window.removeEventListener('scroll', handleScroll);
      observer.disconnect();
    };
  }, []);

  const toggleChecklist = useCallback((key: string) => {
    setChecklistItems(prev => { const next = { ...prev, [key]: !prev[key] }; setChecklist(reportId, next); syncChecklist(reportId, next).catch(() => {}); return next; });
  }, [reportId]);

  const startEdit = useCallback((chapter: Chapter) => {
    setEditChapter(chapter);
    setEditContent(typeof chapter.content === 'string' ? chapter.content : JSON.stringify(chapter.content, null, 2));
    setEditing(true);
  }, []);

  const handleSave = useCallback(() => { setEditing(false); setShowSaveModal(true); }, []);

  const confirmSave = useCallback(async (regenerate: boolean) => {
    if (!editChapter) return;
    try {
      const updated = await updateReport(reportId, [{ order: editChapter.order, content: editChapter as unknown as Record<string, unknown> }], regenerate);
      setReport(updated); setShowSaveModal(false); setEditChapter(null);
    } catch (err) { console.error('保存失败:', err); }
  }, [editChapter, reportId]);

  const handleDownload = useCallback(async (format: 'word' | 'ppt') => {
    try { await downloadReport(reportId, format); } catch { alert('下载失败，请稍后重试'); }
  }, [reportId]);

  return { report, chapters, loading, editing, editChapter, editContent, setEditContent,
    showSaveModal, setShowSaveModal, readProgress, activeChapter, checklistItems, toggleChecklist,
    practiceAnswer, setPracticeAnswer, showAnswer, setShowAnswer, userRating, setUserRating,
    contentRef, startEdit, handleSave, confirmSave, handleDownload,
    setEditing, setEditChapter };
}
