'use client';

import { ReviewSegment } from '@/lib/api/sales';

export function CitationPopover({
  evidence,
  segments,
  onClose,
}: {
  evidence: Array<{ segmentId: string; quote?: string }>;
  segments: ReviewSegment[];
  onClose: () => void;
}) {
  const byId = new Map(segments.map(s => [s.id, s]));
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label="原文引用"
        className="max-h-[70vh] w-full max-w-xl overflow-auto rounded bg-white p-5"
        onClick={e => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-base font-medium">原文引用</h3>
          <button type="button" aria-label="关闭" onClick={onClose} className="text-gray-400 hover:text-gray-600">
            ✕
          </button>
        </div>
        <div className="space-y-3">
          {evidence.map((e, i) => {
            const seg = byId.get(e.segmentId);
            return (
              <div key={`${e.segmentId}-${i}`} className="rounded border border-gray-200 p-3">
                <div className="mb-1 text-xs text-gray-500">
                  第 {seg?.seq ?? '?'} 段 ·{' '}
                  {seg?.speaker === 'customer' ? '客户' : seg?.speaker === 'sales' ? '销售' : '未知'}
                </div>
                <p className="whitespace-pre-wrap text-sm text-gray-800">
                  {seg?.text || '原文未授权或不可见，仅能展示引用片段'}
                </p>
                {e.quote && (
                  <p className="mt-2 border-l-2 border-blue-400 pl-2 text-xs text-blue-700">
                    引用：{e.quote}
                  </p>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
