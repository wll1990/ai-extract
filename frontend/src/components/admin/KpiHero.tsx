'use client';

/** KPI 指标项 */
export interface KpiItem {
  label: string;
  value: string;
  trend?: { direction: 'up' | 'down' | 'flat'; text: string };
  color: 'blue' | 'green' | 'slate' | 'amber';
}

const trendArrow = (d: string) => d === 'up' ? '↑' : d === 'down' ? '↓' : '→';
const trendColor = (d: string) => d === 'up' ? '#16A34A' : d === 'down' ? '#DC2626' : '#64748B';

/** KPI 卡片行 — 白卡 + 左侧彩色竖线，占满容器宽度 */
export function KpiHero({ items }: { items: KpiItem[] }) {
  const colorMap: Record<string, string> = {
    blue: '#3B82F6',
    green: '#22C55E',
    slate: '#64748B',
    amber: '#F59E0B',
  };

  return (
    <section className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {items.map(item => (
        <div
          key={item.label}
          className="rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_3px_rgba(15,23,42,0.04)] p-5 flex items-center gap-4 hover:shadow-md transition-shadow"
        >
          {/* 左侧彩色竖线 */}
          <div
            className="w-1 h-12 rounded-full flex-shrink-0"
            style={{ background: colorMap[item.color] }}
          />
          {/* 右侧数字 + 标签 */}
          <div className="min-w-0">
            <p className="text-[28px] font-extrabold text-[#1E293B] tabular-nums leading-none truncate">
              {item.value}
            </p>
            <p className="text-[12px] text-[#64748B] font-medium mt-1">
              {item.label}
              {item.trend && (
                <span className="ml-1.5" style={{ color: trendColor(item.trend.direction) }}>
                  {trendArrow(item.trend.direction)} {item.trend.text}
                </span>
              )}
            </p>
          </div>
        </div>
      ))}
    </section>
  );
}
