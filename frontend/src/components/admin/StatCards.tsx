'use client';

/** 仪表盘顶部汇总卡片 —— 4列网格，每列一个指标，纯 Tailwind CSS */
interface StatItem {
  label: string;
  value: string;
  trend?: number;    // 正数=上升，负数=下降，0=持平
  unit?: string;     // 变化单位
}

export function StatCards({ stats }: { stats: StatItem[] }) {
  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      {stats.map(s => (
        <div key={s.label} className="rounded-xl bg-surface-2 p-5 shadow-sm">
          <p className="text-sm text-muted-foreground">{s.label}</p>
          <p className="text-2xl font-bold mt-1">{s.value}</p>
          {s.trend !== undefined && (
            <p className={`text-xs mt-1 ${s.trend > 0 ? 'text-green-600' : s.trend < 0 ? 'text-red-500' : 'text-muted-foreground'}`}>
              {s.trend > 0 ? '↑' : s.trend < 0 ? '↓' : '→'}
              {Math.abs(s.trend)}{s.unit || ''} 较上周
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
