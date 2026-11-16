/** 素材状态口径单一来源 —— 列表、筛选、概览管道共用 */

export const MATERIAL_STATUS_LABELS: Record<string, string> = {
  uploaded: '已上传',
  parsed: '等待清洗',
  cleaning: '清洗中',
  analyzing: '分析中',
  analyzed: '已分析',
  extracted: '已萃取',
  rejected: '已拒绝',
  discarded: '已废弃',
  parse_failed: '解析失败',
  cleaning_failed: '清洗失败',
};

export const MATERIAL_STATUS_COLORS: Record<string, string> = {
  uploaded: 'bg-primary-light text-muted-foreground',
  parsed: 'bg-sky-100 text-sky-700',
  cleaning: 'bg-blue-100 text-blue-600',
  analyzing: 'bg-yellow-100 text-yellow-700',
  analyzed: 'bg-green-100 text-green-700',
  extracted: 'bg-emerald-100 text-emerald-700',
  rejected: 'bg-red-100 text-red-600',
  discarded: 'bg-gray-200 text-gray-500',
  parse_failed: 'bg-red-100 text-red-600',
  cleaning_failed: 'bg-red-100 text-red-600',
};

/**
 * 到达这些状态后调度器不会再改，轮询可以停。
 *
 * parsed 不是终态：文件解析完成后还要走清洗 → 萃取，把它当终态会让前端提前停止轮询，
 * 用户看到「已解析」卡住不动。
 */
export const MATERIAL_TERMINAL_STATUSES = new Set([
  'extracted', 'rejected', 'discarded', 'parse_failed', 'cleaning_failed',
]);

/** 可筛选的状态顺序 */
export const MATERIAL_FILTER_STATUSES = [
  'uploaded', 'parsed', 'cleaning', 'analyzing', 'analyzed',
  'extracted', 'rejected', 'discarded', 'parse_failed', 'cleaning_failed',
];
