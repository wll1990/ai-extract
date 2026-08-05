'use client';

import { useEffect, useState, useRef, useCallback } from 'react';
import { apiClient } from '@/lib/api/client';
import { copyToClipboard } from '@/lib/clipboard';

interface GrainTrace {
  grainId: string;
  spaceId?: string;
  sceneDescription?: string;
  expertThought?: string;
  standardScript?: string;
  commonMistakes?: string;
  qualityScore?: number;
  difficultyLevel?: string;
  reportTitle?: string;
  reportId?: string;
  sourceName?: string;
  sourceType?: string;
  sourceSnippet?: string;
  avgSimilarity?: number;
}

interface TraceabilityDrawerProps {
  grainIds: string;
  avgSimilarity?: number | string;
  open: boolean;
  onClose: () => void;
  /** 组织分身 ID — 传入后渲染成员可点击链接 */
  orgSkillId?: string;
}

function matchLevel(sim: number) {
  if (sim >= 50) return { label: '精准匹配', cls: 'bg-green-100 text-green-700', icon: '🏅' };
  if (sim >= 30) return { label: '关联匹配', cls: 'bg-blue-100 text-blue-700', icon: '📎' };
  return { label: '参考', cls: 'bg-gray-100 text-gray-600', icon: '📖' };
}

