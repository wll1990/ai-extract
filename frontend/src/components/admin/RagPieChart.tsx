'use client';

import { ResponsivePie } from '@nivo/pie';

interface Props {
  high: number;
  refCount: number;
  none: number;
  highPct: number;
  refPct: number;
  nonePct: number;
}

const COLORS = ['#22C55E', '#EAB308', '#9CA3AF'];

/** RAG 匹配分布环形图 */
export function RagPieChart({ high, refCount, none, highPct, refPct, nonePct }: Props) {
  const data = [
    { id: '高匹配', label: '高匹配', value: high, pct: highPct },
    { id: '参考', label: '参考', value: refCount, pct: refPct },
    { id: '无匹配', label: '无匹配', value: none, pct: nonePct },
  ].filter(d => d.value > 0);

  if (data.length === 0) return <EmptyPlaceholder label="暂无 RAG 数据" />;

  return (
    <div className="rounded-[12px] bg-surface-2 p-5 shadow-sm">
      <h3 className="mb-3 text-sm font-semibold">🎯 RAG 匹配分布</h3>
      <div className="relative" style={{ height: 220 }}>
        <ResponsivePie
          data={data}
          margin={{ top: 5, right: 80, bottom: 5, left: 5 }}
          innerRadius={0.55}
          padAngle={3}
          cornerRadius={2}
          colors={COLORS}
          enableArcLabels={false}
          enableArcLinkLabels={false}
          animate
          tooltip={({ datum }) => (
            <div className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm shadow-lg">
              {datum.label}: <strong>{datum.value} 次 · {datum.data.pct}%</strong>
            </div>
          )}
          legends={[
            {
              anchor: 'right',
              direction: 'column',
              justify: false,
              translateX: 70,
              translateY: 0,
              itemsSpacing: 8,
              itemWidth: 60,
              itemHeight: 18,
              itemTextColor: '#666',
              itemDirection: 'left-to-right',
              symbolSize: 12,
              symbolShape: 'circle',
            },
          ]}
        />
        {/* Donut 中心数字 */}
        <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
          <p className="text-[28px] font-extrabold text-[#22C55E] tabular-nums leading-none">{highPct}%</p>
          <p className="text-[11px] text-[#64748B] mt-0.5">高匹配</p>
        </div>
      </div>
    </div>
  );
}

function EmptyPlaceholder({ label }: { label: string }) {
  return (
    <div className="rounded-[12px] bg-surface-2 p-5 shadow-sm flex items-center justify-center h-[220px]">
      <p className="text-sm text-muted-foreground">{label}</p>
    </div>
  );
}
