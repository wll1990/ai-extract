'use client';

/** 通用分页控件 — 上一页/下一页/页数/总数 */
export function Pagination({
  page, totalPages, totalElements, onPageChange, loading,
}: {
  page: number; totalPages: number; totalElements: number;
  onPageChange: (page: number) => void; loading?: boolean;
}) {
  if (totalPages <= 1 && totalElements === 0) return null;

  return (
    <div className="flex items-center justify-center gap-3 py-4">
      <button
        disabled={page <= 1 || loading}
        onClick={() => onPageChange(page - 1)}
        className="text-sm rounded-lg px-4 py-2 border border-[#E8ECF1] disabled:opacity-30 hover:bg-[#F8FAFC] transition-colors"
      >
        上一页
      </button>
      <span className="text-xs text-[#94A3B8] tabular-nums">
        第 {page}/{Math.max(totalPages, 1)} 页 · 共 {totalElements} 条
      </span>
      <button
        disabled={page >= totalPages || loading}
        onClick={() => onPageChange(page + 1)}
        className="text-sm rounded-lg px-4 py-2 border border-[#E8ECF1] disabled:opacity-30 hover:bg-[#F8FAFC] transition-colors"
      >
        下一页
      </button>
    </div>
  );
}