export function TraceabilityDrawer({ grainIds, avgSimilarity, open, onClose, orgSkillId }: TraceabilityDrawerProps) {
  const [data, setData] = useState<GrainTrace[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState<Record<string, boolean>>({});
  const [memberLinks, setMemberLinks] = useState<Record<string, string>>({});
  const touchStartX = useRef(0);
  const [swipeOffset, setSwipeOffset] = useState(0);

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
  }, []);

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    const diff = e.touches[0].clientX - touchStartX.current;
    if (diff > 0) setSwipeOffset(diff); // 只跟踪右滑
  }, []);

  const handleTouchEnd = useCallback(() => {
    if (swipeOffset > 80) {
      onClose();
    }
    setSwipeOffset(0);
  }, [swipeOffset, onClose]);

  useEffect(() => {
    if (!open || !grainIds) return;
    setLoading(true);
    apiClient(`/admin/grains/traceability?grainIds=${encodeURIComponent(grainIds)}`)
      .then(r => setData((r as any) || []))
      .finally(() => setLoading(false));
  }, [open, grainIds]);

  // 组织分身：查 spaceId → skillId 映射
  useEffect(() => {
    if (!orgSkillId || !open) return;
    apiClient(`/admin/organization-skills/${orgSkillId}/member-links`)
      .then(r => { if (r?.data) setMemberLinks(r.data); })
      .catch(() => {});
  }, [orgSkillId, open]);

  const handleCopy = (text: string, id: string) => {
    copyToClipboard(text).then(ok => {
      if (ok) {
        setCopied(prev => ({ ...prev, [id]: true }));
        setTimeout(() => setCopied(prev => ({ ...prev, [id]: false })), 1500);
      }
    });
  };

  if (!open) return null;

  return (
    <>
      {/* 遮罩 */}
      <div onClick={onClose} className="fixed inset-0 bg-black/20 backdrop-blur z-[100]" />

      {/* 抽屉 */}
      <div
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        style={{ transform: swipeOffset > 0 ? `translateX(${swipeOffset}px)` : undefined, transition: swipeOffset === 0 ? 'transform 0.25s ease' : undefined }}
        className="fixed top-0 right-0 bottom-0 w-[420px] max-w-[92vw] bg-white z-[101] shadow-[-8px_0_30px_rgba(0,0,0,0.12)] flex flex-col overflow-hidden animate-[slideInRight_0.3s_ease-out]">

        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-50 flex-shrink-0 bg-gray-50/50">
          <div className="flex items-center gap-2">
            <span className="text-[15px] font-bold text-gray-800">📋 溯源</span>
            {!loading && data.length > 0 && (
              <span className="text-[11px] text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-full font-medium">
                {data.length} 条经验
              </span>
            )}
          </div>
          <button onClick={onClose} className="bg-transparent border-none cursor-pointer text-lg text-gray-400 hover:text-gray-600 transition-colors p-0 leading-none">
            ✕
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-4 py-4 space-y-3" style={{ scrollbarWidth: 'thin' }}>
          {loading ? (
            <div className="text-center py-12 text-gray-400 text-sm">加载中…</div>
          ) : data.map((grain, i) => {
            const sim = typeof avgSimilarity === 'number' ? avgSimilarity
              : avgSimilarity ? Number(avgSimilarity) : 0;
            const level = matchLevel(sim);
            const borderColors = ['border-l-indigo-500', 'border-l-violet-500', 'border-l-sky-500'];
            return (
            <div key={grain.grainId} className={`rounded-2xl overflow-hidden border border-gray-100 shadow-sm bg-white border-l-[3px] ${borderColors[i % 3]}`}>

              {/* Card Header */}
              <div className="px-4 pt-4 pb-3 border-b border-gray-50">
                {/* 组织分身：显示可点击的成员名 */}
                {orgSkillId && grain.spaceId && memberLinks[grain.spaceId] && (
                  <a href={`/skill/${memberLinks[grain.spaceId]}`}
                    className="text-[11px] font-medium text-indigo-500 hover:text-indigo-600 hover:underline mb-1 inline-block">
                    👤 成员 →
                  </a>
                )}
                <h3 className="text-sm font-bold text-gray-900 leading-snug">
                  {grain.sceneDescription || '未命名场景'}
                </h3>
                <div className="flex flex-wrap gap-1.5 mt-2">
                  {grain.qualityScore != null && (
                    <span className="text-[10px] font-medium px-2 py-0.5 rounded-md bg-amber-50 text-amber-700">
                      ⭐ {grain.qualityScore}/5
                    </span>
                  )}
                  <span className={`text-[10px] font-medium px-2 py-0.5 rounded-md ${level.cls}`}>
                    {level.icon} {level.label}
                  </span>
                  {grain.difficultyLevel && (
                    <span className="text-[10px] font-medium px-2 py-0.5 rounded-md bg-purple-50 text-purple-700">
                      {grain.difficultyLevel === 'hard' ? '进阶' : grain.difficultyLevel === 'medium' ? '中级' : '基础'}
                    </span>
                  )}
                </div>
              </div>

              {/* 销冠思路 */}
              {grain.expertThought && (
                <div className="px-4 py-3">
                  <div className="flex items-center gap-1.5 mb-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-indigo-500 flex-shrink-0" />
                    <span className="text-[11px] font-semibold text-indigo-600 uppercase tracking-wide">销冠怎么想</span>
                  </div>
                  <p className="text-[13px] text-gray-700 leading-relaxed m-0">
                    {grain.expertThought}
                  </p>
                </div>
              )}

              {/* 标准话术 */}
              {grain.standardScript && (
                <div className="px-4 py-3 border-t border-gray-50">
                  <div className="flex items-center gap-1.5 mb-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-amber-400 flex-shrink-0" />
                    <span className="text-[11px] font-semibold text-amber-600 uppercase tracking-wide">可以这样说</span>
                  </div>
                  <div className="relative bg-amber-50/60 rounded-xl px-3.5 py-3">
                    <span className="absolute left-2.5 top-1.5 text-lg text-amber-300 font-serif leading-none">"</span>
                    <p className="text-[13px] text-amber-900 leading-relaxed italic m-0 pl-2.5">
                      {grain.standardScript}
                    </p>
                    <div className="flex justify-end mt-2">
                      <button
                        onClick={() => handleCopy(grain.standardScript!, grain.grainId)}
                        className={`text-[11px] font-medium px-2.5 py-1 rounded-md transition-colors ${
                          copied[grain.grainId]
                            ? 'bg-green-100 text-green-700'
                            : 'bg-amber-100 text-amber-700 hover:bg-amber-200'
                        }`}
                      >
                        {copied[grain.grainId] ? '✓ 已复制' : '📋 复制话术'}
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* 常见误区 */}
              {grain.commonMistakes && (
                <div className="px-4 py-3 border-t border-gray-50">
                  <div className="flex items-center gap-1.5 mb-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-red-400 flex-shrink-0" />
                    <span className="text-[11px] font-semibold text-red-500 uppercase tracking-wide">新手容易踩的坑</span>
                  </div>
                  <div className="bg-red-50/60 rounded-xl px-3.5 py-3">
                    <p className="text-[12px] text-red-800 leading-relaxed m-0">
                      {grain.commonMistakes}
                    </p>
                  </div>
                </div>
              )}

              {/* 原始对话 */}
              {grain.sourceSnippet && (
                <div className="px-4 py-2.5 border-t border-gray-50">
                  <button
                    onClick={() => setExpanded(prev => ({ ...prev, [grain.grainId]: !prev[grain.grainId] }))}
                    className="text-[11px] text-indigo-500 hover:text-indigo-600 font-medium bg-transparent border-none cursor-pointer p-0"
                  >
                    {expanded[grain.grainId] ? '▾ 收起' : '▸ 展开'}原始对话片段
                  </button>
                  {expanded[grain.grainId] && (
                    <div className="mt-2 p-3 rounded-lg bg-gray-50 text-[11px] text-gray-600 whitespace-pre-wrap leading-relaxed border border-gray-100">
                      {grain.sourceSnippet}
                    </div>
                  )}
                </div>
              )}

              {/* Source Footer */}
              <div className="px-4 py-2.5 bg-gray-50/50 border-t border-gray-50 flex items-center gap-2">
                <span className="text-xs">📄</span>
                <span className="text-[11px] text-gray-500 flex-1 truncate">
                  {grain.sourceName || '未知来源'}
                  {grain.reportTitle && <span className="text-gray-400"> · {grain.reportTitle}</span>}
                </span>
                {grain.reportId && (
                  <button
                    onClick={() => {
                      if (typeof window !== 'undefined') {
                        window.open(`/report/${grain.reportId}`, '_blank');
                      }
                    }}
                    className="text-[10px] text-indigo-500 hover:text-indigo-600 font-medium bg-transparent border-none cursor-pointer whitespace-nowrap"
                  >
                    查看报告 →
                  </button>
                )}
              </div>

            </div>
          )})}
        </div>
      </div>

      <style>{`
        @keyframes slideInRight {
          from { transform: translateX(100%); }
          to { transform: translateX(0); }
        }
      `}</style>
    </>
  );
}
