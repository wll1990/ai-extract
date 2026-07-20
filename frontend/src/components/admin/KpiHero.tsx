'use client';

/** KPI 指标项 */
export interface KpiItem {
  label: string;
  value: string;
  trend?: { direction: 'up' | 'down' | 'flat'; text: string };
  color: 'blue' | 'green' | 'white' | 'amber';
}

/** 深色 KPI Hero —— 四个彩色大数字 + navy 渐变底 + 径向辉光 */
export function KpiHero({ items }: { items: KpiItem[] }) {
  const colorMap: Record<string, string> = {
    blue: '#60A5FA',
    green: '#4ADE80',
    white: '#F8FAFC',
    amber: '#F59E0B',
  };

  return (
    <section
      className="relative overflow-hidden rounded-2xl px-6 py-10 md:px-10 md:py-12"
      style={{
        background: 'linear-gradient(160deg, #020617 0%, #0F172A 30%, #172554 60%, #0F172A 100%)',
      }}
    >
      {/* 径向辉光 */}
      <div
        className="absolute top-[10%] left-[5%] pointer-events-none"
        style={{
          width: 600, height: 600,
          background: 'radial-gradient(circle, rgba(59,130,246,0.15) 0%, transparent 70%)',
          borderRadius: '50%',
        }}
      />
      <div
        className="absolute bottom-[5%] right-[3%] pointer-events-none"
        style={{
          width: 500, height: 500,
          background: 'radial-gradient(circle, rgba(139,92,246,0.10) 0%, transparent 70%)',
          borderRadius: '50%',
        }}
      />

      {/* KPI 列 */}
      <div className="relative z-10 flex rounded-[20px] overflow-hidden max-w-[800px] mx-auto"
        style={{ gap: 1, background: 'rgba(255,255,255,0.06)' }}>
        {items.map((item, i) => (
          <div
            key={item.label}
            className="flex-1 text-center py-7 px-5 transition-colors"
            style={{
              background: 'linear-gradient(180deg, rgba(255,255,255,0.06) 0%, rgba(255,255,255,0.02) 100%)',
              backdropFilter: 'blur(10px)',
              borderRight: i < items.length - 1 ? '1px solid rgba(255,255,255,0.06)' : 'none',
            }}
          >
            {/* 数值 */}
            <p
              className="text-[34px] leading-[1.1] mb-1.5 tracking-[-0.02em]"
              style={{
                fontWeight: 800,
                color: colorMap[item.color],
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {item.value}
            </p>

            {/* 标签 */}
            <p
              className="text-[12px] font-medium tracking-[0.04em] uppercase mb-1"
              style={{ color: '#64748B' }}
            >
              {item.label}
            </p>

            {/* 趋势 */}
            {item.trend && (
              <p className="text-[11px] font-medium" style={{
                color: item.trend.direction === 'up' ? '#4ADE80'
                     : item.trend.direction === 'down' ? '#F87171'
                     : '#94A3B8',
              }}>
                {item.trend.direction === 'up' ? '↑' : item.trend.direction === 'down' ? '↓' : '→'}
                {' '}{item.trend.text}
              </p>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}
