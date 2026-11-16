const MAP: Record<string, { label: string; className: string }> = {
  active: { label: '待生成', className: 'bg-gray-100 text-gray-700' },
  report_ready: { label: '报告已生成', className: 'bg-green-50 text-green-700' },
  converted: { label: '已转线索', className: 'bg-blue-50 text-blue-700' },
  closed: { label: '已关闭', className: 'bg-gray-100 text-gray-500' },
  queued: { label: '排队中', className: 'bg-amber-50 text-amber-700' },
  generating: { label: '生成中', className: 'bg-amber-50 text-amber-700' },
  ready: { label: '已生成', className: 'bg-green-50 text-green-700' },
  failed: { label: '生成失败', className: 'bg-red-50 text-red-700' },
  new: { label: '新线索', className: 'bg-blue-50 text-blue-700' },
  following: { label: '跟进中', className: 'bg-amber-50 text-amber-700' },
  invalid: { label: '无效', className: 'bg-gray-100 text-gray-500' },
  pending: { label: '待办', className: 'bg-amber-50 text-amber-700' },
  done: { label: '已完成', className: 'bg-green-50 text-green-700' },
  cancelled: { label: '已取消', className: 'bg-gray-100 text-gray-500' },
};

/**
 * converted 在复盘是「已转线索」，在线索里是「已成交」，
 * 同一个状态码在两条业务线含义不同，必须由调用方声明 domain。
 */
const DOMAIN_OVERRIDES: Record<string, Record<string, { label: string; className: string }>> = {
  lead: {
    converted: { label: '已成交', className: 'bg-green-50 text-green-700' },
  },
};

export function StatusBadge({ status, domain }: { status: string; domain?: 'lead' }) {
  const item = (domain && DOMAIN_OVERRIDES[domain]?.[status])
    || MAP[status]
    || { label: status, className: 'bg-gray-100 text-gray-700' };
  return (
    <span className={`inline-flex rounded px-2 py-0.5 text-xs ${item.className}`}>
      {item.label}
    </span>
  );
}
