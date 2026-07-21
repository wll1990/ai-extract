'use client';

import { useEffect, useState } from 'react';
import { API_BASE } from '@/lib/api/client';

interface GrainTrace {
  grainId: string;
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
}

interface TraceabilityDrawerProps {
  grainIds: string;
  open: boolean;
  onClose: () => void;
}

export function TraceabilityDrawer({ grainIds, open, onClose }: TraceabilityDrawerProps) {
  const [data, setData] = useState<GrainTrace[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (!open || !grainIds) return;
    setLoading(true);
    fetch(`${API_BASE}/admin/grains/traceability?grainIds=${encodeURIComponent(grainIds)}`)
      .then(r => r.json())
      .then(r => setData(r.data || []))
      .finally(() => setLoading(false));
  }, [open, grainIds]);

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopied(prev => ({ ...prev, [id]: true }));
    setTimeout(() => setCopied(prev => ({ ...prev, [id]: false })), 1500);
  };

  if (!open) return null;

  return (
    <>
      {/* 遮罩 */}
      <div onClick={onClose} className="fixed inset-0 bg-black/20 backdrop-blur z-[100]" />

      {/* 抽屉 */}
      <div className="fixed top-0 right-0 bottom-0 w-[400px] max-w-[90vw] bg-white z-[101] shadow-[-8px_0_30px_rgba(0,0,0,0.12)] flex flex-col overflow-hidden animate-[slideInRight_0.3s_ease-out]">
        {/* 头部 */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100 flex-shrink-0">
          <span className="text-[15px] font-bold text-gray-800">
            📋 溯源 · {data.length} 条销冠锦囊
          </span>
          <button onClick={onClose} className="bg-transparent border-none cursor-pointer text-lg text-gray-400 p-0">
            ✕
          </button>
        </div>

        {/* 内容 */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-3" style={{ scrollbarWidth: 'thin' }}>
          {loading ? (
            <div className="text-center py-10 text-gray-400 text-sm">加载中…</div>
          ) : data.map(grain => (
            <div key={grain.grainId} className="bg-gray-50 rounded-xl p-4 border border-gray-100">
              {/* 标题行 */}
              <div className="flex justify-between items-start mb-2.5">
                <span className="text-[13px] font-semibold text-gray-800">
                  🎯 {grain.sceneDescription || '未命名场景'}
                </span>
                {grain.qualityScore != null && (
                  <span className="text-[11px] text-gray-400 flex-shrink-0 ml-2">
                    ⭐ {grain.qualityScore}/5
                  </span>
                )}
              </div>

              {/* 销冠思路 */}
              {grain.expertThought && (
                <div className="text-xs text-gray-500 leading-relaxed mb-2">
                  <strong className="text-gray-700">销冠思路：</strong>
                  {grain.expertThought}
                </div>
              )}

              {/* 标准话术 + 复制 */}
              {grain.standardScript && (
                <div className="relative border-l-2 border-orange-400 pl-2.5 mb-2 text-xs text-gray-500 italic">
                  &ldquo;{grain.standardScript}&rdquo;
                  <button
                    onClick={() => handleCopy(grain.standardScript!, grain.grainId)}
                    className="absolute right-0 top-0 bg-transparent border-none cursor-pointer text-sm text-gray-400 hover:text-gray-600 transition-colors p-0"
                  >
                    {copied[grain.grainId] ? '✓' : '📋'}
                  </button>
                </div>
              )}

              {/* 常见误区 */}
              {grain.commonMistakes && (
                <div className="text-[11px] text-gray-400 mb-2">
                  ⚠️ 常见误区：{grain.commonMistakes}
                </div>
              )}

              {/* 原始对话片段 */}
              {grain.sourceSnippet && (
                <div className="mt-2">
                  <button
                    onClick={() => setExpanded(prev => ({
                      ...prev,
                      [grain.grainId]: !prev[grain.grainId],
                    }))}
                    className="bg-transparent border-none cursor-pointer text-[11px] text-orange-500 hover:text-orange-600 transition-colors p-0"
                  >
                    {expanded[grain.grainId] ? '收起' : '展开'}原始对话
                  </button>
                  {expanded[grain.grainId] && (
                    <div className="mt-1.5 p-2.5 rounded-lg bg-white text-[11px] text-gray-500 whitespace-pre-wrap leading-relaxed">
                      {grain.sourceSnippet}
                    </div>
                  )}
                </div>
              )}

              {/* 来源 */}
              <div className="text-[10px] text-gray-400 mt-2">
                📄 {grain.sourceName || '未知来源'}
                {grain.reportTitle && ` · ${grain.reportTitle}`}
              </div>
            </div>
          ))}
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
